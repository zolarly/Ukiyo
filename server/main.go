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
	"flag"
	"fmt"
	"html/template"
	"io"
	"io/fs"
	"log"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

type config struct {
	Addr              string   `json:"addr"`
	DataDir           string   `json:"data_dir"`
	TTL               duration `json:"ttl"`
	MaxUploadMB       int64    `json:"max_upload_mb"`
	MaxTotalMB        int64    `json:"max_total_mb"`
	InviteCodes       []string `json:"invite_codes"`
	AndroidPackage    string   `json:"android_package"`
	AndroidCertSHA256 string   `json:"android_cert_sha256"`
}

// duration reads "24h"-style strings from JSON.
type duration struct{ time.Duration }

func (d *duration) UnmarshalJSON(b []byte) error {
	var s string
	if err := json.Unmarshal(b, &s); err != nil {
		return err
	}
	v, err := time.ParseDuration(s)
	d.Duration = v
	return err
}

// loadConfig reads the JSON config at path. Every field except invite_codes has a default;
// unknown fields are rejected so a typo doesn't silently fall back to a default.
func loadConfig(path string) (config, error) {
	cfg := config{Addr: ":8080", DataDir: "data", TTL: duration{24 * time.Hour}, MaxUploadMB: 300, MaxTotalMB: 20000}
	f, err := os.Open(path)
	if err != nil {
		return cfg, err
	}
	defer f.Close()
	dec := json.NewDecoder(f)
	dec.DisallowUnknownFields()
	if err := dec.Decode(&cfg); err != nil {
		return cfg, fmt.Errorf("%s: %w", path, err)
	}
	var codes []string
	for _, c := range cfg.InviteCodes {
		if c = strings.TrimSpace(c); c != "" {
			codes = append(codes, c)
		}
	}
	if len(codes) == 0 {
		return cfg, errors.New("invite_codes is required")
	}
	cfg.InviteCodes = codes
	return cfg, nil
}

type fileMeta struct {
	Name string `json:"name"`
	Type string `json:"type"`
	Size int64  `json:"size"`
}

type share struct {
	Created   time.Time  `json:"created"`
	Files     []fileMeta `json:"files"`
	DeleteKey string     `json:"delete_key"`
}

type server struct {
	cfg config

	mu       sync.Mutex
	reserved int64 // bytes claimed by uploads still in flight
}

var tokenRe = regexp.MustCompile(`^[A-Za-z0-9_-]{22}$`)

func main() {
	path := flag.String("config", "config.json", "path to the JSON config file")
	flag.Parse()
	cfg, err := loadConfig(*path)
	if err != nil {
		log.Fatal("config: ", err)
	}
	s := &server{cfg: cfg}
	if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
		log.Fatal(err)
	}
	go func() {
		for {
			s.sweep()
			time.Sleep(10 * time.Minute)
		}
	}()
	log.Printf("ukiyo listening on %s, data in %s, ttl %s", cfg.Addr, cfg.DataDir, cfg.TTL.Duration)
	// No ReadTimeout: a 300MB upload over a 3Mbps line legitimately takes minutes.
	srv := &http.Server{Addr: cfg.Addr, Handler: s.routes(), ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(srv.ListenAndServe())
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		io.WriteString(w, uploadHTML)
	})
	mux.HandleFunc("POST /api/shares", s.createShare)
	mux.HandleFunc("GET /api/shares/{token}", s.shareJSON)
	mux.HandleFunc("DELETE /api/shares/{token}", s.deleteShare)
	mux.HandleFunc("GET /s/{token}", s.sharePage)
	mux.HandleFunc("GET /s/{token}/{index}", s.serveFile)
	mux.HandleFunc("GET /.well-known/assetlinks.json", s.assetLinks)
	return mux
}

func (s *server) validCode(code string) bool {
	ok := 0
	for _, c := range s.cfg.InviteCodes {
		ok |= subtle.ConstantTimeCompare([]byte(code), []byte(c))
	}
	return ok == 1
}

