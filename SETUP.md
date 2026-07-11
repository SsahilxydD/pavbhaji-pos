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
2. Double-click the exe. A black window opens — **keep it open while the shop runs**.
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
- Give the laptop a **fixed IP** in your router settings (usually called "DHCP reservation"
  or "Static lease" — bind the laptop's MAC address to one IP). Otherwise the address
  can change after a router restart and phones will need the new one typed in.

**Where's my data?** In the `data` folder next to the exe:
- `pbpos.db` — the live database
- `backups\auto-YYYY-MM-DD.db` — automatic daily backup (written at 23:45)

**Backing up = copying that folder.** Do it to a pen drive once a week. The admin
app can also download a backup to a phone anytime (Admin → Download backup).

---

## Part 2 — The phones

1. Copy **`PavBhajiPOS.apk`** to each phone (WhatsApp it to yourself, or USB cable).
2. Open the file on the phone → Android asks to allow installing from unknown source → allow → Install.
3. Make sure the phone is on the **shop WiFi**.
4. Open the app → type the address from the laptop's black window (e.g. `192.168.1.50:8177`) → Connect.
5. Log in with your PIN.

## First login — default PINs (CHANGE THESE!)

| Who | PIN |
|-----|-----|
| Admin | `1111` |
| Waiter 1 | `2222` |
| Chef | `3333` |

Log in as Admin → **Staff & PINs** → reset every PIN, add your real staff.
Then: **Menu & prices** → fix the menu (the app ships with a sample pav bhaji menu),
and **Tables setup** → match your actual table count.

---

## Daily use

- **Waiter**: taps a table → adds items → **Send to kitchen**. More items later = send again
  (each send becomes a numbered KOT on the same order). When food is ready the table
  card turns green. Mark items served. When guests leave: **Close table** — the app shows
  the total; collect cash, close.
- **Chef**: sees every ticket live (with a beep). Taps **Start** → **Ready** per item.
  The **Batch cook** tab shows totals across tables ("Pav Bhaji × 7") for batch cooking.
- **Admin**: live dashboard, day report (sold / cancelled / per waiter), audit log of
  every action, menu & price edits, out-of-stock toggles.

**Number accuracy rules baked in:**
- Order numbers are assigned only by the laptop — no duplicates possible.
- A flaky WiFi "resend" can never double-punch a ticket (each send carries a unique ID).
- Nothing is ever deleted: cancelling needs a reason and is logged with who/when.
- A table can only be closed when every item is served or cancelled — so the day
  report always reconciles: punched = served + cancelled.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Phone says "can't reach server" | Is the phone on the shop WiFi (not mobile data)? Is the black window open on the laptop? |
| All phones lost connection | Laptop asleep? Router restarted? Check `http://localhost:8177` on the laptop. |
| Address changed after router restart | Set the DHCP reservation (see above), then type the new address once on each phone. |
| Forgot admin PIN | On the laptop: stop the server, rename `data\pbpos.db` to something else, start server — it creates a fresh DB with PIN 1111. Then copy your old file back if needed (ask your developer). |
| "That PIN is already taken" | Two people can't share a PIN — pick a different one. |
