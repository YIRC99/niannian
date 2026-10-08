package mail

import (
	"bytes"
	"embed"
	"errors"
	"html/template"
	"strings"
)

// Content supplies structured display data without parsing user-authored text.
// The caller still provides the original plain-text body for fallback clients.
type Content struct {
	Title string
	Body  string
	Code  string
}

//go:embed templates/*.html assets/*.png
var artwork embed.FS

// html/template removes source comments. Preserve only these fixed Outlook
// layout fragments as trusted HTML; user content always stays escaped.
var emailTemplates = template.Must(template.New("").Funcs(template.FuncMap{
	"outlookStart": func() template.HTML {
		return template.HTML(`<!--[if mso]><table role="presentation" width="560" align="center"><tr><td><![endif]-->`)
	},
	"outlookEnd": func() template.HTML {
		return template.HTML(`<!--[if mso]></td></tr></table><![endif]-->`)
	},
}).ParseFS(artwork, "templates/*.html"))

func renderHTML(subject, body string, content []Content) (string, error) {
	if len(content) > 1 {
		return "", errors.New("邮件模板内容重复")
	}
	c := Content{Title: subject, Body: body}
	if len(content) == 1 {
		c = content[0]
	}
	name := "reminder.html"
	if c.Code != "" {
		if len(c.Code) != 6 || strings.Trim(c.Code, "0123456789") != "" {
			return "", errors.New("邮件验证码格式不正确")
		}
		name = "code.html"
	}
	data := struct {
		Content
		Lines []string
	}{c, strings.Split(strings.ReplaceAll(c.Body, "\r\n", "\n"), "\n")}
	var out bytes.Buffer
	if err := emailTemplates.ExecuteTemplate(&out, name, data); err != nil {
		return "", errors.New("邮件模板生成失败")
	}
	return out.String(), nil
}
