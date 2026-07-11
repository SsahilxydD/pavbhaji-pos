package main

import (
	"database/sql"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

type MenuItem struct {
	ID        int64  `json:"id"`
	Name      string `json:"name"`
	Price     int64  `json:"price"` // paise
	Available bool   `json:"available"`
	Sort      int    `json:"sort"`
}

type Category struct {
	ID    int64      `json:"id"`
	Name  string     `json:"name"`
	Sort  int        `json:"sort"`
	Items []MenuItem `json:"items"`
}

type TableState struct {
	ID    int64       `json:"id"`
	Name  string      `json:"name"`
	Order *TableOrder `json:"order"` // nil = table is free
}

type TableOrder struct {
	OrderID      int64  `json:"orderId"`
	OrderNo      int64  `json:"orderNo"`
	OpenedAt     int64  `json:"openedAt"`
	OpenedByName string `json:"openedByName"`
	ItemCount    int64  `json:"itemCount"`
	PendingCount int64  `json:"pendingCount"`
	ReadyCount   int64  `json:"readyCount"`
	Total        int64  `json:"total"`
}

type OrderItem struct {
	ID           int64  `json:"id"`
	MenuItemID   int64  `json:"menuItemId"`
	Name         string `json:"name"`
	Price        int64  `json:"price"`
	Qty          int64  `json:"qty"`
	Note         string `json:"note"`
	Status       string `json:"status"`
	CancelReason string `json:"cancelReason,omitempty"`
}

type Ticket struct {
	ID            int64       `json:"id"`
	TicketNo      int64       `json:"ticketNo"`
	CreatedAt     int64       `json:"createdAt"`
	CreatedByName string      `json:"createdByName"`
	Items         []OrderItem `json:"items"`
}

type OrderDetail struct {
	ID           int64    `json:"id"`
	OrderNo      int64    `json:"orderNo"`
	BizDate      string   `json:"bizDate"`
	TableID      int64    `json:"tableId"`
	TableName    string   `json:"tableName"`
	Status       string   `json:"status"`
	OpenedAt     int64    `json:"openedAt"`
	OpenedByName string   `json:"openedByName"`
	Total        int64    `json:"total"`
	Tickets      []Ticket `json:"tickets"`
}

func (s *Server) loadMenu() []Category {
	cats := []Category{}
	rows, err := s.db.Query(`SELECT id, name, sort FROM categories WHERE active=1 ORDER BY sort, id`)
	if err != nil {
		return cats
	}
	defer rows.Close()
	for rows.Next() {
		var c Category
		rows.Scan(&c.ID, &c.Name, &c.Sort)
		c.Items = []MenuItem{}
		cats = append(cats, c)
	}
	for i := range cats {
		irows, err := s.db.Query(
			`SELECT id, name, price, available, sort FROM menu_items
			 WHERE active=1 AND category_id=? ORDER BY sort, id`, cats[i].ID)
		if err != nil {
			continue
		}
		for irows.Next() {
			var m MenuItem
			irows.Scan(&m.ID, &m.Name, &m.Price, &m.Available, &m.Sort)
			cats[i].Items = append(cats[i].Items, m)
		}
		irows.Close()
	}
	return cats
}

// GET /api/bootstrap — everything a freshly-opened app needs in one shot.
func (s *Server) handleBootstrap(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"me":         staffFrom(r),
		"menu":       s.loadMenu(),
		"tables":     s.loadTables(),
		"serverTime": nowMs(),
		"bizDate":    bizDate(time.Now(), s.dayStartHour),
	})
}

func (s *Server) handleMenu(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"menu": s.loadMenu()})
}

