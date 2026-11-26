package main

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"

	"github.com/redis/go-redis/v9"
	_ "modernc.org/sqlite"
)

// Times are Unix seconds. files is a JSON array of fileMeta.
const schema = `
CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY,
  username      TEXT NOT NULL UNIQUE COLLATE NOCASE,
  password_hash TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS shares (
  token      TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  max_views  INTEGER NOT NULL,
  files      TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS shares_user ON shares(user_id, expires_at);`

// newServer creates the data dir and its SQLite database, and fails fast if Redis is unreachable.
func newServer(cfg config) (*server, error) {
	if err := os.MkdirAll(cfg.DataDir, 0o700); err != nil {
		return nil, err
	}
	db, err := sql.Open("sqlite", filepath.Join(cfg.DataDir, "ukiyo.db")+"?_pragma=foreign_keys(1)")
	if err != nil {
		return nil, err
	}
	// ponytail: one connection serializes all SQL; plenty for a few friends, add WAL + a reader pool if it ever queues.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, fmt.Errorf("sqlite: %w", err)
	}
	rdb := redis.NewClient(&redis.Options{Addr: cfg.RedisAddr})
	if err := rdb.Ping(context.Background()).Err(); err != nil {
		db.Close()
		return nil, fmt.Errorf("redis %s: %w", cfg.RedisAddr, err)
	}
	return &server{cfg: cfg, db: db, rdb: rdb}, nil
}
