package main

import (
	"errors"
	"fmt"
	"html/template"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// sharePage shows what's in a share without spending a view. With a valid ?ticket (set by openPage's
// redirect) it shows previews and download links instead, so reloading never spends another view.
func (s *server) sharePage(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	sh := s.load(ctx, r.PathValue("token"))
	if sh == nil {
		w.WriteHeader(http.StatusNotFound)
		sharePage.Execute(w, map[string]any{"Gone": "链接不存在或已过期销毁。"})
		return
	}
	left := time.Until(sh.Expires)
	data := map[string]any{
		"Token":  sh.Token,
		"Files":  sh.Files,
		"Left":   fmt.Sprintf("%d 小时 %d 分钟", int(left.Hours()), int(left.Minutes())%60),
		"WeChat": strings.Contains(r.UserAgent(), "MicroMessenger"),
	}
	if ticket := r.URL.Query().Get("ticket"); s.validTicket(ctx, ticket, sh.Token) {
		data["Ticket"] = ticket
	} else {
		views, err := s.viewsLeft(ctx, sh)
		if err != nil {
			serverError(w, err)
			return
		}
		if views != nil && *views == 0 {
			w.WriteHeader(http.StatusGone)
			sharePage.Execute(w, map[string]any{"Gone": "查看次数已用完。"})
			return
		}
		data["ViewsLeft"] = views
	}
	sharePage.Execute(w, data)
}

// openPage spends a view and redirects to the ticketed page; on failure the plain page explains why.
func (s *server) openPage(w http.ResponseWriter, r *http.Request) {
	target := "/s/" + url.PathEscape(r.PathValue("token"))
	if sh := s.load(r.Context(), r.PathValue("token")); sh != nil {
		ticket, err := s.open(r.Context(), sh)
		if err != nil && !errors.Is(err, errViewsUsedUp) {
			serverError(w, err)
			return
		}
		if err == nil {
			target += "?ticket=" + ticket
		}
	}
	http.Redirect(w, r, target, http.StatusSeeOther)
}

// assetLinks proves to Android that the app owns this domain, so /s/ links open in the app (App Links).
func (s *server) assetLinks(w http.ResponseWriter, r *http.Request) {
	if s.cfg.AndroidPackage == "" || s.cfg.AndroidCertSHA256 == "" {
		http.NotFound(w, r)
		return
	}
	writeJSON(w, []map[string]any{{
		"relation": []string{"delegate_permission/common.handle_all_urls"},
		"target": map[string]any{
			"namespace":                "android_app",
			"package_name":             s.cfg.AndroidPackage,
			"sha256_cert_fingerprints": []string{s.cfg.AndroidCertSHA256},
		},
	}})
}

const pageHead = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ukiyo 浮世</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#f6f4ef;color:#222}
main{max-width:640px;margin:auto;padding:20px}
h1{font-weight:500;letter-spacing:.1em}
.tip{background:#fff3cd;padding:10px 14px;border-radius:8px}
.item{background:#fff;border-radius:12px;padding:12px;margin:16px 0;box-shadow:0 1px 3px #0001}
.item img,.item video{width:100%;border-radius:8px;display:block;margin-bottom:10px}
.item a,button{display:block;width:100%;text-align:center;padding:12px;background:#222;color:#fff;border:0;border-radius:8px;text-decoration:none;font-size:16px}
.meta{color:#888;font-size:13px;margin:6px 0}
</style></head><body><main>
<h1>浮世</h1>
`

const homeHTML = pageHead + `<p>请用 Ukiyo App 发送。</p>
</main></body></html>`

var sharePage = template.Must(template.New("share").Funcs(template.FuncMap{
	"mb":      func(n int64) string { return fmt.Sprintf("%.1f MB", float64(n)/(1<<20)) },
	"isImage": func(t string) bool { return inlineSafe(t) && strings.HasPrefix(t, "image/") },
	"isVideo": func(t string) bool { return strings.HasPrefix(t, "video/") },
}).Parse(pageHead + `{{if .Gone}}<p>{{.Gone}}</p>{{else}}
{{if .WeChat}}<p class="tip">微信内无法下载原图，请点右上角「…」选择「在浏览器打开」。</p>{{end}}
<p class="meta">{{len .Files}} 个文件 · {{.Left}}后自动销毁{{with .ViewsLeft}} · 还可查看 {{.}} 次{{end}}</p>
{{if .Ticket}}{{range $i, $f := .Files}}<div class="item">
{{if isImage $f.Type}}<img src="/s/{{$.Token}}/{{$i}}?ticket={{$.Ticket}}&inline" loading="lazy" alt="{{$f.Name}}" onerror="this.remove()">
{{else if isVideo $f.Type}}<video src="/s/{{$.Token}}/{{$i}}?ticket={{$.Ticket}}&inline" controls preload="metadata"></video>{{end}}
<div class="meta">{{$f.Name}} · {{mb $f.Size}}</div>
<a href="/s/{{$.Token}}/{{$i}}?ticket={{$.Ticket}}" download="{{$f.Name}}">下载原图</a>
</div>{{end}}
{{else}}{{range .Files}}<div class="meta">{{.Name}} · {{mb .Size}}</div>{{end}}
<form method="post" action="/s/{{.Token}}/open"><button>查看</button></form>{{end}}
{{end}}
</main></body></html>`))
