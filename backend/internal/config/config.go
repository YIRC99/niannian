package config

import (
	"bytes"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/mail"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

//go:embed defaults/*.json
var defaults embed.FS

type Config struct {
	Addr            string   `json:"addr"`
	DataDir         string   `json:"data_dir"`
	DeepSeekAPIKey  string   `json:"deepseek_api_key"`
	DeepSeekBaseURL string   `json:"deepseek_base_url"`
	DeepSeekModel   string   `json:"deepseek_model"`
	SMTPHost        string   `json:"smtp_host"`
	SMTPUser        string   `json:"smtp_user"`
	SMTPPassword    string   `json:"smtp_password"`
	SMTPPort        int      `json:"smtp_port"`
	AllowedEmails   []string `json:"allowed_emails"`
	PublicURL       string   `json:"public_url"`
}

func EmbeddedDefaults() []byte {
	b, err := defaults.ReadFile("defaults/secrets.local.json")
	if err == nil {
		return b
	}
	b, _ = defaults.ReadFile("defaults/base.json")
	return b
}

func Load(embedded []byte) (Config, error) {
	c := Config{Addr: ":56507", DataDir: "data", DeepSeekBaseURL: "https://api.deepseek.com", DeepSeekModel: "deepseek-flash", SMTPHost: "hwsmtp.qiye.163.com", SMTPPort: 465, AllowedEmails: []string{"yirc99@163.com"}}
	apply := func(data []byte) error {
		if len(bytes.TrimSpace(data)) == 0 {
			return nil
		}
		d := json.NewDecoder(bytes.NewReader(data))
		d.DisallowUnknownFields()
		if err := d.Decode(&c); err != nil {
			return err
		}
		var extra any
		if err := d.Decode(&extra); err != io.EOF {
			return errors.New("配置只能包含一个 JSON 对象")
		}
		return nil
	}
	if err := apply(embedded); err != nil {
		return c, fmt.Errorf("内置配置格式错误: %w", err)
	}
	path := os.Getenv("NIANNIAN_CONFIG")
	explicit := path != ""
	if path == "" {
		path = "config.local.json"
	}
	if data, err := os.ReadFile(path); err == nil {
		if err = apply(data); err != nil {
			return c, fmt.Errorf("本地配置格式错误: %w", err)
		}
	} else if explicit || !errors.Is(err, os.ErrNotExist) {
		return c, fmt.Errorf("无法读取配置文件: %w", err)
	}
	vars := map[string]*string{"ADDR": &c.Addr, "DATA_DIR": &c.DataDir, "DEEPSEEK_API_KEY": &c.DeepSeekAPIKey, "DEEPSEEK_BASE_URL": &c.DeepSeekBaseURL, "DEEPSEEK_MODEL": &c.DeepSeekModel, "SMTP_HOST": &c.SMTPHost, "SMTP_USER": &c.SMTPUser, "SMTP_PASSWORD": &c.SMTPPassword, "PUBLIC_URL": &c.PublicURL}
	for k, v := range vars {
		if e, ok := os.LookupEnv("NIANNIAN_" + k); ok {
			*v = strings.TrimSpace(e)
		}
	}
	if s, ok := os.LookupEnv("NIANNIAN_SMTP_PORT"); ok {
		n, err := strconv.Atoi(s)
		if err != nil {
			return c, errors.New("SMTP 端口必须为数字")
		}
		c.SMTPPort = n
	}
	if s, ok := os.LookupEnv("NIANNIAN_ALLOWED_EMAILS"); ok {
		c.AllowedEmails = strings.Split(s, ",")
	}
	for i, email := range c.AllowedEmails {
		c.AllowedEmails[i] = strings.ToLower(strings.TrimSpace(email))
		if _, err := mail.ParseAddress(c.AllowedEmails[i]); err != nil {
			return c, errors.New("允许登录的邮箱格式不正确")
		}
	}
	if len(c.AllowedEmails) == 0 {
		return c, errors.New("至少配置一个允许登录的邮箱")
	}
	if c.SMTPPort < 1 || c.SMTPPort > 65535 {
		return c, errors.New("SMTP 端口超出范围")
	}
	u, err := url.Parse(c.DeepSeekBaseURL)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return c, errors.New("AI 服务地址必须为 HTTPS 地址")
	}
	c.DeepSeekBaseURL = strings.TrimRight(c.DeepSeekBaseURL, "/")
	if c.Addr == "" || c.DataDir == "" {
		return c, errors.New("监听地址和数据目录不能为空")
	}
	c.DataDir, err = filepath.Abs(c.DataDir)
	if err != nil {
		return c, err
	}
	return c, nil
}
