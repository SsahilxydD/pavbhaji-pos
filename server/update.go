package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"
)

// Shipping updates to a shop nobody can walk into.
//
// The counter PC is the only machine here with internet. It polls a manifest on
// the VPS, pulls whatever is newer, and then acts as the update source for the
// phones on its own WiFi — so the phones never need internet, and the app keeps
// finding the server by LAN discovery exactly as before.
//
// Two artifacts move independently:
//   - the APK, cached and handed to phones over the LAN on request
//   - this very exe, downloaded and *staged*, then swapped in at a moment when
//     swapping costs nobody an order (next start, an idle gap, or on demand)

// buildStamp is set at link time:  -ldflags "-X main.buildStamp=<git short sha>"
var buildStamp = "dev"

const (
	defaultUpdateBase = "https://200.234.37.253.sslip.io/u/eabd0225e10f3ccb"

	// The worker exits with this code to say "I have swapped in a new build,
	// start me again" — as opposed to a crash (which the supervisor also
	// restarts) or a clean stop (which it must not).
	updateExitCode = 7

	updateCheckEvery = 30 * time.Minute
	updateFirstCheck = 45 * time.Second
	updateIdleEvery  = 10 * time.Minute
)

type artifact struct {
	File   string `json:"file"`
	URL    string `json:"url"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

type androidManifest struct {
	VersionCode int    `json:"versionCode"`
	VersionName string `json:"versionName"`
	Notes       string `json:"notes"`
	Mandatory   bool   `json:"mandatory"`
	Commit      string `json:"commit"`
	PublishedAt string `json:"publishedAt"`
	artifact
}

type serverManifest struct {
	Build       string   `json:"build"`
	Notes       string   `json:"notes"`
	PublishedAt string   `json:"publishedAt"`
	AMD64       artifact `json:"amd64"`
	I386        artifact `json:"i386"`
}

// stagedUpdate is the small note left beside a downloaded, hash-verified exe.
// The supervisor reads it on the way up, before any of the server exists.
type stagedUpdate struct {
	Build    string `json:"build"`
	File     string `json:"file"`
	SHA256   string `json:"sha256"`
	Notes    string `json:"notes"`
	StagedAt string `json:"stagedAt"`
}

func updatesDir(dataDir string) string { return filepath.Join(dataDir, "updates") }

func (s *Server) updateBase() string {
	return getMeta(s.db, "update_base", defaultUpdateBase)
}

func (s *Server) updatesEnabled() bool {
	return getMeta(s.db, "update_enabled", "1") == "1"
}

// ---------------------------------------------------------------- fetching

var updateHTTP = &http.Client{Timeout: 20 * time.Minute}

func fetchJSON(url string, v any) error {
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "pbpos/"+buildStamp)
	ctxClient := &http.Client{Timeout: 30 * time.Second}
	resp, err := ctxClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("%s: HTTP %d", url, resp.StatusCode)
	}
	return json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(v)
}

func fileSHA256(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// download pulls a to a temp file, checks the hash, and only then moves it into
// place. A half-downloaded APK must never become the file we hand to a phone.
func download(a artifact, dest string) error {
	if a.URL == "" || a.SHA256 == "" {
		return errors.New("manifest artifact has no url/sha256")
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return err
	}
	tmp := dest + ".part"
	os.Remove(tmp)

	req, err := http.NewRequest("GET", a.URL, nil)
	if err != nil {
		return err
	}
	req.Header.Set("User-Agent", "pbpos/"+buildStamp)
	resp, err := updateHTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("%s: HTTP %d", a.URL, resp.StatusCode)
	}

	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	n, err := io.Copy(f, resp.Body)
	f.Close()
	if err != nil {
		os.Remove(tmp)
		return err
	}
	if a.Size > 0 && n != a.Size {
		os.Remove(tmp)
		return fmt.Errorf("size mismatch: got %d, manifest says %d", n, a.Size)
	}
	got, err := fileSHA256(tmp)
	if err != nil {
		os.Remove(tmp)
		return err
	}
	if got != a.SHA256 {
		os.Remove(tmp)
		return fmt.Errorf("sha256 mismatch: got %s, manifest says %s", got[:12], a.SHA256[:12])
	}
	os.Remove(dest)
	return os.Rename(tmp, dest)
}

// keepOnly deletes every file in dir except the ones named, so the data folder
// does not slowly fill with old builds on a shop PC nobody cleans up.
func keepOnly(dir string, names ...string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	keep := map[string]bool{}
	for _, n := range names {
		keep[n] = true
	}
	for _, e := range entries {
		if e.IsDir() || keep[e.Name()] {
			continue
		}
		os.Remove(filepath.Join(dir, e.Name()))
	}
}

// ---------------------------------------------------------------- the poller

func (s *Server) autoUpdate() {
	time.Sleep(updateFirstCheck)
	idleTick := time.NewTicker(updateIdleEvery)
	defer idleTick.Stop()
	pollTick := time.NewTicker(updateCheckEvery)
	defer pollTick.Stop()

	s.checkForUpdates()
	for {
		select {
		case <-pollTick.C:
			s.checkForUpdates()
		case <-idleTick.C:
			s.applyStagedIfIdle()
		}
	}
}

func (s *Server) checkForUpdates() {
	if !s.updatesEnabled() {
		return
	}
	base := s.updateBase()
	s.checkAndroidUpdate(base)
	s.checkServerUpdate(base)
	setMeta(s.db, "update_last_check", fmt.Sprint(nowMs()))
}

// checkAndroidUpdate keeps the newest APK on local disk. Phones ask for it over
// the LAN; this machine is the only one that ever talks to the internet.
func (s *Server) checkAndroidUpdate(base string) {
	var m androidManifest
	if err := fetchJSON(base+"/android/latest.json", &m); err != nil {
		log.Printf("update: android manifest: %v", err)
		return
	}
	if m.VersionCode <= 0 || m.File == "" {
		return
	}
	dir := updatesDir(s.dataDir)
	dest := filepath.Join(dir, m.File)

	if got, err := fileSHA256(dest); err == nil && got == m.SHA256 {
		s.rememberAPK(m) // already have it; make sure the note matches
		return
	}
	if err := download(m.artifact, dest); err != nil {
		log.Printf("update: apk download: %v", err)
		return
	}
	s.rememberAPK(m)
	keepOnly(dir, m.File, "staged.json", stagedExeName(s.dataDir))
	log.Printf("update: app %s (code %d) ready for the phones — %s", m.VersionName, m.VersionCode, m.Notes)
	s.hub.Broadcast("app-update")
}

func (s *Server) rememberAPK(m androidManifest) {
	b, err := json.Marshal(m)
	if err != nil {
		return
	}
	setMeta(s.db, "update_android", string(b))
}

func (s *Server) cachedAPK() (androidManifest, bool) {
	var m androidManifest
	raw := getMeta(s.db, "update_android", "")
	if raw == "" {
		return m, false
	}
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return m, false
	}
	if _, err := os.Stat(filepath.Join(updatesDir(s.dataDir), m.File)); err != nil {
		return m, false
	}
	return m, true
}

// checkServerUpdate downloads a new exe and leaves it staged. Nothing restarts
// here — swapping mid-service is the one thing this must never do on its own.
func (s *Server) checkServerUpdate(base string) {
	var m serverManifest
	if err := fetchJSON(base+"/server/latest.json", &m); err != nil {
		log.Printf("update: server manifest: %v", err)
		return
	}
	if m.Build == "" || m.Build == buildStamp {
		return
	}
	a := m.AMD64
	if runtime.GOARCH == "386" {
		a = m.I386
	}
	if a.File == "" {
		return // this release did not ship our architecture
	}
	if st, ok := readStaged(s.dataDir); ok && st.Build == m.Build {
		return // already sitting there waiting
	}
	dir := updatesDir(s.dataDir)
	dest := filepath.Join(dir, a.File)
	if err := download(a, dest); err != nil {
		log.Printf("update: server download: %v", err)
		return
	}
	st := stagedUpdate{
		Build:    m.Build,
		File:     a.File,
		SHA256:   a.SHA256,
		Notes:    m.Notes,
		StagedAt: time.Now().Format(time.RFC3339),
	}
	if err := writeStaged(s.dataDir, st); err != nil {
		log.Printf("update: staging note: %v", err)
		return
	}
	log.Printf("update: server build %s downloaded and staged — it goes live at the next quiet moment or restart", m.Build)
	s.hub.Broadcast("app-update")
}

func readStaged(dataDir string) (stagedUpdate, bool) {
	var st stagedUpdate
	raw, err := os.ReadFile(filepath.Join(updatesDir(dataDir), "staged.json"))
	if err != nil {
		return st, false
	}
	if err := json.Unmarshal(raw, &st); err != nil || st.File == "" {
		return st, false
	}
	return st, true
}

func writeStaged(dataDir string, st stagedUpdate) error {
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	path := filepath.Join(updatesDir(dataDir), "staged.json")
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, append(b, '\n'), 0o644); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

func stagedExeName(dataDir string) string {
	if st, ok := readStaged(dataDir); ok {
		return st.File
	}
	return ""
}

// applyStagedIfIdle takes the free lunch: a staged build, no open orders, no
// phone connected. Three seconds of downtime nobody is awake to notice.
func (s *Server) applyStagedIfIdle() {
	if _, ok := readStaged(s.dataDir); !ok {
		return
	}
	if s.hub.Count() > 0 {
		return
	}
	var open int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM orders WHERE status='open'`).Scan(&open); err != nil || open > 0 {
		return
	}
	log.Println("update: shop is idle — installing the new server build now")
	go func() {
		time.Sleep(500 * time.Millisecond)
		os.Exit(updateExitCode)
	}()
}

