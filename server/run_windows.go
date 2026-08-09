//go:build windows

package main

// run shows the desktop control window by default. Passing -headless runs the
// server in the foreground instead (useful for autostart or running as a service).
func run(port int, dataDir string, headless bool) {
	if headless {
		serveHeadless(port, dataDir)
		return
	}
	runGUI(port, dataDir)
}
