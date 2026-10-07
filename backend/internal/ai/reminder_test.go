package ai

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"niannian/internal/config"
)

func TestReminderStrategyStrictValidation(t *testing.T) {
	valid := `{"reason":"先通知再升级","steps":[{"channel":"notification","wait_minutes":0,"reason":"先轻提醒"},{"channel":"alarm","wait_minutes":17,"reason":"留出做事时间"}]}`
	for _, tc := range []struct {
		name, raw string
		accepted  bool
	}{
		{"valid", valid, true},
		{"trailing", valid + ` {}`, false},
		{"unauthorized", `{"reason":"电话催办","steps":[{"channel":"phone","wait_minutes":0,"reason":"打电话"}]}`, false},
		{"too_fast", `{"reason":"加快","steps":[{"channel":"notification","wait_minutes":0,"reason":"开始"},{"channel":"alarm","wait_minutes":1,"reason":"继续"}]}`, false},
		{"invalid_first", `{"reason":"延后","steps":[{"channel":"notification","wait_minutes":3,"reason":"开始"}]}`, false},
		{"unknown", `{"reason":"轻提醒","steps":[{"channel":"notification","wait_minutes":0,"reason":"开始","phone_number":"123"}]}`, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				_ = json.NewEncoder(w).Encode(map[string]any{"choices": []any{map[string]any{"finish_reason": "stop", "message": map[string]string{"content": tc.raw}}}})
			}))
			defer server.Close()
			result, err := New(config.Config{DeepSeekAPIKey: "fake", DeepSeekBaseURL: server.URL}).DecideReminder(context.Background(), ReminderContext{AllowedChannels: []string{"notification", "alarm"}, MinInterval: 2})
			if (err == nil) != tc.accepted {
				t.Fatalf("accepted=%v error=%v strategy=%+v", tc.accepted, err, result)
			}
		})
	}
}
