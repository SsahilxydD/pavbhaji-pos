# PavBhaji POS — Setup Guide

Everything runs inside your shop's WiFi. No internet needed, nothing is hosted anywhere.
The **laptop is the brain** — it holds the only copy of the data. Phones are just screens talking to it.

```
        Shop WiFi router (internet NOT required)
                     │
   ┌─────────────┬───┴──────────┬──────────────┐
 LAPTOP        waiter phone   waiter phone   chef phone
 (server +      (app)          (app)         (app, kitchen mode)
  database)
```

---

## Part 1 — The laptop (Windows)

1. Make a folder, e.g. `C:\PavBhajiPOS`, and copy **`PavBhajiPOS-server.exe`** into it
   (from the `dist` folder of this project).
   - If Windows says "**This app can't run on your PC**", the laptop has 32-bit
     Windows — use **`PavBhajiPOS-server-32bit.exe`** instead (same folder, same steps).
   - Either exe needs **Windows 10 or newer**. On a Windows 7/8 laptop neither will
     start — ask your developer.
2. Double-click the exe. The **first** time, Windows may show a blue
   "**Windows protected your PC**" box (normal for a small unsigned program) —
   click **More info → Run anyway**. It only asks once.
   A black window opens — **keep it open while the shop runs**.
   It shows something like:

   ```
   phones connect to:  192.168.1.50:8177
   ```

3. **Windows Firewall will pop up** the first time → tick **Private networks** → click **Allow access**.
   (If you accidentally clicked Cancel: right-click `fix-firewall.bat` → Run as administrator.)
4. Open `http://localhost:8177` in the laptop's browser — you'll see a big QR + the address.
   This page is your "is it running?" check.
5. Run `install-autostart.bat` once — the server will now start automatically whenever
   the laptop boots.

**Laptop rules (important):**
- Keep it **plugged in**.
- Disable sleep: Settings → System → Power → Screen and sleep → **Never** (when plugged in).
- The laptop's address may change after a router restart — **that's fine**: the phones
  hunt for the laptop and re-connect by themselves within ~30 seconds. (If you like
  tidiness anyway, a "DHCP reservation" in the router pins the address forever —
  entirely optional.)

**Where's my data?** In the `data` folder next to the exe:
- `pbpos.db` — the live database
- `backups\auto-YYYY-MM-DD.db` — automatic backup, refreshed **every 20 minutes**
  (one file per day, last 30 days kept)

**Best backup habit: leave a pen drive plugged into the laptop.** Every backup is
automatically copied onto it (`PavBhajiPOS-backups` folder) — if the laptop's disk
dies, the stick has everything up to the last 20 minutes. The status page
(`http://localhost:8177`) shows when the last backup and last pen-drive copy
happened. The admin app can also download a backup to a phone anytime.

**If the server program ever crashes, it restarts itself within 3 seconds** —
closing its window on purpose still stops it normally.

---

## Part 2 — The phones

1. Copy **`PavBhajiPOS.apk`** to each phone (WhatsApp it to yourself, or USB cable).
2. Open the file on the phone → Android asks to allow installing from unknown source → allow → Install.
3. Make sure the phone is on the **shop WiFi**.
4. Open the app → it **finds the laptop by itself** and connects (takes a few seconds).
   - If it can't: tap **📷 Scan QR** and point the phone at the QR on the laptop's
     status page (`http://localhost:8177` in the laptop browser). Typing the address
     still works too, as a last resort.
5. Log in with your PIN.
6. **Language**: every phone picks its own — tap the **EN / हिं / ગુ** button in the
   top-right corner of any screen and choose English / हिंदी / ગુજરાતી. Changes
   apply instantly, per phone.

## First login — default PINs (CHANGE THESE!)

| Who | PIN |
|-----|-----|
| Admin | `1111` |
| Manager | `4444` |
| Waiter 1 | `2222` |
| Chef | `3333` |
| Cashier | `5555` |
| Parcel manager | `6666` |

