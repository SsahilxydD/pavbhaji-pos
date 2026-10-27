package main

import (
	"context"
	"database/sql"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
)

type Staff struct {
	ID     int64  `json:"id"`
	Name   string `json:"name"`
	Role   string `json:"role"`
	Active bool   `json:"active"`
}

type Server struct {
	db           *sql.DB
	hub          *Hub
	sessions     *sessionCache
	port         int
	dataDir      string
	dayStartHour int
	serverID     string // stable random id so phones can re-recognise this install
}

// Brute-force brake: a 4-digit PIN space is only 10,000 guesses — without a
// limiter anyone on the shop WiFi could walk it in minutes. Per phone: 5 free
// tries, then every further failure arms a 30s lockout. Success clears it, and
// a phone that stops guessing for 15 minutes starts fresh.
//
// "Per phone" is the client IP plus the optional X-Device-Id header. Phones that
// send a device id get their own gate, with a looser per-IP gate behind it so
// rotating ids on one network still runs into a wall.
type loginGate struct {
	fails        int
	blockedUntil int64
	lastFail     int64
}

const (
	gateDeviceFails = 5
	gateIPFails     = 25
	gateDecayMs     = 15 * 60_000
)

var (
	gatesMu sync.Mutex
	gates   = map[string]*loginGate{}
)

// clientIP is the address the request really came from. Behind Caddy on this
// box RemoteAddr is always 127.0.0.1, so one phone's typos used to lock out
// every phone; the true peer is the last X-Forwarded-For hop the proxy added.
// The header is trusted only from loopback, so a LAN client cannot forge it.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		if vals := r.Header.Values("X-Forwarded-For"); len(vals) > 0 {
			hops := strings.Split(vals[len(vals)-1], ",")
			if last := strings.TrimSpace(hops[len(hops)-1]); net.ParseIP(last) != nil {
				return last
			}
		}
	}
	return host
}

func deviceID(r *http.Request) string {
	id := strings.TrimSpace(r.Header.Get("X-Device-Id"))
	if len(id) > 64 {
		id = id[:64]
	}
	return id
}

// gateKeys names the gates a login attempt counts against, with their limits.
func gateKeys(ip, dev string) ([]string, []int) {
	if dev == "" {
		return []string{"ip:" + ip}, []int{gateDeviceFails}
	}
	return []string{"dev:" + ip + "|" + dev, "ip:" + ip}, []int{gateDeviceFails, gateIPFails}
}

// loginGates fetches (or opens) those gates, first forgetting any that have
// gone quiet. Caller holds gatesMu.
func loginGates(keys []string, now int64) []*loginGate {
	for k, g := range gates {
		if now-g.lastFail > gateDecayMs && now >= g.blockedUntil {
			delete(gates, k)
		}
	}
	gs := make([]*loginGate, len(keys))
	for i, k := range keys {
		if gates[k] == nil {
			gates[k] = &loginGate{}
		}
		gs[i] = gates[k]
	}
	return gs
}