func (s *Server) loadTables() []TableState {
	out := []TableState{}
	rows, err := s.db.Query(`
		SELECT t.id, t.name,
		       o.id, o.order_no, o.opened_at, st.name,
		       (SELECT COALESCE(SUM(qty),0)        FROM ticket_items ti JOIN tickets tk ON tk.id=ti.ticket_id WHERE tk.order_id=o.id AND ti.status!='cancelled'),
		       (SELECT COALESCE(SUM(qty),0)        FROM ticket_items ti JOIN tickets tk ON tk.id=ti.ticket_id WHERE tk.order_id=o.id AND ti.status IN ('pending','preparing')),
		       (SELECT COALESCE(SUM(qty),0)        FROM ticket_items ti JOIN tickets tk ON tk.id=ti.ticket_id WHERE tk.order_id=o.id AND ti.status='ready'),
		       (SELECT COALESCE(SUM(qty*price),0)  FROM ticket_items ti JOIN tickets tk ON tk.id=ti.ticket_id WHERE tk.order_id=o.id AND ti.status!='cancelled')
		FROM dining_tables t
		LEFT JOIN orders o ON o.table_id = t.id AND o.status='open'
		LEFT JOIN staff st ON st.id = o.opened_by
		WHERE t.active=1
		ORDER BY t.sort, t.id`)
	if err != nil {
		return out
	}
	defer rows.Close()
	for rows.Next() {
		var ts TableState
		var oid, ono, oat, icount, pcount, rcount, total sql.NullInt64
		var oname sql.NullString
		rows.Scan(&ts.ID, &ts.Name, &oid, &ono, &oat, &oname, &icount, &pcount, &rcount, &total)
		if oid.Valid {
			ts.Order = &TableOrder{
				OrderID: oid.Int64, OrderNo: ono.Int64, OpenedAt: oat.Int64,
				OpenedByName: oname.String, ItemCount: icount.Int64,
				PendingCount: pcount.Int64, ReadyCount: rcount.Int64, Total: total.Int64,
			}
		}
		out = append(out, ts)
	}
	return out
}

func (s *Server) handleTables(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"tables": s.loadTables()})
}

func (s *Server) loadOrderDetail(orderID int64) (*OrderDetail, error) {
	var d OrderDetail
	err := s.db.QueryRow(`
		SELECT o.id, o.order_no, o.biz_date, o.table_id, t.name, o.status, o.opened_at, st.name
		FROM orders o JOIN dining_tables t ON t.id=o.table_id JOIN staff st ON st.id=o.opened_by
		WHERE o.id=?`, orderID).
		Scan(&d.ID, &d.OrderNo, &d.BizDate, &d.TableID, &d.TableName, &d.Status, &d.OpenedAt, &d.OpenedByName)
	if err != nil {
		return nil, err
	}
	d.Tickets = []Ticket{}
	rows, err := s.db.Query(`
		SELECT tk.id, tk.ticket_no, tk.created_at, st.name
		FROM tickets tk JOIN staff st ON st.id=tk.created_by
		WHERE tk.order_id=? ORDER BY tk.ticket_no`, orderID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var tk Ticket
		rows.Scan(&tk.ID, &tk.TicketNo, &tk.CreatedAt, &tk.CreatedByName)
		tk.Items = []OrderItem{}
		d.Tickets = append(d.Tickets, tk)
	}
	for i := range d.Tickets {
		irows, err := s.db.Query(`
			SELECT id, menu_item_id, name, price, qty, note, status, COALESCE(cancel_reason,'')
			FROM ticket_items WHERE ticket_id=? ORDER BY id`, d.Tickets[i].ID)
		if err != nil {
			continue
		}
		for irows.Next() {
			var it OrderItem
			irows.Scan(&it.ID, &it.MenuItemID, &it.Name, &it.Price, &it.Qty, &it.Note, &it.Status, &it.CancelReason)
			d.Tickets[i].Items = append(d.Tickets[i].Items, it)
			if it.Status != "cancelled" {
				d.Total += it.Price * it.Qty
			}
		}
		irows.Close()
	}
	return &d, nil
}

func (s *Server) handleOrderDetail(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	d, err := s.loadOrderDetail(id)
	if err != nil {
		fail(w, http.StatusNotFound, "order not found")
		return
	}
	writeJSON(w, http.StatusOK, d)
}

