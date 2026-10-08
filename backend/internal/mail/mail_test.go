package mail

import (
	"context"
	"encoding/base64"
	"image/png"
	"io"
	"mime"
	"mime/multipart"
	netmail "net/mail"
	"niannian/internal/config"
	"strings"
	"testing"
)

type decodedPart struct {
	kind string
	id   string
	body []byte
}

func decodeMessage(t *testing.T, raw []byte) []decodedPart {
	t.Helper()
	m, err := netmail.ReadMessage(strings.NewReader(string(raw)))
	if err != nil {
		t.Fatal(err)
	}
	var parts []decodedPart
	var walk func(string, string, string, io.Reader)
	walk = func(contentType, encoding, id string, r io.Reader) {
		kind, params, err := mime.ParseMediaType(contentType)
		if err != nil {
			t.Fatal(err)
		}
		if strings.HasPrefix(kind, "multipart/") {
			mr := multipart.NewReader(r, params["boundary"])
			for {
				p, err := mr.NextPart()
				if err == io.EOF {
					break
				}
				if err != nil {
					t.Fatal(err)
				}
				walk(p.Header.Get("Content-Type"), p.Header.Get("Content-Transfer-Encoding"), p.Header.Get("Content-ID"), p)
			}
			return
		}
		if encoding == "base64" {
			r = base64.NewDecoder(base64.StdEncoding, r)
		}
		b, err := io.ReadAll(r)
		if err != nil {
			t.Fatal(err)
		}
		parts = append(parts, decodedPart{kind, id, b})
	}
	walk(m.Header.Get("Content-Type"), m.Header.Get("Content-Transfer-Encoding"), "", m.Body)
	return parts
}

func TestMessageIncludesHTMLPlainTextAndInlineArtwork(t *testing.T) {
	c := New(config.Config{SMTPUser: "sender@example.com"})
	body := "读书 <script>alert(1)</script> & 休息\n下一行"
	raw, err := c.message("reader@example.com", "念念提醒", body)
	if err != nil {
		t.Fatal(err)
	}
	parts := decodeMessage(t, raw)
	if len(parts) != 4 {
		t.Fatalf("want plain, HTML and two inline images; got %d parts", len(parts))
	}
	if parts[0].kind != "text/plain" || string(parts[0].body) != body {
		t.Fatal("plain text changed")
	}
	html := string(parts[1].body)
	if !strings.Contains(html, `<!--[if mso]><table role="presentation" width="560" align="center"><tr><td><![endif]-->`) || !strings.Contains(html, `<!--[if mso]></td></tr></table><![endif]-->`) {
		t.Fatal("Outlook fixed-width wrapper was stripped")
	}
	if parts[1].kind != "text/html" || strings.Contains(html, "<script>") || !strings.Contains(html, "&lt;script&gt;") {
		t.Fatal("HTML missing or user input not escaped")
	}
	for _, id := range []string{"letter@niannian", "garden@niannian"} {
		if !strings.Contains(html, "cid:"+id) {
			t.Fatalf("missing HTML CID %s", id)
		}
		found := false
		for _, p := range parts[2:] {
			if p.id == "<"+id+">" && p.kind == "image/png" && len(p.body) > 100 {
				found = true
			}
		}
		if !found {
			t.Fatalf("missing inline artwork %s", id)
		}
	}
	if strings.Contains(html, "seed.png") || strings.Contains(html, "assets/") {
		t.Fatal("preview image path leaked into email")
	}
	for _, p := range parts[2:] {
		if _, err := png.Decode(strings.NewReader(string(p.body))); err != nil {
			t.Fatalf("invalid artwork: %v", err)
		}
	}
	if len(raw) > 1200000 {
		t.Fatalf("email unnecessarily large: %d bytes", len(raw))
	}
}

func TestStructuredReminderPreservesTextAndEscapesTitle(t *testing.T) {
	c := New(config.Config{SMTPUser: "sender@example.com"})
	raw, err := c.message("reader@example.com", "念念提醒", "原始纯文字", Content{Title: `<img src=x onerror=alert(1)>`, Body: "第一行\r\n第二行\n\n每天 20:30"})
	if err != nil {
		t.Fatal(err)
	}
	parts := decodeMessage(t, raw)
	html := string(parts[1].body)
	if string(parts[0].body) != "原始纯文字" || strings.Contains(html, "<img src=x") || !strings.Contains(html, "&lt;img") || !strings.Contains(html, "第一行<br>第二行<br><br>每天 20:30") {
		t.Fatal("structured reminder lost or failed to escape content")
	}
}

func TestVerificationTemplateKeepsLeadingZerosAndExpiry(t *testing.T) {
	c := New(config.Config{SMTPUser: "sender@example.com"})
	raw, err := c.message("reader@example.com", "念念 · 账号验证码", "你的验证码是：001234", Content{Code: "001234"})
	if err != nil {
		t.Fatal(err)
	}
	html := string(decodeMessage(t, raw)[1].body)
	for _, want := range []string{"001234", "10 分钟内有效", "请勿把验证码分享给他人", "cid:letter@niannian"} {
		if !strings.Contains(html, want) {
			t.Fatalf("missing %q", want)
		}
	}
	if strings.Contains(html, "点击完成") || strings.Contains(html, "482619") {
		t.Fatal("reminder or sample leaked into verification email")
	}
	for _, invalid := range []string{"12345", "1234567", "12<456", "１２３４５６"} {
		if _, err := c.message("reader@example.com", "验证码", "", Content{Code: invalid}); err == nil {
			t.Fatalf("accepted invalid code %q", invalid)
		}
	}
}

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
