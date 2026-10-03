package mail

import (
	"context"
	"niannian/internal/config"
	"os"
	"testing"
	"time"
)

func TestLiveSMTPAuthenticationOnly(t *testing.T) {
	if os.Getenv("NIANNIAN_LIVE_SMTP") != "1" {
		t.Skip("opt-in, authenticate only; never sends email")
	}
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err = New(cfg).Check(ctx); err != nil {
		t.Fatal(err)
	}
	t.Log("SMTP TLS + AUTH successful; no email sent")
}
