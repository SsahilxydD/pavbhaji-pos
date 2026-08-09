//go:build windows

package main

import (
	"fmt"
	"net"
	"net/http"
	"os/exec"
	"strings"

	"github.com/lxn/walk"
	d "github.com/lxn/walk/declarative"
)

// listenFree binds a TCP listener, preferring `preferred` (e.g. 8177) but
// falling back to any OS-assigned free port if that one is already in use.
// Returning the bound listener avoids a race between probing and serving.
func listenFree(preferred int) (net.Listener, int, error) {
	if ln, err := net.Listen("tcp", fmt.Sprintf(":%d", preferred)); err == nil {
		return ln, preferred, nil
	}
	ln, err := net.Listen("tcp", ":0")
	if err != nil {
		return nil, 0, err
	}
	return ln, ln.Addr().(*net.TCPAddr).Port, nil
}

// runGUI shows the desktop control window: one Start button that opens a port
// (preferring defaultPort), boots the server and prints the address that every
// tablet & phone should connect to.
func runGUI(defaultPort int, dataDir string) {
	var (
		mw       *walk.MainWindow
		info     *walk.TextEdit
		startBtn *walk.PushButton
		adminBtn *walk.PushButton
		running  bool
		livePort int
	)

	d.MainWindow{
		AssignTo: &mw,
		Title:    "PavBhaji POS Server",
		MinSize:  d.Size{Width: 460, Height: 340},
		Layout:   d.VBox{},
		Children: []d.Widget{
			d.Label{
				Text: "🍛  PavBhaji POS Server",
				Font: d.Font{Family: "Segoe UI", PointSize: 15, Bold: true},
			},
			d.Label{Text: "Press Start, then open the address shown on every tablet & phone."},
			d.PushButton{
				AssignTo: &startBtn,
				Text:     "▶   Start Server",
				MinSize:  d.Size{Height: 46},
				Font:     d.Font{Family: "Segoe UI", PointSize: 12, Bold: true},
				OnClicked: func() {
					if running {
						return
					}
					ln, port, err := listenFree(defaultPort)
					if err != nil {
						walk.MsgBox(mw, "PavBhaji POS", "Could not open a network port:\n"+err.Error(), walk.MsgBoxIconError)
						return
					}
					_, mux, err := buildServer(port, dataDir)
					if err != nil {
						ln.Close()
						walk.MsgBox(mw, "PavBhaji POS", "Could not start the server:\n"+err.Error(), walk.MsgBoxIconError)
						return
					}
					go http.Serve(ln, mux)

					running = true
					livePort = port
					startBtn.SetText("●   Server Running")
					startBtn.SetEnabled(false)
					adminBtn.SetEnabled(true)
					info.SetText(connectInfo(port))
				},
			},
			d.PushButton{
				AssignTo: &adminBtn,
				Text:     "⚙   Open Admin Panel (menu · prices · staff · reports)",
				Enabled:  false,
				MinSize:  d.Size{Height: 38},
				OnClicked: func() {
					if running {
						// opens the default browser on the embedded panel
						exec.Command("rundll32", "url.dll,FileProtocolHandler",
							fmt.Sprintf("http://localhost:%d/admin", livePort)).Start()
					}
				},
			},
			d.TextEdit{
				AssignTo: &info,
				ReadOnly: true,
				VScroll:  true,
				Font:     d.Font{Family: "Consolas", PointSize: 11},
				Text:     "Server stopped.\r\nPress “Start Server” to go live.",
			},
		},
	}.Run()
}

// connectInfo builds the multi-line status text shown once the server is live.
func connectInfo(port int) string {
	var b strings.Builder
	b.WriteString("SERVER RUNNING  ✔\r\n\r\n")
	b.WriteString("Phones on this WiFi find the server by themselves.\r\n")
	b.WriteString("If one asks, scan the QR (browser: localhost below) or type:\r\n\r\n")
	ips := lanIPs()
	if len(ips) == 0 {
		b.WriteString(fmt.Sprintf("    http://localhost:%d\r\n", port))
	}
	for _, ip := range ips {
		b.WriteString(fmt.Sprintf("    http://%s:%d\r\n", ip, port))
	}
	b.WriteString(fmt.Sprintf("\r\nThis PC (status page):  http://localhost:%d\r\n", port))
	b.WriteString(fmt.Sprintf("Admin panel:            http://localhost:%d/admin\r\n", port))
	b.WriteString("\r\nKeep this window open while the shop is running.\r\nClose it to stop the server.")
	return b.String()
}
