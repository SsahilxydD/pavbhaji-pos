package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

// ---- Menu management ----

func (s *Server) handleCategoryCreate(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	var req struct {
		Name string `json:"name"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" {
		fail(w, http.StatusBadRequest, "name required")
		return
	}
	var maxSort int
	s.db.QueryRow(`SELECT COALESCE(MAX(sort),0)+1 FROM categories`).Scan(&maxSort)
	res, err := s.db.Exec(`INSERT INTO categories(name, sort) VALUES(?,?)`, req.Name, maxSort)
	if err != nil {
		fail(w, http.StatusInternalServerError, "could not create category")
		return
	}
	id, _ := res.LastInsertId()
	audit(s.db, st.ID, st.Name, "menu:category", "added category "+req.Name)
	s.hub.Broadcast("menu")
	writeJSON(w, http.StatusCreated, map[string]any{"id": id})
}

func (s *Server) handleCategoryPatch(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Name   *string `json:"name"`
		Sort   *int    `json:"sort"`
		Active *bool   `json:"active"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Name != nil {
		s.db.Exec(`UPDATE categories SET name=? WHERE id=?`, strings.TrimSpace(*req.Name), id)
	}
	if req.Sort != nil {
		s.db.Exec(`UPDATE categories SET sort=? WHERE id=?`, *req.Sort, id)
	}
	if req.Active != nil {
		s.db.Exec(`UPDATE categories SET active=? WHERE id=?`, boolInt(*req.Active), id)
	}
	audit(s.db, st.ID, st.Name, "menu:category", fmt.Sprintf("edited category %d", id))
	s.hub.Broadcast("menu")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func validStation(s string) bool { return s == "kitchen" || s == "counter" }

// Dish colours come from the admin's picker as #RRGGBB. Empty means
// "no colour set" and the app falls back to a stable palette pick.
var colourRe = regexp.MustCompile(`^#[0-9A-Fa-f]{6}$`)

func normColour(c string) (string, bool) {
	c = strings.ToUpper(strings.TrimSpace(c))
	if c != "" && !colourRe.MatchString(c) {
		return "", false
	}
	return c, true
}

func (s *Server) handleMenuItemCreate(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	var req struct {
		CategoryID int64  `json:"categoryId"`
		Name       string `json:"name"`
		Price      int64  `json:"price"` // paise
		Colour     string `json:"colour"`
		Station    string `json:"station"`
		// counter item that must wait for the table's bhaji before it goes out
		ServeWithFood bool `json:"serveWithFood"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" || req.Price < 0 || req.Price > 100000000 {
		fail(w, http.StatusBadRequest, "valid name and price required")
		return
	}
	if req.Station == "" {
		req.Station = "kitchen"
	}
	if !validStation(req.Station) {
		fail(w, http.StatusBadRequest, "station must be kitchen or counter")
		return
	}
	colour, ok := normColour(req.Colour)
	if !ok {
		fail(w, http.StatusBadRequest, "colour must look like #RRGGBB")
		return
	}
	req.Colour = colour
	var catName string
	if err := s.db.QueryRow(`SELECT name FROM categories WHERE id=? AND active=1`, req.CategoryID).Scan(&catName); err != nil {
		fail(w, http.StatusBadRequest, "category not found")
		return
	}
	var maxSort int
	s.db.QueryRow(`SELECT COALESCE(MAX(sort),0)+1 FROM menu_items WHERE category_id=?`, req.CategoryID).Scan(&maxSort)
	res, err := s.db.Exec(`INSERT INTO menu_items(category_id, name, price, sort, colour, station, serve_with_food) VALUES(?,?,?,?,?,?,?)`,
		req.CategoryID, req.Name, req.Price, maxSort, req.Colour, req.Station,
		boolInt(req.ServeWithFood && req.Station == "counter"))
	if err != nil {
		fail(w, http.StatusInternalServerError, "could not create item")
		return
	}
	id, _ := res.LastInsertId()
	audit(s.db, st.ID, st.Name, "menu:item",
		fmt.Sprintf("added %s (%s) at ₹%d.%02d", req.Name, catName, req.Price/100, req.Price%100))
	s.hub.Broadcast("menu")
	writeJSON(w, http.StatusCreated, map[string]any{"id": id})
}

func (s *Server) handleMenuItemPatch(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Name          *string `json:"name"`
		Price         *int64  `json:"price"`
		Available     *bool   `json:"available"`
		Active        *bool   `json:"active"`
		Sort          *int    `json:"sort"`
		Colour        *string `json:"colour"`
		Station       *string `json:"station"`
		ServeWithFood *bool   `json:"serveWithFood"`
		BatchCap      *int64  `json:"batchCap"`    // 0 = back to global default
		BatchAgeMin   *int64  `json:"batchAgeMin"` // 0 = back to global default
	}
	if !readJSON(w, r, &req) {
		return
	}
	var curName string
	var curPrice int64
	if err := s.db.QueryRow(`SELECT name, price FROM menu_items WHERE id=?`, id).Scan(&curName, &curPrice); err != nil {
		fail(w, http.StatusNotFound, "item not found")
		return
	}
	var changes []string
	if req.Name != nil && strings.TrimSpace(*req.Name) != "" && *req.Name != curName {
		s.db.Exec(`UPDATE menu_items SET name=? WHERE id=?`, strings.TrimSpace(*req.Name), id)
		changes = append(changes, fmt.Sprintf("renamed %s → %s", curName, *req.Name))
	}
	if req.Price != nil && *req.Price >= 0 && *req.Price != curPrice {
		s.db.Exec(`UPDATE menu_items SET price=? WHERE id=?`, *req.Price, id)
		changes = append(changes, fmt.Sprintf("%s price ₹%d.%02d → ₹%d.%02d",
			curName, curPrice/100, curPrice%100, *req.Price/100, *req.Price%100))
	}
	if req.Available != nil {
		s.db.Exec(`UPDATE menu_items SET available=? WHERE id=?`, boolInt(*req.Available), id)
		if *req.Available {
			changes = append(changes, curName+" back in stock")
		} else {
			changes = append(changes, curName+" marked out of stock")
		}
	}
	if req.Active != nil {
		s.db.Exec(`UPDATE menu_items SET active=? WHERE id=?`, boolInt(*req.Active), id)
		changes = append(changes, curName+" archived/unarchived")
	}
	if req.Sort != nil {
		s.db.Exec(`UPDATE menu_items SET sort=? WHERE id=?`, *req.Sort, id)
	}
	if req.Colour != nil {
		colour, ok := normColour(*req.Colour)
		if !ok {
			fail(w, http.StatusBadRequest, "colour must look like #RRGGBB")
			return
		}
		s.db.Exec(`UPDATE menu_items SET colour=? WHERE id=?`, colour, id)
		changes = append(changes, curName+" colour → "+colour)
	}
	if req.BatchCap != nil {
		n := *req.BatchCap
		if n < 0 || n > 99 {
			fail(w, http.StatusBadRequest, "tawa capacity must be 1–99 plates (0 = use default)")
			return
		}
		if n == 0 {
			s.db.Exec(`UPDATE menu_items SET batch_cap=NULL WHERE id=?`, id)
			changes = append(changes, curName+" tawa capacity → default")
		} else {
			s.db.Exec(`UPDATE menu_items SET batch_cap=? WHERE id=?`, n, id)
			changes = append(changes, fmt.Sprintf("%s tawa capacity → %d plates", curName, n))
		}
	}
	if req.BatchAgeMin != nil {
		n := *req.BatchAgeMin
		if n < 0 || n > 240 {
			fail(w, http.StatusBadRequest, "seal timer must be 1–240 minutes (0 = use default)")
			return
		}
		if n == 0 {
			s.db.Exec(`UPDATE menu_items SET batch_age_min=NULL WHERE id=?`, id)
			changes = append(changes, curName+" bucket seal timer → default")
		} else {
			s.db.Exec(`UPDATE menu_items SET batch_age_min=? WHERE id=?`, n, id)
			changes = append(changes, fmt.Sprintf("%s bucket seals after %d min", curName, n))
		}
	}
	if req.Station != nil {
		if !validStation(*req.Station) {
			fail(w, http.StatusBadRequest, "station must be kitchen or counter")
			return
		}
		s.db.Exec(`UPDATE menu_items SET station=? WHERE id=?`, *req.Station, id)
		if *req.Station == "counter" {
			changes = append(changes, curName+" → counter (waiter hands it over, skips the kitchen)")
		} else {
			// Kitchen items already travel through their bucket; the
			// wait-for-bhaji flag would be a no-op left lying around.
			s.db.Exec(`UPDATE menu_items SET serve_with_food=0 WHERE id=?`, id)
			changes = append(changes, curName+" → kitchen (cooked in colour buckets)")
		}
	}
	if req.ServeWithFood != nil {
		var station string
		s.db.QueryRow(`SELECT station FROM menu_items WHERE id=?`, id).Scan(&station)
		if station != "counter" {
			fail(w, http.StatusBadRequest, "only counter items can wait for the bhaji")
			return
		}
		s.db.Exec(`UPDATE menu_items SET serve_with_food=? WHERE id=?`, boolInt(*req.ServeWithFood), id)
		if *req.ServeWithFood {
			changes = append(changes, curName+" now waits for the table's bhaji")
		} else {
			changes = append(changes, curName+" goes out immediately again")
		}
	}
	if len(changes) > 0 {
		audit(s.db, st.ID, st.Name, "menu:item", strings.Join(changes, "; "))
	}
	s.hub.Broadcast("menu")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ---- Tables ----

func validTableKind(k string) bool { return k == "dine" || k == "parcel" }

func (s *Server) handleTableCreate(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	var req struct {
		Name string `json:"name"`
		Kind string `json:"kind"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" {
		fail(w, http.StatusBadRequest, "name required")
		return
	}
	if req.Kind == "" {
		req.Kind = "dine"
	}
	if !validTableKind(req.Kind) {
		fail(w, http.StatusBadRequest, "kind must be dine or parcel")
		return
	}
	var maxSort int
	s.db.QueryRow(`SELECT COALESCE(MAX(sort),0)+1 FROM dining_tables`).Scan(&maxSort)
	if _, err := s.db.Exec(`INSERT INTO dining_tables(name, sort, kind) VALUES(?,?,?)`, req.Name, maxSort, req.Kind); err != nil {
		fail(w, http.StatusConflict, "a table with that name already exists")
		return
	}
	audit(s.db, st.ID, st.Name, "tables", "added "+req.Kind+" table "+req.Name)
	s.hub.Broadcast("tables")
	writeJSON(w, http.StatusCreated, map[string]any{"ok": true})
}

func (s *Server) handleTablePatch(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Name   *string `json:"name"`
		Active *bool   `json:"active"`
		Sort   *int    `json:"sort"`
		Kind   *string `json:"kind"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Kind != nil {
		if !validTableKind(*req.Kind) {
			fail(w, http.StatusBadRequest, "kind must be dine or parcel")
			return
		}
		s.db.Exec(`UPDATE dining_tables SET kind=? WHERE id=?`, *req.Kind, id)
	}
	if req.Active != nil && !*req.Active {
		var open int
		s.db.QueryRow(`SELECT COUNT(*) FROM orders WHERE table_id=? AND status='open'`, id).Scan(&open)
		if open > 0 {
			fail(w, http.StatusConflict, "close the open order on this table first")
			return
		}
		s.db.Exec(`UPDATE dining_tables SET active=0 WHERE id=?`, id)
	}
	if req.Active != nil && *req.Active {
		s.db.Exec(`UPDATE dining_tables SET active=1 WHERE id=?`, id)
	}
	if req.Name != nil && strings.TrimSpace(*req.Name) != "" {
		if _, err := s.db.Exec(`UPDATE dining_tables SET name=? WHERE id=?`, strings.TrimSpace(*req.Name), id); err != nil {
			fail(w, http.StatusConflict, "a table with that name already exists")
			return
		}
	}
	if req.Sort != nil {
		s.db.Exec(`UPDATE dining_tables SET sort=? WHERE id=?`, *req.Sort, id)
	}
	audit(s.db, st.ID, st.Name, "tables", fmt.Sprintf("edited table %d", id))
	s.hub.Broadcast("tables")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// GET /api/admin/tables — includes inactive, for the admin screen
func (s *Server) handleAdminTables(w http.ResponseWriter, r *http.Request) {
	type row struct {
		ID     int64  `json:"id"`
		Name   string `json:"name"`
		Active bool   `json:"active"`
		Sort   int    `json:"sort"`
		Kind   string `json:"kind"`
	}
	out := []row{}
	rows, _ := s.db.Query(`SELECT id, name, active, sort, kind FROM dining_tables ORDER BY sort, id`)
	if rows != nil {
		for rows.Next() {
			var t row
			rows.Scan(&t.ID, &t.Name, &t.Active, &t.Sort, &t.Kind)
			out = append(out, t)
		}
		rows.Close()
	}
	writeJSON(w, http.StatusOK, map[string]any{"tables": out})
}

// ---- Staff ----

var pinRe = regexp.MustCompile(`^[0-9]{4}$`)

var staffRoles = map[string]bool{
	"admin": true, "manager": true, "waiter": true,
	"chef": true, "cashier": true, "parcel": true,
}

func (s *Server) handleStaffList(w http.ResponseWriter, r *http.Request) {
	out := []Staff{}
	rows, _ := s.db.Query(`SELECT id, name, role, active FROM staff ORDER BY active DESC, role, name`)
	if rows != nil {
		for rows.Next() {
			var st Staff
			rows.Scan(&st.ID, &st.Name, &st.Role, &st.Active)
			out = append(out, st)
		}
		rows.Close()
	}
	writeJSON(w, http.StatusOK, map[string]any{"staff": out})
}

func (s *Server) handleStaffCreate(w http.ResponseWriter, r *http.Request) {
	me := staffFrom(r)
	var req struct {
		Name string `json:"name"`
		Pin  string `json:"pin"`
		Role string `json:"role"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" {
		fail(w, http.StatusBadRequest, "name required")
		return
	}
	if !pinRe.MatchString(req.Pin) {
		fail(w, http.StatusBadRequest, "PIN must be 4 digits")
		return
	}
	if !staffRoles[req.Role] {
		fail(w, http.StatusBadRequest, "role must be admin, manager, waiter, chef, cashier or parcel")
		return
	}
	salt := getMeta(s.db, "pin_salt", "")
	if _, err := s.db.Exec(`INSERT INTO staff(name, pin_hash, role, created_at) VALUES(?,?,?,?)`,
		req.Name, hashPin(salt, req.Pin), req.Role, nowMs()); err != nil {
		fail(w, http.StatusConflict, "that PIN is already taken — pick another")
		return
	}
	audit(s.db, me.ID, me.Name, "staff", "added "+req.Role+" "+req.Name)
	writeJSON(w, http.StatusCreated, map[string]any{"ok": true})
}

func (s *Server) handleStaffPatch(w http.ResponseWriter, r *http.Request) {
	me := staffFrom(r)
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Name   *string `json:"name"`
		Pin    *string `json:"pin"`
		Role   *string `json:"role"`
		Active *bool   `json:"active"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	var curName string
	if err := s.db.QueryRow(`SELECT name FROM staff WHERE id=?`, id).Scan(&curName); err != nil {
		fail(w, http.StatusNotFound, "staff member not found")
		return
	}
	// Guard: never lock yourself out — the last active admin can't be
	// deactivated or demoted.
	demoting := (req.Active != nil && !*req.Active) || (req.Role != nil && *req.Role != "admin")
	if demoting {
		var isAdmin bool
		s.db.QueryRow(`SELECT role='admin' AND active=1 FROM staff WHERE id=?`, id).Scan(&isAdmin)
		if isAdmin {
			var admins int
			s.db.QueryRow(`SELECT COUNT(*) FROM staff WHERE role='admin' AND active=1`).Scan(&admins)
			if admins <= 1 {
				fail(w, http.StatusConflict, "cannot remove the last admin")
				return
			}
		}
	}
	if req.Name != nil && strings.TrimSpace(*req.Name) != "" {
		s.db.Exec(`UPDATE staff SET name=? WHERE id=?`, strings.TrimSpace(*req.Name), id)
	}
	if req.Pin != nil {
		if !pinRe.MatchString(*req.Pin) {
			fail(w, http.StatusBadRequest, "PIN must be 4 digits")
			return
		}
		salt := getMeta(s.db, "pin_salt", "")
		if _, err := s.db.Exec(`UPDATE staff SET pin_hash=? WHERE id=?`, hashPin(salt, *req.Pin), id); err != nil {
			fail(w, http.StatusConflict, "that PIN is already taken — pick another")
			return
		}
		s.db.Exec(`DELETE FROM sessions WHERE staff_id=?`, id)
		audit(s.db, me.ID, me.Name, "staff", "reset PIN for "+curName)
	}
	if req.Role != nil {
		if !staffRoles[*req.Role] {
			fail(w, http.StatusBadRequest, "invalid role")
			return
		}
		s.db.Exec(`UPDATE staff SET role=? WHERE id=?`, *req.Role, id)
		s.db.Exec(`DELETE FROM sessions WHERE staff_id=?`, id)
		audit(s.db, me.ID, me.Name, "staff", curName+" role changed to "+*req.Role)
	}
	if req.Active != nil {
		s.db.Exec(`UPDATE staff SET active=? WHERE id=?`, boolInt(*req.Active), id)
		if !*req.Active {
			s.db.Exec(`DELETE FROM sessions WHERE staff_id=?`, id)
			audit(s.db, me.ID, me.Name, "staff", "deactivated "+curName)
		} else {
			audit(s.db, me.ID, me.Name, "staff", "reactivated "+curName)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ---- Reports / audit / dashboard ----

// GET /api/admin/report?date=YYYY-MM-DD (defaults to today's business date)
func (s *Server) handleReport(w http.ResponseWriter, r *http.Request) {
	date := r.URL.Query().Get("date")
	if date == "" {
		date = bizDate(time.Now(), s.dayStartHour)
	}
	writeJSON(w, http.StatusOK, s.buildReport(date))
}

type reportItemLine struct {
	Name  string `json:"name"`
	Qty   int64  `json:"qty"`
	Value int64  `json:"value"`
}

func (s *Server) buildReport(date string) map[string]any {
	var opened, closed, stillOpen int64
	s.db.QueryRow(`SELECT COUNT(*) FROM orders WHERE biz_date=?`, date).Scan(&opened)
	s.db.QueryRow(`SELECT COUNT(*) FROM orders WHERE biz_date=? AND status='closed'`, date).Scan(&closed)
	stillOpen = opened - closed

	queryLines := func(status string) []reportItemLine {
		out := []reportItemLine{}
		rows, err := s.db.Query(`
			SELECT ti.name, SUM(ti.qty), SUM(ti.qty*ti.price)
			FROM ticket_items ti
			JOIN tickets tk ON tk.id=ti.ticket_id
			JOIN orders o ON o.id=tk.order_id
			WHERE o.biz_date=? AND ti.status=?
			GROUP BY ti.name ORDER BY SUM(ti.qty*ti.price) DESC`, date, status)
		if err != nil {
			return out
		}
		defer rows.Close()
		for rows.Next() {
			var l reportItemLine
			rows.Scan(&l.Name, &l.Qty, &l.Value)
			out = append(out, l)
		}
		return out
	}
	sold := queryLines("served")
	cancelled := queryLines("cancelled")

	var soldTotal, cancelledTotal, soldQty, cancelledQty int64
	for _, l := range sold {
		soldTotal += l.Value
		soldQty += l.Qty
	}
	for _, l := range cancelled {
		cancelledTotal += l.Value
		cancelledQty += l.Qty
	}

	type waiterLine struct {
		Name  string `json:"name"`
		Qty   int64  `json:"qty"`
		Value int64  `json:"value"`
	}
	waiters := []waiterLine{}
	wrows, err := s.db.Query(`
		SELECT st.name, SUM(ti.qty), SUM(ti.qty*ti.price)
		FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id
		JOIN staff st ON st.id=tk.created_by
		WHERE o.biz_date=? AND ti.status='served'
		GROUP BY st.name ORDER BY SUM(ti.qty*ti.price) DESC`, date)
	if err == nil {
		for wrows.Next() {
			var l waiterLine
			wrows.Scan(&l.Name, &l.Qty, &l.Value)
			waiters = append(waiters, l)
		}
		wrows.Close()
	}

	type cancelDetail struct {
		At     int64  `json:"at"`
		Detail string `json:"detail"`
		By     string `json:"by"`
	}
	cancelLog := []cancelDetail{}
	crows, err := s.db.Query(`
		SELECT at, staff_name, detail FROM events
		WHERE action='item:cancelled' AND at BETWEEN ? AND ?
		ORDER BY at DESC`, dayStartMs(date, s.dayStartHour), dayStartMs(date, s.dayStartHour)+24*3600*1000-1)
	if err == nil {
		for crows.Next() {
			var c cancelDetail
			crows.Scan(&c.At, &c.By, &c.Detail)
			cancelLog = append(cancelLog, c)
		}
		crows.Close()
	}

	// The drawer view: how the day's closed bills were actually settled.
	var paidCash, paidOnline, discountTotal int64
	s.db.QueryRow(`
		SELECT COALESCE(SUM(b.paid_cash),0), COALESCE(SUM(b.paid_online),0), COALESCE(SUM(b.discount),0)
		FROM bills b JOIN orders o ON o.id=b.order_id
		WHERE o.biz_date=? AND o.status='closed'`, date).Scan(&paidCash, &paidOnline, &discountTotal)

	return map[string]any{
		"date":           date,
		"ordersOpened":   opened,
		"ordersClosed":   closed,
		"ordersOpen":     stillOpen,
		"soldQty":        soldQty,
		"soldTotal":      soldTotal,
		"cancelledQty":   cancelledQty,
		"cancelledTotal": cancelledTotal,
		"paidCash":       paidCash,
		"paidOnline":     paidOnline,
		"discountTotal":  discountTotal,
		"sold":           sold,
		"cancelled":      cancelled,
		"waiters":        waiters,
		"cancelLog":      cancelLog,
	}
}

func dayStartMs(date string, dayStartHour int) int64 {
	t, err := time.ParseInLocation("2006-01-02", date, time.Local)
	if err != nil {
		return 0
	}
	return t.Add(time.Duration(dayStartHour) * time.Hour).UnixMilli()
}

// auditGroups: the owner filters the log by what KIND of thing happened, not
// by exact action strings. One action may sit in two groups (order:closed is
// both an order event and a billing event).
var auditGroups = map[string][]string{
	"orders":  {"ticket", "parcel", "order:closed"},
	"kitchen": {"batch:taken", "batch:ready", "batch:sealed"},
	"serving": {"item:served", "serve-all"},
	"cancel":  {"item:cancelled"},
	"billing": {"order:closed", "bill:discount", "bill-settings"},
	"staff":   {"staff", "login", "logout", "login:blocked", "seed"},
}

// GET /api/admin/audit?limit=100&beforeId=0&type=billing
func (s *Server) handleAudit(w http.ResponseWriter, r *http.Request) {
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	beforeID, _ := strconv.ParseInt(r.URL.Query().Get("beforeId"), 10, 64)
	q := `SELECT id, at, staff_name, action, detail FROM events`
	where := []string{}
	args := []any{}
	if beforeID > 0 {
		where = append(where, `id < ?`)
		args = append(args, beforeID)
	}
	if actions, ok := auditGroups[r.URL.Query().Get("type")]; ok {
		ph := make([]string, len(actions))
		for i, a := range actions {
			ph[i] = "?"
			args = append(args, a)
		}
		where = append(where, `action IN (`+strings.Join(ph, ",")+`)`)
	}
	if len(where) > 0 {
		q += ` WHERE ` + strings.Join(where, ` AND `)
	}
	q += ` ORDER BY id DESC LIMIT ?`
	args = append(args, limit)

	type ev struct {
		ID     int64  `json:"id"`
		At     int64  `json:"at"`
		By     string `json:"by"`
		Action string `json:"action"`
		Detail string `json:"detail"`
	}
	out := []ev{}
	rows, err := s.db.Query(q, args...)
	if err == nil {
		for rows.Next() {
			var e ev
			rows.Scan(&e.ID, &e.At, &e.By, &e.Action, &e.Detail)
			out = append(out, e)
		}
		rows.Close()
	}
	writeJSON(w, http.StatusOK, map[string]any{"events": out})
}

// GET /api/admin/dashboard — live numbers for today
func (s *Server) handleDashboard(w http.ResponseWriter, r *http.Request) {
	today := bizDate(time.Now(), s.dayStartHour)
	rep := s.buildReport(today)
	var kitchenPending int64
	s.db.QueryRow(`SELECT COALESCE(SUM(ti.qty),0) FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id AND o.status='open'
		WHERE ti.status IN ('pending','preparing')`).Scan(&kitchenPending)
	rep["kitchenPending"] = kitchenPending
	rep["devicesOnline"] = s.hub.Count()
	writeJSON(w, http.StatusOK, rep)
}

// GET /api/admin/backup — a consistent snapshot of the whole database.
// VACUUM INTO writes a clean copy even while the day is running.
func (s *Server) handleBackup(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	name := "pbpos-backup-" + time.Now().Format("20060102-150405") + ".db"
	tmp := filepath.Join(s.dataDir, name)
	if _, err := s.db.Exec(`VACUUM INTO ?`, tmp); err != nil {
		fail(w, http.StatusInternalServerError, "backup failed: "+err.Error())
		return
	}
	defer os.Remove(tmp)
	audit(s.db, st.ID, st.Name, "backup", "manual backup downloaded")
	w.Header().Set("Content-Disposition", `attachment; filename="`+name+`"`)
	w.Header().Set("Content-Type", "application/octet-stream")
	http.ServeFile(w, r, tmp)
}

// autoBackup writes a daily snapshot into data/backups/ at 23:45.
// One backup file per day, refreshed every 20 minutes — the old 23:45-only
// schedule silently produced ZERO backups in any shop that shuts the laptop
// at closing time. Now the worst case after a disk death is 20 minutes of
// orders, and each refresh also lands on any plugged-in pen drive, so
// "leave a stick in the laptop" = automatic second copy off the disk.
func (s *Server) autoBackup() {
	dir := filepath.Join(s.dataDir, "backups")
	os.MkdirAll(dir, 0o755)
	lastAuditDay := ""
	for {
		day := time.Now().Format("2006-01-02")
		name := filepath.Join(dir, "auto-"+day+".db")
		tmp := name + ".tmp"
		os.Remove(tmp)
		if _, err := s.db.Exec(`VACUUM INTO ?`, tmp); err == nil {
			os.Remove(name) // Windows cannot rename over an existing file
			if err := os.Rename(tmp, name); err == nil {
				if lastAuditDay != day {
					audit(s.db, 0, "system", "backup", "automatic backup running (refreshes every 20 min)")
					lastAuditDay = day
				}
				s.copyBackupToRemovable(name)
				pruneOldBackups(dir, 30)
			}
		} else {
			log.Printf("auto backup failed: %v", err)
		}
		time.Sleep(20 * time.Minute)
	}
}

// copyBackupToRemovable mirrors the newest backup onto every plugged-in pen
// drive (folder PavBhajiPOS-backups). Silent no-op when no drive is present;
// the status page shows when a stick last got a copy.
func (s *Server) copyBackupToRemovable(src string) {
	for _, root := range removableDirs() {
		destDir := filepath.Join(root, "PavBhajiPOS-backups")
		if err := os.MkdirAll(destDir, 0o755); err != nil {
			continue
		}
		dest := filepath.Join(destDir, filepath.Base(src))
		if err := copyFile(src, dest); err != nil {
			log.Printf("pen-drive backup to %s failed: %v", destDir, err)
			continue
		}
		setMeta(s.db, "last_usb_backup", fmt.Sprintf("%d", nowMs()))
		pruneOldBackups(destDir, 30)
	}
}

func copyFile(src, dest string) error {
	data, err := os.ReadFile(src)
	if err != nil {
		return err
	}
	tmp := dest + ".tmp"
	if err := os.WriteFile(tmp, data, 0o644); err != nil {
		return err
	}
	os.Remove(dest)
	return os.Rename(tmp, dest)
}

// pruneOldBackups keeps the newest `keep` auto-*.db files in dir.
func pruneOldBackups(dir string, keep int) {
	m, _ := filepath.Glob(filepath.Join(dir, "auto-*.db"))
	if len(m) <= keep {
		return
	}
	sort.Strings(m) // names embed the date, so lexical order = date order
	for _, old := range m[:len(m)-keep] {
		os.Remove(old)
	}
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
