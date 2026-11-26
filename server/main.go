// Ukiyo server: account-gated uploads, link-based downloads, everything gone after a TTL.
//
// Accounts and share metadata live in SQLite (<data_dir>/ukiyo.db). View counts, download tickets,
// refresh tokens and rate-limit counters live in Redis. The raw files of each share sit untouched
// in <data_dir>/<token>/0, 1, ...
package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"regexp"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
)

type config struct {
	Addr              string   `json:"addr"`
	DataDir           string   `json:"data_dir"`
	TTL               duration `json:"ttl"`
	MaxUploadMB       int64    `json:"max_upload_mb"`
	MaxTotalMB        int64    `json:"max_total_mb"`
	DailyUploadMB     int64    `json:"daily_upload_mb"`
	MaxViewsOptions   []int    `json:"max_views_options"`
	RedisAddr         string   `json:"redis_addr"`
	JWTSecret         string   `json:"jwt_secret"`
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

// loadConfig reads the JSON config at path. Every field except jwt_secret has a default;
// unknown fields are rejected so a typo doesn't silently fall back to a default.
func loadConfig(path string) (config, error) {
	cfg := config{
		Addr: ":8080", DataDir: "data", TTL: duration{24 * time.Hour},
		MaxUploadMB: 300, MaxTotalMB: 20000, DailyUploadMB: 2048,
		MaxViewsOptions: []int{0, 1, 3, 5}, RedisAddr: "127.0.0.1:6379",
	}
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
	if len(cfg.JWTSecret) < 32 {
		return cfg, errors.New("jwt_secret is required and must be at least 32 characters")
	}
	return cfg, nil
}

type server struct {
	cfg config
	db  *sql.DB
	rdb *redis.Client

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
	s, err := newServer(cfg)
	if err != nil {
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
		w.Write([]byte(homeHTML))
	})
	mux.HandleFunc("POST /api/auth/register", s.register)
	mux.HandleFunc("POST /api/auth/login", s.login)
	mux.HandleFunc("POST /api/auth/refresh", s.refresh)
	mux.HandleFunc("POST /api/auth/logout", s.logout)
	mux.HandleFunc("POST /api/shares", s.requireUser(s.createShare))
	mux.HandleFunc("GET /api/shares/{token}", s.shareJSON)
	mux.HandleFunc("POST /api/shares/{token}/open", s.openJSON)
	mux.HandleFunc("DELETE /api/shares/{token}", s.requireUser(s.deleteShare))
	mux.HandleFunc("GET /api/me/shares", s.requireUser(s.myShares))
	mux.HandleFunc("GET /s/{token}", s.sharePage)
	mux.HandleFunc("POST /s/{token}/open", s.openPage)
	mux.HandleFunc("GET /s/{token}/{index}", s.serveFile)
	mux.HandleFunc("GET /.well-known/assetlinks.json", s.assetLinks)
	return mux
}

func newToken() string {
	b := make([]byte, 16)
	rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

// baseURL trusts the Host and X-Forwarded-Proto headers set by the reverse proxy.
func baseURL(r *http.Request) string {
	scheme := "http"
	if r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https" {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func serverError(w http.ResponseWriter, err error) {
	log.Print(err)
	http.Error(w, "server error", http.StatusInternalServerError)
}
