package app

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const maxAPKSize = 200 << 20

type release struct {
	ID           string `json:"id"`
	VersionCode  int64  `json:"version_code"`
	VersionName  string `json:"version_name"`
	Notes        string `json:"notes"`
	SHA256       string `json:"sha256"`
	Size         int64  `json:"size"`
	CreatedAt    string `json:"created_at"`
	Published    bool   `json:"published"`
	DownloadPath string `json:"download_path"`
}

const releaseColumns = `id,version_code,version_name,notes,sha256,size,created_at,published`

func scanRelease(row scanner) (release, error) {
	var v release
	err := row.Scan(&v.ID, &v.VersionCode, &v.VersionName, &v.Notes, &v.SHA256, &v.Size, &v.CreatedAt, &v.Published)
	v.DownloadPath = "/api/v1/updates/" + v.ID + "/download"
	return v, err
}
func (a *App) adminReleases(w http.ResponseWriter, r *http.Request) {
	limit, offset := adminPage(r)
	var total int
	if a.db.QueryRow(`SELECT COUNT(*) FROM app_releases`).Scan(&total) != nil {
		dbFailure(w)
		return
	}
	rows, err := a.db.Query(`SELECT `+releaseColumns+` FROM app_releases ORDER BY version_code DESC LIMIT ? OFFSET ?`, limit, offset)
	if err != nil {
		dbFailure(w)
		return
	}
	defer rows.Close()
	out := []release{}
	for rows.Next() {
		v, e := scanRelease(rows)
		if e != nil {
			dbFailure(w)
			return
		}
		out = append(out, v)
	}
	if rows.Err() != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, map[string]any{"releases": out, "total": total})
}
func (a *App) uploadRelease(w http.ResponseWriter, r *http.Request) {
	controller := http.NewResponseController(w)
	_ = controller.SetReadDeadline(time.Now().Add(10 * time.Minute))
	_ = controller.SetWriteDeadline(time.Now().Add(11 * time.Minute))
	r.Body = http.MaxBytesReader(w, r.Body, maxAPKSize+(1<<20))
	if err := r.ParseMultipartForm(1 << 20); err != nil {
		if r.MultipartForm != nil {
			r.MultipartForm.RemoveAll()
		}
		writeError(w, 400, "上传失败：APK 最大 200 MB")
		return
	}
	defer r.MultipartForm.RemoveAll()
	notes := strings.TrimSpace(r.FormValue("notes"))
	if len(notes) > 16000 {
		writeError(w, 400, "更新说明过长")
		return
	}
	in, header, err := r.FormFile("file")
	if err != nil {
		writeError(w, 400, "请选择 APK 文件")
		return
	}
	defer in.Close()
	if !strings.EqualFold(filepath.Ext(header.Filename), ".apk") || header.Size > maxAPKSize {
		writeError(w, 400, "请选择不超过 200 MB 的 APK 文件")
		return
	}
	dir := filepath.Join(a.cfg.DataDir, "releases")
	if os.MkdirAll(dir, 0700) != nil {
		dbFailure(w)
		return
	}
	f, err := os.CreateTemp(dir, "upload-*.tmp")
	if err != nil {
		dbFailure(w)
		return
	}
	temp := f.Name()
	defer os.Remove(temp)
	hash := sha256.New()
	size, err := io.Copy(io.MultiWriter(f, hash), io.LimitReader(in, maxAPKSize+1))
	syncErr := f.Sync()
	closeErr := f.Close()
	if err != nil || syncErr != nil || closeErr != nil || size > maxAPKSize {
		writeError(w, 400, "文件上传未完成或过大，请重试")
		return
	}
	code, name, err := apkVersion(temp)
	if err != nil {
		writeError(w, 400, err.Error())
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	var exists int
	if a.db.QueryRow(`SELECT COUNT(*) FROM app_releases WHERE version_code=?`, code).Scan(&exists) != nil {
		dbFailure(w)
		return
	}
	if exists != 0 {
		writeError(w, 409, "此版本号已上传，请提高 APK 的 versionCode 后重新构建")
		return
	}
	v := release{ID: randomID(), VersionCode: code, VersionName: name, Notes: notes, SHA256: hex.EncodeToString(hash.Sum(nil)), Size: size, CreatedAt: stamp(a.now()), Published: true}
	v.DownloadPath = "/api/v1/updates/" + v.ID + "/download"
	final := filepath.Join(dir, v.ID+".apk")
	if os.Rename(temp, final) != nil {
		dbFailure(w)
		return
	}
	_, err = a.db.Exec(`INSERT INTO app_releases(`+releaseColumns+`) VALUES(?,?,?,?,?,?,?,1)`, v.ID, v.VersionCode, v.VersionName, v.Notes, v.SHA256, v.Size, v.CreatedAt)
	if err != nil {
		os.Remove(final)
		dbFailure(w)
		return
	}
	writeJSON(w, 201, v)
}
func (a *App) setReleasePublished(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Published *bool `json:"published"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.Published == nil {
		writeError(w, 400, "请提供 published 状态")
		return
	}
	res, err := a.db.Exec(`UPDATE app_releases SET published=? WHERE id=?`, *in.Published, r.PathValue("id"))
	if err != nil {
		dbFailure(w)
		return
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		writeError(w, 404, "版本不存在")
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
func (a *App) latestRelease(w http.ResponseWriter, r *http.Request) {
	code, err := strconv.ParseInt(r.URL.Query().Get("version_code"), 10, 64)
	if err != nil || code < 0 {
		writeError(w, 400, "请提供有效的 version_code")
		return
	}
	v, err := scanRelease(a.db.QueryRow(`SELECT ` + releaseColumns + ` FROM app_releases WHERE published=1 ORDER BY version_code DESC LIMIT 1`))
	if err == sql.ErrNoRows {
		writeJSON(w, 200, map[string]bool{"available": false})
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, 200, map[string]any{"available": v.VersionCode > code, "release": v})
}
func (a *App) downloadRelease(w http.ResponseWriter, r *http.Request) {
	v, err := scanRelease(a.db.QueryRow(`SELECT `+releaseColumns+` FROM app_releases WHERE id=? AND published=1`, r.PathValue("id")))
	if err == sql.ErrNoRows {
		writeError(w, 404, "此版本已下架或不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	f, err := os.Open(filepath.Join(a.cfg.DataDir, "releases", v.ID+".apk"))
	if err != nil {
		writeError(w, 404, "安装文件不存在，请联系管理员重新发布")
		return
	}
	defer f.Close()
	_ = http.NewResponseController(w).SetWriteDeadline(time.Now().Add(10 * time.Minute))
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="niannian-%d.apk"`, v.VersionCode))
	w.Header().Set("ETag", `"`+v.SHA256+`"`)
	created, _ := time.Parse(time.RFC3339Nano, v.CreatedAt)
	http.ServeContent(w, r, "update.apk", created, f)
}
