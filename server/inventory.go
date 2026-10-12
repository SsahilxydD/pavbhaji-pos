package main

import (
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// ---- Raw-material inventory ("in-house stock") ----
//
// What the shop owner asked for on 12 Oct 2026: see how much cheese, paneer
// and pav is in the shop and what to order for tomorrow. Deliberately manual:
// nothing is deducted by sales, there are no recipes and no prices. Staff
// record three kinds of move (in = maal aaya, out = use hua, count = ginti)
// and the order list is simply every item below its par level.
//
// Quantities are integers in THOUSANDTHS of the unit on the wire and in the
// database (2.5 kg = 2500), the same way money is paise, so 0.25 kg of butter
// never turns into a float.

// inventoryUnits is the pick list the clients offer. The column itself is
// free text (1–12 characters), so an odd unit typed by hand still saves.
var inventoryUnits = []string{"kg", "g", "L", "ml", "pcs", "packet", "ladi", "dozen", "box", "bottle", "tin"}

// No quantity or par level may pass 1,000,000 units: far beyond any shop, and
// it keeps a slipped finger (or a client sending whole units as thousandths
// twice over) from storing nonsense.
const inventoryMaxQty = 1_000_000 * 1000

type inventoryItem struct {
	ID            int64  `json:"id"`
	Name          string `json:"name"`
	Unit          string `json:"unit"`
	Qty           int64  `json:"qty"`    // thousandths
	Par           int64  `json:"par"`    // thousandths; 0 = no order level set
	Short         int64  `json:"short"`  // how much to buy to be back at par
	Status        string `json:"status"` // out | low | ok
	Active        bool   `json:"active"`
	Sort          int    `json:"sort"`
	UpdatedAt     int64  `json:"updatedAt"`
	UpdatedBy     string `json:"updatedBy"`
	UsedToday     int64  `json:"usedToday"`
	ReceivedToday int64  `json:"receivedToday"`
}

// inventoryStatus: out when nothing is in hand, low when under par, else ok.
// short is what the order list asks for. With par = 0 it is always 0, so an
// item with no order level never shows up in "Kal ka order".
func inventoryStatus(qty, par int64) (status string, short int64) {
	switch {
	case qty <= 0:
		status = "out"
	case qty < par:
		status = "low"
	default:
		status = "ok"
	}
	if par > qty {
		short = par - qty
	}
	return status, short
}

// fmtQty prints thousandths the way the clients do: at most 3 decimals,
// trailing zeros dropped (2500 → "2.5", 12000 → "12", 250 → "0.25").
func fmtQty(milli int64) string {
	sign := ""
	if milli < 0 {
		sign, milli = "-", -milli
	}
	out := strings.TrimRight(fmt.Sprintf("%d.%03d", milli/1000, milli%1000), "0")
	return sign + strings.TrimSuffix(out, ".")
}

func inventoryName(v string) (string, bool) {
	v = strings.TrimSpace(v)
	n := utf8.RuneCountInString(v)
	return v, n >= 1 && n <= 40
}

func inventoryUnit(v string) (string, bool) {
	v = strings.TrimSpace(v)
	n := utf8.RuneCountInString(v)
	return v, n >= 1 && n <= 12
}

func validInventoryQty(q int64) bool { return q >= 0 && q <= inventoryMaxQty }

// inventoryItems loads items with everything a row on the screen needs.
// usedToday / receivedToday come from today's moves by the SIGN of the
// change, so a count that found less than expected shows up as used and the
// opening count of a new item as received.
func (s *Server) inventoryItems(where string, args ...any) ([]inventoryItem, error) {
	today := bizDate(time.Now(), s.dayStartHour)
	rows, err := s.db.Query(`SELECT i.id, i.name, i.unit, i.qty, i.par, i.active, i.sort, i.updated_at,
		COALESCE(st.name,''),
		COALESCE((SELECT -SUM(m.delta) FROM inventory_moves m
			WHERE m.item_id=i.id AND m.biz_date=? AND m.delta<0), 0),
		COALESCE((SELECT SUM(m.delta) FROM inventory_moves m
			WHERE m.item_id=i.id AND m.biz_date=? AND m.delta>0), 0)
		FROM inventory_items i LEFT JOIN staff st ON st.id=i.updated_by
		WHERE `+where+`
		ORDER BY i.sort, i.name COLLATE NOCASE, i.id`, append([]any{today, today}, args...)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []inventoryItem{}
	for rows.Next() {
		var it inventoryItem
		var active int
		if err := rows.Scan(&it.ID, &it.Name, &it.Unit, &it.Qty, &it.Par, &active, &it.Sort, &it.UpdatedAt,
			&it.UpdatedBy, &it.UsedToday, &it.ReceivedToday); err != nil {
			return nil, err
		}
		it.Active = active == 1
		it.Status, it.Short = inventoryStatus(it.Qty, it.Par)
		out = append(out, it)
	}
	return out, rows.Err()
}

// writeInventoryItem answers a change with the item as the list would show it,
// so the client can swap its row without a second request.
func (s *Server) writeInventoryItem(w http.ResponseWriter, code int, id int64) {
	items, err := s.inventoryItems(`i.id=?`, id)
	if err != nil || len(items) != 1 {
		fail(w, http.StatusInternalServerError, "saved but could not reload the item")
		return
	}
	writeJSON(w, code, items[0])
}

// GET /api/inventory[?all=1] — the live list; all=1 adds the removed items.
func (s *Server) handleInventoryList(w http.ResponseWriter, r *http.Request) {
	where := `i.active=1`
	if r.URL.Query().Get("all") == "1" {
		where = `1=1`
	}
	items, err := s.inventoryItems(where)
	if err != nil {
		fail(w, http.StatusInternalServerError, "db error")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": items, "units": inventoryUnits})
}

// POST /api/inventory {name, unit, qty, par} — a new raw material. Two live
// items may not share a name (case-insensitive); a removed one does not block
// its name, so "Cheese" can be added again after it was taken off the list.
func (s *Server) handleInventoryCreate(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	var req struct {
		Name string `json:"name"`
		Unit string `json:"unit"`
		Qty  int64  `json:"qty"` // thousandths
		Par  int64  `json:"par"` // thousandths
	}
	if !readJSON(w, r, &req) {
		return
	}
	name, ok := inventoryName(req.Name)
	if !ok {
		fail(w, http.StatusBadRequest, "name must be 1–40 characters")
		return
	}
	unit, ok := inventoryUnit(req.Unit)
	if !ok {
		fail(w, http.StatusBadRequest, "unit must be 1–12 characters")
		return
	}
	if !validInventoryQty(req.Qty) || !validInventoryQty(req.Par) {
		fail(w, http.StatusBadRequest, "quantity must be between 0 and 1,000,000")
		return
	}

	tx, err := s.db.Begin()
	if err != nil {
		fail(w, http.StatusInternalServerError, "db busy, try again")
		return
	}
	defer tx.Rollback()

	var dup int
	tx.QueryRow(`SELECT COUNT(*) FROM inventory_items WHERE active=1 AND name=? COLLATE NOCASE`, name).Scan(&dup)
	if dup > 0 {
		fail(w, http.StatusConflict, name+" is already in the inventory")
		return
	}
	now := nowMs()
	res, err := tx.Exec(`INSERT INTO inventory_items(name, unit, qty, par, updated_at, updated_by) VALUES(?,?,?,?,?,?)`,
		name, unit, req.Qty, req.Par, now, st.ID)
	if err != nil {
		fail(w, http.StatusInternalServerError, "could not add the item")
		return
	}
	id, _ := res.LastInsertId()
	// The opening quantity goes in as a count, so the item's history starts
	// from a real number instead of appearing out of nowhere.
	if req.Qty > 0 {
		if _, err := tx.Exec(`INSERT INTO inventory_moves(item_id, biz_date, kind, delta, qty_after, by_staff, at)
			VALUES(?,?,'count',?,?,?,?)`,
			id, bizDate(time.Now(), s.dayStartHour), req.Qty, req.Qty, st.ID, now); err != nil {
			fail(w, http.StatusInternalServerError, "could not record the opening count")
			return
		}
	}
	if err := tx.Commit(); err != nil {
		fail(w, http.StatusInternalServerError, "could not commit")
		return
	}
	detail := fmt.Sprintf("added %s · %s %s in hand", name, fmtQty(req.Qty), unit)
	if req.Par > 0 {
		detail += fmt.Sprintf(", should have %s %s", fmtQty(req.Par), unit)
	}
	audit(s.db, st.ID, st.Name, "inventory:added", detail)
	s.hub.Broadcast("inventory")
	s.writeInventoryItem(w, http.StatusCreated, id)
}

// PATCH /api/inventory/{id} {name?, unit?, par?, active?, sort?} — edit an
// item. The quantity is NOT editable here: it only ever changes through a
// move, so every change in stock has a row in the history. active:false takes
// the item off the lists and keeps its rows; active:true brings it back.
// All of it is checked first and saved together, so a bad field changes nothing.
func (s *Server) handleInventoryPatch(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Name   *string `json:"name"`
		Unit   *string `json:"unit"`
		Par    *int64  `json:"par"` // thousandths
		Active *bool   `json:"active"`
		Sort   *int    `json:"sort"`
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

	var name, unit string
	var qty, par int64
	var active, sort int
	if err := tx.QueryRow(`SELECT name, unit, qty, par, active, sort FROM inventory_items WHERE id=?`, id).
		Scan(&name, &unit, &qty, &par, &active, &sort); err != nil {
		fail(w, http.StatusNotFound, "item not found")
		return
	}
	curUnit := unit
	var changes []string
	renamed, restored, removed := false, false, false
	if req.Name != nil {
		n, ok := inventoryName(*req.Name)
		if !ok {
			fail(w, http.StatusBadRequest, "name must be 1–40 characters")
			return
		}
		if n != name {
			changes = append(changes, fmt.Sprintf("renamed %s → %s", name, n))
			name, renamed = n, true
		}
	}
	if req.Unit != nil {
		u, ok := inventoryUnit(*req.Unit)
		if !ok {
			fail(w, http.StatusBadRequest, "unit must be 1–12 characters")
			return
		}
		if u != unit {
			changes = append(changes, fmt.Sprintf("%s unit %s → %s", name, unit, u))
			unit = u
		}
	}
	if req.Par != nil {
		if !validInventoryQty(*req.Par) {
			fail(w, http.StatusBadRequest, "quantity must be between 0 and 1,000,000")
			return
		}
		if *req.Par != par {
			changes = append(changes, fmt.Sprintf("%s should have %s %s → %s %s",
				name, fmtQty(par), curUnit, fmtQty(*req.Par), unit))
			par = *req.Par
		}
	}
	if req.Sort != nil && *req.Sort != sort {
		changes = append(changes, fmt.Sprintf("%s position %d → %d", name, sort, *req.Sort))
		sort = *req.Sort
	}
	if req.Active != nil && boolInt(*req.Active) != active {
		active = boolInt(*req.Active)
		if *req.Active {
			restored = true
			changes = append(changes, name+" back on the list")
		} else {
			removed = true
		}
	}
	// Same rule as on create, for the two ways an item can land on a taken
	// name: being renamed, or coming back after a namesake was added.
	if active == 1 && (renamed || restored) {
		var dup int
		tx.QueryRow(`SELECT COUNT(*) FROM inventory_items WHERE active=1 AND id!=? AND name=? COLLATE NOCASE`,
			id, name).Scan(&dup)
		if dup > 0 {
			fail(w, http.StatusConflict, name+" is already in the inventory")
			return
		}
	}
	if len(changes) == 0 && !removed {
		// Nothing differs from what is stored: no write, no audit row. The
		// transaction must be let go before the reload (single connection).
		tx.Rollback()
		s.writeInventoryItem(w, http.StatusOK, id)
		return
	}
	if _, err := tx.Exec(`UPDATE inventory_items SET name=?, unit=?, par=?, active=?, sort=?, updated_at=?, updated_by=?
		WHERE id=?`, name, unit, par, active, sort, nowMs(), st.ID, id); err != nil {
		fail(w, http.StatusInternalServerError, "could not save the item")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(w, http.StatusInternalServerError, "could not commit")
		return
	}
	if len(changes) > 0 {
		audit(s.db, st.ID, st.Name, "inventory:edited", strings.Join(changes, "; "))
	}
	if removed {
		audit(s.db, st.ID, st.Name, "inventory:removed",
			fmt.Sprintf("removed %s (%s %s in hand)", name, fmtQty(qty), unit))
	}
	s.hub.Broadcast("inventory")
	s.writeInventoryItem(w, http.StatusOK, id)
}

// POST /api/inventory/{id}/move {kind, qty, note?} — one stock move.
//
//	in    qty > 0   adds to what is in hand (maal aaya)
//	out   qty > 0   takes away (use hua); never below zero: asking for more
//	                than is in hand is a 409 that says what IS in hand, so the
//	                person recounts instead of the shelf going negative
//	count qty >= 0  sets the quantity after counting (ginti). A count equal to
//	                the current quantity still writes a move: "counted,
//	                unchanged" is worth having on record.
//
// The new quantity and its history row are written in one transaction.
func (s *Server) handleInventoryMove(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Kind string `json:"kind"`
		Qty  int64  `json:"qty"` // thousandths
		Note string `json:"note"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.Kind != "in" && req.Kind != "out" && req.Kind != "count" {
		fail(w, http.StatusBadRequest, "kind must be in, out or count")
		return
	}
	if !validInventoryQty(req.Qty) {
		fail(w, http.StatusBadRequest, "quantity must be between 0 and 1,000,000")
		return
	}
	if req.Kind != "count" && req.Qty == 0 {
		fail(w, http.StatusBadRequest, "quantity must be more than 0")
		return
	}
	note := strings.TrimSpace(req.Note)
	if utf8.RuneCountInString(note) > 80 {
		fail(w, http.StatusBadRequest, "note must be 80 characters or less")
		return
	}

	tx, err := s.db.Begin()
	if err != nil {
		fail(w, http.StatusInternalServerError, "db busy, try again")
		return
	}
	defer tx.Rollback()

	var name, unit string
	var qty int64
	var active int
	if err := tx.QueryRow(`SELECT name, unit, qty, active FROM inventory_items WHERE id=?`, id).
		Scan(&name, &unit, &qty, &active); err != nil {
		fail(w, http.StatusNotFound, "item not found")
		return
	}
	// A phone with a stale list may still show an item someone just removed.
	if active == 0 {
		fail(w, http.StatusConflict, name+" has been removed from the inventory")
		return
	}

	var after int64
	var detail string
	switch req.Kind {
	case "in":
		after = qty + req.Qty
		if after > inventoryMaxQty {
			fail(w, http.StatusBadRequest, "quantity must be between 0 and 1,000,000")
			return
		}
		detail = fmt.Sprintf("%s +%s %s → %s %s", name, fmtQty(req.Qty), unit, fmtQty(after), unit)
	case "out":
		if req.Qty > qty {
			fail(w, http.StatusConflict, fmt.Sprintf("only %s %s of %s in hand", fmtQty(qty), unit, name))
			return
		}
		after = qty - req.Qty
		detail = fmt.Sprintf("%s -%s %s → %s %s", name, fmtQty(req.Qty), unit, fmtQty(after), unit)
	default:
		after = req.Qty
		if after == qty {
			detail = fmt.Sprintf("%s counted %s %s (unchanged)", name, fmtQty(after), unit)
		} else {
			detail = fmt.Sprintf("%s counted %s %s (was %s %s)", name, fmtQty(after), unit, fmtQty(qty), unit)
		}
	}
	if note != "" {
		detail += " · " + note
	}

	now := nowMs()
	if _, err := tx.Exec(`UPDATE inventory_items SET qty=?, updated_at=?, updated_by=? WHERE id=?`,
		after, now, st.ID, id); err != nil {
		fail(w, http.StatusInternalServerError, "could not save the quantity")
		return
	}
	if _, err := tx.Exec(`INSERT INTO inventory_moves(item_id, biz_date, kind, delta, qty_after, note, by_staff, at)
		VALUES(?,?,?,?,?,?,?,?)`,
		id, bizDate(time.Now(), s.dayStartHour), req.Kind, after-qty, after, note, st.ID, now); err != nil {
		fail(w, http.StatusInternalServerError, "could not record the move")
		return
	}
	if err := tx.Commit(); err != nil {
		fail(w, http.StatusInternalServerError, "could not commit")
		return
	}
	audit(s.db, st.ID, st.Name, "inventory:"+req.Kind, detail)
	s.hub.Broadcast("inventory")
	s.writeInventoryItem(w, http.StatusOK, id)
}

// GET /api/inventory/{id}/moves?limit=50 — one item's history, newest first
// (at most 200). Works for removed items too: their rows stay for history.
func (s *Server) handleInventoryMoves(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	if limit <= 0 {
		limit = 50
	}
	if limit > 200 {
		limit = 200
	}
	var n int
	s.db.QueryRow(`SELECT COUNT(*) FROM inventory_items WHERE id=?`, id).Scan(&n)
	if n == 0 {
		fail(w, http.StatusNotFound, "item not found")
		return
	}
	type move struct {
		ID       int64  `json:"id"`
		Kind     string `json:"kind"`
		Delta    int64  `json:"delta"`    // signed, thousandths
		QtyAfter int64  `json:"qtyAfter"` // thousandths
		Note     string `json:"note"`
		By       string `json:"by"`
		At       int64  `json:"at"`
		BizDate  string `json:"bizDate"`
	}
	out := []move{}
	rows, err := s.db.Query(`SELECT m.id, m.kind, m.delta, m.qty_after, m.note, COALESCE(st.name,''), m.at, m.biz_date
		FROM inventory_moves m LEFT JOIN staff st ON st.id=m.by_staff
		WHERE m.item_id=? ORDER BY m.at DESC, m.id DESC LIMIT ?`, id, limit)
	if err != nil {
		fail(w, http.StatusInternalServerError, "db error")
		return
	}
	defer rows.Close()
	for rows.Next() {
		var m move
		rows.Scan(&m.ID, &m.Kind, &m.Delta, &m.QtyAfter, &m.Note, &m.By, &m.At, &m.BizDate)
		out = append(out, m)
	}
	writeJSON(w, http.StatusOK, map[string]any{"moves": out})
}
