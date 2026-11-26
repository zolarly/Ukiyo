package main

import (
	"context"
	"errors"
	"io/fs"
	"net"
	"net/http"
	"path/filepath"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

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

// limited reports whether the Redis counter at key has reached max.
func (s *server) limited(ctx context.Context, key string, max int64) (bool, error) {
	n, err := s.rdb.Get(ctx, key).Int64()
	if errors.Is(err, redis.Nil) {
		return false, nil
	}
	return n >= max, err
}

// count adds n to the Redis counter at key and restarts its expiry, so the window slides with each hit.
func (s *server) count(ctx context.Context, key string, n int64, ttl time.Duration) error {
	_, err := s.rdb.TxPipelined(ctx, func(p redis.Pipeliner) error {
		p.IncrBy(ctx, key, n)
		p.Expire(ctx, key, ttl)
		return nil
	})
	return err
}

// clientIP is the last X-Forwarded-For hop (appended by Caddy), else the TCP peer.
// Without a proxy in front the header is client-controlled, so production must run behind one.
func clientIP(r *http.Request) string {
	if xff := r.Header.Values("X-Forwarded-For"); len(xff) > 0 {
		hops := strings.Split(xff[len(xff)-1], ",")
		return strings.TrimSpace(hops[len(hops)-1])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
