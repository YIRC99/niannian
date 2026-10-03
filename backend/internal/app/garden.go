package app

import (
	"database/sql"
	"net/http"
	"net/url"
	"time"
)

const gardenPlantLimit = 2000
const gardenRecentLimit = 12

type gardenPlant struct {
	TaskID          string `json:"task_id"`
	Title           string `json:"title"`
	CompletionCount int    `json:"completion_count"`
	LastCompletedAt string `json:"last_completed_at"`
	Archived        bool   `json:"archived"`
	Stage           string `json:"stage"`
}

type gardenResponse struct {
	Timezone          string        `json:"timezone"`
	GeneratedAt       string        `json:"generated_at"`
	WeekStartDate     string        `json:"week_start_date"`
	TotalCompletions  int           `json:"total_completions"`
	WeekCompletions   int           `json:"week_completions"`
	TotalPlants       int           `json:"total_plants"`
	Plants            []gardenPlant `json:"plants"`
	RecentCompletions []Completion  `json:"recent_completions"`
	Truncated         bool          `json:"truncated"`
	PlantLimit        int           `json:"plant_limit"`
}

func gardenStage(count int) string {
	switch {
	case count >= 7:
		return "bloom"
	case count >= 3:
		return "leaf"
	case count >= 1:
		return "sprout"
	default:
		return "seed"
	}
}

func (a *App) garden(w http.ResponseWriter, r *http.Request) {
	q, err := url.ParseQuery(r.URL.RawQuery)
	zone := "Asia/Shanghai"
	if err != nil || len(q) > 1 || (len(q) == 1 && len(q["timezone"]) != 1) {
		writeError(w, 400, "请只提供一个 timezone 参数")
		return
	}
	if len(q) == 1 {
		zone = q.Get("timezone")
	}
	if zone == "" || zone == "Local" || len(zone) > 100 {
		writeError(w, 400, "请提供明确的显示时区")
		return
	}
	loc, err := time.LoadLocation(zone)
	if err != nil {
		writeError(w, 400, "显示时区无效")
		return
	}
	now := a.now()
	localNow := now.In(loc)
	day := time.Date(localNow.Year(), localNow.Month(), localNow.Day(), 0, 0, 0, 0, loc)
	weekStart := day.AddDate(0, 0, -(int(day.Weekday())+6)%7)
	weekEnd := weekStart.AddDate(0, 0, 7)
	out := gardenResponse{
		Timezone: zone, GeneratedAt: stamp(now), WeekStartDate: weekStart.Format("2006-01-02"),
		Plants: []gardenPlant{}, RecentCompletions: []Completion{}, PlantLimit: gardenPlantLimit,
	}
	userID := currentUser(r).ID
	// A single read snapshot keeps totals, plants and recent records consistent
	// if a completion or archive arrives while this response is being built.
	tx, err := a.db.BeginTx(r.Context(), &sql.TxOptions{ReadOnly: true})
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	// Only actual completion rows feed growth. Notification delivery and gaps
	// between completions cannot add or remove any progress.
	err = tx.QueryRowContext(r.Context(), `SELECT COUNT(*),COALESCE(SUM(CASE WHEN unixepoch(c.created_at)>=? AND unixepoch(c.created_at)<? THEN 1 ELSE 0 END),0)
		FROM completions c JOIN tasks t ON t.id=c.task_id
		WHERE c.user_id=? AND t.user_id=?`, weekStart.Unix(), weekEnd.Unix(), userID, userID).Scan(&out.TotalCompletions, &out.WeekCompletions)
	if err != nil {
		dbFailure(w)
		return
	}
	// Active tasks are bounded at creation, but archived tasks are not. The
	// response reports the full plant count even when its explicit cap applies.
	rows, err := tx.QueryContext(r.Context(), `SELECT t.id,t.title,t.archived,COUNT(c.id),
		COALESCE((SELECT last.created_at FROM completions last WHERE last.task_id=t.id AND last.user_id=? ORDER BY julianday(last.created_at) DESC,last.created_at DESC,last.id LIMIT 1),'') AS last_completed_at,
		COUNT(*) OVER()
		FROM tasks t LEFT JOIN completions c ON c.task_id=t.id AND c.user_id=?
		WHERE t.user_id=? GROUP BY t.id HAVING t.archived=0 OR COUNT(c.id)>0
		ORDER BY t.archived,julianday(last_completed_at) DESC,t.created_at DESC,t.id LIMIT ?`, userID, userID, userID, gardenPlantLimit)
	if err != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		var p gardenPlant
		if err = rows.Scan(&p.TaskID, &p.Title, &p.Archived, &p.CompletionCount, &p.LastCompletedAt, &out.TotalPlants); err != nil {
			break
		}
		p.Stage = gardenStage(p.CompletionCount)
		out.Plants = append(out.Plants, p)
	}
	if err == nil {
		err = rows.Err()
	}
	rows.Close()
	if err != nil {
		dbFailure(w)
		return
	}
	out.Truncated = out.TotalPlants > len(out.Plants)
	rows, err = tx.QueryContext(r.Context(), `SELECT c.id,c.task_id,c.task_title,c.cycle_key,c.text,c.media_ids,c.created_at
		FROM completions c JOIN tasks t ON t.id=c.task_id WHERE c.user_id=? AND t.user_id=?
		ORDER BY julianday(c.created_at) DESC,c.created_at DESC,c.id LIMIT ?`, userID, userID, gardenRecentLimit)
	if err != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		var c Completion
		c, err = scanCompletion(rows)
		if err != nil {
			break
		}
		out.RecentCompletions = append(out.RecentCompletions, c)
	}
	if err == nil {
		err = rows.Err()
	}
	rows.Close()
	if err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, http.StatusOK, out)
}
