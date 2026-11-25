package main

import (
	"bytes"
	"encoding/json"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"
)

func upload(t *testing.T, url, code string, files map[string][]byte) *http.Response {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	for name, data := range files {
		fw, _ := mw.CreateFormFile("file", name)
		fw.Write(data)
	}
	mw.Close()
	req, _ := http.NewRequest("POST", url+"/api/shares", &body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+code)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func get(t *testing.T, url string, header ...string) (*http.Response, []byte) {
	t.Helper()
	req, _ := http.NewRequest("GET", url, nil)
	for i := 0; i+1 < len(header); i += 2 {
		req.Header.Set(header[i], header[i+1])
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	return resp, b
}

func newTestServer(t *testing.T) (*server, *httptest.Server) {
	t.Helper()
	s := &server{cfg: config{
		DataDir:     t.TempDir(),
		TTL:         duration{24 * time.Hour},
		MaxUploadMB: 1,
		MaxTotalMB:  20000,
		InviteCodes: []string{"friend"},
	}}
	ts := httptest.NewServer(s.routes())
	t.Cleanup(ts.Close)
	return s, ts
}

func TestLoadConfig(t *testing.T) {
	dir := t.TempDir()
	write := func(content string) string {
		p := filepath.Join(dir, "config.json")
		os.WriteFile(p, []byte(content), 0o600)
		return p
	}

	cfg, err := loadConfig(write(`{"invite_codes": [" friend ", ""]}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Addr != ":8080" || cfg.DataDir != "data" || cfg.TTL.Duration != 24*time.Hour ||
		cfg.MaxUploadMB != 300 || cfg.MaxTotalMB != 20000 || !slices.Equal(cfg.InviteCodes, []string{"friend"}) {
		t.Fatalf("defaults: %+v", cfg)
	}

	cfg, err = loadConfig(write(`{"invite_codes": ["a"], "ttl": "90m", "max_total_mb": 5}`))
	if err != nil || cfg.TTL.Duration != 90*time.Minute || cfg.MaxTotalMB != 5 {
		t.Fatalf("overrides: %+v %v", cfg, err)
	}

	for _, bad := range []string{
		`{}`,                      // 没有邀请码
		`{"invite_codes": [" "]}`, // 邀请码全是空白
		`{"invite_codes": ["a"], "ttl": "1 day"}`, // ttl 格式错误
		`{"invite_codes": ["a"], "tll": "1h"}`,    // 拼错的字段名
		`not json`,
	} {
		if _, err := loadConfig(write(bad)); err == nil {
			t.Errorf("%s: want error", bad)
		}
	}
	if _, err := loadConfig(filepath.Join(dir, "missing.json")); err == nil {
		t.Error("missing file: want error")
	}
}

func TestShareLifecycle(t *testing.T) {
	s, ts := newTestServer(t)
	photo := []byte("\xff\xd8\xff original bytes with embedded motion video")

	if resp := upload(t, ts.URL, "wrong", map[string][]byte{"a.jpg": photo}); resp.StatusCode != 401 {
		t.Fatalf("bad invite code: got %d, want 401", resp.StatusCode)
	}
	if resp := upload(t, ts.URL, "friend", map[string][]byte{"big.jpg": make([]byte, 2<<20)}); resp.StatusCode != 413 {
		t.Fatalf("oversize upload: got %d, want 413", resp.StatusCode)
	}

	resp := upload(t, ts.URL, "friend", map[string][]byte{"浮世.jpg": photo})
	var created struct{ Token, URL string }
	json.NewDecoder(resp.Body).Decode(&created)
	if resp.StatusCode != 200 || !tokenRe.MatchString(created.Token) || created.URL != ts.URL+"/s/"+created.Token {
		t.Fatalf("create: %d %+v", resp.StatusCode, created)
	}

	if resp, page := get(t, created.URL); resp.StatusCode != 200 || !strings.Contains(string(page), "浮世.jpg") {
		t.Fatalf("share page: %d %s", resp.StatusCode, page)
	}
	resp, got := get(t, created.URL+"/0")
	if !bytes.Equal(got, photo) {
		t.Fatalf("download changed bytes: %q", got)
	}
	if cd := resp.Header.Get("Content-Disposition"); !strings.HasPrefix(cd, "attachment") || resp.Header.Get("Content-Security-Policy") != "sandbox" {
		t.Fatalf("download headers: %v", resp.Header)
	}
	if resp, got := get(t, created.URL+"/0", "Range", "bytes=3-"); resp.StatusCode != 206 || !bytes.Equal(got, photo[3:]) {
		t.Fatalf("range: %d %q", resp.StatusCode, got)
	}
	for _, bad := range []string{"/s/../meta.json", "/s/" + created.Token + "/1", "/s/" + created.Token + "/-1", "/s/short/0"} {
		if resp, _ := get(t, ts.URL+bad); resp.StatusCode != 404 {
			t.Fatalf("%s: got %d, want 404", bad, resp.StatusCode)
		}
	}

	// Expired: unreachable immediately, deleted from disk by the sweep.
	dir := filepath.Join(s.cfg.DataDir, created.Token)
	old := time.Now().Add(-25 * time.Hour)
	os.Chtimes(dir, old, old)
	s.cfg.TTL.Duration = time.Nanosecond
	if resp, _ := get(t, created.URL+"/0"); resp.StatusCode != 404 {
		t.Fatalf("expired download: got %d, want 404", resp.StatusCode)
	}
	s.cfg.TTL.Duration = 24 * time.Hour
	s.sweep()
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("sweep left %s behind", dir)
	}
	if entries, _ := os.ReadDir(s.cfg.DataDir); len(entries) != 0 {
		t.Fatalf("leftover temp dirs from failed uploads: %v", entries)
	}
}

func del(t *testing.T, url, key string) *http.Response {
	t.Helper()
	req, _ := http.NewRequest("DELETE", url, nil)
	req.Header.Set("Authorization", "Bearer "+key)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp
}

func TestDeleteShare(t *testing.T) {
	s, ts := newTestServer(t)
	resp := upload(t, ts.URL, "friend", map[string][]byte{"a.jpg": []byte("photo")})
	var created struct {
		Token, URL string
		DeleteKey  string `json:"delete_key"`
	}
	json.NewDecoder(resp.Body).Decode(&created)
	if !tokenRe.MatchString(created.DeleteKey) || created.DeleteKey == created.Token {
		t.Fatalf("delete_key: %+v", created)
	}
	api := ts.URL + "/api/shares/" + created.Token
	if _, body := get(t, api); strings.Contains(string(body), created.DeleteKey) {
		t.Fatalf("share JSON leaks delete_key: %s", body)
	}

	if resp := del(t, api, "wrong"); resp.StatusCode != 403 {
		t.Fatalf("wrong key: got %d, want 403", resp.StatusCode)
	}
	if resp := del(t, api, created.DeleteKey); resp.StatusCode != 204 {
		t.Fatalf("delete: got %d, want 204", resp.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(s.cfg.DataDir, created.Token)); !os.IsNotExist(err) {
		t.Fatal("share dir still on disk after delete")
	}
	if resp, _ := get(t, created.URL+"/0"); resp.StatusCode != 404 {
		t.Fatalf("download after delete: got %d, want 404", resp.StatusCode)
	}
	if resp := del(t, api, created.DeleteKey); resp.StatusCode != 404 {
		t.Fatalf("delete twice: got %d, want 404", resp.StatusCode)
	}
}

func TestStorageQuota(t *testing.T) {
	s, ts := newTestServer(t)
	s.cfg.MaxTotalMB = 1
	if resp := upload(t, ts.URL, "friend", map[string][]byte{"a.jpg": make([]byte, 600<<10)}); resp.StatusCode != 200 {
		t.Fatalf("first upload: got %d, want 200", resp.StatusCode)
	}
	if resp := upload(t, ts.URL, "friend", map[string][]byte{"b.jpg": make([]byte, 600<<10)}); resp.StatusCode != 507 {
		t.Fatalf("over quota: got %d, want 507", resp.StatusCode)
	}
	if s.reserved != 0 {
		t.Fatalf("reservation leaked: %d bytes", s.reserved)
	}
}

func TestAssetLinks(t *testing.T) {
	s, ts := newTestServer(t)
	url := ts.URL + "/.well-known/assetlinks.json"
	if resp, _ := get(t, url); resp.StatusCode != 404 {
		t.Fatalf("unconfigured: got %d, want 404", resp.StatusCode)
	}
	s.cfg.AndroidPackage = "io.github.zolarly.ukiyo"
	s.cfg.AndroidCertSHA256 = "AA:BB"
	resp, body := get(t, url)
	var links []struct {
		Relation []string
		Target   struct {
			Namespace   string
			PackageName string   `json:"package_name"`
			Fingerprint []string `json:"sha256_cert_fingerprints"`
		}
	}
	if err := json.Unmarshal(body, &links); err != nil || resp.StatusCode != 200 || len(links) != 1 ||
		links[0].Target.Namespace != "android_app" || links[0].Target.PackageName != "io.github.zolarly.ukiyo" ||
		!slices.Equal(links[0].Target.Fingerprint, []string{"AA:BB"}) ||
		!slices.Equal(links[0].Relation, []string{"delegate_permission/common.handle_all_urls"}) {
		t.Fatalf("assetlinks: %d %s", resp.StatusCode, body)
	}
}
