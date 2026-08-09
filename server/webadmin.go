package main

import (
	_ "embed"
	"net/http"
)

//go:embed webadmin.html
var webAdminHTML []byte

// GET /admin — the laptop-side admin panel (also reachable from any browser
// on the shop WiFi). The page itself is public; every action inside it calls
// the same role-guarded API the phone app uses, after an admin-PIN login —
// so this adds a surface, not a privilege.
func (s *Server) handleWebAdmin(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write(webAdminHTML)
}
