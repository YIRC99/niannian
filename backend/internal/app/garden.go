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
	ID              string `json:"id"`
	TaskID          string `json:"task_id"`
	Title           string `json:"title"`
	FlowerType      string `json:"flower_type"`
	GrowthCount     int    `json:"growth_count"`
	WaterCount      int    `json:"water_count"`
	CompletionCount int    `json:"completion_count"`
	LastCompletedAt string `json:"last_completed_at"`
	Archived        bool   `json:"archived"`
	Stage           string `json:"stage"`
}

type gardenResponse struct {
	Timezone            string        `json:"timezone"`
	GeneratedAt         string        `json:"generated_at"`
	WeekStartDate       string        `json:"week_start_date"`
	TotalCompletions    int           `json:"total_completions"`
	WeekCompletions     int           `json:"week_completions"`
	TotalPlants         int           `json:"total_plants"`
	Plants              []gardenPlant `json:"plants"`
	RecentCompletions   []Completion  `json:"recent_completions"`
	Truncated           bool          `json:"truncated"`
	PlantLimit          int           `json:"plant_limit"`
	Wallet              gardenWallet  `json:"wallet"`
	RewardPerCompletion int           `json:"reward_per_completion"`
	WaterCost           int           `json:"water_cost"`
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
		Plants: []gardenPlant{}, RecentCompletions: []Completion{}, PlantLimit: gardenPlantLimit, RewardPerCompletion: gardenRewardPerCompletion, WaterCost: 1,
	}
	userID := currentUser(r).ID
	a.mu.Lock()
	defer a.mu.Unlock()
	// Sync legacy seeds in the same snapshot used to read wallet and growth.
	tx, err := a.db.BeginTx(r.Context(), &sql.TxOptions{})
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	if err = syncGarden(tx, userID); err != nil {
		dbFailure(w)
		return
	}
	if out.Wallet, err = readGardenWallet(tx, userID); err != nil {
		dbFailure(w)
		return
	}
	err = tx.QueryRowContext(r.Context(), `SELECT COUNT(*),COALESCE(SUM(CASE WHEN unixepoch(c.created_at)>=? AND unixepoch(c.created_at)<? THEN 1 ELSE 0 END),0)
		FROM completions c JOIN tasks t ON t.id=c.task_id
		WHERE c.user_id=? AND t.user_id=?`, weekStart.Unix(), weekEnd.Unix(), userID, userID).Scan(&out.TotalCompletions, &out.WeekCompletions)
	if err != nil {
		dbFailure(w)
		return
	}
	// Active tasks are bounded at creation, but archived tasks are not. The
	// response reports the full plant count even when its explicit cap applies.
	visible := ` FROM garden_plants p LEFT JOIN tasks t ON t.id=p.task_id AND t.user_id=p.user_id WHERE p.user_id=? AND (p.task_id IS NULL OR t.archived=0 OR p.growth_count>0 OR EXISTS(SELECT 1 FROM completions c WHERE c.task_id=p.task_id AND c.user_id=p.user_id))`
	if err = tx.QueryRowContext(r.Context(), `SELECT COUNT(*)`+visible, userID).Scan(&out.TotalPlants); err != nil {
		dbFailure(w)
		return
	}
	rows, err := tx.QueryContext(r.Context(), `SELECT `+gardenPlantColumns+visible+` ORDER BY COALESCE(t.archived,0),julianday((SELECT c.created_at FROM completions c WHERE c.task_id=p.task_id AND c.user_id=p.user_id ORDER BY julianday(c.created_at) DESC,c.created_at DESC,c.id LIMIT 1)) DESC,p.created_at DESC,p.id LIMIT ?`, userID, gardenPlantLimit)
	if err != nil {
		dbFailure(w)
		return
	}
	for rows.Next() {
		var p gardenPlant
		if p, err = scanGardenPlant(rows); err != nil {
			break
		}
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
