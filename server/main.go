package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strconv"
	"sync/atomic"
	"syscall"
	"time"
)

func main() {
	var port int
	var dataDir string
	var headless bool
	var worker bool
	flag.IntVar(&port, "port", 8177, "port to listen on")
	flag.StringVar(&dataDir, "data", "", "data directory (default: 'data' next to the exe)")
	flag.BoolVar(&headless, "headless", false, "run without the desktop window (console / service mode)")
	flag.BoolVar(&worker, "worker", false, "internal: run as the supervised worker process")
	flag.Parse()

	dataDir = resolveDataDir(dataDir)
	if !worker {
		supervise(dataDir) // never returns
	}
	run(port, dataDir, headless)
}

// The exe supervises itself: the process you start is a tiny parent that runs
// the real server as a -worker child and restarts it if it CRASHES (non-zero
// exit). Deliberate stops — window closed, Ctrl+C, kill — stop everything.
// This is what turns "the exe crashed at 7pm on a Saturday" from a shop
// outage into a 3-second blip nobody notices.
// It is also the only place that can swap the exe: a build downloaded during
// yesterday's service goes live here, before a single table is open.
func supervise(dataDir string) {
	// Resolve our own path once, before anything can rename it out from under us.
	exe, err := os.Executable()
	if err != nil {
		return // can't find ourselves: run unsupervised rather than not at all
	}
	if resolved, err := filepath.EvalSymlinks(exe); err == nil {
		exe = resolved
	}
	justUpdated := applyStagedUpdate(dataDir, exe)
	var child atomic.Pointer[os.Process]
	var stopping atomic.Bool
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		s := <-sig
		stopping.Store(true)
		if p := child.Load(); p != nil {
			p.Signal(s) // best effort; on Windows console events reach the child anyway
		}
	}()

	rapid := 0
	for {
		cmd := exec.Command(exe, append([]string{"-worker"}, os.Args[1:]...)...)
		cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
		started := time.Now()
		runErr := cmd.Start()
		if runErr == nil {
			child.Store(cmd.Process)
			runErr = cmd.Wait()
			child.Store(nil)
		}
		if !stopping.Load() && exitCodeOf(runErr) == updateExitCode {
			justUpdated = applyStagedUpdate(dataDir, exe)
			rapid = 0
			continue // asked for, not a crash: come straight back on the new build
		}
		if runErr == nil || stopping.Load() {
			os.Exit(0) // clean exit or requested stop — really stop
		}
		if time.Since(started) < 5*time.Second {
			rapid++
		} else {
			rapid, justUpdated = 0, false
		}
		// A brand-new build that dies on sight is the one failure that could
		// keep the shop shut, so it gets exactly one chance before the
		// previous build comes back.
		if rapid >= 3 && justUpdated && rollbackUpdate(exe) {
			justUpdated, rapid = false, 0
			continue
		}
		if rapid >= 5 {
			log.Println("server keeps dying immediately — giving up. Run it from a console to see the error.")
			os.Exit(1)
		}
		log.Printf("server stopped unexpectedly (%v) — restarting in 3s. Close the window / Ctrl+C to stop.", runErr)
		time.Sleep(3 * time.Second)
	}
}

// resolveDataDir returns the effective data directory: the -data override, or a
// "data" folder next to the executable.
func resolveDataDir(dataDir string) string {
	if dataDir == "" {
		exe, err := os.Executable()
		if err != nil {
			exe = "."
		}
		dataDir = filepath.Join(filepath.Dir(exe), "data")
	}
	return dataDir
}

