# PavBhaji POS

LAN-only restaurant POS for a single shop: dine-in table orders, kitchen display,
roles (admin / manager / waiter / chef), full audit trail. **Never hosted anywhere** — a
Windows laptop on the shop WiFi is the server; Android phones are the clients.

- `server/` — Go. Single static binary, embedded SQLite (pure-Go driver), REST + WebSocket,
  status page with pairing QR on `http://localhost:8177`.
- `android/` — Kotlin + Jetpack Compose. One APK, screen decided by the PIN's role.
- `dist/` — build outputs: `PavBhajiPOS-server.exe` (Windows), `PavBhajiPOS.apk`,
  plus Windows helper scripts.
- `SETUP.md` — plain-English install guide (laptop + phones).

## Design notes (why it's "number accurate")

- **One database.** SQLite on the laptop, `SetMaxOpenConns(1)` — every transaction fully
  serialized. Order numbers are `MAX(order_no)+1` per business day inside the same
  transaction that inserts the ticket → no duplicates, no gaps.
- **Idempotent ticket sends.** The phone generates `clientTicketId` (UUID) when the cart
  is created and reuses it for every retry; the server has a UNIQUE index on it and
  replays the saved result on a duplicate. Flaky WiFi cannot double-punch an order.
- **Append-only.** Items are never deleted — `cancelled` with who/when/reason.
  Menu name+price are snapshotted into each line, so price edits never rewrite history.
- **Server-side roles.** Every endpoint declares allowed roles; the UI is only a convenience.
- **Colour buckets (cook by colour, serve by clock).** Every kitchen line drops into its
  dish's open batch inside the punch transaction. A bucket seals at 15 plates
  (`meta.batch_cap`) or the moment a chef takes it; taking is an atomic claim — a second
  chef gets a 409 naming the holder, and a chef holding an unfinished bucket cannot take
  another (one bucket per chef). "Bucket ready" flips every line to `ready` with one
  timestamp, which is exactly the order the waiter's pickup queue (`/api/pickup`) sorts by.
  Counter items (drinks etc., `menu_items.station`) skip the kitchen and appear on the
  waiter's Ready line the moment they're punched.
- **Close = reconciled.** An order can close only when each line is served or cancelled,
  so `punched = served + cancelled` always holds in the day report.
- Business day rolls at 06:00 (`meta.day_start_hour`) so a 1 AM order counts to the
  previous evening.

## Build

```bash
# server (from server/): needs Go
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -ldflags="-s -w" -o ../dist/PavBhajiPOS-server.exe .

# android (from android/): needs JDK 17+, Android SDK 35
gradle assembleRelease   # output: app/build/outputs/apk/release/app-release.apk
```

Release signing: put an `android/keystore.properties` next to `app/` with
`storeFile`/`storePassword`/`keyAlias`/`keyPassword` pointing at your own
keystore. Without it the release APK builds unsigned (fine for trying it out).

Toolchain used originally: Go + JDK 21 + Gradle 8.13 under `~/tools/`, SDK under `~/Android/Sdk`.

## Defaults

Seeded on first run: staff `Admin/1111`, `Waiter 1/2222`, `Chef/3333` (change immediately),
sample menu, tables T1–T10. Server port `8177`. Data in `data/` next to the exe;
automatic daily backup at 23:45 into `data/backups/`.
