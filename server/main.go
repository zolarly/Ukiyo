// Ukiyo server: invite-gated uploads, link-based downloads, everything gone after a TTL.
//
// Each share is a directory under the data dir named by its token, holding the
// raw files ("0", "1", ...) and meta.json. No database: shares live for 24h,
// so the filesystem is the index.
package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"log"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

type config struct {
	dataDir  string
	ttl      time.Duration
	maxBytes int64
	codes    []string
}

type fileMeta struct {
	Name string `json:"name"`
	Type string `json:"type"`
	Size int64  `json:"size"`
}

type share struct {
	Created time.Time  `json:"created"`
	Files   []fileMeta `json:"files"`
}

type server struct{ cfg config }

var tokenRe = regexp.MustCompile(`^[A-Za-z0-9_-]{22}$`)

func main() {
	ttl, err := time.ParseDuration(env("UKIYO_TTL", "24h"))
	if err != nil {
		log.Fatal("UKIYO_TTL: ", err)
	}
	maxMB, err := strconv.ParseInt(env("UKIYO_MAX_MB", "300"), 10, 64)
	if err != nil {
		log.Fatal("UKIYO_MAX_MB: ", err)
	}
	var codes []string
	for _, c := range strings.Split(os.Getenv("UKIYO_INVITE_CODES"), ",") {
		if c = strings.TrimSpace(c); c != "" {
			codes = append(codes, c)
		}
	}
	if len(codes) == 0 {
		log.Fatal("UKIYO_INVITE_CODES is required (comma separated)")
	}
	s := &server{config{env("UKIYO_DATA", "data"), ttl, maxMB << 20, codes}}
	if err := os.MkdirAll(s.cfg.dataDir, 0o700); err != nil {
		log.Fatal(err)
	}
	go func() {
		for {
			s.sweep()
			time.Sleep(10 * time.Minute)
		}
	}()
	addr := env("UKIYO_ADDR", ":8080")
	log.Printf("ukiyo listening on %s, data in %s, ttl %s", addr, s.cfg.dataDir, ttl)
	// No ReadTimeout: a 300MB upload over a 3Mbps line legitimately takes minutes.
	srv := &http.Server{Addr: addr, Handler: s.routes(), ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(srv.ListenAndServe())
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, uploadHTML)
	})
	mux.HandleFunc("POST /api/shares", s.createShare)
	mux.HandleFunc("GET /api/shares/{token}", s.shareJSON)
	mux.HandleFunc("GET /s/{token}", s.sharePage)
	mux.HandleFunc("GET /s/{token}/{index}", s.serveFile)
	return mux
}

func (s *server) validCode(code string) bool {
	ok := 0
	for _, c := range s.cfg.codes {
		ok |= subtle.ConstantTimeCompare([]byte(code), []byte(c))
	}
	return ok == 1
}

func newToken() string {
	b := make([]byte, 16)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func (s *server) createShare(w http.ResponseWriter, r *http.Request) {
	if !s.validCode(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")) {
		http.Error(w, "invalid invite code", http.StatusUnauthorized)
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, s.cfg.maxBytes)
	mr, err := r.MultipartReader()
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	// Write into a temp dir and rename at the end, so a half-finished upload is never visible.
	tmp, err := os.MkdirTemp(s.cfg.dataDir, ".tmp-")
	if err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	defer os.RemoveAll(tmp) // no-op once renamed

	var sh share
	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			uploadError(w, err)
			return
		}
		if part.FormName() != "file" || part.FileName() == "" {
			continue
		}
		// Bytes are stored untouched: no re-encoding, EXIF and embedded motion-photo video survive.
		f, err := os.Create(filepath.Join(tmp, strconv.Itoa(len(sh.Files))))
		if err != nil {
			http.Error(w, "storage error", http.StatusInternalServerError)
			return
		}
		n, err := io.Copy(f, part)
		if cerr := f.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			uploadError(w, err)
			return
		}
		sh.Files = append(sh.Files, fileMeta{Name: part.FileName(), Type: part.Header.Get("Content-Type"), Size: n})
	}
	if len(sh.Files) == 0 {
		http.Error(w, `no files in field "file"`, http.StatusBadRequest)
		return
	}
	sh.Created = time.Now()
	meta, _ := json.Marshal(sh)
	if err := os.WriteFile(filepath.Join(tmp, "meta.json"), meta, 0o600); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	token := newToken()
	if err := os.Rename(tmp, filepath.Join(s.cfg.dataDir, token)); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]any{
		"token":      token,
		"url":        baseURL(r) + "/s/" + token,
		"expires_at": sh.Created.Add(s.cfg.ttl),
	})
}

