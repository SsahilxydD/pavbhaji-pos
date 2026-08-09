//go:build !windows

package main

import (
	"os"
	"path/filepath"
)

// removableDirs lists mounted external media (the desktop-Linux mount spots).
func removableDirs() []string {
	var out []string
	for _, pat := range []string{"/run/media/*/*", "/media/*/*", "/media/*"} {
		m, _ := filepath.Glob(pat)
		for _, d := range m {
			if fi, err := os.Stat(d); err == nil && fi.IsDir() {
				out = append(out, d)
			}
		}
	}
	return out
}
