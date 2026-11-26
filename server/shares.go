package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

// ticketTTL is how long one "open" may keep downloading (and resuming) the files.
const ticketTTL = 3 * time.Hour

var errViewsUsedUp = errors.New("views used up")

type fileMeta struct {
	Name string `json:"name"`
	Type string `json:"type"`
	Size int64  `json:"size"`
}

type share struct {
	Token    string
	UserID   int64
	Created  time.Time
	Expires  time.Time
	MaxViews int // 0 = unlimited
	Files    []fileMeta
}

func (s *server) createShare(w http.ResponseWriter, r *http.Request, uid int64) {
	ctx := r.Context()
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
	// Checked before the upload, counted after: the last upload of the day may overshoot a little.
	quotaKey := fmt.Sprintf("quota:%d:%s", uid, time.Now().Format("20060102"))
	if over, err := s.limited(ctx, quotaKey, s.cfg.DailyUploadMB<<20); err != nil {
		serverError(w, err)
		return
	} else if over {
		http.Error(w, "daily upload quota used up", http.StatusTooManyRequests)
		return
	}
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

	sh := share{UserID: uid}
	var total int64
	for {
		part, err := mr.NextPart()
		if err == io.EOF {
			break
		}
		if err != nil {
			uploadError(w, err)
			return
		}
		if part.FormName() == "max_views" {
			b, _ := io.ReadAll(io.LimitReader(part, 16))
			v, err := strconv.Atoi(strings.TrimSpace(string(b)))
			if err != nil || !slices.Contains(s.cfg.MaxViewsOptions, v) {
				http.Error(w, "invalid max_views", http.StatusBadRequest)
				return
			}
			sh.MaxViews = v
			continue
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
		total += n
		sh.Files = append(sh.Files, fileMeta{Name: part.FileName(), Type: part.Header.Get("Content-Type"), Size: n})
	}
	if len(sh.Files) == 0 {
		http.Error(w, `no files in field "file"`, http.StatusBadRequest)
		return
	}
	sh.Token, sh.Created = newToken(), time.Now()
	sh.Expires = sh.Created.Add(s.cfg.TTL.Duration)
	dir := filepath.Join(s.cfg.DataDir, sh.Token)
	if err := os.Rename(tmp, dir); err != nil {
		http.Error(w, "storage error", http.StatusInternalServerError)
		return
	}
	files, _ := json.Marshal(sh.Files)
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO shares (token, user_id, created_at, expires_at, max_views, files) VALUES (?, ?, ?, ?, ?, ?)`,
		sh.Token, uid, sh.Created.Unix(), sh.Expires.Unix(), sh.MaxViews, files); err != nil {
		os.RemoveAll(dir)
		serverError(w, err)
		return
	}
	if err := s.count(ctx, quotaKey, total, 24*time.Hour); err != nil {
		log.Print("quota: ", err) // the share exists already; don't fail the upload over a counter
	}
	writeJSON(w, map[string]any{
		"token":      sh.Token,
		"url":        baseURL(r) + "/s/" + sh.Token,
		"expires_at": sh.Expires,
		"max_views":  sh.MaxViews,
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

// load returns the share for token, or nil if it is malformed, missing or expired.
func (s *server) load(ctx context.Context, token string) *share {
	if !tokenRe.MatchString(token) {
		return nil
	}
	sh := share{Token: token}
	var created, expires int64
	var files string
	err := s.db.QueryRowContext(ctx,
		`SELECT user_id, created_at, expires_at, max_views, files FROM shares WHERE token = ? AND expires_at > ?`,
		token, time.Now().Unix()).Scan(&sh.UserID, &created, &expires, &sh.MaxViews, &files)
	if err != nil {
		if !errors.Is(err, sql.ErrNoRows) {
			log.Print("load share: ", err)
		}
		return nil
	}
	if err := json.Unmarshal([]byte(files), &sh.Files); err != nil {
		log.Print("load share: ", err)
		return nil
	}
	sh.Created, sh.Expires = time.Unix(created, 0), time.Unix(expires, 0)
	return &sh
}

func (s *server) viewsUsed(ctx context.Context, token string) (int, error) {
	n, err := s.rdb.Get(ctx, "views:"+token).Int()
	if errors.Is(err, redis.Nil) {
		return 0, nil
	}
	return n, err
}

// viewsLeft is nil for unlimited shares.
func (s *server) viewsLeft(ctx context.Context, sh *share) (*int, error) {
	if sh.MaxViews == 0 {
		return nil, nil
	}
	used, err := s.viewsUsed(ctx, sh.Token)
	left := max(sh.MaxViews-used, 0)
	return &left, err
}

// open spends one view of sh and returns a download ticket. Spending the last view cuts the share's
// life down to that ticket's, so the files are swept once its download window closes.
func (s *server) open(ctx context.Context, sh *share) (string, error) {
	key := "views:" + sh.Token
	n, err := s.rdb.Incr(ctx, key).Result()
	if err != nil {
		return "", err
	}
	s.rdb.ExpireAt(ctx, key, sh.Expires)
	if sh.MaxViews > 0 && n > int64(sh.MaxViews) {
		s.rdb.Decr(ctx, key)
		return "", errViewsUsedUp
	}
	if n == int64(sh.MaxViews) {
		if _, err := s.db.ExecContext(ctx, `UPDATE shares SET expires_at = MIN(expires_at, ?) WHERE token = ?`,
			time.Now().Add(ticketTTL).Unix(), sh.Token); err != nil {
			return "", err
		}
	}
	ticket := newToken()
	return ticket, s.rdb.Set(ctx, "ticket:"+ticket, sh.Token, ticketTTL).Err()
}

func (s *server) validTicket(ctx context.Context, ticket, token string) bool {
	if ticket == "" {
		return false
	}
	got, err := s.rdb.Get(ctx, "ticket:"+ticket).Result()
	return err == nil && got == token
}

// shareJSON describes a share without spending a view or revealing download URLs,
// so chat-app link previews and "what's in it?" looks are free.
func (s *server) shareJSON(w http.ResponseWriter, r *http.Request) {
	sh := s.load(r.Context(), r.PathValue("token"))
	if sh == nil {
		http.NotFound(w, r)
		return
	}
	left, err := s.viewsLeft(r.Context(), sh)
	if err != nil {
		serverError(w, err)
		return
	}
	if left != nil && *left == 0 {
		http.Error(w, "views used up", http.StatusGone)
		return
	}
	writeJSON(w, map[string]any{"files": sh.Files, "expires_at": sh.Expires, "max_views": sh.MaxViews, "views_left": left})
}

func (s *server) openJSON(w http.ResponseWriter, r *http.Request) {
	sh := s.load(r.Context(), r.PathValue("token"))
	if sh == nil {
		http.NotFound(w, r)
		return
	}
	ticket, err := s.open(r.Context(), sh)
	if errors.Is(err, errViewsUsedUp) {
		http.Error(w, "views used up", http.StatusGone)
		return
	}
	if err != nil {
		serverError(w, err)
		return
	}
	type file struct {
		fileMeta
		URL string `json:"url"`
	}
	files := make([]file, len(sh.Files))
	for i, f := range sh.Files {
		files[i] = file{f, fmt.Sprintf("%s/s/%s/%d?ticket=%s", baseURL(r), sh.Token, i, ticket)}
	}
	writeJSON(w, map[string]any{"ticket": ticket, "files": files})
}

func (s *server) myShares(w http.ResponseWriter, r *http.Request, uid int64) {
	ctx := r.Context()
	type item struct {
		Token     string    `json:"token"`
		URL       string    `json:"url"`
		Count     int       `json:"count"`
		ExpiresAt time.Time `json:"expires_at"`
		MaxViews  int       `json:"max_views"`
		ViewsUsed int       `json:"views_used"`
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT token, json_array_length(files), expires_at, max_views FROM shares
		 WHERE user_id = ? AND expires_at > ? ORDER BY created_at DESC, rowid DESC`, uid, time.Now().Unix())
	if err != nil {
		serverError(w, err)
		return
	}
	items := []item{}
	for rows.Next() {
		var it item
		var expires int64
		if err := rows.Scan(&it.Token, &it.Count, &expires, &it.MaxViews); err != nil {
			rows.Close()
			serverError(w, err)
			return
		}
		it.URL, it.ExpiresAt = baseURL(r)+"/s/"+it.Token, time.Unix(expires, 0)
		items = append(items, it)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		serverError(w, err)
		return
	}
	for i := range items {
		if items[i].ViewsUsed, err = s.viewsUsed(ctx, items[i].Token); err != nil {
			serverError(w, err)
			return
		}
	}
	writeJSON(w, items)
}