func uploadError(w http.ResponseWriter, err error) {
	var tooBig *http.MaxBytesError
	if errors.As(err, &tooBig) {
		http.Error(w, "upload too large", http.StatusRequestEntityTooLarge)
		return
	}
	http.Error(w, "upload failed: "+err.Error(), http.StatusBadRequest)
}

// baseURL trusts the Host and X-Forwarded-Proto headers set by the reverse proxy.
func baseURL(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

// load returns the share for token, or nil if it is malformed, missing or expired.
func (s *server) load(token string) *share {
	if !tokenRe.MatchString(token) {
		return nil
	}
	b, err := os.ReadFile(filepath.Join(s.cfg.dataDir, token, "meta.json"))
	if err != nil {
		return nil
	}
	var sh share
	if json.Unmarshal(b, &sh) != nil || time.Since(sh.Created) > s.cfg.ttl {
		return nil
	}
	return &sh
}

func (s *server) shareJSON(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	sh := s.load(token)
	if sh == nil {
		http.NotFound(w, r)
		return
	}
	type file struct {
		fileMeta
		URL string `json:"url"`
	}
	files := make([]file, len(sh.Files))
	for i, f := range sh.Files {
		files[i] = file{f, fmt.Sprintf("%s/s/%s/%d", baseURL(r), token, i)}
	}
	writeJSON(w, map[string]any{"files": files, "expires_at": sh.Created.Add(s.cfg.ttl)})
}

func (s *server) serveFile(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	sh := s.load(token)
	i, err := strconv.Atoi(r.PathValue("index"))
	if sh == nil || err != nil || i < 0 || i >= len(sh.Files) {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(filepath.Join(s.cfg.dataDir, token, strconv.Itoa(i)))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	meta := sh.Files[i]
	// Uploaded content is untrusted: only image/video may render inline, and never with scripts.
	disposition, ctype := "attachment", "application/octet-stream"
	if inlineSafe(meta.Type) {
		ctype = meta.Type
		if r.URL.Query().Has("inline") {
			disposition = "inline"
		}
	}
	h := w.Header()
	h.Set("Content-Type", ctype)
	h.Set("Content-Disposition", mime.FormatMediaType(disposition, map[string]string{"filename": meta.Name}))
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Content-Security-Policy", "sandbox")
	http.ServeContent(w, r, "", sh.Created, f) // handles Range, so interrupted downloads resume
}

func inlineSafe(t string) bool {
	return (strings.HasPrefix(t, "image/") && t != "image/svg+xml") || strings.HasPrefix(t, "video/")
}

func (s *server) sharePage(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	sh := s.load(token)
	if sh == nil {
		w.WriteHeader(http.StatusNotFound)
		sharePage.Execute(w, nil)
		return
	}
	left := time.Until(sh.Created.Add(s.cfg.ttl))
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	sharePage.Execute(w, map[string]any{
		"Token":  token,
		"Files":  sh.Files,
		"Left":   fmt.Sprintf("%d 小时 %d 分钟", int(left.Hours()), int(left.Minutes())%60),
		"WeChat": strings.Contains(r.UserAgent(), "MicroMessenger"),
	})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

// sweep deletes every share (and abandoned temp upload) older than the TTL.
// A share dir's mtime is when its last file was written, i.e. upload completion.
func (s *server) sweep() {
	entries, err := os.ReadDir(s.cfg.dataDir)
	if err != nil {
		log.Print("sweep: ", err)
		return
	}
	for _, e := range entries {
		info, err := e.Info()
		if err != nil || time.Since(info.ModTime()) <= s.cfg.ttl {
			continue
		}
		if err := os.RemoveAll(filepath.Join(s.cfg.dataDir, e.Name())); err != nil {
			log.Print("sweep: ", err)
		}
	}
}

var sharePage = template.Must(template.New("share").Funcs(template.FuncMap{
	"mb":      func(n int64) string { return fmt.Sprintf("%.1f MB", float64(n)/(1<<20)) },
	"isImage": func(t string) bool { return inlineSafe(t) && strings.HasPrefix(t, "image/") },
	"isVideo": func(t string) bool { return strings.HasPrefix(t, "video/") },
}).Parse(`<!doctype html>
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
.item a{display:block;text-align:center;padding:12px;background:#222;color:#fff;border-radius:8px;text-decoration:none}
.meta{color:#888;font-size:13px;margin:6px 0}
</style></head><body><main>
<h1>浮世</h1>
{{if not .}}<p>链接不存在或已过期销毁。</p>{{else}}
{{if .WeChat}}<p class="tip">微信内无法下载原图，请点右上角「…」选择「在浏览器打开」。</p>{{end}}
<p class="meta">{{len .Files}} 个文件 · {{.Left}}后自动销毁</p>
{{range $i, $f := .Files}}<div class="item">
{{if isImage $f.Type}}<img src="/s/{{$.Token}}/{{$i}}?inline" loading="lazy" alt="{{$f.Name}}" onerror="this.remove()">
{{else if isVideo $f.Type}}<video src="/s/{{$.Token}}/{{$i}}?inline" controls preload="metadata"></video>{{end}}
<div class="meta">{{$f.Name}} · {{mb $f.Size}}</div>
<a href="/s/{{$.Token}}/{{$i}}" download="{{$f.Name}}">下载原图</a>
</div>{{end}}{{end}}
</main></body></html>`))

// No accept= on the file input: on iOS that would make Safari transcode HEIC to JPEG.
const uploadHTML = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ukiyo 浮世 · 上传</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#f6f4ef;color:#222}
main{max-width:640px;margin:auto;padding:20px}
h1{font-weight:500;letter-spacing:.1em}
input,button{display:block;width:100%;box-sizing:border-box;margin:10px 0;padding:12px;font-size:16px;border-radius:8px;border:1px solid #ccc}
button{background:#222;color:#fff;border:0}
#out{word-break:break-all}
</style></head><body><main>
<h1>浮世</h1>
<input id="code" placeholder="邀请码">
<input id="files" type="file" multiple>
<button id="go">上传并生成链接</button>
<p id="out"></p>
<script>
const code = document.getElementById("code"), out = document.getElementById("out");
code.value = localStorage.getItem("ukiyo-code") || "";
document.getElementById("go").onclick = () => {
  const files = document.getElementById("files").files;
  if (!files.length) return;
  localStorage.setItem("ukiyo-code", code.value);
  const form = new FormData();
  for (const f of files) form.append("file", f);
  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/shares");
  xhr.setRequestHeader("Authorization", "Bearer " + code.value);
  xhr.upload.onprogress = e => out.textContent = "上传中 " + Math.round(e.loaded / e.total * 100) + "%";
  xhr.onload = () => {
    if (xhr.status !== 200) { out.textContent = "失败：" + xhr.responseText; return; }
    const url = JSON.parse(xhr.responseText).url;
    out.innerHTML = "";
    const a = document.createElement("a");
    a.href = a.textContent = url;
    out.append("链接（24 小时后销毁）：", a);
    navigator.clipboard && navigator.clipboard.writeText(url).then(() => out.append("（已复制）"));
  };
  xhr.onerror = () => out.textContent = "网络错误";
  xhr.send(form);
};
</script>
</main></body></html>`
