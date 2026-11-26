package main

import (
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/redis/go-redis/v9"
)

const (
	accessTTL  = 2 * time.Hour
	refreshTTL = 30 * 24 * time.Hour
	pbkdf2Iter = 600_000
)

var usernameRe = regexp.MustCompile(`^[A-Za-z0-9_]{3,20}$`)

type credentials struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

type refreshBody struct {
	RefreshToken string `json:"refresh_token"`
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4<<10)).Decode(v); err != nil {
		http.Error(w, "invalid JSON body", http.StatusBadRequest)
		return false
	}
	return true
}

// Error texts in this file are Chinese because the app shows them to the user as-is.

func (s *server) register(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	key := "rl:reg:" + clientIP(r)
	if over, err := s.limited(ctx, key, 5); err != nil {
		serverError(w, err)
		return
	} else if over {
		http.Error(w, "注册太频繁，请稍后再试", http.StatusTooManyRequests)
		return
	}
	var c credentials
	if !readJSON(w, r, &c) {
		return
	}
	if !usernameRe.MatchString(c.Username) {
		http.Error(w, "用户名需为 3–20 位字母、数字或下划线", http.StatusBadRequest)
		return
	}
	if len(c.Password) < 8 {
		http.Error(w, "密码至少 8 位", http.StatusBadRequest)
		return
	}
	hash, err := hashPassword(c.Password)
	if err != nil {
		serverError(w, err)
		return
	}
	res, err := s.db.ExecContext(ctx,
		`INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?) ON CONFLICT(username) DO NOTHING`,
		c.Username, hash, time.Now().Unix())
	if err != nil {
		serverError(w, err)
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		http.Error(w, "用户名已存在", http.StatusConflict)
		return
	}
	uid, _ := res.LastInsertId()
	if err := s.count(ctx, key, 1, time.Hour); err != nil {
		serverError(w, err)
		return
	}
	s.issueTokens(w, r, uid, c.Username)
}

func (s *server) login(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	key := "rl:login:" + clientIP(r)
	if over, err := s.limited(ctx, key, 10); err != nil {
		serverError(w, err)
		return
	} else if over {
		http.Error(w, "尝试太频繁，请稍后再试", http.StatusTooManyRequests)
		return
	}
	var c credentials
	if !readJSON(w, r, &c) {
		return
	}
	var uid int64
	var hash, name string
	err := s.db.QueryRowContext(ctx, `SELECT id, password_hash, username FROM users WHERE username = ?`, c.Username).
		Scan(&uid, &hash, &name)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		serverError(w, err)
		return
	}
	if err != nil || !checkPassword(hash, c.Password) {
		if err := s.count(ctx, key, 1, 15*time.Minute); err != nil {
			serverError(w, err)
			return
		}
		http.Error(w, "用户名或密码错误", http.StatusUnauthorized)
		return
	}
	s.issueTokens(w, r, uid, name)
}

// refresh trades a refresh token for a new pair. GETDEL makes the old one single-use even under concurrent calls.
func (s *server) refresh(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	var body refreshBody
	if !readJSON(w, r, &body) {
		return
	}
	uid, err := s.rdb.GetDel(ctx, "refresh:"+body.RefreshToken).Int64()
	if errors.Is(err, redis.Nil) {
		http.Error(w, "登录已失效，请重新登录", http.StatusUnauthorized)
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	var name string
	if err := s.db.QueryRowContext(ctx, `SELECT username FROM users WHERE id = ?`, uid).Scan(&name); err != nil {
		serverError(w, err)
		return
	}
	s.issueTokens(w, r, uid, name)
}

func (s *server) logout(w http.ResponseWriter, r *http.Request) {
	var body refreshBody
	if !readJSON(w, r, &body) {
		return
	}
	if err := s.rdb.Del(r.Context(), "refresh:"+body.RefreshToken).Err(); err != nil {
		serverError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// issueTokens answers with a 2h JWT access token and a 30-day opaque refresh token kept in Redis.
func (s *server) issueTokens(w http.ResponseWriter, r *http.Request, uid int64, username string) {
	access, err := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.RegisteredClaims{
		Subject:   strconv.FormatInt(uid, 10),
		ExpiresAt: jwt.NewNumericDate(time.Now().Add(accessTTL)),
	}).SignedString([]byte(s.cfg.JWTSecret))
	if err != nil {
		serverError(w, err)
		return
	}
	refresh := newToken()
	if err := s.rdb.Set(r.Context(), "refresh:"+refresh, uid, refreshTTL).Err(); err != nil {
		serverError(w, err)
		return
	}
	writeJSON(w, map[string]any{"access_token": access, "refresh_token": refresh, "username": username})
}

// requireUser lets a request through only with a valid access token, handing the user id to h.
func (s *server) requireUser(h func(http.ResponseWriter, *http.Request, int64)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		raw, _ := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		var claims jwt.RegisteredClaims
		_, err := jwt.ParseWithClaims(raw, &claims,
			func(*jwt.Token) (any, error) { return []byte(s.cfg.JWTSecret), nil },
			jwt.WithValidMethods([]string{"HS256"}), jwt.WithExpirationRequired())
		uid, perr := strconv.ParseInt(claims.Subject, 10, 64)
		if err != nil || perr != nil {
			http.Error(w, "未登录或登录已过期", http.StatusUnauthorized)
			return
		}
		h(w, r, uid)
	}
}

// hashPassword returns "pbkdf2$<iter>$<salt>$<hash>" (PBKDF2-SHA256, 16-byte salt, base64).
func hashPassword(pw string) (string, error) {
	salt := make([]byte, 16)
	rand.Read(salt)
	key, err := pbkdf2.Key(sha256.New, pw, salt, pbkdf2Iter, 32)
	if err != nil {
		return "", err
	}
	b64 := base64.RawStdEncoding.EncodeToString
	return fmt.Sprintf("pbkdf2$%d$%s$%s", pbkdf2Iter, b64(salt), b64(key)), nil
}

func checkPassword(stored, pw string) bool {
	parts := strings.Split(stored, "$")
	if len(parts) != 4 || parts[0] != "pbkdf2" {
		return false
	}
	iter, err1 := strconv.Atoi(parts[1])
	salt, err2 := base64.RawStdEncoding.DecodeString(parts[2])
	want, err3 := base64.RawStdEncoding.DecodeString(parts[3])
	if err1 != nil || err2 != nil || err3 != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, pw, salt, iter, len(want))
	return err == nil && subtle.ConstantTimeCompare(got, want) == 1
}
