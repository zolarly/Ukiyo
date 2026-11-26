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
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/golang-jwt/jwt/v5"
)

const testSecret = "0123456789abcdef0123456789abcdef"

func newTestServer(t *testing.T) (*server, *httptest.Server) {
	t.Helper()
	mr := miniredis.RunT(t)
	s, err := newServer(config{
		DataDir:         t.TempDir(),
		TTL:             duration{24 * time.Hour},
		MaxUploadMB:     1,
		MaxTotalMB:      20000,
		DailyUploadMB:   2048,
		MaxViewsOptions: []int{0, 1, 3, 5},
		RedisAddr:       mr.Addr(),
		JWTSecret:       testSecret,
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.db.Close(); s.rdb.Close() })
	ts := httptest.NewServer(s.routes())
	t.Cleanup(ts.Close)
	return s, ts
}

// do sends a request; header is name/value pairs. "Bearer" as a name sets the Authorization header.
func do(t *testing.T, method, url string, body io.Reader, header ...string) (*http.Response, []byte) {
	t.Helper()
	req, _ := http.NewRequest(method, url, body)
	for i := 0; i+1 < len(header); i += 2 {
		if header[i] == "Bearer" {
			req.Header.Set("Authorization", "Bearer "+header[i+1])
		} else {
			req.Header.Set(header[i], header[i+1])
		}
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	return resp, b
}

func get(t *testing.T, url string, header ...string) (*http.Response, []byte) {
	t.Helper()
	return do(t, "GET", url, nil, header...)
}

func postJSON(t *testing.T, url string, v any, header ...string) (*http.Response, []byte) {
	t.Helper()
	b, _ := json.Marshal(v)
	return do(t, "POST", url, bytes.NewReader(b), append(header, "Content-Type", "application/json")...)
}

type tokens struct {
	Access   string `json:"access_token"`
	Refresh  string `json:"refresh_token"`
	Username string
}

func auth(t *testing.T, base, action, user, password string) (int, tokens) {
	t.Helper()
	resp, body := postJSON(t, base+"/api/auth/"+action, map[string]string{"username": user, "password": password})
	var tok tokens
	json.Unmarshal(body, &tok)
	return resp.StatusCode, tok
}

func register(t *testing.T, base, user string) string {
	t.Helper()
	code, tok := auth(t, base, "register", user, "password1")
	if code != 200 || tok.Access == "" {
		t.Fatalf("register %s: %d", user, code)
	}
	return tok.Access
}

func upload(t *testing.T, base, access, maxViews string, files map[string][]byte) (*http.Response, []byte) {
	t.Helper()
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	if maxViews != "" {
		mw.WriteField("max_views", maxViews)
	}
	for name, data := range files {
		fw, _ := mw.CreateFormFile("file", name)
		fw.Write(data)
	}
	mw.Close()
	return do(t, "POST", base+"/api/shares", &body, "Content-Type", mw.FormDataContentType(), "Bearer", access)
}

func create(t *testing.T, base, access, maxViews string, files map[string][]byte) (token, url string) {
	t.Helper()
	resp, body := upload(t, base, access, maxViews, files)
	var created struct{ Token, URL string }
	json.Unmarshal(body, &created)
	if resp.StatusCode != 200 || !tokenRe.MatchString(created.Token) || created.URL != base+"/s/"+created.Token {
		t.Fatalf("create: %d %s", resp.StatusCode, body)
	}
	return created.Token, created.URL
}

type openedFile struct {
	Name string
	Size int64
	URL  string
}

func open(t *testing.T, base, token string) (int, []openedFile) {
	t.Helper()
	resp, body := do(t, "POST", base+"/api/shares/"+token+"/open", nil)
	var opened struct{ Files []openedFile }
	json.Unmarshal(body, &opened)
	return resp.StatusCode, opened.Files
}

type shareInfo struct {
	Files     []fileMeta
	MaxViews  int  `json:"max_views"`
	ViewsLeft *int `json:"views_left"`
}

func TestLoadConfig(t *testing.T) {
	dir := t.TempDir()
	write := func(content string) string {
		p := filepath.Join(dir, "config.json")
		os.WriteFile(p, []byte(content), 0o600)
		return p
	}

	cfg, err := loadConfig(write(`{"jwt_secret": "` + testSecret + `"}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Addr != ":8080" || cfg.DataDir != "data" || cfg.TTL.Duration != 24*time.Hour ||
		cfg.MaxUploadMB != 300 || cfg.MaxTotalMB != 20000 || cfg.DailyUploadMB != 2048 ||
		!slices.Equal(cfg.MaxViewsOptions, []int{0, 1, 3, 5}) || cfg.RedisAddr != "127.0.0.1:6379" {
		t.Fatalf("defaults: %+v", cfg)
	}

	cfg, err = loadConfig(write(`{"jwt_secret": "` + testSecret + `", "ttl": "90m", "max_total_mb": 5}`))
	if err != nil || cfg.TTL.Duration != 90*time.Minute || cfg.MaxTotalMB != 5 {
		t.Fatalf("overrides: %+v %v", cfg, err)
	}

	for _, bad := range []string{
		`{}`,                      // 没有 jwt_secret
		`{"jwt_secret": "short"}`, // 太短
		`{"jwt_secret": "` + testSecret + `", "ttl": "1 day"}`,        // ttl 格式错误
		`{"jwt_secret": "` + testSecret + `", "invite_codes": ["a"]}`, // 已删除的字段
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

func TestAuth(t *testing.T) {
	_, ts := newTestServer(t)
	me := ts.URL + "/api/me/shares"

	code, tok := auth(t, ts.URL, "register", "Alice", "password1")
	if code != 200 || tok.Access == "" || tok.Refresh == "" || tok.Username != "Alice" {
		t.Fatalf("register: %d %+v", code, tok)
	}
	if resp, _ := get(t, me, "Bearer", tok.Access); resp.StatusCode != 200 {
		t.Fatalf("me with fresh token: %d", resp.StatusCode)
	}
	for _, c := range []struct {
		user, password string
		want           int
	}{
		{"alice", "password1", 409}, // usernames are case-insensitive
		{"ab", "password1", 400},
		{"bad name", "password1", 400},
		{"bob", "short", 400},
	} {
		if code, _ := auth(t, ts.URL, "register", c.user, c.password); code != c.want {
			t.Errorf("register %q/%q: got %d, want %d", c.user, c.password, code, c.want)
		}
	}

	if code, _ := auth(t, ts.URL, "login", "Alice", "wrong-password"); code != 401 {
		t.Fatalf("wrong password: got %d, want 401", code)
	}
	code, tok = auth(t, ts.URL, "login", "ALICE", "password1")
	if code != 200 || tok.Username != "Alice" {
		t.Fatalf("login: %d %+v", code, tok)
	}

	refresh := func(rt string) (int, tokens) {
		resp, body := postJSON(t, ts.URL+"/api/auth/refresh", map[string]string{"refresh_token": rt})
		var tok tokens
		json.Unmarshal(body, &tok)
		return resp.StatusCode, tok
	}
	code, renewed := refresh(tok.Refresh)
	if code != 200 || renewed.Refresh == tok.Refresh || renewed.Username != "Alice" {
		t.Fatalf("refresh: %d %+v", code, renewed)
	}
	if code, _ := refresh(tok.Refresh); code != 401 {
		t.Fatalf("reused refresh token: got %d, want 401", code)
	}
	if resp, _ := postJSON(t, ts.URL+"/api/auth/logout", map[string]string{"refresh_token": renewed.Refresh}); resp.StatusCode != 204 {
		t.Fatalf("logout: %d", resp.StatusCode)
	}
	if code, _ := refresh(renewed.Refresh); code != 401 {
		t.Fatalf("refresh after logout: got %d, want 401", code)
	}

	sign := func(secret string, exp time.Time) string {
		s, _ := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.RegisteredClaims{
			Subject: "1", ExpiresAt: jwt.NewNumericDate(exp),
		}).SignedString([]byte(secret))
		return s
	}
	for name, access := range map[string]string{
		"none":         "",
		"garbage":      "garbage",
		"expired":      sign(testSecret, time.Now().Add(-time.Minute)),
		"wrong secret": sign(strings.Repeat("x", 32), time.Now().Add(time.Hour)),
	} {
		if resp, _ := get(t, me, "Bearer", access); resp.StatusCode != 401 {
			t.Errorf("%s access token: got %d, want 401", name, resp.StatusCode)
		}
	}
}

func TestRateLimits(t *testing.T) {
	_, ts := newTestServer(t)
	for i := range 5 {
		register(t, ts.URL, "user"+strconv.Itoa(i))
	}
	if code, _ := auth(t, ts.URL, "register", "user5", "password1"); code != 429 {
		t.Fatalf("6th registration in an hour: got %d, want 429", code)
	}
	for range 10 {
		if code, _ := auth(t, ts.URL, "login", "nobody", "password1"); code != 401 {
			t.Fatalf("unknown user: got %d, want 401", code)
		}
	}
	if code, _ := auth(t, ts.URL, "login", "user0", "password1"); code != 429 {
		t.Fatalf("login after 10 failures: got %d, want 429", code)
	}
	// Another client behind the proxy is not affected.
	body := map[string]string{"username": "user0", "password": "password1"}
	if resp, _ := postJSON(t, ts.URL+"/api/auth/login", body, "X-Forwarded-For", "203.0.113.9"); resp.StatusCode != 200 {
		t.Fatalf("login from another IP: got %d, want 200", resp.StatusCode)
	}
}

func TestShareLifecycle(t *testing.T) {
	s, ts := newTestServer(t)
	access := register(t, ts.URL, "alice")
	photo := []byte("\xff\xd8\xff original bytes with embedded motion video")

	if resp, _ := upload(t, ts.URL, "garbage", "", map[string][]byte{"a.jpg": photo}); resp.StatusCode != 401 {
		t.Fatalf("not logged in: got %d, want 401", resp.StatusCode)
	}
	if resp, _ := upload(t, ts.URL, access, "", map[string][]byte{"big.jpg": make([]byte, 2<<20)}); resp.StatusCode != 413 {
		t.Fatalf("oversize upload: got %d, want 413", resp.StatusCode)
	}
	if resp, _ := upload(t, ts.URL, access, "2", map[string][]byte{"a.jpg": photo}); resp.StatusCode != 400 {
		t.Fatalf("max_views not an option: got %d, want 400", resp.StatusCode)
	}
	token, url := create(t, ts.URL, access, "", map[string][]byte{"浮世.jpg": photo})
	api := ts.URL + "/api/shares/" + token

	// Looking is free and gives no way to download.
	resp, body := get(t, api)
	var info shareInfo
	json.Unmarshal(body, &info)
	if resp.StatusCode != 200 || len(info.Files) != 1 || info.Files[0].Name != "浮世.jpg" || info.MaxViews != 0 ||
		info.ViewsLeft != nil || strings.Contains(string(body), "/s/") {
		t.Fatalf("share JSON: %d %s", resp.StatusCode, body)
	}
	for _, u := range []string{url + "/0", url + "/0?ticket=" + newToken()} {
		if resp, _ := get(t, u); resp.StatusCode != 403 {
			t.Fatalf("%s without a valid ticket: got %d, want 403", u, resp.StatusCode)
		}
	}

	code, files := open(t, ts.URL, token)
	if code != 200 || len(files) != 1 || !strings.Contains(files[0].URL, "?ticket=") {
		t.Fatalf("open: %d %+v", code, files)
	}
	resp, got := get(t, files[0].URL)
	if !bytes.Equal(got, photo) {
		t.Fatalf("download changed bytes: %q", got)
	}
	if cd := resp.Header.Get("Content-Disposition"); !strings.HasPrefix(cd, "attachment") || resp.Header.Get("Content-Security-Policy") != "sandbox" {
		t.Fatalf("download headers: %v", resp.Header)
	}
	if resp, got := get(t, files[0].URL, "Range", "bytes=3-"); resp.StatusCode != 206 || !bytes.Equal(got, photo[3:]) {
		t.Fatalf("range: %d %q", resp.StatusCode, got)
	}
	for _, bad := range []string{"/s/../ukiyo.db", "/s/" + token + "/1", "/s/" + token + "/-1", "/s/short/0"} {
		if resp, _ := get(t, ts.URL+bad); resp.StatusCode != 404 {
			t.Fatalf("%s: got %d, want 404", bad, resp.StatusCode)
		}
	}

	// Web page: the plain page only lists names; "查看" spends a view and lands on a ticketed page.
	if resp, page := get(t, url); resp.StatusCode != 200 || !strings.Contains(string(page), "浮世.jpg") ||
		!strings.Contains(string(page), `action="/s/`+token+`/open"`) || strings.Contains(string(page), "ticket=") {
		t.Fatalf("share page: %d %s", resp.StatusCode, page)
	}
	resp, page := do(t, "POST", url+"/open", nil)
	if resp.StatusCode != 200 || !resp.Request.URL.Query().Has("ticket") || !strings.Contains(string(page), "下载原图") {
		t.Fatalf("opened page: %d %s %s", resp.StatusCode, resp.Request.URL, page)
	}

	// Expired: unreachable immediately, deleted from disk by the sweep.
	s.db.Exec(`UPDATE shares SET expires_at = 0`)
	if resp, _ := get(t, files[0].URL); resp.StatusCode != 404 {
		t.Fatalf("expired download: got %d, want 404", resp.StatusCode)
	}
	if resp, _ := get(t, api); resp.StatusCode != 404 {
		t.Fatalf("expired share JSON: got %d, want 404", resp.StatusCode)
	}
	s.sweep()
	var rows int
	s.db.QueryRow(`SELECT COUNT(*) FROM shares`).Scan(&rows)
	entries, _ := os.ReadDir(s.cfg.DataDir)
	for _, e := range entries {
		if e.IsDir() {
			t.Errorf("sweep left dir %s behind (temp dir of a failed upload, or the expired share)", e.Name())
		}
	}
	if rows != 0 {
		t.Fatalf("sweep left %d rows", rows)
	}
}

func TestMaxViews(t *testing.T) {
	s, ts := newTestServer(t)
	access := register(t, ts.URL, "alice")
	photo := []byte("one look only")
	token, url := create(t, ts.URL, access, "1", map[string][]byte{"a.jpg": photo})
	api := ts.URL + "/api/shares/" + token

	_, body := get(t, api)
	var info shareInfo
	json.Unmarshal(body, &info)
	if info.MaxViews != 1 || info.ViewsLeft == nil || *info.ViewsLeft != 1 {
		t.Fatalf("before open: %s", body)
	}
	code, files := open(t, ts.URL, token)
	if code != 200 {
		t.Fatalf("first open: %d", code)
	}
	if resp, _ := get(t, api); resp.StatusCode != 410 {
		t.Fatalf("share JSON after last view: got %d, want 410", resp.StatusCode)
	}
	if code, _ := open(t, ts.URL, token); code != 410 {
		t.Fatalf("second open: got %d, want 410", code)
	}
	if resp, page := get(t, url); resp.StatusCode != 410 || !strings.Contains(string(page), "查看次数已用完") {
		t.Fatalf("share page after last view: %d %s", resp.StatusCode, page)
	}
	// The viewer who spent the last view can still finish (and resume) the download.
	if _, got := get(t, files[0].URL); !bytes.Equal(got, photo) {
		t.Fatalf("download after last view: %q", got)
	}
	var expires int64
	s.db.QueryRow(`SELECT expires_at FROM shares WHERE token = ?`, token).Scan(&expires)
	if expires > time.Now().Add(ticketTTL).Unix() {
		t.Fatalf("share outlives the last ticket: expires %v", time.Unix(expires, 0))
	}
}

func TestMySharesAndDelete(t *testing.T) {
	s, ts := newTestServer(t)
	alice, bob := register(t, ts.URL, "alice"), register(t, ts.URL, "bob")
	first, _ := create(t, ts.URL, alice, "3", map[string][]byte{"a.jpg": []byte("a")})
	second, _ := create(t, ts.URL, alice, "", map[string][]byte{"b.jpg": []byte("b"), "c.mp4": []byte("c")})
	create(t, ts.URL, bob, "", map[string][]byte{"d.jpg": []byte("d")})
	open(t, ts.URL, first)

	_, body := get(t, ts.URL+"/api/me/shares", "Bearer", alice)
	var mine []struct {
		Token     string
		Count     int
		MaxViews  int `json:"max_views"`
		ViewsUsed int `json:"views_used"`
	}
	json.Unmarshal(body, &mine)
	if len(mine) != 2 || mine[0].Token != second || mine[0].Count != 2 ||
		mine[1].Token != first || mine[1].MaxViews != 3 || mine[1].ViewsUsed != 1 {
		t.Fatalf("my shares, newest first: %s", body)
	}

	api := ts.URL + "/api/shares/" + first
	if resp, _ := do(t, "DELETE", api, nil); resp.StatusCode != 401 {
		t.Fatalf("delete without login: got %d, want 401", resp.StatusCode)
	}
	if resp, _ := do(t, "DELETE", api, nil, "Bearer", bob); resp.StatusCode != 403 {
		t.Fatalf("delete someone else's: got %d, want 403", resp.StatusCode)
	}
	if resp, _ := do(t, "DELETE", api, nil, "Bearer", alice); resp.StatusCode != 204 {
		t.Fatalf("delete: got %d, want 204", resp.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(s.cfg.DataDir, first)); !os.IsNotExist(err) {
		t.Fatal("share dir still on disk after delete")
	}
	if resp, _ := get(t, api); resp.StatusCode != 404 {
		t.Fatalf("share JSON after delete: got %d, want 404", resp.StatusCode)
	}
	if resp, _ := do(t, "DELETE", api, nil, "Bearer", alice); resp.StatusCode != 404 {
		t.Fatalf("delete twice: got %d, want 404", resp.StatusCode)
	}
}

func TestStorageQuota(t *testing.T) {
	s, ts := newTestServer(t)
	access := register(t, ts.URL, "alice")
	s.cfg.MaxTotalMB = 1
	create(t, ts.URL, access, "", map[string][]byte{"a.jpg": make([]byte, 600<<10)})
	if resp, _ := upload(t, ts.URL, access, "", map[string][]byte{"b.jpg": make([]byte, 600<<10)}); resp.StatusCode != 507 {
		t.Fatalf("over quota: got %d, want 507", resp.StatusCode)
	}
	if s.reserved != 0 {
		t.Fatalf("reservation leaked: %d bytes", s.reserved)
	}
}

func TestDailyQuota(t *testing.T) {
	s, ts := newTestServer(t)
	alice, bob := register(t, ts.URL, "alice"), register(t, ts.URL, "bob")
	s.cfg.DailyUploadMB = 1
	// Under the quota when each upload starts, so both pass even though together they overshoot.
	create(t, ts.URL, alice, "", map[string][]byte{"a.jpg": make([]byte, 600<<10)})
	create(t, ts.URL, alice, "", map[string][]byte{"b.jpg": make([]byte, 600<<10)})
	if resp, _ := upload(t, ts.URL, alice, "", map[string][]byte{"c.jpg": []byte("c")}); resp.StatusCode != 429 {
		t.Fatalf("over daily quota: got %d, want 429", resp.StatusCode)
	}
	create(t, ts.URL, bob, "", map[string][]byte{"d.jpg": []byte("d")}) // per account
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
