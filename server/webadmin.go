package main

import (
	_ "embed"
	"net/http"
)

//go:embed webadmin.html
var webAdminHTML []byte

//go:embed weblogin.html
var webLoginHTML []byte

//go:embed webmanager.html
var webManagerHTML []byte

//go:embed webcashier.html
var webCashierHTML []byte

//go:embed webshared.css
var webSharedCSS []byte

//go:embed webinventory.js
var webInventoryJS []byte

func servePage(w http.ResponseWriter, body []byte) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	// The shell is tiny and changes only on deploy; the staff's phones should
	// re-fetch it rather than pin a stale build after an update.
	w.Header().Set("Cache-Control", "no-cache")
	w.Write(body)
}

// GET /admin — the laptop-side admin panel (also reachable from any browser
// on the shop WiFi). The page itself is public; every action inside it calls
// the same role-guarded API the phone app uses, after an admin-PIN login —
// so this adds a surface, not a privilege.
func (s *Server) handleWebAdmin(w http.ResponseWriter, r *http.Request) {
	servePage(w, webAdminHTML)
}

// GET / — the single front door. One link for the whole shop: the PIN typed
// here decides which screen the browser lands on, so nobody has to remember
// (or be told) a role-specific URL. Serving it is public; the PIN is the gate.
func (s *Server) handleWebLogin(w http.ResponseWriter, r *http.Request) {
	servePage(w, webLoginHTML)
}

// GET /manager — tables, order taking, parcels, live kitchen.
func (s *Server) handleWebManager(w http.ResponseWriter, r *http.Request) {
	servePage(w, webManagerHTML)
}

// GET /cashier — billing, discounts, payment split, close, reprint.
func (s *Server) handleWebCashier(w http.ResponseWriter, r *http.Request) {
	servePage(w, webCashierHTML)
}

// GET /pos.css — one skin shared by the login gate, manager and cashier pages.
func (s *Server) handleWebCSS(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/css; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Write(webSharedCSS)
}

// GET /inventory.js — the raw-material inventory view, one copy loaded by
// both the admin and the manager page. Like the pages it is public and holds
// no data: every number it shows comes from the role-guarded /api/inventory.
func (s *Server) handleWebInventoryJS(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	w.Write(webInventoryJS)
}