// ---------------------------------------------------------------- the swap

// applyStagedUpdate runs in the supervisor, before the server exists. A running
// exe cannot be overwritten, but it can be renamed out of the way — which is
// exactly what leaves the old build sitting there as the rollback.
//
// `exe` is passed in, never looked up here: on Linux /proc/self/exe follows the
// rename, so after one swap os.Executable() reports the *.old path and every
// later swap or rollback would aim at the wrong file.
func applyStagedUpdate(dataDir, exe string) bool {
	st, ok := readStaged(dataDir)
	if !ok {
		return false
	}
	notePath := filepath.Join(updatesDir(dataDir), "staged.json")
	src := filepath.Join(updatesDir(dataDir), st.File)

	if st.Build == buildStamp {
		os.Remove(notePath) // we are already this build
		return false
	}
	got, err := fileSHA256(src)
	if err != nil || got != st.SHA256 {
		log.Printf("update: staged build failed its checksum, discarding it (%v)", err)
		os.Remove(src)
		os.Remove(notePath)
		return false
	}
	old := exe + ".old"

	os.Remove(old)
	if err := os.Rename(exe, old); err != nil {
		log.Printf("update: cannot move the old exe aside: %v", err)
		return false
	}
	if err := installExe(src, exe); err != nil {
		log.Printf("update: install failed (%v) — putting the old build back", err)
		os.Remove(exe)
		os.Rename(old, exe)
		return false
	}
	os.Remove(notePath)
	os.Remove(src)
	log.Printf("update: installed server build %s — %s", st.Build, st.Notes)
	return true
}

