//go:build !windows

package main

// run starts the server. Non-Windows platforms have no desktop control window,
// so it always runs headless. The headless flag is accepted for flag parity.
func run(port int, dataDir string, headless bool) {
	serveHeadless(port, dataDir)
}
