package main

import (
	"strings"
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
  role TEXT NOT NULL CHECK(role IN ('admin','manager','waiter','chef')),
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
  sort INTEGER NOT NULL DEFAULT 0,
  colour TEXT NOT NULL DEFAULT '',
  station TEXT NOT NULL DEFAULT 'kitchen' CHECK(station IN ('kitchen','counter')),
  batch_cap INTEGER,     -- this dish's tawa capacity; NULL = global default (meta batch_cap)
  batch_age_min INTEGER  -- minutes before an open bucket auto-seals; NULL = global default
);

CREATE TABLE IF NOT EXISTS dining_tables (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  active INTEGER NOT NULL DEFAULT 1,
  sort INTEGER NOT NULL DEFAULT 0,
  kind TEXT NOT NULL DEFAULT 'dine' CHECK(kind IN ('dine','parcel'))
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
  cancel_reason TEXT,
  station TEXT NOT NULL DEFAULT 'kitchen',
  batch_id INTEGER REFERENCES batches(id)
);
CREATE INDEX IF NOT EXISTS ticket_items_by_ticket ON ticket_items(ticket_id);
CREATE INDEX IF NOT EXISTS tickets_by_order ON tickets(order_id);
CREATE INDEX IF NOT EXISTS orders_by_bizdate ON orders(biz_date);

-- Colour buckets: kitchen lines accumulate into the open batch for their dish
-- until it reaches the cap (meta batch_cap, default 15) or a chef takes it.
-- open   = still filling            full  = sealed, waiting for a chef
-- taken  = locked to one chef       ready = done, lines flipped to 'ready'
CREATE TABLE IF NOT EXISTS batches (
  id INTEGER PRIMARY KEY,
  menu_item_id INTEGER NOT NULL REFERENCES menu_items(id),
  status TEXT NOT NULL DEFAULT 'open' CHECK(status IN ('open','full','taken','ready')),
  created_at INTEGER NOT NULL,
  taken_by INTEGER REFERENCES staff(id),
  taken_at INTEGER,
  ready_at INTEGER
);
CREATE INDEX IF NOT EXISTS batches_by_item_status ON batches(menu_item_id, status);

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
	// Additive migrations for databases created before the batch model.
	ensureColumn(db, "menu_items", "colour", `ALTER TABLE menu_items ADD COLUMN colour TEXT NOT NULL DEFAULT ''`)
	ensureColumn(db, "menu_items", "station", `ALTER TABLE menu_items ADD COLUMN station TEXT NOT NULL DEFAULT 'kitchen'`)
	ensureColumn(db, "dining_tables", "kind", `ALTER TABLE dining_tables ADD COLUMN kind TEXT NOT NULL DEFAULT 'dine'`)
	ensureColumn(db, "menu_items", "batch_cap", `ALTER TABLE menu_items ADD COLUMN batch_cap INTEGER`)
	ensureColumn(db, "menu_items", "batch_age_min", `ALTER TABLE menu_items ADD COLUMN batch_age_min INTEGER`)
	ensureColumn(db, "ticket_items", "station", `ALTER TABLE ticket_items ADD COLUMN station TEXT NOT NULL DEFAULT 'kitchen'`)
	ensureColumn(db, "ticket_items", "batch_id", `ALTER TABLE ticket_items ADD COLUMN batch_id INTEGER REFERENCES batches(id)`)
	// This index must come after the column migrations: on a pre-batch database
	// ticket_items.batch_id only exists once ensureColumn has added it.
	if _, err := db.Exec(`CREATE INDEX IF NOT EXISTS ticket_items_by_batch ON ticket_items(batch_id)`); err != nil {
		log.Fatalf("migrate index: %v", err)
	}
	// staff.role was CHECK-constrained to admin/waiter/chef; SQLite cannot
	// alter a CHECK, so older databases get one table rebuild to admit 'manager'.
	var staffDDL string
	db.QueryRow(`SELECT sql FROM sqlite_master WHERE type='table' AND name='staff'`).Scan(&staffDDL)
	if !strings.Contains(staffDDL, "manager") {
		for _, q := range []string{
			// Other tables hold live rows referencing staff(id); the rebuild is
			// safe (ids are preserved) but only with enforcement paused.
			`PRAGMA foreign_keys=OFF`,
			`CREATE TABLE staff_new (
			  id INTEGER PRIMARY KEY,
			  name TEXT NOT NULL,
			  pin_hash TEXT NOT NULL UNIQUE,
			  role TEXT NOT NULL CHECK(role IN ('admin','manager','waiter','chef')),
			  active INTEGER NOT NULL DEFAULT 1,
			  created_at INTEGER NOT NULL
			)`,
			`INSERT INTO staff_new SELECT id, name, pin_hash, role, active, created_at FROM staff`,
			`DROP TABLE staff`,
			`ALTER TABLE staff_new RENAME TO staff`,
			`PRAGMA foreign_keys=ON`,
		} {
			if _, err := db.Exec(q); err != nil {
				log.Fatalf("migrate staff.role: %v", err)
			}
		}
		log.Printf("migrated: staff.role now allows 'manager'")
	}
	return db
}

