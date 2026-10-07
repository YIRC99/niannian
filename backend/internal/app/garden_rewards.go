package app

import (
	"database/sql"
	"errors"
	"net/http"
	"strings"
	"unicode/utf8"
)

const gardenRewardPerCompletion = 3
const gardenSchema = `
CREATE TABLE IF NOT EXISTS garden_wallets(user_id TEXT PRIMARY KEY REFERENCES users(id),balance INTEGER NOT NULL DEFAULT 0 CHECK(balance>=0),total_earned INTEGER NOT NULL DEFAULT 0,total_spent INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS garden_plants(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),task_id TEXT UNIQUE REFERENCES tasks(id),title TEXT NOT NULL,flower_type TEXT NOT NULL DEFAULT '',growth_count INTEGER NOT NULL DEFAULT 0 CHECK(growth_count>=0),water_count INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,request_id TEXT,UNIQUE(user_id,request_id));
CREATE INDEX IF NOT EXISTS garden_plants_user ON garden_plants(user_id);
CREATE TABLE IF NOT EXISTS garden_rewards(completion_id TEXT PRIMARY KEY REFERENCES completions(id),user_id TEXT NOT NULL REFERENCES users(id),amount INTEGER NOT NULL,created_at TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS garden_watering(user_id TEXT NOT NULL REFERENCES users(id),request_id TEXT NOT NULL,plant_id TEXT NOT NULL REFERENCES garden_plants(id),created_at TEXT NOT NULL,PRIMARY KEY(user_id,request_id));
`

type gardenWallet struct {
	Balance     int `json:"balance"`
	TotalEarned int `json:"total_earned"`
	TotalSpent  int `json:"total_spent"`
}

type completionReward struct {
	WaterDrops int  `json:"water_drops"`
	Balance    int  `json:"balance"`
	Added      bool `json:"added"`
}

func validGardenFlower(flower string) bool {
	return flower == "tulip" || flower == "daisy" || flower == "lupine" || flower == "hydrangea"
}

func validGardenRequestID(id string) bool {
	if len(id) < 1 || len(id) > 100 {
		return false
	}
	for _, c := range id {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
			return false
		}
	}
	return true
}

// Previously completed work already paid for the old flower's progress. Mark it
// consumed and preserve that progress, without also minting spendable water.
// New completions call this before insertion, then award water in the same tx.
func syncGarden(tx *sql.Tx, userID string) error {
	if _, err := tx.Exec(`INSERT OR IGNORE INTO garden_wallets(user_id) VALUES(?)`, userID); err != nil {
		return err
	}
	if _, err := tx.Exec(`INSERT OR IGNORE INTO garden_plants(id,user_id,task_id,title,created_at)
		SELECT t.id,t.user_id,t.id,t.title,t.created_at FROM tasks t WHERE t.user_id=?
		AND (t.archived=0 OR EXISTS(SELECT 1 FROM completions c WHERE c.task_id=t.id AND c.user_id=t.user_id))`, userID); err != nil {
		return err
	}
	if _, err := tx.Exec(`UPDATE garden_plants SET growth_count=growth_count+(SELECT COUNT(*) FROM completions c JOIN tasks t ON t.id=c.task_id
		WHERE c.task_id=garden_plants.task_id AND c.user_id=? AND t.user_id=? AND NOT EXISTS(SELECT 1 FROM garden_rewards r WHERE r.completion_id=c.id)) WHERE user_id=?`, userID, userID, userID); err != nil {
		return err
	}
	_, err := tx.Exec(`INSERT OR IGNORE INTO garden_rewards(completion_id,user_id,amount,created_at)
		SELECT c.id,c.user_id,0,c.created_at FROM completions c JOIN tasks t ON t.id=c.task_id WHERE c.user_id=? AND t.user_id=?`, userID, userID)
	return err
}

func readGardenWallet(tx *sql.Tx, userID string) (gardenWallet, error) {
	var wallet gardenWallet
	err := tx.QueryRow(`SELECT balance,total_earned,total_spent FROM garden_wallets WHERE user_id=?`, userID).Scan(&wallet.Balance, &wallet.TotalEarned, &wallet.TotalSpent)
	return wallet, err
}

const gardenPlantColumns = `p.id,COALESCE(p.task_id,''),COALESCE(t.title,p.title),p.flower_type,p.growth_count,p.water_count,
	(SELECT COUNT(*) FROM completions c WHERE c.task_id=p.task_id AND c.user_id=p.user_id),
	COALESCE((SELECT c.created_at FROM completions c WHERE c.task_id=p.task_id AND c.user_id=p.user_id ORDER BY julianday(c.created_at) DESC,c.created_at DESC,c.id LIMIT 1),''),COALESCE(t.archived,0)`

func scanGardenPlant(row scanner) (gardenPlant, error) {
	var p gardenPlant
	err := row.Scan(&p.ID, &p.TaskID, &p.Title, &p.FlowerType, &p.GrowthCount, &p.WaterCount, &p.CompletionCount, &p.LastCompletedAt, &p.Archived)
	p.Stage = gardenStage(p.GrowthCount)
	return p, err
}

func readGardenPlant(tx *sql.Tx, id, userID string) (gardenPlant, error) {
	return scanGardenPlant(tx.QueryRow(`SELECT `+gardenPlantColumns+` FROM garden_plants p LEFT JOIN tasks t ON t.id=p.task_id AND t.user_id=p.user_id WHERE p.id=? AND p.user_id=?`, id, userID))
}