// GET /api/dining-tables/{id}/order — the open order on a table, or {"order": null}
func (s *Server) handleTableOrder(w http.ResponseWriter, r *http.Request) {
	tableID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var orderID int64
	err := s.db.QueryRow(`SELECT id FROM orders WHERE table_id=? AND status='open'`, tableID).Scan(&orderID)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"order": nil})
		return
	}
	d, err := s.loadOrderDetail(orderID)
	if err != nil {
		fail(w, http.StatusInternalServerError, "could not load order")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"order": d})
}

// POST /api/dining-tables/{id}/tickets
// Body: {clientTicketId, items:[{menuItemId, qty, note}]}
//
// This is THE endpoint number-accuracy hangs on:
//   - clientTicketId is generated on the phone before first send; retries reuse it.
//     A duplicate send returns the already-saved order instead of double-punching.
//   - Order numbers are assigned in the same transaction that inserts the ticket.
//   - Menu name+price are snapshotted into the items, so later price edits
//     never rewrite history.
func (s *Server) handleCreateTicket(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	tableID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		ClientTicketID string `json:"clientTicketId"`
		Items          []struct {
			MenuItemID int64  `json:"menuItemId"`
			Qty        int64  `json:"qty"`
			Note       string `json:"note"`
		} `json:"items"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.ClientTicketID = strings.TrimSpace(req.ClientTicketID)
	if len(req.ClientTicketID) < 8 {
		fail(w, http.StatusBadRequest, "clientTicketId required (min 8 chars)")
		return
	}
	if len(req.Items) == 0 || len(req.Items) > 100 {
		fail(w, http.StatusBadRequest, "a ticket needs between 1 and 100 lines")
		return
	}
	for _, it := range req.Items {
		if it.Qty < 1 || it.Qty > 50 {
			fail(w, http.StatusBadRequest, "quantity must be between 1 and 50")
			return
		}
		if len(it.Note) > 200 {
			fail(w, http.StatusBadRequest, "note too long")
			return
		}
	}

	// Idempotency replay: same clientTicketId → return the existing result, 200.
	var existingOrder int64
	err := s.db.QueryRow(`SELECT order_id FROM tickets WHERE client_ticket_id=?`, req.ClientTicketID).Scan(&existingOrder)
	if err == nil {
		d, err := s.loadOrderDetail(existingOrder)
		if err != nil {
			fail(w, http.StatusInternalServerError, "could not load order")
			return
		}
		writeJSON(w, http.StatusOK, d)
		return
	}

	tx, err := s.db.Begin()
	if err != nil {
		fail(w, http.StatusInternalServerError, "db busy, try again")
		return
	}
	defer tx.Rollback()

	var tName string
	if err := tx.QueryRow(`SELECT name FROM dining_tables WHERE id=? AND active=1`, tableID).Scan(&tName); err != nil {
		fail(w, http.StatusNotFound, "table not found")
		return
	}

	now := nowMs()
	bd := bizDate(time.Now(), s.dayStartHour)

	// Find or open the order for this table.
	var orderID, orderNo int64
	err = tx.QueryRow(`SELECT id, order_no FROM orders WHERE table_id=? AND status='open'`, tableID).Scan(&orderID, &orderNo)
	if err == sql.ErrNoRows {
		if err := tx.QueryRow(`SELECT COALESCE(MAX(order_no),0)+1 FROM orders WHERE biz_date=?`, bd).Scan(&orderNo); err != nil {
			fail(w, http.StatusInternalServerError, "could not assign order number")
			return
		}
		res, err := tx.Exec(`INSERT INTO orders(biz_date, order_no, table_id, status, opened_by, opened_at)
			VALUES(?,?,?,'open',?,?)`, bd, orderNo, tableID, st.ID, now)
		if err != nil {
			fail(w, http.StatusInternalServerError, "could not open order")
			return
		}
		orderID, _ = res.LastInsertId()
	} else if err != nil {
		fail(w, http.StatusInternalServerError, "db error")
		return
	}

	var ticketNo int64
	tx.QueryRow(`SELECT COALESCE(MAX(ticket_no),0)+1 FROM tickets WHERE order_id=?`, orderID).Scan(&ticketNo)
	res, err := tx.Exec(`INSERT INTO tickets(order_id, ticket_no, client_ticket_id, created_by, created_at)
		VALUES(?,?,?,?,?)`, orderID, ticketNo, req.ClientTicketID, st.ID, now)
	if err != nil {
		// UNIQUE(client_ticket_id) race: another retry landed first — replay it.
		tx.Rollback()
		var oid int64
		if s.db.QueryRow(`SELECT order_id FROM tickets WHERE client_ticket_id=?`, req.ClientTicketID).Scan(&oid) == nil {
			if d, err := s.loadOrderDetail(oid); err == nil {
				writeJSON(w, http.StatusOK, d)
				return
			}
		}
		fail(w, http.StatusInternalServerError, "could not save ticket")
		return
	}
	ticketID, _ := res.LastInsertId()

	var summary []string
	for _, it := range req.Items {
		var name string
		var price int64
		var available bool
		err := tx.QueryRow(`SELECT name, price, available FROM menu_items WHERE id=? AND active=1`,
			it.MenuItemID).Scan(&name, &price, &available)
		if err != nil {
			fail(w, http.StatusBadRequest, "menu item not found (was it just removed?)")
			return
		}
		if !available {
			fail(w, http.StatusConflict, name+" is marked out of stock")
			return
		}
		if _, err := tx.Exec(`INSERT INTO ticket_items(ticket_id, menu_item_id, name, price, qty, note, status, status_at, status_by)
			VALUES(?,?,?,?,?,?,'pending',?,?)`,
			ticketID, it.MenuItemID, name, price, it.Qty, strings.TrimSpace(it.Note), now, st.ID); err != nil {
			fail(w, http.StatusInternalServerError, "could not save items")
			return
		}
		summary = append(summary, fmt.Sprintf("%d× %s", it.Qty, name))
	}
	if _, err := tx.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		now, st.ID, st.Name, "ticket",
		fmt.Sprintf("%s · order #%d · KOT %d: %s", tName, orderNo, ticketNo, strings.Join(summary, ", "))); err != nil {
		fail(w, http.StatusInternalServerError, "could not write audit event")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(w, http.StatusInternalServerError, "could not commit")
		return
	}

	s.hub.Broadcast("tables", "kitchen")
	d, err := s.loadOrderDetail(orderID)
	if err != nil {
		fail(w, http.StatusInternalServerError, "saved but could not reload order")
		return
	}
	writeJSON(w, http.StatusCreated, d)
}

// Allowed status moves per role. Cancelling has its own endpoint + rules.
func canTransition(role, from, to string) bool {
	switch to {
	case "preparing":
		return (role == "chef" || role == "admin") && from == "pending"
	case "ready":
		return (role == "chef" || role == "admin") && (from == "pending" || from == "preparing")
	case "served":
		if role == "waiter" || role == "admin" {
			if from == "pending" || from == "ready" {
				return true // waiter can hand over drinks etc. straight away
			}
			return from == "preparing" && role == "admin"
		}
	}
	return false
}

// POST /api/items/{id}/status {to}
func (s *Server) handleItemStatus(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	itemID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		To string `json:"to"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	var from, name, tableName string
	var qty, orderID int64
	err := s.db.QueryRow(`
		SELECT ti.status, ti.name, ti.qty, o.id, t.name
		FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id
		JOIN dining_tables t ON t.id=o.table_id
		WHERE ti.id=? AND o.status='open'`, itemID).Scan(&from, &name, &qty, &orderID, &tableName)
	if err != nil {
		fail(w, http.StatusNotFound, "item not found on any open order")
		return
	}
	if from == req.To {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true}) // harmless double-tap
		return
	}
	if !canTransition(st.Role, from, req.To) {
		fail(w, http.StatusConflict, fmt.Sprintf("can't move %s from %s to %s as %s", name, from, req.To, st.Role))
		return
	}
	s.db.Exec(`UPDATE ticket_items SET status=?, status_at=?, status_by=? WHERE id=?`,
		req.To, nowMs(), st.ID, itemID)
	audit(s.db, st.ID, st.Name, "item:"+req.To, fmt.Sprintf("%s: %d× %s (%s → %s)", tableName, qty, name, from, req.To))
	s.hub.Broadcast("tables", "kitchen")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// POST /api/items/{id}/cancel {reason} — nothing is ever deleted, only cancelled.