// buildServer opens the database, seeds it, wires every route and starts the
// background jobs. It returns the ready-to-serve mux for the given port.
func buildServer(port int, dataDir string) (*Server, *http.ServeMux, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, nil, fmt.Errorf("cannot create data dir %s: %w", dataDir, err)
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
	s.serverID = ensureServerID(db)

	mux := http.NewServeMux()

	// Public
	// "/{$}" is the root exactly; "/" stays the catch-all, so the old LAN
	// status page still answers /anything-else while the shop's one public
	// link now opens the PIN gate.
	mux.HandleFunc("GET /{$}", s.handleWebLogin)
	mux.HandleFunc("GET /", s.handleStatus)
	mux.HandleFunc("GET /admin", s.handleWebAdmin)
	mux.HandleFunc("GET /manager", s.handleWebManager)
	mux.HandleFunc("GET /cashier", s.handleWebCashier)
	mux.HandleFunc("GET /pos.css", s.handleWebCSS)
	mux.HandleFunc("GET /qr.png", s.handleQR)
	mux.HandleFunc("GET /api/ping", s.handlePing)
	mux.HandleFunc("POST /api/login", s.handleLogin)

	// Any logged-in role
	mux.HandleFunc("POST /api/logout", s.requireRole("*", s.handleLogout))
	mux.HandleFunc("GET /api/bootstrap", s.requireRole("*", s.handleBootstrap))
	mux.HandleFunc("GET /api/menu", s.requireRole("*", s.handleMenu))
	mux.HandleFunc("GET /api/tables", s.requireRole("*", s.handleTables))
	mux.HandleFunc("GET /api/orders/{id}", s.requireRole("*", s.handleOrderDetail))
	mux.HandleFunc("GET /api/dining-tables/{id}/order", s.requireRole("*", s.handleTableOrder))
	mux.HandleFunc("GET /api/kitchen", s.requireRole("*", s.handleKitchen))
	mux.HandleFunc("GET /api/pickup", s.requireRole("*", s.handlePickup))
	mux.HandleFunc("GET /ws", s.requireRole("*", s.handleWS))

	// App updates: this machine holds the APK it pulled from the internet and
	// hands it to the phones over the shop WiFi. No phone needs a data pack.
	mux.HandleFunc("GET /api/app-update", s.requireRole("*", s.handleAppUpdate))
	mux.HandleFunc("GET /api/app-update.apk", s.requireRole("*", s.handleAppUpdateAPK))

	// Colour buckets: chefs take and finish them
	mux.HandleFunc("POST /api/batches/{id}/take", s.requireRole("chef admin", s.handleBatchTake))
	mux.HandleFunc("POST /api/batches/{id}/ready", s.requireRole("chef admin", s.handleBatchReady))
	mux.HandleFunc("POST /api/batches/{id}/release", s.requireRole("chef admin", s.handleBatchRelease))

	// Strict division of labour, enforced here and nowhere else:
	//   - punching: manager/admin (+ parcel manager, scoped to parcel tables)
	//   - serving: waiters ONLY, one plate per tap (no bulk serve exists)
	//   - settling & closing: the CASHIER only — close demands a generated
	//     bill whose payments (cash + online) add up to its total.
	mux.HandleFunc("POST /api/dining-tables/{id}/tickets", s.requireRole("manager admin parcel", s.handleCreateTicket))
	//   - changing table (customers moved, or two tables joined): the floor's
	//     job — manager and admin.
	mux.HandleFunc("POST /api/orders/{id}/move", s.requireRole("manager admin", s.handleMoveOrder))
	mux.HandleFunc("POST /api/orders/{id}/bill", s.requireRole("cashier", s.handleBillGenerate))
	mux.HandleFunc("PATCH /api/orders/{id}/bill", s.requireRole("cashier", s.handleBillPatch))
	mux.HandleFunc("POST /api/orders/{id}/close", s.requireRole("cashier", s.handleCloseOrder))

	// Settled-bill history: closed bills never vanish — cashier & owner read
	// them back (and reprint) any time.
	mux.HandleFunc("GET /api/bills", s.requireRole("cashier admin", s.handleBillsList))
	mux.HandleFunc("GET /api/orders/{id}/bill", s.requireRole("cashier admin", s.handleBillGet))

	// Parcels: the parcel manager's add-order flow (customer name + phone
	// ride on the order; a P-slot is allocated automatically).
	mux.HandleFunc("POST /api/parcels", s.requireRole("parcel manager admin", s.handleParcelCreate))

	// Printable bill — money-facing roles only (waiters/chefs never see prices).
	mux.HandleFunc("GET /bill/{id}", s.requireRole("cashier parcel manager admin", s.handleBillHTML))

	// Item status: role rules are inside the handler (chef cooks, waiter serves)
	mux.HandleFunc("POST /api/items/{id}/status", s.requireRole("*", s.handleItemStatus))
	mux.HandleFunc("POST /api/items/{id}/cancel", s.requireRole("manager admin parcel", s.handleItemCancel))

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
	mux.HandleFunc("GET /api/admin/bill-settings", s.requireRole("admin", s.handleBillSettings))
	mux.HandleFunc("POST /api/admin/bill-settings", s.requireRole("admin", s.handleBillSettings))

	mux.HandleFunc("GET /api/admin/update", s.requireRole("admin", s.handleUpdateStatus))
	mux.HandleFunc("POST /api/admin/update/check", s.requireRole("admin", s.handleUpdateCheck))
	mux.HandleFunc("POST /api/admin/update/apply", s.requireRole("admin", s.handleUpdateApply))

	go s.autoBackup()
	go s.autoSealBatches()
	go s.discoveryResponder()
	go s.autoUpdate()

	return s, mux, nil
}

// serveHeadless runs the server in the foreground with a console banner. Used on
// Linux, and on Windows when -headless is passed.
func serveHeadless(port int, dataDir string) {
	_, mux, err := buildServer(port, dataDir)
	if err != nil {
		log.Fatal(err)
	}

	fmt.Println("──────────────────────────────────────────────")
	fmt.Println("  🍛 PavBhaji POS server")
	fmt.Printf("  data:   %s\n", dataDir)
	for _, ip := range lanIPs() {
		fmt.Printf("  phones connect to:  %s:%d\n", ip, port)
	}
	fmt.Println("  (phones on this WiFi find the server by themselves)")
	fmt.Printf("  status page:  http://localhost:%d\n", port)
	fmt.Printf("  admin panel:  http://localhost:%d/admin\n", port)
	fmt.Println("  keep this window open while the shop is running")
	fmt.Println("──────────────────────────────────────────────")

	addr := fmt.Sprintf(":%d", port)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}