// deleteShare lets the owner destroy a share early.
func (s *server) deleteShare(w http.ResponseWriter, r *http.Request, uid int64) {
	sh := s.load(r.Context(), r.PathValue("token"))
	if sh == nil {
		http.NotFound(w, r)
		return
	}
	if sh.UserID != uid {
		http.Error(w, "not your share", http.StatusForbidden)
		return
	}
	if err := s.removeShare(r.Context(), sh.Token); err != nil {
		serverError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// removeShare drops the row first, so the share is gone for everyone even if the files linger until the sweep.
func (s *server) removeShare(ctx context.Context, token string) error {
	if _, err := s.db.ExecContext(ctx, `DELETE FROM shares WHERE token = ?`, token); err != nil {
		return err
	}
	s.rdb.Del(ctx, "views:"+token)
	return os.RemoveAll(filepath.Join(s.cfg.DataDir, token))
}

func (s *server) serveFile(w http.ResponseWriter, r *http.Request) {
	sh := s.load(r.Context(), r.PathValue("token"))
	i, err := strconv.Atoi(r.PathValue("index"))
	if sh == nil || err != nil || i < 0 || i >= len(sh.Files) {
		http.NotFound(w, r)
		return
	}
	if !s.validTicket(r.Context(), r.URL.Query().Get("ticket"), sh.Token) {
		http.Error(w, "invalid or expired ticket", http.StatusForbidden)
		return
	}
	f, err := os.Open(filepath.Join(s.cfg.DataDir, sh.Token, strconv.Itoa(i)))
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

// sweep deletes expired shares, then any directory older than the TTL that no live share owns:
// temp dirs of crashed uploads, or share dirs whose files outlived their row.
func (s *server) sweep() {
	ctx := context.Background()
	rows, err := s.db.QueryContext(ctx, `SELECT token FROM shares WHERE expires_at <= ?`, time.Now().Unix())
	if err != nil {
		log.Print("sweep: ", err)
		return
	}
	var expired []string
	for rows.Next() {
		var t string
		if rows.Scan(&t) == nil {
			expired = append(expired, t)
		}
	}
	rows.Close()
	for _, t := range expired {
		if err := s.removeShare(ctx, t); err != nil {
			log.Print("sweep: ", err)
		}
	}

	entries, err := os.ReadDir(s.cfg.DataDir)
	if err != nil {
		log.Print("sweep: ", err)
		return
	}
	for _, e := range entries {
		info, err := e.Info()
		if err != nil || !e.IsDir() || time.Since(info.ModTime()) <= s.cfg.TTL.Duration || s.load(ctx, e.Name()) != nil {
			continue
		}
		if err := os.RemoveAll(filepath.Join(s.cfg.DataDir, e.Name())); err != nil {
			log.Print("sweep: ", err)
		}
	}
}
