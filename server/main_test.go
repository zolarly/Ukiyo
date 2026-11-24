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

func TestShareLifecycle(t *testing.T) {
	s := &server{config{t.TempDir(), 24 * time.Hour, 1 << 20, []string{"friend"}}}
	ts := httptest.NewServer(s.routes())
	defer ts.Close()
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
	dir := filepath.Join(s.cfg.dataDir, created.Token)
	old := time.Now().Add(-25 * time.Hour)
	os.Chtimes(dir, old, old)
	s.cfg.ttl = time.Nanosecond
	if resp, _ := get(t, created.URL+"/0"); resp.StatusCode != 404 {
		t.Fatalf("expired download: got %d, want 404", resp.StatusCode)
	}
	s.cfg.ttl = 24 * time.Hour
	s.sweep()
	if _, err := os.Stat(dir); !os.IsNotExist(err) {
		t.Fatalf("sweep left %s behind", dir)
	}
	if entries, _ := os.ReadDir(s.cfg.dataDir); len(entries) != 0 {
		t.Fatalf("leftover temp dirs from failed uploads: %v", entries)
	}
}
