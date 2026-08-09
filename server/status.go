package main

import (
	"fmt"
	"net/http"
	"time"

	qrcode "github.com/skip2/go-qrcode"
)

// The status page is what you see if you open a browser on the laptop itself
// (http://localhost:8177). It exists so setting up a new phone is: open app,
// type the address you see here (or scan the QR from a phone camera app later).
// It intentionally shows no order contents — just connection info and counts.
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	ips := lanIPs()
	addr := "no network?"
	if len(ips) > 0 {
		addr = fmt.Sprintf("%s:%d", ips[0], s.port)
	}
	var openOrders, ordersToday int
	today := bizDate(time.Now(), s.dayStartHour)
	s.db.QueryRow(`SELECT COUNT(*) FROM orders WHERE status='open'`).Scan(&openOrders)
	s.db.QueryRow(`SELECT COUNT(*) FROM orders WHERE biz_date=?`, today).Scan(&ordersToday)

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprintf(w, `<!doctype html>
<html><head><meta charset="utf-8"><meta http-equiv="refresh" content="10">
<title>PavBhaji POS — server</title>
<style>
 body{font-family:system-ui,sans-serif;background:#1b1b1f;color:#eee;display:flex;
      flex-direction:column;align-items:center;padding:40px;gap:12px}
 .addr{font-size:2.2rem;font-weight:700;letter-spacing:1px;background:#2c2c33;
       padding:12px 28px;border-radius:12px}
 img{background:#fff;padding:14px;border-radius:12px;margin:10px}
 .row{color:#aaa} b{color:#fff}
</style></head><body>
<h2>🍛 PavBhaji POS server is running</h2>
<div class="addr">%s</div>
<div class="row">Phones on this WiFi find the server by themselves — just open the app.</div>
<div class="row">If one asks: tap <b>📷 Scan QR</b> in the app and point it here (or type the address).</div>
<img src="/qr.png" width="220" height="220" alt="QR">
<div class="row">Devices connected: <b>%d</b> · Orders today: <b>%d</b> · Open tables: <b>%d</b></div>
<div class="row">Database and backups live in the <b>data</b> folder next to the .exe — copy that folder to back everything up.</div>
</body></html>`, addr, s.hub.Count(), ordersToday, openOrders)
}

func (s *Server) handleQR(w http.ResponseWriter, r *http.Request) {
	ips := lanIPs()
	if len(ips) == 0 {
		http.NotFound(w, r)
		return
	}
	png, err := qrcode.Encode(fmt.Sprintf("%s:%d", ips[0], s.port), qrcode.Medium, 440)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Write(png)
}
