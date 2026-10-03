package mail

import (
	"context"
	"encoding/base64"
	"niannian/internal/config"
	"strings"
	"testing"
)

func TestMessageIsUTF8AndHeaderInjectionRejected(t *testing.T) {
	c := New(config.Config{SMTPUser: "sender@example.com", SMTPHost: "smtp.example.com", SMTPPort: 465})
	msg, err := c.message("reader@example.com", "念念提醒：申报", "今天记得完成申报。")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(msg), "charset=UTF-8") || !strings.Contains(string(msg), base64.StdEncoding.EncodeToString([]byte("今天记得完成申报。"))) {
		t.Fatal("UTF8 MIME missing")
	}
	if _, err = c.message("reader@example.com\r\nBcc: other@example.com", "主题", "内容"); err == nil {
		t.Fatal("recipient injection accepted")
	}
	if _, err = c.message("reader@example.com", "主题\nInjected: yes", "内容"); err == nil {
		t.Fatal("subject injection accepted")
	}
}

func TestMissingConfigFailsWithoutNetwork(t *testing.T) {
	if err := New(config.Config{}).Send(context.Background(), "a@example.com", "subject", "body"); err == nil {
		t.Fatal("missing credentials accepted")
	}
}