func (a *App) createGardenPlant(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Title      string `json:"title"`
		FlowerType string `json:"flower_type"`
		RequestID  string `json:"request_id"`
	}
	if !decode(w, r, &in) {
		return
	}
	in.Title = strings.Join(strings.Fields(in.Title), " ")
	if !validGardenFlower(in.FlowerType) || !validGardenRequestID(in.RequestID) || utf8.RuneCountInString(in.Title) < 1 || utf8.RuneCountInString(in.Title) > 100 {
		writeError(w, 400, "请提供花名、四种花中的一种和有效的 request_id")
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	userID := currentUser(r).ID
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	if err = syncGarden(tx, userID); err != nil {
		dbFailure(w)
		return
	}
	var id, title, flower string
	err = tx.QueryRow(`SELECT id,title,flower_type FROM garden_plants WHERE user_id=? AND request_id=?`, userID, in.RequestID).Scan(&id, &title, &flower)
	status := http.StatusOK
	if errors.Is(err, sql.ErrNoRows) {
		var count int
		if err = tx.QueryRow(`SELECT COUNT(*) FROM garden_plants WHERE user_id=?`, userID).Scan(&count); err != nil {
			dbFailure(w)
			return
		}
		if count >= gardenPlantLimit {
			writeError(w, 409, "花园已经种满了，先照顾已有的花吧")
			return
		}
		id = randomID()
		_, err = tx.Exec(`INSERT INTO garden_plants(id,user_id,title,flower_type,created_at,request_id) VALUES(?,?,?,?,?,?)`, id, userID, in.Title, in.FlowerType, stamp(a.now()), in.RequestID)
		status = http.StatusCreated
	} else if err == nil && (title != in.Title || flower != in.FlowerType) {
		writeError(w, 409, "这次播种请求已使用，请刷新后重试")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	plant, err := readGardenPlant(tx, id, userID)
	if err != nil {
		dbFailure(w)
		return
	}
	wallet, err := readGardenWallet(tx, userID)
	if err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, status, map[string]any{"plant": plant, "wallet": wallet})
}

func (a *App) waterGardenPlant(w http.ResponseWriter, r *http.Request) {
	var in struct {
		RequestID string `json:"request_id"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !validGardenRequestID(in.RequestID) {
		writeError(w, 400, "请提供有效的 request_id")
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	userID, id := currentUser(r).ID, r.PathValue("id")
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	if err = syncGarden(tx, userID); err != nil {
		dbFailure(w)
		return
	}
	plant, err := readGardenPlant(tx, id, userID)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, 404, "这朵花不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	growth := completionGrowth{FromCount: plant.GrowthCount, ToCount: plant.GrowthCount, FromStage: plant.Stage, ToStage: plant.Stage}
	var previousID string
	err = tx.QueryRow(`SELECT plant_id FROM garden_watering WHERE user_id=? AND request_id=?`, userID, in.RequestID).Scan(&previousID)
	spent := 0
	if err == nil && previousID != id {
		writeError(w, 409, "这次浇水请求已使用，请刷新后重试")
		return
	}
	if errors.Is(err, sql.ErrNoRows) {
		if plant.GrowthCount >= 7 {
			writeError(w, 409, "这朵花已经盛开了，把水滴留给其他花吧")
			return
		}
		result, e := tx.Exec(`UPDATE garden_wallets SET balance=balance-1,total_spent=total_spent+1 WHERE user_id=? AND balance>=1`, userID)
		if e != nil {
			dbFailure(w)
			return
		}
		n, e := result.RowsAffected()
		if e != nil {
			dbFailure(w)
			return
		}
		if n == 0 {
			writeError(w, 409, "水滴还不够，完成一个提醒可获得 3 滴水")
			return
		}
		if _, err = tx.Exec(`UPDATE garden_plants SET growth_count=growth_count+1,water_count=water_count+1 WHERE id=? AND user_id=?`, id, userID); err != nil {
			dbFailure(w)
			return
		}
		if _, err = tx.Exec(`INSERT INTO garden_watering(user_id,request_id,plant_id,created_at) VALUES(?,?,?,?)`, userID, in.RequestID, id, stamp(a.now())); err != nil {
			dbFailure(w)
			return
		}
		plant.GrowthCount++
		plant.WaterCount++
		plant.Stage = gardenStage(plant.GrowthCount)
		growth.ToCount, growth.ToStage, growth.Added = plant.GrowthCount, plant.Stage, true
		spent = 1
	} else if err != nil {
		dbFailure(w)
		return
	}
	wallet, err := readGardenWallet(tx, userID)
	if err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"plant": plant, "wallet": wallet, "growth": growth, "spent": spent})
}

func (a *App) setGardenFlower(w http.ResponseWriter, r *http.Request) {
	var in struct {
		FlowerType string `json:"flower_type"`
	}
	if !decode(w, r, &in) {
		return
	}
	if !validGardenFlower(in.FlowerType) {
		writeError(w, 400, "请选择郁金香、雏菊、羽扇豆或绣球")
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	userID, id := currentUser(r).ID, r.PathValue("id")
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		dbFailure(w)
		return
	}
	defer tx.Rollback()
	if err = syncGarden(tx, userID); err != nil {
		dbFailure(w)
		return
	}
	plant, err := readGardenPlant(tx, id, userID)
	if errors.Is(err, sql.ErrNoRows) {
		writeError(w, 404, "这朵花不存在")
		return
	}
	if err != nil {
		dbFailure(w)
		return
	}
	if _, err = tx.Exec(`UPDATE garden_plants SET flower_type=? WHERE id=? AND user_id=?`, in.FlowerType, id, userID); err != nil {
		dbFailure(w)
		return
	}
	plant.FlowerType = in.FlowerType
	wallet, err := readGardenWallet(tx, userID)
	if err != nil {
		dbFailure(w)
		return
	}
	if err = tx.Commit(); err != nil {
		dbFailure(w)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"plant": plant, "wallet": wallet})
}
