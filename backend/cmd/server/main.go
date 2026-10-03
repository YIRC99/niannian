package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"niannian/internal/app"
	"niannian/internal/config"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"
	_ "time/tzdata"
)

func main() {
	if err := run(); err != nil {
		log.Print(err)
		os.Exit(1)
	}
}
func run() error {
	cfg, err := config.Load(config.EmbeddedDefaults())
	if err != nil {
		return err
	}
	service, err := app.New(cfg)
	if err != nil {
		return err
	}
	defer service.Close()
	// Bind before starting the scheduler: a failed HTTP startup must never send mail.
	listener, err := net.Listen("tcp", cfg.Addr)
	if err != nil {
		return fmt.Errorf("HTTP 服务启动失败: %w", err)
	}
	defer listener.Close()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	var workers sync.WaitGroup
	workers.Add(1)
	go func() { defer workers.Done(); service.RunScheduler(ctx) }()
	server := &http.Server{Addr: cfg.Addr, Handler: service.Handler(), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, WriteTimeout: 90 * time.Second, IdleTimeout: 90 * time.Second, MaxHeaderBytes: 16 << 10}
	done := make(chan error, 1)
	go func() { log.Printf("念念后端已启动，监听 %s", cfg.Addr); done <- server.Serve(listener) }()
	select {
	case err = <-done:
		stop()
	case <-ctx.Done():
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	if shutdownErr := server.Shutdown(shutdownCtx); shutdownErr != nil {
		server.Close()
	}
	workers.Wait()
	if err != nil && !errors.Is(err, http.ErrServerClosed) {
		return fmt.Errorf("HTTP 服务异常退出: %w", err)
	}
	return nil
}
