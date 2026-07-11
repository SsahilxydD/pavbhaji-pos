package main

import (
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"log"
	"time"

	_ "modernc.org/sqlite"
)

const schema = `
CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS staff (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  pin_hash TEXT NOT NULL UNIQUE,
  role TEXT NOT NULL CHECK(role IN ('admin','waiter','chef')),
  active INTEGER NOT NULL DEFAULT 1,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
  token TEXT PRIMARY KEY,
  staff_id INTEGER NOT NULL REFERENCES staff(id),
  created_at INTEGER NOT NULL,
  last_seen INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS categories (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  sort INTEGER NOT NULL DEFAULT 0,
  active INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS menu_items (
  id INTEGER PRIMARY KEY,
  category_id INTEGER NOT NULL REFERENCES categories(id),
  name TEXT NOT NULL,
  price INTEGER NOT NULL,
  available INTEGER NOT NULL DEFAULT 1,
  active INTEGER NOT NULL DEFAULT 1,
  sort INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS dining_tables (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  active INTEGER NOT NULL DEFAULT 1,
  sort INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS orders (
  id INTEGER PRIMARY KEY,
  biz_date TEXT NOT NULL,
  order_no INTEGER NOT NULL,
  table_id INTEGER NOT NULL REFERENCES dining_tables(id),
  status TEXT NOT NULL CHECK(status IN ('open','closed')),
  opened_by INTEGER NOT NULL REFERENCES staff(id),
  opened_at INTEGER NOT NULL,
  closed_by INTEGER,
  closed_at INTEGER,
  UNIQUE(biz_date, order_no)
);
CREATE UNIQUE INDEX IF NOT EXISTS one_open_order_per_table
  ON orders(table_id) WHERE status='open';

CREATE TABLE IF NOT EXISTS tickets (
  id INTEGER PRIMARY KEY,
  order_id INTEGER NOT NULL REFERENCES orders(id),
  ticket_no INTEGER NOT NULL,
  client_ticket_id TEXT NOT NULL UNIQUE,
  created_by INTEGER NOT NULL REFERENCES staff(id),
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ticket_items (
  id INTEGER PRIMARY KEY,
  ticket_id INTEGER NOT NULL REFERENCES tickets(id),
  menu_item_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  price INTEGER NOT NULL,
  qty INTEGER NOT NULL CHECK(qty > 0),
  note TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending'
    CHECK(status IN ('pending','preparing','ready','served','cancelled')),
  status_at INTEGER NOT NULL,
  status_by INTEGER,
  cancel_reason TEXT
);
CREATE INDEX IF NOT EXISTS ticket_items_by_ticket ON ticket_items(ticket_id);
CREATE INDEX IF NOT EXISTS tickets_by_order ON tickets(order_id);
CREATE INDEX IF NOT EXISTS orders_by_bizdate ON orders(biz_date);

CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY,
  at INTEGER NOT NULL,
  staff_id INTEGER,
  staff_name TEXT NOT NULL DEFAULT '',
  action TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS events_by_at ON events(at);
`

func openDB(path string) *sql.DB {
	dsn := "file:" + path + "?_pragma=journal_mode(WAL)&_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=synchronous(FULL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		log.Fatalf("open db: %v", err)
	}
	// Single connection: every transaction is fully serialized, which is exactly
	// what we want for a one-shop POS — no write races, order numbers can never collide.
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		log.Fatalf("migrate: %v", err)
	}
	return db
}

func nowMs() int64 { return time.Now().UnixMilli() }

// bizDate returns the business date for a timestamp. The day rolls over at
// dayStartHour (default 6 AM) so a late-night 1 AM order still counts as
// the previous evening's business.
func bizDate(t time.Time, dayStartHour int) string {
	t = t.Local()
	if t.Hour() < dayStartHour {
		t = t.AddDate(0, 0, -1)
	}
	return t.Format("2006-01-02")
}

func getMeta(db *sql.DB, key, def string) string {
	var v string
	err := db.QueryRow(`SELECT value FROM meta WHERE key=?`, key).Scan(&v)
	if err != nil {
		return def
	}
	return v
}

func setMeta(db *sql.DB, key, value string) {
	db.Exec(`INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value`, key, value)
}

// hashPin hashes a PIN with a per-installation salt. The hash column is UNIQUE,
// which also enforces that two staff members can never share a PIN.
func hashPin(salt, pin string) string {
	sum := sha256.Sum256([]byte(salt + ":" + pin))
	return hex.EncodeToString(sum[:])
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func seedIfEmpty(db *sql.DB) {
	if getMeta(db, "pin_salt", "") == "" {
		setMeta(db, "pin_salt", randomHex(16))
	}
	salt := getMeta(db, "pin_salt", "")

	var n int
	db.QueryRow(`SELECT COUNT(*) FROM staff`).Scan(&n)
	if n > 0 {
		return
	}
	now := nowMs()
	db.Exec(`INSERT INTO staff(name, pin_hash, role, created_at) VALUES
		('Admin', ?, 'admin', ?),
		('Waiter 1', ?, 'waiter', ?),
		('Chef', ?, 'chef', ?)`,
		hashPin(salt, "1111"), now, hashPin(salt, "2222"), now, hashPin(salt, "3333"), now)

	seedMenu := []struct {
		cat   string
		items []struct {
			name  string
			price int // paise
		}
	}{
		{"Bhaji", []struct {
			name  string
			price int
		}{
			{"Pav Bhaji", 12000}, {"Cheese Pav Bhaji", 15000},
			{"Jain Pav Bhaji", 13000}, {"Khada Pav Bhaji", 14000},
		}},
		{"Extras", []struct {
			name  string
			price int
		}{
			{"Extra Pav (2 pc)", 2000}, {"Extra Butter", 1500}, {"Chopped Onion", 1000},
		}},
		{"Drinks", []struct {
			name  string
			price int
		}{
			{"Chaas", 3000}, {"Masala Soda", 4000}, {"Water Bottle", 2000},
		}},
	}
	for ci, c := range seedMenu {
		res, _ := db.Exec(`INSERT INTO categories(name, sort) VALUES(?,?)`, c.cat, ci)
		catID, _ := res.LastInsertId()
		for ii, it := range c.items {
			db.Exec(`INSERT INTO menu_items(category_id, name, price, sort) VALUES(?,?,?,?)`,
				catID, it.name, it.price, ii)
		}
	}
	for i := 1; i <= 10; i++ {
		db.Exec(`INSERT INTO dining_tables(name, sort) VALUES(?,?)`, fmt.Sprintf("T%d", i), i)
	}
	db.Exec(`INSERT INTO events(at, action, detail) VALUES(?, 'seed', 'first run: default staff, menu and tables created')`, now)
	log.Println("first run: seeded default staff (PINs 1111/2222/3333 — change them!), sample menu, tables T1–T10")
}

func audit(db *sql.DB, staffID int64, staffName, action, detail string) {
	db.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		nowMs(), staffID, staffName, action, detail)
}
