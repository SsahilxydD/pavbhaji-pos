package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
)

// Phones find the laptop by shouting a magic word at this UDP port and
// listening for who answers — nobody has to read an IP off a screen.
const (
	discoveryPort  = 8178
	discoveryMagic = "PBPOS_DISCOVER_V1"
)

type serverInfo struct {
	App  string `json:"app"`
	Name string `json:"name"`
	ID   string `json:"id"`
	Port int    `json:"port"`
}

func (s *Server) info() serverInfo {
	return serverInfo{App: "pbpos", Name: "PavBhaji POS", ID: s.serverID, Port: s.port}
}

// GET /api/ping — public. The app calls this to recognise a PavBhaji server
// (and to tell *which one*) before trusting an address it discovered.
func (s *Server) handlePing(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, s.info())
}

// discoveryResponder answers UDP broadcasts from the app. The reply's source
// address tells the phone where the server lives; the payload adds port + id.
func (s *Server) discoveryResponder() {
	pc, err := net.ListenPacket("udp4", fmt.Sprintf(":%d", discoveryPort))
	if err != nil {
		log.Printf("discovery disabled (%v) — phones can still scan the QR or type the address", err)
		return
	}
	reply, _ := json.Marshal(s.info())
	buf := make([]byte, 64)
	for {
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			if errors.Is(err, net.ErrClosed) {
				return
			}
			continue
		}
		if string(buf[:n]) != discoveryMagic {
			continue
		}
		pc.WriteTo(reply, from)
	}
}

// ensureServerID gives each installation a stable random identity so a phone
// that re-discovers "a" server knows it found *its* server again.
func ensureServerID(db *sql.DB) string {
	if id := getMeta(db, "server_id", ""); id != "" {
		return id
	}
	b := make([]byte, 8)
	rand.Read(b)
	id := hex.EncodeToString(b)
	setMeta(db, "server_id", id)
	return id
}
