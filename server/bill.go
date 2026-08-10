package main

import (
	"fmt"
	"html"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// billLine is one printed row: identical dishes are merged across KOTs so the
// customer sees "3× Pav Bhaji", not three separate lines.
type billLine struct {
	name string
	qty  int64
	rate int64
	amt  int64
}

// Bill is the settlement record the cashier edits before an order may close:
// a snapshot subtotal, an optional discount, and how the money arrived.
type Bill struct {
	OrderID      int64  `json:"orderId"`
	Subtotal     int64  `json:"subtotal"`
	Discount     int64  `json:"discount"`
	DiscountNote string `json:"discountNote"`
	Total        int64  `json:"total"`
	PaidCash     int64  `json:"paidCash"`
	PaidOnline   int64  `json:"paidOnline"`
}

// orderItemsTotal is the live value of an order: every non-cancelled line.
func (s *Server) orderItemsTotal(orderID int64) int64 {
	var t int64
	s.db.QueryRow(`SELECT COALESCE(SUM(ti.qty*ti.price),0) FROM ticket_items ti
		JOIN tickets tk ON tk.id=ti.ticket_id
		WHERE tk.order_id=? AND ti.status!='cancelled'`, orderID).Scan(&t)
	return t
}

func (s *Server) loadBillRow(orderID int64) (*Bill, bool) {
	b := &Bill{OrderID: orderID}
	err := s.db.QueryRow(`SELECT subtotal, discount, discount_note, total, paid_cash, paid_online
		FROM bills WHERE order_id=?`, orderID).
		Scan(&b.Subtotal, &b.Discount, &b.DiscountNote, &b.Total, &b.PaidCash, &b.PaidOnline)
	return b, err == nil
}

// BillSummary is one row of the settled-bill history.
type BillSummary struct {
	OrderID      int64  `json:"orderId"`
	OrderNo      int64  `json:"orderNo"`
	TableName    string `json:"tableName"`
	CustomerName string `json:"customerName"`
	ClosedAt     int64  `json:"closedAt"`
	ClosedByName string `json:"closedByName"`
	Subtotal     int64  `json:"subtotal"`
	Discount     int64  `json:"discount"`
	DiscountNote string `json:"discountNote"`
	Total        int64  `json:"total"`
	PaidCash     int64  `json:"paidCash"`
	PaidOnline   int64  `json:"paidOnline"`
	ItemCount    int64  `json:"itemCount"`
}

// GET /api/bills?date=YYYY-MM-DD — every settled bill of one business day
// (default today), newest first. Nothing ever vanishes on close: this is the
// cashier's and owner's paper spike. Cashier + admin only.
func (s *Server) handleBillsList(w http.ResponseWriter, r *http.Request) {
	date := r.URL.Query().Get("date")
	if date == "" {
		date = bizDate(time.Now(), s.dayStartHour)
	}
	out := []BillSummary{}
	rows, err := s.db.Query(`
		SELECT o.id, o.order_no, t.name, o.customer_name, COALESCE(o.closed_at,0), COALESCE(st.name,''),
		       b.subtotal, b.discount, b.discount_note, b.total, b.paid_cash, b.paid_online,
		       (SELECT COALESCE(SUM(ti.qty),0) FROM ticket_items ti JOIN tickets tk ON tk.id=ti.ticket_id
		        WHERE tk.order_id=o.id AND ti.status!='cancelled')
		FROM bills b
		JOIN orders o ON o.id=b.order_id
		JOIN dining_tables t ON t.id=o.table_id
		LEFT JOIN staff st ON st.id=o.closed_by
		WHERE o.biz_date=? AND o.status='closed'
		ORDER BY o.closed_at DESC, o.id DESC`, date)
	if err == nil {
		for rows.Next() {
			var b BillSummary
			rows.Scan(&b.OrderID, &b.OrderNo, &b.TableName, &b.CustomerName, &b.ClosedAt, &b.ClosedByName,
				&b.Subtotal, &b.Discount, &b.DiscountNote, &b.Total, &b.PaidCash, &b.PaidOnline, &b.ItemCount)
			out = append(out, b)
		}
		rows.Close()
	}
	writeJSON(w, http.StatusOK, map[string]any{"date": date, "bills": out})
}

// GET /api/orders/{id}/bill — the bill row alone, for the history detail view.
func (s *Server) handleBillGet(w http.ResponseWriter, r *http.Request) {
	orderID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	b, ok := s.loadBillRow(orderID)
	if !ok {
		fail(w, http.StatusNotFound, "no bill for this order")
		return
	}
	writeJSON(w, http.StatusOK, b)
}

// POST /api/orders/{id}/bill — generate the bill, or refresh it against the
// order's live items. Discount and payments already entered survive a refresh
// (the discount is clamped if the subtotal shrank below it). Cashier only.
func (s *Server) handleBillGenerate(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	orderID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var status string
	if err := s.db.QueryRow(`SELECT status FROM orders WHERE id=?`, orderID).Scan(&status); err != nil {
		fail(w, http.StatusNotFound, "order not found")
		return
	}
	if status == "closed" { // reprint case: the settled bill is frozen history
		if b, ok := s.loadBillRow(orderID); ok {
			writeJSON(w, http.StatusOK, b)
			return
		}
		fail(w, http.StatusConflict, "order closed without a bill (old data)")
		return
	}
	subtotal := s.orderItemsTotal(orderID)
	now := nowMs()
	s.db.Exec(`INSERT INTO bills(order_id, subtotal, discount, discount_note, total, paid_cash, paid_online, generated_by, generated_at, updated_at)
		VALUES(?,?,0,'',?,0,0,?,?,?)
		ON CONFLICT(order_id) DO UPDATE SET
		  subtotal = excluded.subtotal,
		  discount = MIN(bills.discount, excluded.subtotal),
		  total    = excluded.subtotal - MIN(bills.discount, excluded.subtotal),
		  updated_at = excluded.updated_at`,
		orderID, subtotal, subtotal, st.ID, now, now)
	b, _ := s.loadBillRow(orderID)
	writeJSON(w, http.StatusOK, b)
}

// PATCH /api/orders/{id}/bill {discount?, discountNote?, paidCash?, paidOnline?}
// The cashier's edit surface: give a discount (audited, with a note) and
// record how the customer paid — all cash, all online, or any split.
func (s *Server) handleBillPatch(w http.ResponseWriter, r *http.Request) {
	st := staffFrom(r)
	orderID, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	var req struct {
		Discount     *int64  `json:"discount"`
		DiscountNote *string `json:"discountNote"`
		PaidCash     *int64  `json:"paidCash"`
		PaidOnline   *int64  `json:"paidOnline"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	var status string
	if err := s.db.QueryRow(`SELECT status FROM orders WHERE id=?`, orderID).Scan(&status); err != nil {
		fail(w, http.StatusNotFound, "order not found")
		return
	}
	if status == "closed" {
		fail(w, http.StatusConflict, "order already closed — its bill is frozen")
		return
	}
	b, ok := s.loadBillRow(orderID)
	if !ok {
		fail(w, http.StatusConflict, "generate the bill first")
		return
	}
	subtotal := s.orderItemsTotal(orderID) // refresh against live items too
	discount, note := b.Discount, b.DiscountNote
	cash, online := b.PaidCash, b.PaidOnline
	if req.Discount != nil {
		discount = *req.Discount
	}
	if req.DiscountNote != nil {
		note = strings.TrimSpace(*req.DiscountNote)
	}
	if req.PaidCash != nil {
		cash = *req.PaidCash
	}
	if req.PaidOnline != nil {
		online = *req.PaidOnline
	}
	if discount < 0 || discount > subtotal {
		fail(w, http.StatusBadRequest, "discount must be between 0 and the subtotal")
		return
	}
	if len(note) > 120 {
		fail(w, http.StatusBadRequest, "discount note too long")
		return
	}
	if cash < 0 || online < 0 {
		fail(w, http.StatusBadRequest, "payment amounts can't be negative")
		return
	}
	total := subtotal - discount
	if cash+online > total {
		fail(w, http.StatusBadRequest, "payment exceeds the bill total")
		return
	}
	s.db.Exec(`UPDATE bills SET subtotal=?, discount=?, discount_note=?, total=?, paid_cash=?, paid_online=?, updated_at=?
		WHERE order_id=?`, subtotal, discount, note, total, cash, online, nowMs(), orderID)
	if discount != b.Discount {
		var orderNo int64
		var tName string
		s.db.QueryRow(`SELECT o.order_no, t.name FROM orders o JOIN dining_tables t ON t.id=o.table_id WHERE o.id=?`, orderID).
			Scan(&orderNo, &tName)
		detail := fmt.Sprintf("%s · order #%d: discount %s", tName, orderNo, billRupees(discount))
		if note != "" {
			detail += " — " + note
		}
		audit(s.db, st.ID, st.Name, "bill:discount", detail)
	}
	nb, _ := s.loadBillRow(orderID)
	writeJSON(w, http.StatusOK, nb)
}

func billRupees(p int64) string {
	if p%100 == 0 {
		return fmt.Sprintf("₹%d", p/100)
	}
	return fmt.Sprintf("₹%d.%02d", p/100, p%100)
}

// GET /bill/{id} — a print-ready HTML bill for one order, laid out for an
// 80 mm thermal roll but equally happy as A4/PDF. The cashier's phone loads it
// in a hidden WebView and hands it to Android's print dialog; any browser on
// the laptop can print the same URL. Money-facing roles only (the route guard
// keeps waiters and chefs away from prices).
func (s *Server) handleBillHTML(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	d, err := s.loadOrderDetail(id)
	if err != nil {
		http.Error(w, "order not found", http.StatusNotFound)
		return
	}

	var lines []billLine
	idx := map[string]int{}
	var pieces int64
	for _, tk := range d.Tickets {
		for _, it := range tk.Items {
			if it.Status == "cancelled" {
				continue
			}
			key := it.Name + "\x00" + strconv.FormatInt(it.Price, 10)
			if i, ok := idx[key]; ok {
				lines[i].qty += it.Qty
				lines[i].amt += it.Qty * it.Price
			} else {
				idx[key] = len(lines)
				lines = append(lines, billLine{it.Name, it.Qty, it.Price, it.Qty * it.Price})
			}
			pieces += it.Qty
		}
	}

	shop := getMeta(s.db, "shop_name", "Pav Bhaji POS")
	phone := getMeta(s.db, "shop_phone", "")
	footer := getMeta(s.db, "bill_footer", "Thank you! Visit again 🙏")

	var b strings.Builder
	b.WriteString(`<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bill #` + strconv.FormatInt(d.OrderNo, 10) + `</title>
<style>
  * { box-sizing:border-box; margin:0; }
  body { font-family:"Segoe UI",system-ui,sans-serif; background:#f4f0ea; color:#1c1713; }
  .bill { max-width:320px; margin:18px auto; background:#fff; padding:18px 16px;
          border:1px solid #e5dcd0; border-radius:10px; font-size:14px; line-height:1.5; }
  h1 { font-size:20px; text-align:center; letter-spacing:.02em; }
  .sub { text-align:center; color:#6f645b; font-size:12.5px; }
  .rule { border:0; border-top:1.5px dashed #c9bda9; margin:10px 0; }
  .meta { width:100%; font-size:13px; }
  .meta td { padding:1.5px 0; vertical-align:top; }
  .meta td:last-child { text-align:right; font-weight:600; }
  table.items { width:100%; border-collapse:collapse; font-size:13.5px; }
  table.items th { text-align:left; font-size:11px; color:#6f645b; text-transform:uppercase;
                   letter-spacing:.06em; padding:2px 0 5px; }
  table.items td { padding:3.5px 0; vertical-align:top; }
  .r { text-align:right; } .c { text-align:center; }
  .total td { padding-top:9px; font-size:17px; font-weight:800; }
  .count { color:#6f645b; font-size:12px; text-align:right; }
  .open-note { color:#a05a00; font-size:12px; text-align:center; margin-top:6px; }
  .foot { text-align:center; margin-top:10px; font-size:13px; }
  .noprint { text-align:center; margin:14px 0 26px; }
  .noprint button { font:inherit; font-weight:700; padding:9px 22px; border-radius:9px;
                    border:1px solid #a8271f; background:#a8271f; color:#fff; cursor:pointer; }
  @media print {
    body { background:#fff; }
    .bill { border:0; border-radius:0; margin:0 auto; padding:0; max-width:100%; }
    .noprint { display:none; }
  }
  @page { margin:6mm; }
</style></head><body><div class="bill">
`)
	b.WriteString(`<h1>` + html.EscapeString(shop) + `</h1>`)
	if phone != "" {
		b.WriteString(`<div class="sub">☎ ` + html.EscapeString(phone) + `</div>`)
	}
	b.WriteString(`<hr class="rule"><table class="meta">`)
	b.WriteString(`<tr><td>Bill / Order</td><td>#` + strconv.FormatInt(d.OrderNo, 10) + `</td></tr>`)
	b.WriteString(`<tr><td>Date</td><td>` + time.Now().Format("02 Jan 2006, 3:04 pm") + `</td></tr>`)
	if d.CustomerName != "" {
		who := d.CustomerName
		if d.CustomerPhone != "" {
			who += " · " + d.CustomerPhone
		}
		b.WriteString(`<tr><td>Parcel for</td><td>` + html.EscapeString(who) + `</td></tr>`)
		b.WriteString(`<tr><td>Slot</td><td>` + html.EscapeString(d.TableName) + `</td></tr>`)
	} else {
		b.WriteString(`<tr><td>Table</td><td>` + html.EscapeString(d.TableName) + `</td></tr>`)
	}
	b.WriteString(`<tr><td>Order taken by</td><td>` + html.EscapeString(d.OpenedByName) + `</td></tr>`)
	b.WriteString(`</table><hr class="rule">`)

	b.WriteString(`<table class="items"><tr><th>Item</th><th class="c">Qty</th><th class="r">Rate</th><th class="r">Amount</th></tr>`)
	for _, l := range lines {
		b.WriteString(`<tr><td>` + html.EscapeString(l.name) + `</td><td class="c">` +
			strconv.FormatInt(l.qty, 10) + `</td><td class="r">` + billRupees(l.rate) +
			`</td><td class="r">` + billRupees(l.amt) + `</td></tr>`)
	}
	// Settlement lines: with a generated bill, print subtotal → discount →
	// final total, then how it was (or is being) paid.
	bill, hasBill := s.loadBillRow(id)
	discount := int64(0)
	if hasBill && bill.Discount > 0 {
		discount = bill.Discount
		if discount > d.Total {
			discount = d.Total
		}
	}
	finalTotal := d.Total - discount
	if discount > 0 {
		b.WriteString(`<tr><td colspan="2">Subtotal</td><td colspan="2" class="r">` + billRupees(d.Total) + `</td></tr>`)
		note := ""
		if bill.DiscountNote != "" {
			note = ` <span style="font-size:11px;color:#6f645b">(` + html.EscapeString(bill.DiscountNote) + `)</span>`
		}
		b.WriteString(`<tr><td colspan="2">Discount` + note + `</td><td colspan="2" class="r">− ` + billRupees(discount) + `</td></tr>`)
	}
	b.WriteString(`<tr class="total"><td colspan="2">TOTAL</td><td colspan="2" class="r">` + billRupees(finalTotal) + `</td></tr>`)
	if hasBill && bill.PaidCash+bill.PaidOnline > 0 {
		var pay []string
		if bill.PaidCash > 0 {
			pay = append(pay, "Cash "+billRupees(bill.PaidCash))
		}
		if bill.PaidOnline > 0 {
			pay = append(pay, "Online "+billRupees(bill.PaidOnline))
		}
		paid := ""
		if bill.PaidCash+bill.PaidOnline == finalTotal {
			paid = ` · <b>PAID ✓</b>`
		}
		b.WriteString(`<tr><td colspan="2" style="color:#6f645b">Payment</td><td colspan="2" class="r" style="font-size:12.5px">` +
			strings.Join(pay, " + ") + paid + `</td></tr>`)
	}
	b.WriteString(`</table><div class="count">` + strconv.FormatInt(pieces, 10) + ` item(s)</div>`)
	if d.Status == "open" {
		b.WriteString(`<div class="open-note">running bill — order is still open</div>`)
	}
	b.WriteString(`<hr class="rule"><div class="foot">` + html.EscapeString(footer) + `</div></div>`)
	b.WriteString(`<div class="noprint"><button onclick="window.print()">🖨 Print</button></div>`)
	b.WriteString(`</body></html>`)

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Write([]byte(b.String()))
}

// GET/POST /api/admin/bill-settings — the three lines the owner controls on
// every printed bill: shop name, phone, and the thank-you footer.
func (s *Server) handleBillSettings(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		writeJSON(w, http.StatusOK, map[string]any{
			"shopName":   getMeta(s.db, "shop_name", "Pav Bhaji POS"),
			"shopPhone":  getMeta(s.db, "shop_phone", ""),
			"billFooter": getMeta(s.db, "bill_footer", "Thank you! Visit again 🙏"),
		})
		return
	}
	me := staffFrom(r)
	var req struct {
		ShopName   *string `json:"shopName"`
		ShopPhone  *string `json:"shopPhone"`
		BillFooter *string `json:"billFooter"`
	}
	if !readJSON(w, r, &req) {
		return
	}
	if req.ShopName != nil && strings.TrimSpace(*req.ShopName) != "" {
		setMeta(s.db, "shop_name", strings.TrimSpace(*req.ShopName))
	}
	if req.ShopPhone != nil {
		setMeta(s.db, "shop_phone", strings.TrimSpace(*req.ShopPhone))
	}
	if req.BillFooter != nil {
		setMeta(s.db, "bill_footer", strings.TrimSpace(*req.BillFooter))
	}
	audit(s.db, me.ID, me.Name, "bill-settings", "bill header/footer updated")
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}