(Cashier and Parcel PINs exist only on fresh installs — on an older database,
add them yourself under **Staff** with roles *cashier* / *parcel*.)

Log in as Admin → **Staff** → reset every PIN, add your real staff.
Then: **Menu & colours** → fix the menu (the app ships with a sample pav bhaji menu)
and pick each dish's bucket colour (tap the colour dot — any colour you like),
and **Tables** → match your actual table count.

---

## Daily use

- **Waiter**: one screen, nothing to learn. A single list of every plate to carry
  **right now**, oldest on top — each row shows the dish and the **table number**,
  tap **✓** as you drop it. Drinks and extras appear instantly (✋ — just carry).
  Waiters don't take orders; that's the manager. **Only waiters can mark a plate
  served** — one plate, one tap, no bulk button anywhere. That ✓ is the proof the
  plate reached the table, and it's what the serve history and day report trust.
- **Manager**: takes every order. Two tabs. **Tables** — tap a table → add items →
  **Send to kitchen** (more items later = send again; each send is a numbered KOT on
  the same order). The bhaji picker is a **horizontal row of colours**: colour is
  the category — tap a colour, its varieties list underneath (extras & drinks
  keep their normal lists). Card colours say what to do next: amber = kitchen has it, **green =
  plates waiting, blue = everything served — send the customer to the cashier**
  (only the cashier can bill and close a table).
  Below the dining tables sits a **🥡 PARCEL** section (P1, P2…) — walk-up / takeaway
  orders punched at the counter, same flow. **Ready** — a watch-only view of the
  carry queue **grouped by table**, for chasing waiters during a rush (no serve
  buttons: serving is the waiters' power alone). Managers cannot close tables —
  that's the cashier's job, and it needs every plate ✓'d by a waiter first.
- **Chef**: a queue of **colour buckets** (red = spicy, yellow = cheese…). **Colour is
  the club: every bhaji variety painted the same colour cooks together in one
  bucket** — the card lists each variety with its table. Orders fill a bucket up
  to 15 plates; tap a bucket to see which tables are inside; **Take this bucket**
  locks it to you (another chef must pick the next colour, and you can hold only **one
  bucket at a time** — finish yours before taking the next); **Bucket ready ✓** sends every
  plate to the waiters' Ready line in one tap.
- **Cashier**: the same tables grid the manager sees, **with ₹ totals on every
  card**. Tap an occupied table → a clean, invoice-style **bill**: merged item
  lines, qty × rate, grand total. **🖨 Print bill** opens Android's print
  dialog — a Bluetooth/WiFi thermal printer works, and **Save as PDF** lets the
  cashier WhatsApp the bill to a customer. The bill's shop name, phone and
  thank-you line are set on the laptop: admin panel → **Billing**.
  **Only the cashier can close a table, and only through the bill**: opening a
  table generates its bill; the cashier can then add a **discount** (₹ + an
  optional reason — every discount lands in the audit log) and record **how the
  customer paid**: 💵 all cash, 📲 all online/UPI, or a **split** (type the
  online amount, the rest counts as cash). **Close** unlocks only when the
  payment adds up to the bill total — and the server double-checks: no bill, a
  stale bill (items were added after it), or money that doesn't add up all
  refuse to close. Cashiers cannot punch, cancel or serve items. The day
  report shows **cash vs online vs discounts** for reconciliation.
  **Closed bills never vanish**: the cashier's second tab, **🧾 Bills**, lists
  every settled bill of the day (browse older days with ◀ ▶) — tap one for the
  full read-back (items, discount, cash/online split, who closed it) and
  reprint it any time.
- **Parcel manager**: takeaway has its own screen. **＋ New parcel** → type the
  customer's **name** (phone optional) → tap items → send. Every waiting
  customer becomes a **tab, like tables for the manager**: **yellow = kitchen
  still cooking, green = everything ready — hand it over**, blue = the waiter
  has ✓'d every plate at the counter — send the customer to the cashier, who
  bills and closes the parcel like any table. Tap a tab for the full order
  (add more items). Parcel slots (P4, P5…) create themselves when a rush
  outgrows the pool — nothing to set up.
- **Admin**: one screen — today's sales up top, then Floor / Kitchen / Menu & colours /
  Tables / Staff / **🧾 Bills** (the same settled-bill history the cashier sees,
  with full detail + reprint) / Day report (the activity log lives inside the
  report, top right — **filterable by type**: orders, kitchen, serving,
  cancelled, billing, staff). The laptop admin panel has the same **Bills** tab
  and log filters.
  Prefer a big screen? Open **`http://localhost:8177/admin`** in the laptop's browser
  (there's a button for it in the server window) — log in with the admin PIN and manage
  menu, prices, staff & PINs, tables, reports and backups right on the laptop. Works
  from any browser on the shop WiFi too.
  In **Menu & colours**, tap a dish's colour dot to repaint it anywhere — pick a ready
  swatch or mix any colour; every phone updates live. **The colour is not just looks:
  bhajis sharing a colour cook together in one bucket and sit under one colour chip
  on the order screen** — paint variants of the same base the same colour (and give
  them the same tawa cap); a dish with no colour always cooks alone. The dialog also sets **station**
  (kitchen = cooked in buckets, counter = waiter hands it over), out-of-stock is
  the switch on each card, and kitchen dishes get two tuning knobs: **Tawa cap**
  (plates per bucket, blank = 15) and **Seal after** (minutes before a waiting
  bucket seals itself so quiet-hour orders don't wait for a full tawa; blank = 10).

**Who sees money:** the money-facing roles — admin, cashier, and the parcel
manager (they take payment on handover). Chef, waiter and manager screens never
show prices — the manager punches items, the waiter carries plates, the
cashier/owner handles the bill. Extras and drinks are **counter** items: they
never appear on the chef's board, they land straight on the waiters' serve list.

**Number accuracy rules baked in:**
- Order numbers are assigned only by the laptop — no duplicates possible.
- A flaky WiFi "resend" can never double-punch a ticket (each send carries a unique ID).
- Nothing is ever deleted: cancelling needs a reason and is logged with who/when.
- A table can only be closed by the cashier, only with a generated bill whose
  payment (cash + online) adds up, and only when every item is served or cancelled — so the day
  report always reconciles: punched = served + cancelled.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "This app can't run on your PC" when opening the exe | 32-bit Windows → use `PavBhajiPOS-server-32bit.exe`. Still failing? The copy may be damaged — copy it from the pen drive again. Windows 7/8 can't run either exe (needs Windows 10+). |
| Phone says "can't reach server" | Is the phone on the shop WiFi (not mobile data)? Is the black window open on the laptop? |
| All phones lost connection | Laptop asleep? Router restarted? Check `http://localhost:8177` on the laptop. |
| Address changed after router restart | Nothing to do — phones re-find the laptop by themselves within ~30 s. In a hurry: on the PIN screen tap "server … · change" → it searches again (or 📷 Scan QR). |
| App can't find the laptop automatically | Some routers block "broadcast" chatter. Scan the QR instead — and check the router doesn't have "AP/client isolation" turned on (that blocks phones from the laptop entirely). |
| Forgot admin PIN | On the laptop: stop the server, rename `data\pbpos.db` to something else, start server — it creates a fresh DB with PIN 1111. Then copy your old file back if needed (ask your developer). |
| "That PIN is already taken" | Two people can't share a PIN — pick a different one. |
| "Too many wrong PINs — wait 30s" | After 5 wrong tries a phone must wait 30 seconds (protection against PIN guessing). Wait, then type carefully. Repeated lockouts appear in the audit log. |