func ensureColumn(db *sql.DB, table, column, ddl string) {
	rows, err := db.Query(`SELECT name FROM pragma_table_info(?)`, table)
	if err != nil {
		log.Fatalf("migrate %s: %v", table, err)
	}
	defer rows.Close()
	for rows.Next() {
		var name string
		rows.Scan(&name)
		if name == column {
			return
		}
	}
	if _, err := db.Exec(ddl); err != nil {
		log.Fatalf("migrate %s.%s: %v", table, column, err)
	}
	log.Printf("migrated: added %s.%s", table, column)
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
	// A silent failure here means an install nobody can ever log in to — be loud.
	if _, err := db.Exec(`INSERT INTO staff(name, pin_hash, role, created_at) VALUES
		('Admin', ?, 'admin', ?),
		('Waiter 1', ?, 'waiter', ?),
		('Chef', ?, 'chef', ?),
		('Manager', ?, 'manager', ?)`,
		hashPin(salt, "1111"), now, hashPin(salt, "2222"), now, hashPin(salt, "3333"), now,
		hashPin(salt, "4444"), now); err != nil {
		log.Fatalf("seed staff: %v", err)
	}

	type seedItem struct {
		name    string
		price   int // paise
		colour  string
		station string
	}
	seedMenu := []struct {
		cat   string
		items []seedItem
	}{
		{"Bhaji", []seedItem{
			{"Pav Bhaji", 12000, "#FF8A3D", "kitchen"},
			{"Cheese Pav Bhaji", 15000, "#FFC93D", "kitchen"},
			{"Jain Pav Bhaji", 13000, "#6FCF6F", "kitchen"},
			{"Khada Pav Bhaji", 14000, "#C79BFF", "kitchen"},
		}},
		// Extras never enter the kitchen: the pav/extras counter handles them,
		// the waiter just carries. Only bhaji cooks in colour buckets.
		{"Extras", []seedItem{
			{"Extra Pav (2 pc)", 2000, "#E8483F", "counter"},
			{"Extra Butter", 1500, "#5BC8DB", "counter"},
			{"Chopped Onion", 1000, "#6FCF6F", "counter"},
		}},
		{"Drinks", []seedItem{
			{"Chaas", 3000, "#5BC8DB", "counter"},
			{"Masala Soda", 4000, "#E8483F", "counter"},
			{"Water Bottle", 2000, "#5BC8DB", "counter"},
		}},
	}
	for ci, c := range seedMenu {
		res, _ := db.Exec(`INSERT INTO categories(name, sort) VALUES(?,?)`, c.cat, ci)
		catID, _ := res.LastInsertId()
		for ii, it := range c.items {
			db.Exec(`INSERT INTO menu_items(category_id, name, price, sort, colour, station) VALUES(?,?,?,?,?,?)`,
				catID, it.name, it.price, ii, it.colour, it.station)
		}
	}
	for i := 1; i <= 10; i++ {
		db.Exec(`INSERT INTO dining_tables(name, sort) VALUES(?,?)`, fmt.Sprintf("T%d", i), i)
	}
	// Parcel slots: walk-up/takeaway orders the admin (or anyone) punches at
	// the counter. Same order machinery, separate section on the tables grid.
	for i := 1; i <= 3; i++ {
		db.Exec(`INSERT INTO dining_tables(name, sort, kind) VALUES(?,?,'parcel')`, fmt.Sprintf("P%d", i), 100+i)
	}
	db.Exec(`INSERT INTO events(at, action, detail) VALUES(?, 'seed', 'first run: default staff, menu and tables created')`, now)
	log.Println("first run: seeded default staff (PINs admin 1111 / waiter 2222 / chef 3333 / manager 4444 — change them!), sample menu, tables T1–T10 + parcel P1–P3")
}

func audit(db *sql.DB, staffID int64, staffName, action, detail string) {
	db.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		nowMs(), staffID, staffName, action, detail)
}
