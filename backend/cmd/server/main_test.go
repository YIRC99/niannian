package main

import (
	"net"
	"strings"
	"testing"
)

func TestBindFailureReturnsErrorAndReleasesStorage(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	t.Setenv("NIANNIAN_ADDR", listener.Addr().String())
	t.Setenv("NIANNIAN_DATA_DIR", t.TempDir())
	if err := run(); err == nil || !strings.Contains(err.Error(), "HTTP 服务启动失败") {
		t.Fatalf("expected bind failure, got %v", err)
	}
	// A second failed startup must reach bind again, rather than leaking the data lock.
	if err := run(); err == nil || !strings.Contains(err.Error(), "HTTP 服务启动失败") {
		t.Fatalf("second startup did not release storage: %v", err)
	}
}
