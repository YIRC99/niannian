package mail

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"fmt"
	"mime"
	"mime/multipart"
	"net"
	netmail "net/mail"
	"net/smtp"
	"net/textproto"
	"strconv"
	"strings"
	"time"

	"niannian/internal/config"
)

type Client struct{ cfg config.Config }

func New(cfg config.Config) *Client { return &Client{cfg: cfg} }

func address(s string) (string, error) {
	if strings.ContainsAny(s, "\r\n") {
		return "", errors.New("邮箱格式不正确")
	}
	a, err := netmail.ParseAddress(s)
	if err != nil || a.Address != s {
		return "", errors.New("邮箱格式不正确")
	}
	return a.Address, nil
}

func (c *Client) message(to, subject, body string, content ...Content) ([]byte, error) {
	if _, err := address(to); err != nil {
		return nil, err
	}
	if _, err := address(c.cfg.SMTPUser); err != nil {
		return nil, errors.New("发件邮箱配置错误")
	}
	if strings.ContainsAny(subject, "\r\n") {
		return nil, errors.New("邮件标题格式不正确")
	}
	html, err := renderHTML(subject, body, content)
	if err != nil {
		return nil, err
	}
	var payload bytes.Buffer
	alternative := multipart.NewWriter(&payload)
	if err = writePart(alternative, "text/plain; charset=UTF-8", "", []byte(body)); err != nil {
		return nil, err
	}
	var relatedBody bytes.Buffer
	related := multipart.NewWriter(&relatedBody)
	if err = writePart(related, "text/html; charset=UTF-8", "", []byte(html)); err != nil {
		return nil, err
	}
	for _, name := range []string{"letter", "garden"} {
		data, readErr := artwork.ReadFile("assets/" + name + ".png")
		if readErr != nil {
			return nil, errors.New("邮件插画读取失败")
		}
		if err = writePart(related, "image/png", name+"@niannian", data); err != nil {
			return nil, err
		}
	}
	if err = related.Close(); err != nil {
		return nil, err
	}
	h := textproto.MIMEHeader{}
	h.Set("Content-Type", mime.FormatMediaType("multipart/related", map[string]string{"boundary": related.Boundary(), "type": "text/html"}))
	p, err := alternative.CreatePart(h)
	if err != nil {
		return nil, err
	}
	if _, err = p.Write(relatedBody.Bytes()); err != nil {
		return nil, err
	}
	if err = alternative.Close(); err != nil {
		return nil, err
	}
	contentType := mime.FormatMediaType("multipart/alternative", map[string]string{"boundary": alternative.Boundary()})
	return append([]byte(fmt.Sprintf("From: %s <%s>\r\nTo: <%s>\r\nSubject: %s\r\nDate: %s\r\nMIME-Version: 1.0\r\nContent-Type: %s\r\n\r\n", mime.QEncoding.Encode("UTF-8", "念念提醒"), c.cfg.SMTPUser, to, mime.QEncoding.Encode("UTF-8", subject), time.Now().Format(time.RFC1123Z), contentType)), payload.Bytes()...), nil
}

func writePart(w *multipart.Writer, contentType, id string, data []byte) error {
	h := textproto.MIMEHeader{}
	h.Set("Content-Type", contentType)
	h.Set("Content-Transfer-Encoding", "base64")
	if id != "" {
		h.Set("Content-ID", "<"+id+">")
		h.Set("Content-Disposition", "inline")
	}
	p, err := w.CreatePart(h)
	if err != nil {
		return err
	}
	encoded := base64.StdEncoding.EncodeToString(data)
	var lines strings.Builder
	for len(encoded) > 76 {
		lines.WriteString(encoded[:76])
		lines.WriteString("\r\n")
		encoded = encoded[76:]
	}
	lines.WriteString(encoded)
	lines.WriteString("\r\n")
	_, err = p.Write([]byte(lines.String()))
	return err
}

func (c *Client) connect(ctx context.Context) (*smtp.Client, error) {
	if c.cfg.SMTPHost == "" || c.cfg.SMTPUser == "" || c.cfg.SMTPPassword == "" {
		return nil, errors.New("邮件服务尚未配置")
	}
	addr := net.JoinHostPort(c.cfg.SMTPHost, strconv.Itoa(c.cfg.SMTPPort))
	dialer := &net.Dialer{Timeout: 12 * time.Second}
	tc := &tls.Config{ServerName: c.cfg.SMTPHost, MinVersion: tls.VersionTLS12}
	var conn net.Conn
	var err error
	if c.cfg.SMTPPort == 465 {
		conn, err = (&tls.Dialer{NetDialer: dialer, Config: tc}).DialContext(ctx, "tcp", addr)
	} else {
		conn, err = dialer.DialContext(ctx, "tcp", addr)
	}
	if err != nil {
		return nil, errors.New("无法连接邮件服务器，请检查 SMTP 配置或网络")
	}
	deadline := time.Now().Add(25 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	_ = conn.SetDeadline(deadline)
	s, err := smtp.NewClient(conn, c.cfg.SMTPHost)
	if err != nil {
		conn.Close()
		return nil, errors.New("邮件服务器握手失败")
	}
	if c.cfg.SMTPPort != 465 {
		if err = s.StartTLS(tc); err != nil {
			s.Close()
			return nil, errors.New("邮件服务器未能建立 TLS 加密连接")
		}
	}
	if err = s.Auth(smtp.PlainAuth("", c.cfg.SMTPUser, c.cfg.SMTPPassword, c.cfg.SMTPHost)); err != nil {
		s.Close()
		return nil, errors.New("邮件服务器认证失败，请检查发件邮箱和授权码")
	}
	return s, nil
}

// Check authenticates without sending any email.
func (c *Client) Check(ctx context.Context) error {
	s, err := c.connect(ctx)
	if err != nil {
		return err
	}
	defer s.Close()
	return s.Quit()
}

func (c *Client) Send(ctx context.Context, to, subject, body string, content ...Content) error {
	msg, err := c.message(to, subject, body, content...)
	if err != nil {
		return err
	}
	s, err := c.connect(ctx)
	if err != nil {
		return err
	}
	defer s.Close()
	if err = s.Mail(c.cfg.SMTPUser); err != nil {
		return errors.New("邮件服务器拒绝发件人")
	}
	if err = s.Rcpt(to); err != nil {
		return errors.New("邮件服务器拒绝收件人")
	}
	w, err := s.Data()
	if err != nil {
		return errors.New("邮件服务器未接受邮件内容")
	}
	if _, err = w.Write(msg); err != nil {
		return errors.New("邮件传输中断，发送结果待确认")
	}
	if err = w.Close(); err != nil {
		return errors.New("邮件服务器未确认接收，发送结果待确认")
	}
	// DATA acceptance establishes success even if the final QUIT fails.
	_ = s.Quit()
	return nil
}
