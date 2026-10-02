package main

import (
	"database/sql"
	"encoding/json"
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
	Colour    string `json:"colour"`  // dish identity hue, admin-picked ('' = app picks a fallback)
	Station   string `json:"station"` // 'kitchen' = goes into colour buckets, 'counter' = waiter hands over
	// Counter item that may not be handed over while this table still has bhaji
	// on the tawa — extra pav, butter, onion. Meaningless for kitchen items.
	ServeWithFood bool   `json:"serveWithFood"`
	BatchCap      *int64 `json:"batchCap"`    // tawa capacity override; nil = global default
	BatchAgeMin   *int64 `json:"batchAgeMin"` // auto-seal minutes override; nil = global default
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
	OrderID       int64  `json:"orderId"`
	OrderNo       int64  `json:"orderNo"`
	OpenedAt      int64  `json:"openedAt"`
	OpenedByName  string `json:"openedByName"`
	ItemCount     int64  `json:"itemCount"`
	PendingCount  int64  `json:"pendingCount"`
	ReadyCount    int64  `json:"readyCount"`
	Total         int64  `json:"total"`
	CustomerName  string `json:"customerName"`  // parcel orders only
	CustomerPhone string `json:"customerPhone"` // parcel orders only
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
	ID            int64    `json:"id"`
	OrderNo       int64    `json:"orderNo"`
	BizDate       string   `json:"bizDate"`
	TableID       int64    `json:"tableId"`
	TableName     string   `json:"tableName"`
	Status        string   `json:"status"`
	OpenedAt      int64    `json:"openedAt"`
	OpenedByName  string   `json:"openedByName"`
	Total         int64    `json:"total"`
	CustomerName  string   `json:"customerName"`
	CustomerPhone string   `json:"customerPhone"`
	Tickets       []Ticket `json:"tickets"`
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
			`SELECT id, name, price, available, sort, colour, station, serve_with_food, batch_cap, batch_age_min FROM menu_items
			 WHERE active=1 AND category_id=? ORDER BY sort, id`, cats[i].ID)
		if err != nil {
			continue
		}
		for irows.Next() {
			var m MenuItem
			var bc, ba sql.NullInt64
			irows.Scan(&m.ID, &m.Name, &m.Price, &m.Available, &m.Sort, &m.Colour, &m.Station, &m.ServeWithFood, &bc, &ba)
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
		       o.id, o.order_no, o.opened_at, st.name, o.customer_name, o.customer_phone,
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
		var oname, cname, cphone sql.NullString
		rows.Scan(&ts.ID, &ts.Name, &ts.Kind, &oid, &ono, &oat, &oname, &cname, &cphone, &icount, &pcount, &rcount, &total)
		if oid.Valid {
			ts.Order = &TableOrder{
				OrderID: oid.Int64, OrderNo: ono.Int64, OpenedAt: oat.Int64,
				OpenedByName: oname.String, ItemCount: icount.Int64,
				PendingCount: pcount.Int64, ReadyCount: rcount.Int64, Total: total.Int64,
				CustomerName: cname.String, CustomerPhone: cphone.String,
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
		SELECT o.id, o.order_no, o.biz_date, o.table_id, t.name, o.status, o.opened_at, st.name,
		       o.customer_name, o.customer_phone
		FROM orders o JOIN dining_tables t ON t.id=o.table_id JOIN staff st ON st.id=o.opened_by
		WHERE o.id=?`, orderID).
		Scan(&d.ID, &d.OrderNo, &d.BizDate, &d.TableID, &d.TableName, &d.Status, &d.OpenedAt, &d.OpenedByName,
			&d.CustomerName, &d.CustomerPhone)
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

// assignBatch puts one kitchen line into its COLOUR's bucket, inside the
// punch transaction. Colour is the club: every bhaji variety sharing a colour
// cooks together in one bucket (a colourless dish gets a private 'item:<id>'
// key, so it never clubs). Rules (locked in the design spec):
//   - at most one OPEN bucket per colour; lines join it while the total stays ≤ cap
//   - a line never splits: if it doesn't fit, the open bucket seals as 'full'
//     and the whole line starts the next bucket
//   - reaching the cap seals the bucket immediately (cap = the incoming
//     line's dish cap — same-colour varieties share a tawa, so keep them equal)
func assignBatch(tx *sql.Tx, colourKey string, menuItemID, qty, cap, now int64) (int64, error) {
	newBucket := func() (int64, error) {
		res, err := tx.Exec(`INSERT INTO batches(menu_item_id, colour, status, created_at) VALUES(?,?,'open',?)`, menuItemID, colourKey, now)
		if err != nil {
			return 0, err
		}
		return res.LastInsertId()
	}
	var batchID, cur int64
	err := tx.QueryRow(`
		SELECT b.id, COALESCE((SELECT SUM(ti.qty) FROM ticket_items ti
		                       WHERE ti.batch_id=b.id AND ti.status!='cancelled'),0)
		FROM batches b WHERE b.colour=? AND b.status='open'
		ORDER BY b.id LIMIT 1`, colourKey).Scan(&batchID, &cur)
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

// ticketLine is one requested KOT line, shared by table tickets and parcels.
type ticketLine struct {
	MenuItemID int64  `json:"menuItemId"`
	Qty        int64  `json:"qty"`
	Note       string `json:"note"`
}

// validateTicketLines applies the sanity rules every new KOT must pass.
// Returns "" when the lines are acceptable.
func validateTicketLines(items []ticketLine) string {
	if len(items) == 0 || len(items) > 100 {
		return "a ticket needs between 1 and 100 lines"
	}
	for _, it := range items {
		if it.Qty < 1 || it.Qty > 50 {
			return "quantity must be between 1 and 50"
		}
		if len(it.Note) > 200 {
			return "note too long"
		}
	}
	return ""
}

// punchLines writes a ticket's lines inside the caller's transaction: menu
// name+price are snapshotted so later price edits never rewrite history, and
// kitchen lines drop into their dish's colour bucket. On failure it returns
// the HTTP status + message the caller should fail with.
func punchLines(tx *sql.Tx, ticketID, staffID int64, items []ticketLine, globalCap, now int64) (summary []string, status int, errMsg string) {
	for _, it := range items {
		var name, station, colour string
		var price, itemCap int64
		var available, withFood bool
		err := tx.QueryRow(`SELECT name, price, available, station, COALESCE(batch_cap, ?), colour, serve_with_food
			FROM menu_items WHERE id=? AND active=1`,
			globalCap, it.MenuItemID).Scan(&name, &price, &available, &station, &itemCap, &colour, &withFood)
		if err != nil {
			return nil, http.StatusBadRequest, "menu item not found (was it just removed?)"
		}
		if !available {
			return nil, http.StatusConflict, name + " is marked out of stock"
		}
		var batchID any // NULL for counter items — they never enter the kitchen
		if station == "kitchen" {
			colourKey := colour
			if colourKey == "" {
				colourKey = fmt.Sprintf("item:%d", it.MenuItemID)
			}
			id, err := assignBatch(tx, colourKey, it.MenuItemID, it.Qty, itemCap, now)
			if err != nil {
				return nil, http.StatusInternalServerError, "could not assign colour bucket"
			}
			batchID = id
		}
		// The wait-for-bhaji flag is snapshotted with the line: a menu edit
		// mid-service must not change what a waiter is already holding.
		if _, err := tx.Exec(`INSERT INTO ticket_items(ticket_id, menu_item_id, name, price, qty, note, status, status_at, status_by, station, serve_with_food, batch_id)
			VALUES(?,?,?,?,?,?,'pending',?,?,?,?,?)`,
			ticketID, it.MenuItemID, name, price, it.Qty, strings.TrimSpace(it.Note), now, staffID, station,
			boolInt(withFood && station == "counter"), batchID); err != nil {
			return nil, http.StatusInternalServerError, "could not save items"
		}
		summary = append(summary, fmt.Sprintf("%d× %s", it.Qty, name))
	}
	return summary, 0, ""
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
		ClientTicketID string       `json:"clientTicketId"`
		Items          []ticketLine `json:"items"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.ClientTicketID = strings.TrimSpace(req.ClientTicketID)
	if len(req.ClientTicketID) < 8 {
		fail(w, http.StatusBadRequest, "clientTicketId required (min 8 chars)")
		return
	}
	if msg := validateTicketLines(req.Items); msg != "" {
		fail(w, http.StatusBadRequest, msg)
		return
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

	var tName, tKind string
	if err := tx.QueryRow(`SELECT name, kind FROM dining_tables WHERE id=? AND active=1`, tableID).Scan(&tName, &tKind); err != nil {
		fail(w, http.StatusNotFound, "table not found")
		return
	}
	// The parcel manager's world is parcels only — dine-in tables are the
	// manager's job. (Enforced here, not in the UI.)
	if st.Role == "parcel" && tKind != "parcel" {
		fail(w, http.StatusForbidden, "the parcel manager can only punch parcel orders")
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

	summary, errStatus, errMsg := punchLines(tx, ticketID, st.ID, req.Items, globalCap, now)
	if errMsg != "" {
		fail(w, errStatus, errMsg)
		return
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

// POST /api/parcels {customerName, customerPhone, clientTicketId, items:[…]}
//
// The parcel manager's add-order flow. One transaction: pick a free parcel
// slot (auto-growing the P-pool when the shop is busier than it's ever been),
// open a fresh order carrying the customer's name/phone, punch the first KOT.
// Same idempotency contract as table tickets: a retry with the same
// clientTicketId returns the already-saved order instead of a duplicate.
func (s *Server) handleParcelCreate(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	var req struct {
		CustomerName   string       `json:"customerName"`
		CustomerPhone  string       `json:"customerPhone"`
		ClientTicketID string       `json:"clientTicketId"`
		Items          []ticketLine `json:"items"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	req.CustomerName = strings.TrimSpace(req.CustomerName)
	req.CustomerPhone = strings.TrimSpace(req.CustomerPhone)
	req.ClientTicketID = strings.TrimSpace(req.ClientTicketID)
	if req.CustomerName == "" {
		fail(w, http.StatusBadRequest, "customer name required")
		return
	}
	if len(req.CustomerName) > 60 || len(req.CustomerPhone) > 20 {
		fail(w, http.StatusBadRequest, "customer name or phone too long")
		return
	}
	if len(req.ClientTicketID) < 8 {
		fail(w, http.StatusBadRequest, "clientTicketId required (min 8 chars)")
		return
	}
	if msg := validateTicketLines(req.Items); msg != "" {
		fail(w, http.StatusBadRequest, msg)
		return
	}

	// Idempotency replay: same clientTicketId → the parcel already exists.
	var existingOrder int64
	if s.db.QueryRow(`SELECT order_id FROM tickets WHERE client_ticket_id=?`, req.ClientTicketID).Scan(&existingOrder) == nil {
		if d, err := s.loadOrderDetail(existingOrder); err == nil {
			writeJSON(w, http.StatusOK, d)
			return
		}
		fail(w, http.StatusInternalServerError, "could not load order")
		return
	}

	globalCap := s.batchCap()
	tx, err := s.db.Begin()
	if err != nil {
		fail(w, http.StatusInternalServerError, "db busy, try again")
		return
	}
	defer tx.Rollback()

	// A free active parcel slot, lowest first so P1 gets reused before P9.
	var tableID int64
	var tName string
	err = tx.QueryRow(`
		SELECT t.id, t.name FROM dining_tables t
		WHERE t.kind='parcel' AND t.active=1
		AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.table_id=t.id AND o.status='open')
		ORDER BY t.sort, t.id LIMIT 1`).Scan(&tableID, &tName)
	if err == sql.ErrNoRows {
		// Pool exhausted: mint the next P<n>. The name is UNIQUE, so walk
		// forward past any numbers the admin already used.
		var have int64
		tx.QueryRow(`SELECT COUNT(*) FROM dining_tables WHERE kind='parcel'`).Scan(&have)
		created := false
		for n := have + 1; n <= have+200; n++ {
			nm := fmt.Sprintf("P%d", n)
			if res, err := tx.Exec(`INSERT INTO dining_tables(name, sort, kind) VALUES(?,?,'parcel')`, nm, 100+n); err == nil {
				tableID, _ = res.LastInsertId()
				tName = nm
				created = true
				break
			}
		}
		if !created {
			fail(w, http.StatusInternalServerError, "could not create a parcel slot")
			return
		}
	} else if err != nil {
		fail(w, http.StatusInternalServerError, "db error")
		return
	}

	now := nowMs()
	bd := bizDate(time.Now(), s.dayStartHour)
	var orderNo int64
	if err := tx.QueryRow(`SELECT COALESCE(MAX(order_no),0)+1 FROM orders WHERE biz_date=?`, bd).Scan(&orderNo); err != nil {
		fail(w, http.StatusInternalServerError, "could not assign order number")
		return
	}
	res, err := tx.Exec(`INSERT INTO orders(biz_date, order_no, table_id, status, opened_by, opened_at, customer_name, customer_phone)
		VALUES(?,?,?,'open',?,?,?,?)`, bd, orderNo, tableID, st.ID, now, req.CustomerName, req.CustomerPhone)
	if err != nil {
		fail(w, http.StatusInternalServerError, "could not open order")
		return
	}
	orderID, _ := res.LastInsertId()

	res, err = tx.Exec(`INSERT INTO tickets(order_id, ticket_no, client_ticket_id, created_by, created_at)
		VALUES(?,1,?,?,?)`, orderID, req.ClientTicketID, st.ID, now)
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

	summary, errStatus, errMsg := punchLines(tx, ticketID, st.ID, req.Items, globalCap, now)
	if errMsg != "" {
		fail(w, errStatus, errMsg)
		return
	}
	who := req.CustomerName
	if req.CustomerPhone != "" {
		who += " (" + req.CustomerPhone + ")"
	}
	if _, err := tx.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		now, st.ID, st.Name, "parcel",
		fmt.Sprintf("🥡 %s · %s · order #%d: %s", who, tName, orderNo, strings.Join(summary, ", "))); err != nil {
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
		// Serving is the waiter's testimony that a plate physically went out.
		// Nobody else — not even admin — may write it, and there is no bulk
		// serve anywhere: one plate carried, one tap.
		if role == "waiter" {
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
	var from, name, tableName, tableKind, station string
	var qty, orderID int64
	err := s.db.QueryRow(`
		SELECT ti.status, ti.name, ti.qty, o.id, t.name, t.kind, ti.station
		FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id
		JOIN dining_tables t ON t.id=o.table_id
		WHERE ti.id=? AND o.status='open'`, itemID).Scan(&from, &name, &qty, &orderID, &tableName, &tableKind, &station)
	if err != nil {
		fail(w, http.StatusNotFound, "item not found on any open order")
		return
	}
	if st.Role == "parcel" && tableKind != "parcel" {
		fail(w, http.StatusForbidden, "the parcel manager can only touch parcel orders")
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
	// Extra pav and its kind wait at the counter. The app greys the row out, but
	// a stale phone must not be able to hand one over before ANY bhaji has left
	// the tawa — otherwise the pav lands cold, minutes before the food. The
	// first ready plate opens the gate; the rest of the table's bhajis don't
	// have to wait for each other.
	if req.To == "served" && from == "pending" && station == "counter" {
		var withFood bool
		var cooking, arrived int64
		s.db.QueryRow(`SELECT ti.serve_with_food,
			(SELECT COALESCE(SUM(k.qty),0) FROM ticket_items k
			   JOIN tickets ktk ON ktk.id=k.ticket_id
			  WHERE ktk.order_id=? AND k.station='kitchen'
			    AND k.status IN ('pending','preparing')),
			(SELECT COUNT(*) FROM ticket_items a
			   JOIN tickets atk ON atk.id=a.ticket_id
			  WHERE atk.order_id=? AND a.station='kitchen'
			    AND a.status IN ('ready','served'))
			FROM ticket_items ti WHERE ti.id=?`, orderID, orderID, itemID).Scan(&withFood, &cooking, &arrived)
		if withFood && cooking > 0 && arrived == 0 {
			fail(w, http.StatusConflict, fmt.Sprintf("%s goes out with the bhaji — nothing ready yet for %s (%d cooking)",
				name, tableName, cooking))
			return
		}
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
	var from, name, tableName, tableKind string
	var qty int64
	err := s.db.QueryRow(`
		SELECT ti.status, ti.name, ti.qty, t.name, t.kind
		FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		JOIN orders o ON o.id=tk.order_id
		JOIN dining_tables t ON t.id=o.table_id
		WHERE ti.id=? AND o.status='open'`, itemID).Scan(&from, &name, &qty, &tableName, &tableKind)
	if err != nil {
		fail(w, http.StatusNotFound, "item not found on any open order")
		return
	}
	if from == "cancelled" {
		fail(w, http.StatusConflict, "already cancelled")
		return
	}
	// Parcel manager: manager-like rights on pending lines, parcels only.
	allowed := from == "pending" && (st.Role == "manager" || st.Role == "admin" ||
		(st.Role == "parcel" && tableKind == "parcel")) ||
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

// POST /api/orders/{id}/move {"toTableId":N, "merge":bool}
//
// Customers get up and sit at another table without telling anyone (3 Oct
// 2026). Every screen reads a line's table through orders.table_id (the
// chef's bucket lines, the waiter's carry list, the cashier's grid, the
// printed bill), so re-pointing that one column moves the whole running
// order: its KOTs, the plates on the tawa, the bill and any payment taken.
//
// A free target is a plain move. An occupied one is a merge, and only when
// the caller asked for it (merge:true; otherwise 409): the source's KOTs are
// numbered on after the target's, its bill (discount, cash, online) is folded
// into the target's, and the emptied source order is deleted so the night's
// order count doesn't gain a ghost. Dine-in tables only; parcel slots stay put.
func (s *Server) handleMoveOrder(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	orderID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		ToTableID int64 `json:"toTableId"`
		Merge     bool  `json:"merge"`
	}
	if !readJSON(w, r, &req) {
		return
	}

	tx, err := s.db.Begin()
	if err != nil {
		fail(w, http.StatusInternalServerError, "db busy, try again")
		return
	}
	defer tx.Rollback()

	var fromTableID, orderNo, openedAt int64
	var fromName, fromKind string
	err = tx.QueryRow(`SELECT o.table_id, o.order_no, o.opened_at, t.name, t.kind
		FROM orders o JOIN dining_tables t ON t.id=o.table_id
		WHERE o.id=? AND o.status='open'`, orderID).Scan(&fromTableID, &orderNo, &openedAt, &fromName, &fromKind)
	if err != nil {
		fail(w, http.StatusNotFound, "open order not found")
		return
	}
	var toName, toKind string
	if err := tx.QueryRow(`SELECT name, kind FROM dining_tables WHERE id=? AND active=1`, req.ToTableID).
		Scan(&toName, &toKind); err != nil {
		fail(w, http.StatusNotFound, "table not found")
		return
	}
	if fromKind != "dine" || toKind != "dine" {
		fail(w, http.StatusBadRequest, "only dine-in tables can change table")
		return
	}
	if req.ToTableID == fromTableID {
		fail(w, http.StatusBadRequest, "the order is already on "+toName)
		return
	}

	now := nowMs()
	resultID := orderID
	var action, detail string
	var targetID, targetNo int64
	err = tx.QueryRow(`SELECT id, order_no FROM orders WHERE table_id=? AND status='open'`, req.ToTableID).
		Scan(&targetID, &targetNo)
	switch {
	case err == sql.ErrNoRows:
		if _, err := tx.Exec(`UPDATE orders SET table_id=? WHERE id=?`, req.ToTableID, orderID); err != nil {
			fail(w, http.StatusInternalServerError, "could not change the table")
			return
		}
		action = "order:moved"
		detail = fmt.Sprintf("order #%d · %s → %s", orderNo, fromName, toName)
	case err != nil:
		fail(w, http.StatusInternalServerError, "db error")
		return
	case !req.Merge:
		fail(w, http.StatusConflict, fmt.Sprintf("%s already has order #%d — merge the two?", toName, targetNo))
		return
	default:
		var lastKOT int64
		tx.QueryRow(`SELECT COALESCE(MAX(ticket_no),0) FROM tickets WHERE order_id=?`, targetID).Scan(&lastKOT)
		res, err := tx.Exec(`UPDATE tickets SET order_id=?, ticket_no=ticket_no+? WHERE order_id=?`,
			targetID, lastKOT, orderID)
		if err != nil {
			fail(w, http.StatusInternalServerError, "could not move the KOTs")
			return
		}
		kots, _ := res.RowsAffected()

		// The source's bill, if the cashier had started one, rides along:
		// re-pointed when the target has none, otherwise added into it.
		var disc, cash, online int64
		var note string
		berr := tx.QueryRow(`SELECT discount, discount_note, paid_cash, paid_online FROM bills WHERE order_id=?`, orderID).
			Scan(&disc, &note, &cash, &online)
		if berr != nil && berr != sql.ErrNoRows {
			fail(w, http.StatusInternalServerError, "db error")
			return
		}
		if berr == nil {
			var targetBills int
			tx.QueryRow(`SELECT COUNT(*) FROM bills WHERE order_id=?`, targetID).Scan(&targetBills)
			if targetBills == 0 {
				_, err = tx.Exec(`UPDATE bills SET order_id=? WHERE order_id=?`, targetID, orderID)
			} else {
				_, err = tx.Exec(`UPDATE bills SET
					discount = discount + ?,
					discount_note = CASE WHEN ?='' THEN discount_note WHEN discount_note='' THEN ? ELSE discount_note || ' + ' || ? END,
					paid_cash = paid_cash + ?, paid_online = paid_online + ?
					WHERE order_id=?`, disc, note, note, note, cash, online, targetID)
				if err == nil {
					_, err = tx.Exec(`DELETE FROM bills WHERE order_id=?`, orderID)
				}
			}
			if err != nil {
				fail(w, http.StatusInternalServerError, "could not carry the bill over")
				return
			}
		}
		// The target's bill (if any) now covers both tables' plates: refresh
		// it the way the cashier's own refresh does, discount clamped.
		var subtotal int64
		tx.QueryRow(`SELECT COALESCE(SUM(ti.qty*ti.price),0) FROM ticket_items ti
			JOIN tickets tk ON tk.id=ti.ticket_id
			WHERE tk.order_id=? AND ti.status!='cancelled'`, targetID).Scan(&subtotal)
		if _, err := tx.Exec(`UPDATE bills SET subtotal=?, discount=MIN(discount, ?),
			total=? - MIN(discount, ?), updated_at=? WHERE order_id=?`,
			subtotal, subtotal, subtotal, subtotal, now, targetID); err != nil {
			fail(w, http.StatusInternalServerError, "could not refresh the bill")
			return
		}
		// The table has been occupied since the earlier of the two.
		if _, err := tx.Exec(`UPDATE orders SET opened_at=MIN(opened_at, ?) WHERE id=?`, openedAt, targetID); err != nil {
			fail(w, http.StatusInternalServerError, "db error")
			return
		}
		if _, err := tx.Exec(`DELETE FROM orders WHERE id=?`, orderID); err != nil {
			fail(w, http.StatusInternalServerError, "could not merge the orders")
			return
		}
		resultID = targetID
		action = "order:merged"
		detail = fmt.Sprintf("order #%d (%s) merged into order #%d (%s) · %d KOT moved",
			orderNo, fromName, targetNo, toName, kots)
	}

	if _, err := tx.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		now, st.ID, st.Name, action, detail); err != nil {
		fail(w, http.StatusInternalServerError, "could not write audit event")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(w, http.StatusInternalServerError, "could not commit")
		return
	}
	s.hub.Broadcast("tables", "kitchen")
	d, err := s.loadOrderDetail(resultID)
	if err != nil {
		fail(w, http.StatusInternalServerError, "moved but could not reload the order")
		return
	}
	writeJSON(w, http.StatusOK, d)
}

// POST /api/orders/{id}/close — the cashier owns the table's exit. Unserved
// lines do NOT block it (25 Aug 2026): whatever is still pending/on the tawa
// when the money is settled gets force-served with the close, so the kitchen
// queue clears and the day report reconciles against the bill it was paid on.
// The audit event names every line that went out that way. The money gates
// (bill generated, subtotal fresh, payment adds up) still stand.
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
	var unserved []string
	for rows.Next() {
		var qty int64
		var name, status string
		rows.Scan(&qty, &name, &status)
		unserved = append(unserved, fmt.Sprintf("%d× %s (was %s)", qty, name, status))
	}
	rows.Close()

	var total int64
	tx.QueryRow(`SELECT COALESCE(SUM(qty*price),0) FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		WHERE tk.order_id=? AND ti.status!='cancelled'`, orderID).Scan(&total)

	// Settlement gate: closing needs a generated bill whose subtotal still
	// matches the live items, and payments that add up to its total. This is
	// what makes "close" mean "the money is actually in the drawer/phone".
	var bSubtotal, bDiscount, bTotal, bCash, bOnline int64
	err = tx.QueryRow(`SELECT subtotal, discount, total, paid_cash, paid_online FROM bills WHERE order_id=?`, orderID).
		Scan(&bSubtotal, &bDiscount, &bTotal, &bCash, &bOnline)
	if err != nil {
		fail(w, http.StatusConflict, "generate the bill first — closing needs a settled bill")
		return
	}
	if bSubtotal != total {
		fail(w, http.StatusConflict, "items changed after the bill was made — open the bill again to refresh it")
		return
	}
	if bCash+bOnline != bTotal {
		fail(w, http.StatusConflict, fmt.Sprintf("payment doesn't add up: %s cash + %s online ≠ bill total %s",
			billRupees(bCash), billRupees(bOnline), billRupees(bTotal)))
		return
	}

	now := nowMs()
	// Sweep whatever never made it out. Same shape as a cancel: the batch row
	// is left alone — an emptied bucket drops out of the chef's queue on its
	// own (qty 0) and never holds its chef hostage.
	if len(unserved) > 0 {
		if _, err := tx.Exec(`UPDATE ticket_items SET status='served', status_at=?, status_by=?
			WHERE ticket_id IN (SELECT id FROM tickets WHERE order_id=?)
			  AND status NOT IN ('served','cancelled')`, now, st.ID, orderID); err != nil {
			fail(w, http.StatusInternalServerError, "could not clear the unserved items")
			return
		}
	}
	if _, err := tx.Exec(`UPDATE orders SET status='closed', closed_by=?, closed_at=? WHERE id=?`,
		st.ID, now, orderID); err != nil {
		fail(w, http.StatusInternalServerError, "could not close order")
		return
	}
	detail := fmt.Sprintf("%s · order #%d closed · bill %s (cash %s + online %s",
		tableName, orderNo, billRupees(bTotal), billRupees(bCash), billRupees(bOnline))
	if bDiscount > 0 {
		detail += fmt.Sprintf(", discount %s", billRupees(bDiscount))
	}
	detail += ")"
	if len(unserved) > 0 {
		detail += fmt.Sprintf(" · closed with %d unserved line(s): %s",
			len(unserved), strings.Join(unserved, ", "))
	}
	if _, err := tx.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		now, st.ID, st.Name, "order:closed", detail); err != nil {
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
	ID         int64  `json:"id"` // the ticket_items row — a tick targets THIS line
	TableName  string `json:"tableName"`
	Qty        int64  `json:"qty"`
	Note       string `json:"note"`
	Name       string `json:"name"`       // the variety — one colour bucket can hold several
	MenuItemID int64  `json:"menuItemId"` // the variety's identity, for per-variety ticks
	Done       bool   `json:"done"`       // already flipped to ready (ticked off mid-bucket)
	Since      int64  `json:"since"`      // when its KOT was punched — the bucket's age is its oldest live line
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
		SELECT b.id, b.menu_item_id, m.name, m.colour, b.colour, b.status, COALESCE(m.batch_cap, ?), b.created_at,
		       COALESCE(b.taken_at,0), COALESCE(b.taken_by,0), COALESCE(st.name,'')
		FROM batches b
		JOIN menu_items m ON m.id=b.menu_item_id
		LEFT JOIN staff st ON st.id=b.taken_by
		WHERE b.status IN ('open','full','taken')
		ORDER BY b.created_at, b.id`, globalCap)
	if err == nil {
		for rows.Next() {
			var b Bucket
			var clubColour string
			rows.Scan(&b.ID, &b.MenuItemID, &b.Name, &b.Colour, &clubColour, &b.Status, &b.Cap, &b.CreatedAt,
				&b.TakenAt, &b.TakenBy, &b.TakenByName)
			// The club colour is the bucket's identity; 'item:<id>' sentinels
			// fall back to the opening dish's own colour.
			if clubColour != "" && !strings.HasPrefix(clubColour, "item:") {
				b.Colour = clubColour
			}
			b.Lines = []BucketLine{}
			buckets = append(buckets, b)
		}
		rows.Close()
	}
	out := buckets[:0]
	for i := range buckets {
		// Ready lines ride along flagged done: a taken bucket keeps showing a
		// ticked-off variety (greyed, ✓) until the whole bucket closes, so the
		// chef sees their tick land. Qty stays live-to-cook only — it drives
		// the fill bar, the ready-button count and the hide rule below.
		irows, err := s.db.Query(`
			SELECT ti.id, dt.name, ti.qty, ti.note, ti.name, ti.menu_item_id, ti.status='ready', tk.created_at
			FROM ticket_items ti
			JOIN tickets tk ON tk.id=ti.ticket_id
			JOIN orders o ON o.id=tk.order_id
			JOIN dining_tables dt ON dt.id=o.table_id
			WHERE ti.batch_id=? AND ti.status IN ('pending','preparing','ready')
			ORDER BY ti.id`, buckets[i].ID)
		if err != nil {
			continue
		}
		for irows.Next() {
			var l BucketLine
			irows.Scan(&l.ID, &l.TableName, &l.Qty, &l.Note, &l.Name, &l.MenuItemID, &l.Done, &l.Since)
			buckets[i].Lines = append(buckets[i].Lines, l)
			if !l.Done {
				buckets[i].Qty += l.Qty
			}
		}
		irows.Close()
		// Title = the varieties actually inside, so a clubbed bucket reads
		// "Pav Bhaji + Cheese Pav Bhaji", not just whichever dish opened it.
		seen := map[string]bool{}
		var names []string
		for _, l := range buckets[i].Lines {
			if !seen[l.Name] {
				seen[l.Name] = true
				names = append(names, l.Name)
			}
		}
		if len(names) > 0 {
			buckets[i].Name = strings.Join(names, " + ")
		}
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

// POST /api/batches/{id}/release — the chef steps away without finishing.
// The plates stay exactly where they are (still 'preparing' — they HAVE been
// on the tawa, so a waiter must not be able to cancel them behind the chef's
// back) and the bucket goes back to 'full': unlocked so anyone can pick it up
// again, sealed so no fresh line clubs into a half-cooked tawa. This is what
// frees the one-bucket-per-chef lock when a bhaji has to simmer for a while.
func (s *Server) handleBatchRelease(w http.ResponseWriter, r *http.Request) {
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
		fail(w, http.StatusConflict, "nobody is holding this bucket — it is "+status)
		return
	}
	if takenBy != st.ID && st.Role != "admin" {
		fail(w, http.StatusForbidden, "only the chef who took this bucket can leave it")
		return
	}

	if _, err := tx.Exec(`UPDATE batches SET status='full', taken_by=NULL, taken_at=NULL
		WHERE id=?`, batchID); err != nil {
		fail(w, http.StatusInternalServerError, "could not leave bucket")
		return
	}

	var qty int64
	tx.QueryRow(`SELECT COALESCE(SUM(qty),0) FROM ticket_items
		WHERE batch_id=? AND status IN ('pending','preparing')`, batchID).Scan(&qty)
	if _, err := tx.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
		now, st.ID, st.Name, "batch:released",
		fmt.Sprintf("%s ×%d — %s left bucket #%d for later", dish, qty, st.Name, batchID)); err != nil {
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

// POST /api/batches/{id}/ready — plates leave the tawa. With no body the whole
// bucket is done: every live line flips to 'ready' stamped with this moment,
// which is exactly the order the waiter's pickup queue sorts by. A clubbed
// bucket cooks several varieties side by side and they rarely finish together,
// so an optional {"menuItemId":N} ticks off just that variety — its plates go
// to the pass while the rest keep cooking, and the bucket closes by itself
// when the last variety is ticked. Finer still, {"ticketItemId":N} ticks ONE
// plate: a table that ordered 4 of a dish rarely gets all 4 off the tawa at
// the same second, so the chef sends them out one by one. Only the chef who
// took it (or admin) may finish any of it.
func (s *Server) handleBatchReady(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	batchID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	now := nowMs()

	// The body is optional (older calls send none) — a decode error means "whole bucket".
	var req struct {
		MenuItemID   int64 `json:"menuItemId"`
		TicketItemID int64 `json:"ticketItemId"`
	}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&req)
	}

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

	// Flip the requested slice: one plate, one variety, or everything still live.
	var flipped int64
	var plateVariety string
	if req.TicketItemID != 0 {
		// One plate off a line that may hold several. A line is one row with a
		// single status, so a partial tick SPLITS it: the plate that is done
		// keeps this row (and its id, which is what the waiter's pass sorts and
		// serves by), and the rest of the line is re-inserted as its own row,
		// still cooking, carrying the original status and timestamps so nothing
		// silently ages backwards. A tap on a plate already at the pass — a
		// double tap, or the other phone getting there first — matches no row
		// and is a harmless no-op.
		var qty int64
		err := tx.QueryRow(`SELECT qty, name FROM ticket_items
			WHERE id=? AND batch_id=? AND status IN ('pending','preparing')`,
			req.TicketItemID, batchID).Scan(&qty, &plateVariety)
		switch {
		case err == sql.ErrNoRows:
			// already gone to the pass — fall through, flipped stays 0
		case err != nil:
			fail(w, http.StatusInternalServerError, "could not read that line")
			return
		default:
			if qty > 1 {
				if _, err := tx.Exec(`INSERT INTO ticket_items
					(ticket_id, menu_item_id, name, price, qty, note, status, status_at, status_by, cancel_reason, station, batch_id)
					SELECT ticket_id, menu_item_id, name, price, ?, note, status, status_at, status_by, cancel_reason, station, batch_id
					FROM ticket_items WHERE id=?`, qty-1, req.TicketItemID); err != nil {
					fail(w, http.StatusInternalServerError, "could not split that line")
					return
				}
			}
			if _, err := tx.Exec(`UPDATE ticket_items SET qty=1, status='ready', status_at=?, status_by=?
				WHERE id=?`, now, st.ID, req.TicketItemID); err != nil {
				fail(w, http.StatusInternalServerError, "could not mark the plate ready")
				return
			}
			flipped = 1
		}
	} else {
		flip := `UPDATE ticket_items SET status='ready', status_at=?, status_by=?
			WHERE batch_id=? AND status IN ('pending','preparing')`
		args := []any{now, st.ID, batchID}
		if req.MenuItemID != 0 {
			flip += ` AND menu_item_id=?`
			args = append(args, req.MenuItemID)
		}
		res, err := tx.Exec(flip, args...)
		if err != nil {
			fail(w, http.StatusInternalServerError, "could not mark items ready")
			return
		}
		flipped, _ = res.RowsAffected()
	}

	// Anything left on the tawa? If not the bucket is finished, whichever
	// route got it there.
	var remaining int64
	tx.QueryRow(`SELECT COALESCE(SUM(qty),0) FROM ticket_items
		WHERE batch_id=? AND status IN ('pending','preparing')`, batchID).Scan(&remaining)
	if remaining == 0 {
		if _, err := tx.Exec(`UPDATE batches SET status='ready', ready_at=? WHERE id=?`, now, batchID); err != nil {
			fail(w, http.StatusInternalServerError, "could not finish bucket")
			return
		}
	}

	// A tick on an already-done variety (double tap, or a race with the other
	// phone) flips nothing: harmless, say ok and let the refresh catch up.
	if flipped > 0 {
		var qty int64
		action, detail := "batch:ready", ""
		if req.TicketItemID != 0 {
			action = "batch:plate-ready"
			if remaining > 0 {
				detail = fmt.Sprintf("%s ×1 ready — bucket #%d (%d plates still cooking)", plateVariety, batchID, remaining)
			} else {
				detail = fmt.Sprintf("%s ×1 ready — bucket #%d finished", plateVariety, batchID)
			}
		} else if req.MenuItemID != 0 {
			var variety string
			tx.QueryRow(`SELECT name FROM menu_items WHERE id=?`, req.MenuItemID).Scan(&variety)
			tx.QueryRow(`SELECT COALESCE(SUM(qty),0) FROM ticket_items
				WHERE batch_id=? AND menu_item_id=? AND status='ready'`, batchID, req.MenuItemID).Scan(&qty)
			if remaining > 0 {
				action = "batch:item-ready"
				detail = fmt.Sprintf("%s ×%d ready — bucket #%d (%d plates still cooking)", variety, qty, batchID, remaining)
			} else {
				detail = fmt.Sprintf("%s ×%d ready — bucket #%d finished", variety, qty, batchID)
			}
		} else {
			tx.QueryRow(`SELECT COALESCE(SUM(qty),0) FROM ticket_items WHERE batch_id=? AND status='ready'`, batchID).Scan(&qty)
			detail = fmt.Sprintf("%s ×%d ready — bucket #%d", dish, qty, batchID)
		}
		if _, err := tx.Exec(`INSERT INTO events(at, staff_id, staff_name, action, detail) VALUES(?,?,?,?,?)`,
			now, st.ID, st.Name, action, detail); err != nil {
			fail(w, http.StatusInternalServerError, "could not write audit event")
			return
		}
	}
	if err := tx.Commit(); err != nil {
		fail(w, http.StatusInternalServerError, "could not commit")
		return
	}
	s.hub.Broadcast("tables", "kitchen")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "plates": flipped, "remaining": remaining})
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
	// A counter line flagged serve-with-food, listed but not yet handable because
	// this table still has bhaji cooking. WaitingOn is how many plates are left.
	Waiting   bool   `json:"waiting"`
	WaitingOn int64  `json:"waitingOn"`
	ServedBy  string `json:"servedBy"` // only on history lines: who carried it
}

// GET /api/pickup — everything a waiter should be carrying right now, as one
// flat queue sorted by time, not by table. Ready kitchen items plus pending
// counter items; a line disappears when it's marked served.
func (s *Server) handlePickup(w http.ResponseWriter, r *http.Request) {
	lines := []PickupLine{}
	rows, err := s.db.Query(`
		SELECT ti.id, ti.name, ti.qty, ti.note, ti.status_at, dt.name, COALESCE(m.colour,''), ti.station,
		       ti.serve_with_food,
		       (SELECT COALESCE(SUM(k.qty),0) FROM ticket_items k
		          JOIN tickets ktk ON ktk.id=k.ticket_id
		         WHERE ktk.order_id=o.id AND k.station='kitchen'
		           AND k.status IN ('pending','preparing')),
		       (SELECT COUNT(*) FROM ticket_items a
		          JOIN tickets atk ON atk.id=a.ticket_id
		         WHERE atk.order_id=o.id AND a.station='kitchen'
		           AND a.status IN ('ready','served'))
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
			var withFood bool
			var cooking, arrived int64
			rows.Scan(&l.ItemID, &l.Name, &l.Qty, &l.Note, &l.SinceMs, &l.TableName, &l.Colour, &l.Station,
				&withFood, &cooking, &arrived)
			// Extra pav stays on the list so nobody forgets it, but it is not
			// handable until the table's FIRST bhaji leaves the tawa. Once one
			// plate has gone out the pav can ride along with it — waiting for
			// the last of three bhajis would leave the table eating dry.
			if withFood && l.Station == "counter" && cooking > 0 && arrived == 0 {
				l.Waiting = true
				l.WaitingOn = cooking
			}
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
