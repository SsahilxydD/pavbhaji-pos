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
	port         int
	dataDir      string
	dayStartHour int
	serverID     string // stable random id so phones can re-recognise this install
}

// Brute-force brake: a 4-digit PIN space is only 10,000 guesses — without a
// limiter anyone on the shop WiFi could walk it in minutes. Per IP: 5 free
// tries, then every further failure arms a 30s lockout. Success clears it.
type loginGate struct {
	fails        int
	blockedUntil int64
}

var (
	gatesMu sync.Mutex
	gates   = map[string]*loginGate{}
)

func loginIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
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
	ip := loginIP(r)
	gatesMu.Lock()
	g := gates[ip]
	if g == nil {
		g = &loginGate{}
		gates[ip] = g
	}
	if now := nowMs(); now < g.blockedUntil {
		wait := (g.blockedUntil - now + 999) / 1000
		gatesMu.Unlock()
		fail(w, http.StatusTooManyRequests, fmt.Sprintf("too many wrong PINs — wait %ds", wait))
		return
	}
	gatesMu.Unlock()

	salt := getMeta(s.db, "pin_salt", "")
	var st Staff
	err := s.db.QueryRow(
		`SELECT id, name, role FROM staff WHERE pin_hash=? AND active=1`,
		hashPin(salt, req.Pin)).Scan(&st.ID, &st.Name, &st.Role)
	if err != nil {
		gatesMu.Lock()
		g.fails++
		if g.fails >= 5 {
			g.blockedUntil = nowMs() + 30_000
		}
		fails := g.fails
		gatesMu.Unlock()
		// The owner should see a guessing spree in the log — but not 10,000 rows of it.
		if fails == 5 || fails%50 == 0 {
			audit(s.db, 0, "system", "login:blocked", fmt.Sprintf("%d wrong PINs from %s — 30s lockouts armed", fails, ip))
		}
		fail(w, http.StatusUnauthorized, "wrong PIN")
		return
	}
	gatesMu.Lock()
	delete(gates, ip)
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
		var st Staff
		err := s.db.QueryRow(
			`SELECT s.id, s.name, s.role, s.active FROM sessions se
			 JOIN staff s ON s.id = se.staff_id WHERE se.token=?`, token).
			Scan(&st.ID, &st.Name, &st.Role, &st.Active)
		if err != nil || !st.Active {
			fail(w, http.StatusUnauthorized, "session expired, log in again")
			return
		}
		s.db.Exec(`UPDATE sessions SET last_seen=? WHERE token=?`, nowMs(), token)
		if roles != "*" && !strings.Contains(roles, st.Role) {
			fail(w, http.StatusForbidden, "your role ("+st.Role+") is not allowed to do this")
			return
		}
		r = r.WithContext(withStaff(r.Context(), st))
		next(w, r)
	}
}