// installExe writes the new binary straight to the path the supervisor will
// launch. Unlike copyFile it must land executable, and it must not go via a
// .tmp+rename dance — the destination name is the one thing that has to be
// right the moment it exists.
func installExe(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	return os.Chmod(dst, 0o755)
}

// rollbackUpdate puts yesterday's build back. This is the whole reason the old
// exe is renamed rather than deleted: a build that will not even start must not
// be able to keep a shop shut until someone who knows about files walks in.
func rollbackUpdate(exe string) bool {
	old := exe + ".old"
	if _, err := os.Stat(old); err != nil {
		return false
	}
	if err := installExe(old, exe); err != nil {
		log.Printf("update: rollback failed: %v", err)
		return false
	}
	os.Remove(old)
	log.Println("update: the new build would not start — put the previous one back")
	return true
}

// exitCodeOf digs the process exit status out of whatever Wait returned.
func exitCodeOf(err error) int {
	var ee *exec.ExitError
	if errors.As(err, &ee) {
		return ee.ExitCode()
	}
	return -1
}

// ---------------------------------------------------------------- endpoints

// handleAppUpdate is what a phone asks. It answers with the APK this machine is
// already holding; the phone decides whether that beats what it is running.
func (s *Server) handleAppUpdate(w http.ResponseWriter, r *http.Request) {
	m, ok := s.cachedAPK()
	if !ok {
		writeJSON(w, 200, map[string]any{"available": false, "serverBuild": buildStamp})
		return
	}
	writeJSON(w, 200, map[string]any{
		"available":   true,
		"versionCode": m.VersionCode,
		"versionName": m.VersionName,
		"notes":       m.Notes,
		"mandatory":   m.Mandatory,
		"size":        m.Size,
		"sha256":      m.SHA256,
		"url":         "/api/app-update.apk",
		"serverBuild": buildStamp,
	})
}

func (s *Server) handleAppUpdateAPK(w http.ResponseWriter, r *http.Request) {
	m, ok := s.cachedAPK()
	if !ok {
		http.NotFound(w, r)
		return
	}
	path := filepath.Join(updatesDir(s.dataDir), m.File)
	f, err := os.Open(path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", "attachment; filename=\""+m.File+"\"")
	http.ServeContent(w, r, m.File, fi.ModTime(), f)
}

// handleUpdateStatus is the owner's one-glance answer to "is anything waiting?"
func (s *Server) handleUpdateStatus(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{
		"serverBuild": buildStamp,
		"enabled":     s.updatesEnabled(),
		"base":        s.updateBase(),
		"lastCheck":   getMeta(s.db, "update_last_check", ""),
	}
	if st, ok := readStaged(s.dataDir); ok {
		out["pendingServer"] = st
	}
	if m, ok := s.cachedAPK(); ok {
		out["app"] = map[string]any{
			"versionCode": m.VersionCode,
			"versionName": m.VersionName,
			"notes":       m.Notes,
		}
	}
	writeJSON(w, 200, out)
}

// handleUpdateCheck forces a poll right now, so a fix can be pushed and pulled
// inside a minute instead of waiting for the next half-hour tick.
func (s *Server) handleUpdateCheck(w http.ResponseWriter, r *http.Request) {
	go s.checkForUpdates()
	writeJSON(w, 202, map[string]any{"ok": true})
}

// handleUpdateApply installs a staged server build on the owner's say-so.
func (s *Server) handleUpdateApply(w http.ResponseWriter, r *http.Request) {
	st, ok := readStaged(s.dataDir)
	if !ok {
		writeJSON(w, 400, map[string]any{"error": "nothing staged"})
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "build": st.Build,
		"message": "installing — the server comes back in a few seconds"})
	go func() {
		time.Sleep(700 * time.Millisecond) // let the reply reach the browser
		os.Exit(updateExitCode)
	}()
}