func newToken() string {
	b := make([]byte, 16)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// reserve claims n bytes of the max_total_mb quota for an upload, counting both what is on disk
// and what other in-flight uploads have claimed. Counting is conservative: an upload's partial temp
// files are on disk and in reserved at the same time.
func (s *server) reserve(n int64) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.diskUsage()+s.reserved+n > s.cfg.MaxTotalMB<<20 {
		return false
	}
	s.reserved += n
	return true
}

func (s *server) release(n int64) {
	s.mu.Lock()
	s.reserved -= n
	s.mu.Unlock()
}

func (s *server) diskUsage() int64 {
	var total int64
	filepath.WalkDir(s.cfg.DataDir, func(_ string, d fs.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if info, err := d.Info(); err == nil {
				total += info.Size()
			}
		}
		return nil
	})
	return total
}

func (s *server) createShare(w http.ResponseWriter, r *http.Request) {
	if !s.validCode(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")) {
		http.Error(w, "invalid invite code", http.StatusUnauthorized)
		return
	}
	// Oversized or unknown-length bodies count as the per-upload max; MaxBytesReader rejects the rest with 413.
	n := r.ContentLength
	if n < 0 || n > s.cfg.MaxUploadMB<<20 {
		n = s.cfg.MaxUploadMB << 20
	}
	if !s.reserve(n) {
		http.Error(w, "storage full", http.StatusInsufficientStorage)
		return
	}
	defer s.release(n)
	r.Body = http.MaxBytesReader(w, r.Body, s.cfg.MaxUploadMB<<20)
	mr, err := r.MultipartReader()
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	// Write into a temp dir and rename at the end, so a half-finished upload is never visible.
	tmp, err := os.MkdirTemp(s.cfg.DataDir, ".tmp-")
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
	sh.DeleteKey = newToken()
	sh.Created = time.Now()
	meta, _ := json.Marshal(sh)
	if err := os.WriteFile(filepath.Join(tmp, "meta.json"), meta, 0o600); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	token := newToken()
	if err := os.Rename(tmp, filepath.Join(s.cfg.DataDir, token)); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]any{
		"token":      token,
		"url":        baseURL(r) + "/s/" + token,
		"expires_at": sh.Created.Add(s.cfg.TTL.Duration),
		"delete_key": sh.DeleteKey,
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
	b, err := os.ReadFile(filepath.Join(s.cfg.DataDir, token, "meta.json"))
	if err != nil {
		return nil
	}
	var sh share
	if json.Unmarshal(b, &sh) != nil || time.Since(sh.Created) > s.cfg.TTL.Duration {
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
	writeJSON(w, map[string]any{"files": files, "expires_at": sh.Created.Add(s.cfg.TTL.Duration)})
}

// deleteShare lets the sender destroy a share early with the delete_key returned at creation.
func (s *server) deleteShare(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	sh := s.load(token)
	if sh == nil {
		http.NotFound(w, r)
		return
	}
	key := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	if sh.DeleteKey == "" || subtle.ConstantTimeCompare([]byte(key), []byte(sh.DeleteKey)) != 1 {
		http.Error(w, "invalid delete key", http.StatusForbidden)
		return
	}
	if err := os.RemoveAll(filepath.Join(s.cfg.DataDir, token)); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusNoContent)
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

func (s *server) serveFile(w http.ResponseWriter, r *http.Request) {
	token := r.PathValue("token")
	sh := s.load(token)
	i, err := strconv.Atoi(r.PathValue("index"))
	if sh == nil || err != nil || i < 0 || i >= len(sh.Files) {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(filepath.Join(s.cfg.DataDir, token, strconv.Itoa(i)))
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
	left := time.Until(sh.Created.Add(s.cfg.TTL.Duration))
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
	entries, err := os.ReadDir(s.cfg.DataDir)
	if err != nil {
		log.Print("sweep: ", err)
		return
	}
	for _, e := range entries {
		info, err := e.Info()
		if err != nil || time.Since(info.ModTime()) <= s.cfg.TTL.Duration {
			continue
		}
		if err := os.RemoveAll(filepath.Join(s.cfg.DataDir, e.Name())); err != nil {
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
