package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadEmbeddedOverridesAndFile(t *testing.T) {
	t.Chdir(t.TempDir())
	t.Setenv("NIANNIAN_ADDR", "127.0.0.1:9090")
	if err := os.WriteFile("config.local.json", []byte(`{"smtp_user":"file@example.com","allowed_emails":["me@example.com"]}`), 0600); err != nil {
		t.Fatal(err)
	}
	c, err := Load([]byte(`{"smtp_user":"embedded@example.com","deepseek_api_key":"private"}`))
	if err != nil {
		t.Fatal(err)
	}
	if c.SMTPUser != "file@example.com" || c.Addr != "127.0.0.1:9090" || c.DeepSeekAPIKey != "private" || len(c.AllowedEmails) != 0 {
		t.Fatal("configuration precedence incorrect")
	}
	if !filepath.IsAbs(c.DataDir) {
		t.Fatal("data folder should resolve absolute")
	}
}

func TestOpenRegistrationIgnoresLegacyAllowlist(t *testing.T) {
	t.Chdir(t.TempDir())
	t.Setenv("NIANNIAN_ALLOWED_EMAILS", "old@example.com")
	for _, raw := range []string{`{}`, `{"allowed_emails":[]}`, `{"allowed_emails":["old@example.com"]}`} {
		c, err := Load([]byte(raw))
		if err != nil || len(c.AllowedEmails) != 0 {
			t.Fatalf("legacy whitelist still active: err=%v", err)
		}
	}
}

func TestInvalidFileNeverSilentlyIgnored(t *testing.T) {
	t.Chdir(t.TempDir())
	os.WriteFile("config.local.json", []byte(`{"smtp_port":"oops"}`), 0600)
	if _, err := Load(nil); err == nil {
		t.Fatal("invalid config accepted")
	}
}

func TestTrailingJSONIsRejected(t *testing.T) {
	t.Chdir(t.TempDir())
	if _, err := Load([]byte(`{} {"smtp_port":1}`)); err == nil {
		t.Fatal("trailing config object ignored")
	}
}

func TestRejectInsecureProviderAndBadPort(t *testing.T) {
	t.Chdir(t.TempDir())
	for _, raw := range []string{`{"deepseek_base_url":"http://evil.example"}`, `{"smtp_port":-1}`} {
		if _, err := Load([]byte(raw)); err == nil {
			t.Fatalf("bad config accepted: %s", raw)
		}
	}
}