// POST /api/login {pin} -> {token, staff}
func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Pin string `json:"pin"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Pin = strings.TrimSpace(req.Pin)
	if len(req.Pin) < 4 {
		fail(w, http.StatusBadRequest, "PIN must be at least 4 digits")
		return
	}
	ip, dev := clientIP(r), deviceID(r)
	keys, limits := gateKeys(ip, dev)
	gatesMu.Lock()
	gs := loginGates(keys, nowMs())
	for _, g := range gs {
		if now := nowMs(); now < g.blockedUntil {
			wait := (g.blockedUntil - now + 999) / 1000
			gatesMu.Unlock()
			fail(w, http.StatusTooManyRequests, fmt.Sprintf("too many wrong PINs — wait %ds", wait))
			return
		}
	}
	gatesMu.Unlock()

	salt := getMeta(s.db, "pin_salt", "")
	var st Staff
	err := s.db.QueryRow(
		`SELECT id, name, role FROM staff WHERE pin_hash=? AND active=1`,
		hashPin(salt, req.Pin)).Scan(&st.ID, &st.Name, &st.Role)
	if err != nil {
		now := nowMs()
		gatesMu.Lock()
		armed := false
		for i, g := range gs {
			g.fails++
			g.lastFail = now
			if g.fails >= limits[i] {
				g.blockedUntil = now + 30_000
			}
			if g.fails == limits[i] || g.fails%50 == 0 {
				armed = true
			}
		}
		fails := gs[0].fails
		gatesMu.Unlock()
		// The owner should see a guessing spree in the log — but not 10,000 rows of it.
		if armed {
			from := ip
			if dev != "" {
				from += " (device " + dev + ")"
			}
			audit(s.db, 0, "system", "login:blocked", fmt.Sprintf("%d wrong PINs from %s — 30s lockouts armed", fails, from))
		}
		fail(w, http.StatusUnauthorized, "wrong PIN")
		return
	}
	// Success clears this phone's own gate. The per-IP gate is left to decay,
	// so a colleague's login on the same WiFi doesn't wipe a guesser's count.
	gatesMu.Lock()
	delete(gates, keys[0])
	gatesMu.Unlock()
	st.Active = true
	token := randomHex(24)
	now := nowMs()
	s.db.Exec(`INSERT INTO sessions(token, staff_id, created_at, last_seen) VALUES(?,?,?,?)`,
		token, st.ID, now, now)
	audit(s.db, st.ID, st.Name, "login", st.Role+" logged in")
	writeJSON(w, http.StatusOK, map[string]any{"token": token, "staff": st})
}

// POST /api/logout
func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	token := bearerToken(r)
	s.db.Exec(`DELETE FROM sessions WHERE token=?`, token)
	s.sessions.drop(token)
	audit(s.db, st.ID, st.Name, "logout", "")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func bearerToken(r *http.Request) string {
	h := r.Header.Get("Authorization")
	if strings.HasPrefix(h, "Bearer ") {
		return strings.TrimPrefix(h, "Bearer ")
	}
	// WebSocket clients can't set headers from all libs; allow ?token=
	return r.URL.Query().Get("token")
}

type ctxKey int

const staffKey ctxKey = 1

func staffFrom(r *http.Request) Staff {
	st, _ := r.Context().Value(staffKey).(Staff)
	return st
}

func withStaff(ctx context.Context, st Staff) context.Context {
	return context.WithValue(ctx, staffKey, st)
}

// sessionCache keeps recently checked logins in memory. Every API call used to
// cost a session lookup plus a last_seen write — a full fsync with
// synchronous=FULL — on the single DB connection that punches also wait on.
// Now a token is re-checked against the DB once a minute and last_seen is
// written at most once a minute. Anything that kills a login (logout, PIN or
// role change, deactivation) drops it here too, so the cache never outlives
// the row it mirrors.
type sessionCache struct {
	mu sync.Mutex
	m  map[string]*sessionEntry
}

type sessionEntry struct {
	st       Staff
	checked  int64 // when the DB last confirmed this token
	lastSeen int64 // the last_seen value the DB holds
}

const (
	sessionRecheckMs = 60_000
	sessionSeenMs    = 60_000
	sessionIdleDays  = 60
)

func newSessionCache() *sessionCache {
	return &sessionCache{m: map[string]*sessionEntry{}}
}

func (c *sessionCache) drop(token string) {
	c.mu.Lock()
	delete(c.m, token)
	c.mu.Unlock()
}

func (c *sessionCache) dropStaff(staffID int64) {
	c.mu.Lock()
	for k, e := range c.m {
		if e.st.ID == staffID {
			delete(c.m, k)
		}
	}
	c.mu.Unlock()
}

// session resolves a token to its staff member, from memory when fresh.
func (s *Server) session(token string) (Staff, bool) {
	now := nowMs()
	c := s.sessions
	c.mu.Lock()
	e := c.m[token]
	if e != nil && now-e.checked < sessionRecheckMs {
		st := e.st
		touch := now-e.lastSeen >= sessionSeenMs
		if touch {
			e.lastSeen = now
		}
		c.mu.Unlock()
		if touch {
			s.db.Exec(`UPDATE sessions SET last_seen=? WHERE token=?`, now, token)
		}
		return st, true
	}
	c.mu.Unlock()

	var st Staff
	var lastSeen int64
	err := s.db.QueryRow(
		`SELECT s.id, s.name, s.role, s.active, se.last_seen FROM sessions se
		 JOIN staff s ON s.id = se.staff_id WHERE se.token=?`, token).
		Scan(&st.ID, &st.Name, &st.Role, &st.Active, &lastSeen)
	if err != nil || !st.Active {
		c.drop(token)
		return Staff{}, false
	}
	if now-lastSeen >= sessionSeenMs {
		s.db.Exec(`UPDATE sessions SET last_seen=? WHERE token=?`, now, token)
		lastSeen = now
	}
	c.mu.Lock()
	c.m[token] = &sessionEntry{st: st, checked: now, lastSeen: lastSeen}
	c.mu.Unlock()
	return st, true
}

// pruneSessions forgets logins nobody has used in sessionIdleDays, and cache
// entries too stale to be trusted without a DB check anyway.
func (s *Server) pruneSessions() {
	now := nowMs()
	res, err := s.db.Exec(`DELETE FROM sessions WHERE last_seen < ?`, now-sessionIdleDays*86_400_000)
	if err == nil {
		if n, _ := res.RowsAffected(); n > 0 {
			audit(s.db, 0, "system", "sessions:pruned",
				fmt.Sprintf("%d logins unused for %d days signed out", n, sessionIdleDays))
		}
	}
	c := s.sessions
	c.mu.Lock()
	for k, e := range c.m {
		if now-e.checked >= sessionRecheckMs {
			delete(c.m, k)
		}
	}
	c.mu.Unlock()
}

// requireRole wraps a handler; roles is a comma-free list like "admin", "waiter admin".
// Every protected endpoint declares who may call it — enforcement lives here on the
// server, never in the phone UI.
func (s *Server) requireRole(roles string, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := bearerToken(r)
		if token == "" {
			fail(w, http.StatusUnauthorized, "not logged in")
			return
		}
		st, ok := s.session(token)
		if !ok {
			fail(w, http.StatusUnauthorized, "session expired, log in again")
			return
		}
		if roles != "*" && !strings.Contains(roles, st.Role) {
			fail(w, http.StatusForbidden, "your role ("+st.Role+") is not allowed to do this")
			return
		}
		r = r.WithContext(withStaff(r.Context(), st))
		next(w, r)
	}
}
