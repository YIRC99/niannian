package app

import (
	"bytes"
	"database/sql"
	_ "golang.org/x/image/webp"
	"image"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"net/http"
	"niannian/internal/schedule"
	"os"
	"path/filepath"
	"strings"
	"time"
	"unicode/utf8"
)

const maxMediaBytes = 10 << 20

func (a *App) uploadMedia(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, maxMediaBytes+(64<<10))
	if err := r.ParseMultipartForm(1 << 20); err != nil {
		if r.MultipartForm != nil {
			r.MultipartForm.RemoveAll()
		}
		writeError(w, 400, "请选择不超过 10 MB 的 JPG、PNG 或 WebP 照片")
		return
	}
	defer r.MultipartForm.RemoveAll()
	files := r.MultipartForm.File["file"]
	if len(files) != 1 {
		writeError(w, 400, "每次请上传一张照片")
		return
	}
	file, err := files[0].Open()
	if err != nil {
		writeError(w, 400, "无法读取照片")
		return
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, maxMediaBytes+1))
	if err != nil || len(data) == 0 || len(data) > maxMediaBytes {
		writeError(w, 400, "照片不能超过 10 MB")
		return
	}
	cfg, format, err := image.DecodeConfig(bytes.NewReader(data))
	types := map[string]string{"jpeg": "image/jpeg", "png": "image/png", "webp": "image/webp"}
	mime, ok := types[format]
	if err != nil || !ok || cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 16000 || cfg.Height > 16000 || int64(cfg.Width)*int64(cfg.Height) > 40000000 {
		writeError(w, 400, "照片格式损坏或尺寸过大，请使用 JPG、PNG 或 WebP")
		return
	}
	if _, _, err = image.Decode(bytes.NewReader(data)); err != nil {
		writeError(w, 400, "照片内容不完整，请重新选择")
		return
	}
	id := randomID()
	filename := id + "." + format
	path := filepath.Join(a.cfg.DataDir, "media", filename)
	// Write atomically before inserting metadata; a process crash may leave an unreferenced file,
	// but a recorded completion never points to a half-written photo.
	temp, err := os.CreateTemp(filepath.Join(a.cfg.DataDir, "media"), ".upload-*")
	if err != nil {
		dbFailure(w)
		return
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err = temp.Chmod(0600); err == nil {
		_, err = temp.Write(data)
	}
	if err == nil {
		err = temp.Sync()
	}
	closeErr := temp.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		dbFailure(w)
		return
	}
	if err = os.Rename(tempName, path); err != nil {
		dbFailure(w)
		return
	}
	_, err = a.db.Exec(`INSERT INTO media(id,user_id,filename,content_type,size,created_at) VALUES(?,?,?,?,?,?)`, id, currentUser(r).ID, filename, mime, len(data), stamp(a.now()))
	if err != nil {
		os.Remove(path)
		dbFailure(w)
		return
	}
	writeJSON(w, 201, map[string]string{"id": id, "url": "/api/v1/media/" + id})
}
func (a *App) getMedia(w http.ResponseWriter, r *http.Request) {
	var filename, mime string
	err := a.db.QueryRow(`SELECT filename,content_type FROM media WHERE id=? AND user_id=?`, r.PathValue("id"), currentUser(r).ID).Scan(&filename, &mime)
	if err == sql.ErrNoRows {
		writeError(w, 404, "照片不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	if filepath.Base(filename) != filename {
		writeError(w, 404, "照片不存在")
		return
	}
	f, err := os.Open(filepath.Join(a.cfg.DataDir, "media", filename))
	if err != nil {
		writeError(w, 404, "照片文件暂不可用")
		return
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		dbFailure(w)
		return
	}
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Content-Disposition", `inline; filename="photo.`+strings.TrimPrefix(mime, "image/")+`"`)
	http.ServeContent(w, r, filename, info.ModTime(), f)
}
func (a *App) parseAI(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Text string `json:"text"`
	}
	if !decode(w, r, &in) {
		return
	}
	in.Text = strings.TrimSpace(in.Text)
	if len(in.Text) == 0 || utf8.RuneCountInString(in.Text) > 2000 {
		writeError(w, 400, "请用 1–2000 字描述需要提醒的事")
		return
	}
	// Persist quota so restarts cannot bypass it, and never hold the scheduler mutex
	// while waiting for an external AI response.
	a.mu.Lock()
	now := a.now()
	var n int
	err := a.db.QueryRow(`SELECT COUNT(*) FROM ai_requests WHERE user_id=? AND created_at>?`, currentUser(r).ID, stamp(now.Add(-time.Minute))).Scan(&n)
	if err == nil && n < 6 {
		_, err = a.db.Exec(`INSERT INTO ai_requests(user_id,created_at) VALUES(?,?)`, currentUser(r).ID, stamp(now))
		if err == nil {
			_, err = a.db.Exec(`DELETE FROM ai_requests WHERE created_at<?`, stamp(now.Add(-24*time.Hour)))
		}
	}
	a.mu.Unlock()
	if err != nil {
		dbFailure(w)
		return
	}
	if n >= 6 {
		writeError(w, 429, "AI 请求有点频繁，请稍等一分钟")
		return
	}
	draft, err := a.parser.Parse(r.Context(), in.Text, now)
	if err != nil {
		writeError(w, 502, err.Error())
		return
	}
	if err = schedule.Validate(draft.Rule); err != nil {
		writeError(w, 502, "AI 返回的规则无效，请重新描述或手动创建")
		return
	}
	writeJSON(w, 200, draft)
}