// pending: waiter or admin. Once the kitchen has started (preparing/ready) or it
// was already served: admin only.
func (s *Server) handleItemCancel(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	itemID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Reason string `json:"reason"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.Reason = strings.TrimSpace(req.Reason)
	if len(req.Reason) < 2 {
		fail(w, http.StatusBadRequest, "a reason is required to cancel")
		return
	}
	var from, name, tableName string
	var qty int64
	err := s.db.QueryRow(`
		SELECT ti.status, ti.name, ti.qty, t.name
		FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id
		JOIN dining_tables t ON t.id=o.table_id
		WHERE ti.id=? AND o.status='open'`, itemID).Scan(&from, &name, &qty, &tableName)
	if err != nil {
		fail(w, http.StatusNotFound, "item not found on any open order")
		return
	}
	if from == "cancelled" {
		fail(w, http.StatusConflict, "already cancelled")
		return
	}
	allowed := from == "pending" && (st.Role == "waiter" || st.Role == "admin") ||
		from != "pending" && st.Role == "admin"
	if !allowed {
		fail(w, http.StatusForbidden, "only admin can cancel an item the kitchen already started")
		return
	}
	s.db.Exec(`UPDATE ticket_items SET status='cancelled', status_at=?, status_by=?, cancel_reason=? WHERE id=?`,
		nowMs(), st.ID, req.Reason, itemID)
	audit(s.db, st.ID, st.Name, "item:cancelled",
		fmt.Sprintf("%s: %d× %s (was %s) — %s", tableName, qty, name, from, req.Reason))
	s.hub.Broadcast("tables", "kitchen")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// POST /api/orders/{id}/serve-all — everything pending/ready becomes served.
// Items the kitchen is actively cooking (preparing) are NOT touched.
func (s *Server) handleServeAll(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	orderID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var tableName string
	err := s.db.QueryRow(`SELECT t.name FROM orders o JOIN dining_tables t ON t.id=o.table_id
		WHERE o.id=? AND o.status='open'`, orderID).Scan(&tableName)
	if err != nil {
		fail(w, http.StatusNotFound, "open order not found")
		return
	}
	res, _ := s.db.Exec(`UPDATE ticket_items SET status='served', status_at=?, status_by=?
		WHERE status IN ('pending','ready')
		AND ticket_id IN (SELECT id FROM tickets WHERE order_id=?)`, nowMs(), st.ID, orderID)
	n, _ := res.RowsAffected()
	if n > 0 {
		audit(s.db, st.ID, st.Name, "serve-all", fmt.Sprintf("%s: %d line(s) marked served", tableName, n))
		s.hub.Broadcast("tables", "kitchen")
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "updated": n})
}

// POST /api/orders/{id}/close — only when every line is served or cancelled.
// Otherwise 409 with a human-readable list of blockers. No force flag: the
// day-close report must reconcile, so we never fake item states.
func (s *Server) handleCloseOrder(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	orderID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)

	tx, err := s.db.Begin()
	if err != nil {
		fail(w, http.StatusInternalServerError, "db busy")
		return
	}
	defer tx.Rollback()

	var tableName string
	var orderNo int64
	err = tx.QueryRow(`SELECT t.name, o.order_no FROM orders o JOIN dining_tables t ON t.id=o.table_id
		WHERE o.id=? AND o.status='open'`, orderID).Scan(&tableName, &orderNo)
	if err != nil {
		fail(w, http.StatusNotFound, "open order not found")
		return
	}

	rows, err := tx.Query(`SELECT ti.qty, ti.name, ti.status FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		WHERE tk.order_id=? AND ti.status NOT IN ('served','cancelled')`, orderID)
	if err != nil {
		fail(w, http.StatusInternalServerError, "db error")
		return
	}
	var blockers []string
	for rows.Next() {
		var qty int64
		var name, status string
		rows.Scan(&qty, &name, &status)
		blockers = append(blockers, fmt.Sprintf("%d× %s — still %s", qty, name, status))
	}
	rows.Close()
	if len(blockers) > 0 {
		writeJSON(w, http.StatusConflict, apiError{
			Error:    "some items are not served or cancelled yet",
			Blockers: blockers,
		})
		return
	}

	var total int64
	tx.QueryRow(`SELECT COALESCE(SUM(qty*price),0) FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		WHERE tk.order_id=? AND ti.status!='cancelled'`, orderID).Scan(&total)

	now := nowMs()
	if _, err := tx.Exec(`UPDATE orders SET status='closed', closed_by=?, closed_at=? WHERE id=?`,
		st.ID, now, orderID); err != nil {
		fail(w, http.StatusInternalServerError, "could not close order")
		return
	}
	if _, err := tx.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		now, st.ID, st.Name, "order:closed",
		fmt.Sprintf("%s · order #%d closed · total ₹%d.%02d", tableName, orderNo, total/100, total%100)); err != nil {
		fail(w, http.StatusInternalServerError, "could not write audit event")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(w, http.StatusInternalServerError, "could not commit")
		return
	}
	s.hub.Broadcast("tables", "kitchen")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "total": total})
}

type KitchenTicket struct {
	TicketID  int64       `json:"ticketId"`
	OrderNo   int64       `json:"orderNo"`
	TableName string      `json:"tableName"`
	CreatedAt int64       `json:"createdAt"`
	Waiter    string      `json:"waiter"`
	Items     []OrderItem `json:"items"`
}

// GET /api/kitchen — tickets that still have anything to cook or hand over,
// plus the batch view: total pending quantity per dish across all tables.
func (s *Server) handleKitchen(w http.ResponseWriter, r *http.Request) {
	tickets := []KitchenTicket{}
	rows, err := s.db.Query(`
		SELECT tk.id, o.order_no, t.name, tk.created_at, st.name
		FROM tickets tk
		JOIN orders o ON o.id=tk.order_id AND o.status='open'
		JOIN dining_tables t ON t.id=o.table_id
		JOIN staff st ON st.id=tk.created_by
		WHERE EXISTS (SELECT 1 FROM ticket_items ti WHERE ti.ticket_id=tk.id
		              AND ti.status IN ('pending','preparing','ready'))
		ORDER BY tk.created_at`)
	if err == nil {
		for rows.Next() {
			var kt KitchenTicket
			rows.Scan(&kt.TicketID, &kt.OrderNo, &kt.TableName, &kt.CreatedAt, &kt.Waiter)
			kt.Items = []OrderItem{}
			tickets = append(tickets, kt)
		}
		rows.Close()
	}
	for i := range tickets {
		irows, err := s.db.Query(`SELECT id, menu_item_id, name, price, qty, note, status, ''
			FROM ticket_items WHERE ticket_id=? AND status IN ('pending','preparing','ready') ORDER BY id`,
			tickets[i].TicketID)
		if err != nil {
			continue
		}
		for irows.Next() {
			var it OrderItem
			irows.Scan(&it.ID, &it.MenuItemID, &it.Name, &it.Price, &it.Qty, &it.Note, &it.Status, &it.CancelReason)
			tickets[i].Items = append(tickets[i].Items, it)
		}
		irows.Close()
	}

	type batchLine struct {
		Name string `json:"name"`
		Qty  int64  `json:"qty"`
	}
	batch := []batchLine{}
	brows, err := s.db.Query(`
		SELECT ti.name, SUM(ti.qty) FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id AND o.status='open'
		WHERE ti.status IN ('pending','preparing')
		GROUP BY ti.name ORDER BY SUM(ti.qty) DESC`)
	if err == nil {
		for brows.Next() {
			var b batchLine
			brows.Scan(&b.Name, &b.Qty)
			batch = append(batch, b)
		}
		brows.Close()
	}
	writeJSON(w, http.StatusOK, map[string]any{"tickets": tickets, "batch": batch})
}
