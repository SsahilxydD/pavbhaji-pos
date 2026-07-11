package main

import (
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Hub pushes tiny invalidation messages ("tables changed", "kitchen changed")
// to every connected phone. Phones then refetch whatever screen they're on.
// Refetch-on-nudge is deliberately dumb: there is no delta-application logic
// to get out of sync, and at one-restaurant scale the refetches are trivial.
type Hub struct {
	mu    sync.Mutex
	conns map[*websocket.Conn]bool
}

func newHub() *Hub {
	return &Hub{conns: map[*websocket.Conn]bool{}}
}

func (h *Hub) Count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.conns)
}

func (h *Hub) Broadcast(topics ...string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, topic := range topics {
		msg := []byte(`{"topic":"` + topic + `"}`)
		for c := range h.conns {
			c.SetWriteDeadline(time.Now().Add(5 * time.Second))
			if err := c.WriteMessage(websocket.TextMessage, msg); err != nil {
				c.Close()
				delete(h.conns, c)
			}
		}
	}
}

var upgrader = websocket.Upgrader{
	// LAN-only server: phones connect by bare IP, there is no Origin to trust-check.
	CheckOrigin: func(r *http.Request) bool { return true },
}

// GET /ws?token=... — authenticated like every API call.
func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	s.hub.mu.Lock()
	s.hub.conns[conn] = true
	n := len(s.hub.conns)
	s.hub.mu.Unlock()
	log.Printf("device connected (%d online)", n)

	conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	conn.SetPongHandler(func(string) error {
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		return nil
	})

	// Ping every 20s so half-dead WiFi connections get cleaned up.
	stop := make(chan struct{})
	go func() {
		t := time.NewTicker(20 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if err := conn.WriteMessage(websocket.PingMessage, nil); err != nil {
					conn.Close()
					return
				}
			}
		}
	}()

	for {
		if _, _, err := conn.ReadMessage(); err != nil {
			break
		}
		conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	}
	close(stop)
	s.hub.mu.Lock()
	delete(s.hub.conns, conn)
	n = len(s.hub.conns)
	s.hub.mu.Unlock()
	conn.Close()
	log.Printf("device disconnected (%d online)", n)
}
