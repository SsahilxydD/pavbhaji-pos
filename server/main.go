package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
)

func main() {
	var port int
	var dataDir string
	flag.IntVar(&port, "port", 8177, "port to listen on")
	flag.StringVar(&dataDir, "data", "", "data directory (default: 'data' next to the exe)")
	flag.Parse()

	if dataDir == "" {
		exe, err := os.Executable()
		if err != nil {
			exe = "."
		}
		dataDir = filepath.Join(filepath.Dir(exe), "data")
	}
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		log.Fatalf("cannot create data dir %s: %v", dataDir, err)
	}

	db := openDB(filepath.Join(dataDir, "pbpos.db"))
	seedIfEmpty(db)

	s := &Server{
		db:      db,
		hub:     newHub(),
		port:    port,
		dataDir: dataDir,
	}
	s.dayStartHour, _ = strconv.Atoi(getMeta(db, "day_start_hour", "6"))

	mux := http.NewServeMux()

	// Public
	mux.HandleFunc("GET /", s.handleStatus)
	mux.HandleFunc("GET /qr.png", s.handleQR)
	mux.HandleFunc("POST /api/login", s.handleLogin)

	// Any logged-in role
	mux.HandleFunc("POST /api/logout", s.requireRole("*", s.handleLogout))
	mux.HandleFunc("GET /api/bootstrap", s.requireRole("*", s.handleBootstrap))
	mux.HandleFunc("GET /api/menu", s.requireRole("*", s.handleMenu))
	mux.HandleFunc("GET /api/tables", s.requireRole("*", s.handleTables))
	mux.HandleFunc("GET /api/orders/{id}", s.requireRole("*", s.handleOrderDetail))
	mux.HandleFunc("GET /api/dining-tables/{id}/order", s.requireRole("*", s.handleTableOrder))
	mux.HandleFunc("GET /api/kitchen", s.requireRole("*", s.handleKitchen))
	mux.HandleFunc("GET /ws", s.requireRole("*", s.handleWS))

	// Taking orders and serving: waiters and admins
	mux.HandleFunc("POST /api/dining-tables/{id}/tickets", s.requireRole("waiter admin", s.handleCreateTicket))
	mux.HandleFunc("POST /api/orders/{id}/serve-all", s.requireRole("waiter admin", s.handleServeAll))
	mux.HandleFunc("POST /api/orders/{id}/close", s.requireRole("waiter admin", s.handleCloseOrder))

	// Item status: role rules are inside the handler (chef cooks, waiter serves)
	mux.HandleFunc("POST /api/items/{id}/status", s.requireRole("*", s.handleItemStatus))
	mux.HandleFunc("POST /api/items/{id}/cancel", s.requireRole("waiter admin", s.handleItemCancel))

	// Admin only
	mux.HandleFunc("POST /api/admin/categories", s.requireRole("admin", s.handleCategoryCreate))
	mux.HandleFunc("PATCH /api/admin/categories/{id}", s.requireRole("admin", s.handleCategoryPatch))
	mux.HandleFunc("POST /api/admin/menu-items", s.requireRole("admin", s.handleMenuItemCreate))
	mux.HandleFunc("PATCH /api/admin/menu-items/{id}", s.requireRole("admin", s.handleMenuItemPatch))
	mux.HandleFunc("GET /api/admin/tables", s.requireRole("admin", s.handleAdminTables))
	mux.HandleFunc("POST /api/admin/tables", s.requireRole("admin", s.handleTableCreate))
	mux.HandleFunc("PATCH /api/admin/tables/{id}", s.requireRole("admin", s.handleTablePatch))
	mux.HandleFunc("GET /api/admin/staff", s.requireRole("admin", s.handleStaffList))
	mux.HandleFunc("POST /api/admin/staff", s.requireRole("admin", s.handleStaffCreate))
	mux.HandleFunc("PATCH /api/admin/staff/{id}", s.requireRole("admin", s.handleStaffPatch))
	mux.HandleFunc("GET /api/admin/report", s.requireRole("admin", s.handleReport))
	mux.HandleFunc("GET /api/admin/audit", s.requireRole("admin", s.handleAudit))
	mux.HandleFunc("GET /api/admin/dashboard", s.requireRole("admin", s.handleDashboard))
	mux.HandleFunc("GET /api/admin/backup", s.requireRole("admin", s.handleBackup))

	go s.autoBackup()

	fmt.Println("──────────────────────────────────────────────")
	fmt.Println("  🍛 PavBhaji POS server")
	fmt.Printf("  data:   %s\n", dataDir)
	for _, ip := range lanIPs() {
		fmt.Printf("  phones connect to:  %s:%d\n", ip, port)
	}
	fmt.Printf("  status page:  http://localhost:%d\n", port)
	fmt.Println("  keep this window open while the shop is running")
	fmt.Println("──────────────────────────────────────────────")

	addr := fmt.Sprintf(":%d", port)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}
