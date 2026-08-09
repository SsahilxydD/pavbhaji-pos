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
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	Price       int64  `json:"price"` // paise
	Available   bool   `json:"available"`
	Sort        int    `json:"sort"`
	Colour      string `json:"colour"`      // dish identity hue, admin-picked ('' = app picks a fallback)
	Station     string `json:"station"`     // 'kitchen' = goes into colour buckets, 'counter' = waiter hands over
	BatchCap    *int64 `json:"batchCap"`    // tawa capacity override; nil = global default
	BatchAgeMin *int64 `json:"batchAgeMin"` // auto-seal minutes override; nil = global default
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
	Kind  string      `json:"kind"`  // 'dine' or 'parcel' (counter/takeaway slot)
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
	Station      string `json:"station"`
	Colour       string `json:"colour"`
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
			`SELECT id, name, price, available, sort, colour, station, batch_cap, batch_age_min FROM menu_items
			 WHERE active=1 AND category_id=? ORDER BY sort, id`, cats[i].ID)
		if err != nil {
			continue
		}
		for irows.Next() {
			var m MenuItem
			var bc, ba sql.NullInt64
			irows.Scan(&m.ID, &m.Name, &m.Price, &m.Available, &m.Sort, &m.Colour, &m.Station, &bc, &ba)
			if bc.Valid {
				m.BatchCap = &bc.Int64
			}
			if ba.Valid {
				m.BatchAgeMin = &ba.Int64
			}
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
		SELECT t.id, t.name, t.kind,
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
		rows.Scan(&ts.ID, &ts.Name, &ts.Kind, &oid, &ono, &oat, &oname, &icount, &pcount, &rcount, &total)
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
			SELECT ti.id, ti.menu_item_id, ti.name, ti.price, ti.qty, ti.note, ti.status,
			       COALESCE(ti.cancel_reason,''), ti.station, COALESCE(m.colour,'')
			FROM ticket_items ti LEFT JOIN menu_items m ON m.id=ti.menu_item_id
			WHERE ti.ticket_id=? ORDER BY ti.id`, d.Tickets[i].ID)
		if err != nil {
			continue
		}
		for irows.Next() {
			var it OrderItem
			irows.Scan(&it.ID, &it.MenuItemID, &it.Name, &it.Price, &it.Qty, &it.Note, &it.Status,
				&it.CancelReason, &it.Station, &it.Colour)
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

// batchCap is how many plates a colour bucket holds before it seals.
// Stored in meta so it can be tuned to the tawa without a rebuild; each dish
// can override it (menu_items.batch_cap) for smaller tawas / slow movers.
func (s *Server) batchCap() int64 {
	n, _ := strconv.ParseInt(getMeta(s.db, "batch_cap", "15"), 10, 64)
	if n < 1 {
		n = 15
	}
	return n
}

// batchAgeMin: an OPEN bucket older than this many minutes auto-seals as
// 'full' so the first order of a quiet hour never waits for a full tawa.
// Global default in meta; per-dish override in menu_items.batch_age_min.
func (s *Server) batchAgeMin() int64 {
	n, _ := strconv.ParseInt(getMeta(s.db, "batch_age_min", "10"), 10, 64)
	if n < 1 {
		n = 10
	}
	return n
}

// autoSealBatches runs forever: every 30s it seals open buckets that have
// aged past their dish's threshold, then pokes every kitchen screen.
func (s *Server) autoSealBatches() {
	for {
		time.Sleep(30 * time.Second)
		globalAge := s.batchAgeMin()
		now := nowMs()
		type sealRow struct {
			id  int64
			ago int64
			nm  string
		}
		var toSeal []sealRow
		rows, err := s.db.Query(`
			SELECT b.id, b.created_at, m.name
			FROM batches b JOIN menu_items m ON m.id=b.menu_item_id
			WHERE b.status='open'
			AND b.created_at + COALESCE(m.batch_age_min, ?)*60000 <= ?
			AND EXISTS (SELECT 1 FROM ticket_items ti
			            WHERE ti.batch_id=b.id AND ti.status='pending')`, globalAge, now)
		if err != nil {
			continue
		}
		for rows.Next() {
			var r sealRow
			var createdAt int64
			rows.Scan(&r.id, &createdAt, &r.nm)
			r.ago = (now - createdAt) / 60000
			toSeal = append(toSeal, r)
		}
		rows.Close()
		sealed := false
		for _, r := range toSeal {
			res, err := s.db.Exec(`UPDATE batches SET status='full' WHERE id=? AND status='open'`, r.id)
			if err != nil {
				continue
			}
			if n, _ := res.RowsAffected(); n > 0 {
				sealed = true
				audit(s.db, 0, "system", "batch:sealed",
					fmt.Sprintf("%s bucket #%d sealed after %d min — waiting for a chef", r.nm, r.id, r.ago))
			}
		}
		if sealed {
			s.hub.Broadcast("kitchen")
		}
	}
}

// assignBatch puts one kitchen line into its dish's colour bucket, inside the
// punch transaction. Rules (locked in the design spec):
//   - at most one OPEN bucket per dish; lines join it while the total stays ≤ cap
//   - a line never splits: if it doesn't fit, the open bucket seals as 'full'
//     and the whole line starts the next bucket
//   - reaching the cap seals the bucket immediately
func assignBatch(tx *sql.Tx, menuItemID, qty, cap, now int64) (int64, error) {
	newBucket := func() (int64, error) {
		res, err := tx.Exec(`INSERT INTO batches(menu_item_id, status, created_at) VALUES(?,'open',?)`, menuItemID, now)
		if err != nil {
			return 0, err
		}
		return res.LastInsertId()
	}
	var batchID, cur int64
	err := tx.QueryRow(`
		SELECT b.id, COALESCE((SELECT SUM(ti.qty) FROM ticket_items ti
		                       WHERE ti.batch_id=b.id AND ti.status!='cancelled'),0)
		FROM batches b WHERE b.menu_item_id=? AND b.status='open'
		ORDER BY b.id LIMIT 1`, menuItemID).Scan(&batchID, &cur)
	if err == sql.ErrNoRows {
		if batchID, err = newBucket(); err != nil {
			return 0, err
		}
		cur = 0
	} else if err != nil {
		return 0, err
	}
	if cur > 0 && cur+qty > cap {
		if _, err := tx.Exec(`UPDATE batches SET status='full' WHERE id=?`, batchID); err != nil {
			return 0, err
		}
		if batchID, err = newBucket(); err != nil {
			return 0, err
		}
		cur = 0
	}
	if cur+qty >= cap {
		if _, err := tx.Exec(`UPDATE batches SET status='full' WHERE id=?`, batchID); err != nil {
			return 0, err
		}
	}
	return batchID, nil
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
//   - Kitchen lines drop into their dish's colour bucket in the same transaction.
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

	// Read the global cap before Begin: with one DB connection, a query on s.db
	// while the transaction holds the connection would deadlock. Per-dish
	// overrides are COALESCEd in the item query inside the transaction.
	globalCap := s.batchCap()

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
		var name, station string
		var price, itemCap int64
		var available bool
		err := tx.QueryRow(`SELECT name, price, available, station, COALESCE(batch_cap, ?)
			FROM menu_items WHERE id=? AND active=1`,
			globalCap, it.MenuItemID).Scan(&name, &price, &available, &station, &itemCap)
		if err != nil {
			fail(w, http.StatusBadRequest, "menu item not found (was it just removed?)")
			return
		}
		if !available {
			fail(w, http.StatusConflict, name+" is marked out of stock")
			return
		}
		var batchID any // NULL for counter items — they never enter the kitchen
		if station == "kitchen" {
			id, err := assignBatch(tx, it.MenuItemID, it.Qty, itemCap, now)
			if err != nil {
				fail(w, http.StatusInternalServerError, "could not assign colour bucket")
				return
			}
			batchID = id
		}
		if _, err := tx.Exec(`INSERT INTO ticket_items(ticket_id, menu_item_id, name, price, qty, note, status, status_at, status_by, station, batch_id)
			VALUES(?,?,?,?,?,?,'pending',?,?,?,?)`,
			ticketID, it.MenuItemID, name, price, it.Qty, strings.TrimSpace(it.Note), now, st.ID, station, batchID); err != nil {
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
// A waiter can hand over a pending item only when it's a counter item
// (drinks etc.) — kitchen items travel through their colour bucket.
func canTransition(role, from, to, station string) bool {
	switch to {
	case "preparing":
		return (role == "chef" || role == "admin") && from == "pending"
	case "ready":
		return (role == "chef" || role == "admin") && (from == "pending" || from == "preparing")
	case "served":
		if role == "admin" {
			return from == "pending" || from == "preparing" || from == "ready"
		}
		if role == "waiter" || role == "manager" {
			if from == "ready" {
				return true
			}
			return from == "pending" && station == "counter"
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
	var from, name, tableName, station string
	var qty, orderID int64
	err := s.db.QueryRow(`
		SELECT ti.status, ti.name, ti.qty, o.id, t.name, ti.station
		FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id
		JOIN dining_tables t ON t.id=o.table_id
		WHERE ti.id=? AND o.status='open'`, itemID).Scan(&from, &name, &qty, &orderID, &tableName, &station)
	if err != nil {
		fail(w, http.StatusNotFound, "item not found on any open order")
		return
	}
	if from == req.To {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true}) // harmless double-tap
		return
	}
	if !canTransition(st.Role, from, req.To, station) {
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
// pending: manager or admin. Once the kitchen has started (preparing/ready) or it
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
	allowed := from == "pending" && (st.Role == "manager" || st.Role == "admin") ||
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

// ---- The chef's bucket queue ----

type BucketLine struct {
	TableName string `json:"tableName"`
	Qty       int64  `json:"qty"`
	Note      string `json:"note"`
}

type Bucket struct {
	ID          int64        `json:"id"`
	MenuItemID  int64        `json:"menuItemId"`
	Name        string       `json:"name"`
	Colour      string       `json:"colour"`
	Status      string       `json:"status"` // open | full | taken
	Cap         int64        `json:"cap"`    // this dish's tawa capacity
	Qty         int64        `json:"qty"`
	CreatedAt   int64        `json:"createdAt"`
	TakenAt     int64        `json:"takenAt,omitempty"`
	TakenBy     int64        `json:"takenBy,omitempty"`
	TakenByName string       `json:"takenByName,omitempty"`
	Lines       []BucketLine `json:"lines"`
}

// GET /api/kitchen — every bucket that still needs cooking, oldest first.
// Lines are the live-to-cook portion only (pending/preparing): cancelled or
// force-served lines drop out, and a bucket with nothing left to cook is hidden.
func (s *Server) handleKitchen(w http.ResponseWriter, r *http.Request) {
	globalCap := s.batchCap()
	buckets := []Bucket{}
	rows, err := s.db.Query(`
		SELECT b.id, b.menu_item_id, m.name, m.colour, b.status, COALESCE(m.batch_cap, ?), b.created_at,
		       COALESCE(b.taken_at,0), COALESCE(b.taken_by,0), COALESCE(st.name,'')
		FROM batches b
		JOIN menu_items m ON m.id=b.menu_item_id
		LEFT JOIN staff st ON st.id=b.taken_by
		WHERE b.status IN ('open','full','taken')
		ORDER BY b.created_at, b.id`, globalCap)
	if err == nil {
		for rows.Next() {
			var b Bucket
			rows.Scan(&b.ID, &b.MenuItemID, &b.Name, &b.Colour, &b.Status, &b.Cap, &b.CreatedAt,
				&b.TakenAt, &b.TakenBy, &b.TakenByName)
			b.Lines = []BucketLine{}
			buckets = append(buckets, b)
		}
		rows.Close()
	}
	out := buckets[:0]
	for i := range buckets {
		irows, err := s.db.Query(`
			SELECT dt.name, ti.qty, ti.note
			FROM ticket_items ti
			JOIN tickets tk ON tk.id=ti.ticket_id
			JOIN orders o ON o.id=tk.order_id
			JOIN dining_tables dt ON dt.id=o.table_id
			WHERE ti.batch_id=? AND ti.status IN ('pending','preparing')
			ORDER BY ti.id`, buckets[i].ID)
		if err != nil {
			continue
		}
		for irows.Next() {
			var l BucketLine
			irows.Scan(&l.TableName, &l.Qty, &l.Note)
			buckets[i].Lines = append(buckets[i].Lines, l)
			buckets[i].Qty += l.Qty
		}
		irows.Close()
		if buckets[i].Qty > 0 {
			out = append(out, buckets[i])
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"buckets": out, "cap": globalCap, "serverTime": nowMs(),
	})
}

// POST /api/batches/{id}/take — one chef claims the bucket, atomically.
// The status guard on the UPDATE is the lock: a second chef's tap matches
// zero rows and gets a 409 naming who holds it.
func (s *Server) handleBatchTake(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	batchID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	now := nowMs()

	tx, err := s.db.Begin()
	if err != nil {
		fail(w, http.StatusInternalServerError, "db busy, try again")
		return
	}
	defer tx.Rollback()

	// One bucket per chef: finish what you took before taking the next.
	// Only buckets with something left to cook count — a bucket emptied by
	// cancellations doesn't hold its chef hostage.
	var busyDish string
	err = tx.QueryRow(`
		SELECT m.name FROM batches b JOIN menu_items m ON m.id=b.menu_item_id
		WHERE b.status='taken' AND b.taken_by=?
		AND EXISTS (SELECT 1 FROM ticket_items ti
		            WHERE ti.batch_id=b.id AND ti.status IN ('pending','preparing'))
		LIMIT 1`, st.ID).Scan(&busyDish)
	if err == nil {
		fail(w, http.StatusConflict, "finish your "+busyDish+" bucket first — one bucket per chef")
		return
	}
	if err != sql.ErrNoRows {
		fail(w, http.StatusInternalServerError, "db error")
		return
	}

	res, err := tx.Exec(`UPDATE batches SET status='taken', taken_by=?, taken_at=?
		WHERE id=? AND status IN ('open','full')`, st.ID, now, batchID)
	if err != nil {
		fail(w, http.StatusInternalServerError, "db error")
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		var status, holder string
		err := tx.QueryRow(`SELECT b.status, COALESCE(stf.name,'') FROM batches b
			LEFT JOIN staff stf ON stf.id=b.taken_by WHERE b.id=?`, batchID).Scan(&status, &holder)
		if err != nil {
			fail(w, http.StatusNotFound, "bucket not found")
			return
		}
		if status == "taken" {
			fail(w, http.StatusConflict, holder+" already took this bucket — pick another colour")
		} else {
			fail(w, http.StatusConflict, "this bucket is already "+status)
		}
		return
	}
	// Everything in the bucket is now on the tawa.
	if _, err := tx.Exec(`UPDATE ticket_items SET status='preparing', status_at=?, status_by=?
		WHERE batch_id=? AND status='pending'`, now, st.ID, batchID); err != nil {
		fail(w, http.StatusInternalServerError, "could not mark items cooking")
		return
	}
	var dish string
	var qty int64
	tx.QueryRow(`SELECT m.name FROM batches b JOIN menu_items m ON m.id=b.menu_item_id WHERE b.id=?`, batchID).Scan(&dish)
	tx.QueryRow(`SELECT COALESCE(SUM(qty),0) FROM ticket_items WHERE batch_id=? AND status='preparing'`, batchID).Scan(&qty)
	if _, err := tx.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		now, st.ID, st.Name, "batch:taken", fmt.Sprintf("%s ×%d — %s is cooking bucket #%d", dish, qty, st.Name, batchID)); err != nil {
		fail(w, http.StatusInternalServerError, "could not write audit event")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(w, http.StatusInternalServerError, "could not commit")
		return
	}
	s.hub.Broadcast("tables", "kitchen")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// POST /api/batches/{id}/ready — the whole bucket is done: every live line
// flips to 'ready' stamped with this moment, which is exactly the order the
// waiter's pickup queue sorts by. Only the chef who took it (or admin) may finish it.
func (s *Server) handleBatchReady(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	batchID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	now := nowMs()

	tx, err := s.db.Begin()
	if err != nil {
		fail(w, http.StatusInternalServerError, "db busy, try again")
		return
	}
	defer tx.Rollback()

	var status, dish string
	var takenBy int64
	err = tx.QueryRow(`SELECT b.status, COALESCE(b.taken_by,0), m.name
		FROM batches b JOIN menu_items m ON m.id=b.menu_item_id WHERE b.id=?`, batchID).
		Scan(&status, &takenBy, &dish)
	if err != nil {
		fail(w, http.StatusNotFound, "bucket not found")
		return
	}
	if status != "taken" {
		fail(w, http.StatusConflict, "take the bucket first — it is currently "+status)
		return
	}
	if takenBy != st.ID && st.Role != "admin" {
		fail(w, http.StatusForbidden, "only the chef who took this bucket can finish it")
		return
	}
	if _, err := tx.Exec(`UPDATE batches SET status='ready', ready_at=? WHERE id=?`, now, batchID); err != nil {
		fail(w, http.StatusInternalServerError, "could not finish bucket")
		return
	}
	if _, err := tx.Exec(`UPDATE ticket_items SET status='ready', status_at=?, status_by=?
		WHERE batch_id=? AND status IN ('pending','preparing')`, now, st.ID, batchID); err != nil {
		fail(w, http.StatusInternalServerError, "could not mark items ready")
		return
	}
	var qty int64
	tx.QueryRow(`SELECT COALESCE(SUM(qty),0) FROM ticket_items WHERE batch_id=? AND status='ready'`, batchID).Scan(&qty)
	if _, err := tx.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		now, st.ID, st.Name, "batch:ready", fmt.Sprintf("%s ×%d ready — bucket #%d", dish, qty, batchID)); err != nil {
		fail(w, http.StatusInternalServerError, "could not write audit event")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(w, http.StatusInternalServerError, "could not commit")
		return
	}
	s.hub.Broadcast("tables", "kitchen")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "plates": qty})
}

// ---- The waiter's ready line ----

type PickupLine struct {
	ItemID    int64  `json:"itemId"`
	Name      string `json:"name"`
	Qty       int64  `json:"qty"`
	Note      string `json:"note"`
	Colour    string `json:"colour"`
	Station   string `json:"station"` // 'counter' lines appear the moment they're punched
	TableName string `json:"tableName"`
	SinceMs   int64  `json:"sinceMs"` // when it became carryable (ready_at, or punch time for counter)
	ServedBy  string `json:"servedBy"` // only on history lines: who carried it
}

// GET /api/pickup — everything a waiter should be carrying right now, as one
// flat queue sorted by time, not by table. Ready kitchen items plus pending
// counter items; a line disappears when it's marked served.
func (s *Server) handlePickup(w http.ResponseWriter, r *http.Request) {
	lines := []PickupLine{}
	rows, err := s.db.Query(`
		SELECT ti.id, ti.name, ti.qty, ti.note, ti.status_at, dt.name, COALESCE(m.colour,''), ti.station
		FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id AND o.status='open'
		JOIN dining_tables dt ON dt.id=o.table_id
		LEFT JOIN menu_items m ON m.id=ti.menu_item_id
		WHERE ti.status='ready' OR (ti.status='pending' AND ti.station='counter')
		ORDER BY ti.status_at, ti.id`)
	if err == nil {
		for rows.Next() {
			var l PickupLine
			rows.Scan(&l.ItemID, &l.Name, &l.Qty, &l.Note, &l.SinceMs, &l.TableName, &l.Colour, &l.Station)
			lines = append(lines, l)
		}
		rows.Close()
	}
	// The waiter's memory: what already went out in the last hour (newest
	// first, max 30), with who carried it. Closed tables stay visible — a
	// "did that plate go out?" doubt outlives the order.
	served := []PickupLine{}
	rows, err = s.db.Query(`
		SELECT ti.id, ti.name, ti.qty, ti.note, ti.status_at, dt.name, COALESCE(m.colour,''), ti.station,
		       COALESCE(st.name,'')
		FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id
		JOIN dining_tables dt ON dt.id=o.table_id
		LEFT JOIN menu_items m ON m.id=ti.menu_item_id
		LEFT JOIN staff st ON st.id=ti.status_by
		WHERE ti.status='served' AND ti.status_at > ?
		ORDER BY ti.status_at DESC, ti.id DESC LIMIT 30`, nowMs()-60*60*1000)
	if err == nil {
		for rows.Next() {
			var l PickupLine
			rows.Scan(&l.ItemID, &l.Name, &l.Qty, &l.Note, &l.SinceMs, &l.TableName, &l.Colour, &l.Station, &l.ServedBy)
			served = append(served, l)
		}
		rows.Close()
	}
	writeJSON(w, http.StatusOK, map[string]any{"lines": lines, "served": served, "serverTime": nowMs()})
}
