package main

import (
	"syscall"
	"unsafe"
)

// removableDirs lists the roots of plugged-in USB sticks (D:\ … Z:\ whose
// drive type is DRIVE_REMOVABLE).
func removableDirs() []string {
	var out []string
	getDriveType := syscall.NewLazyDLL("kernel32.dll").NewProc("GetDriveTypeW")
	for c := 'D'; c <= 'Z'; c++ {
		root := string(c) + `:\`
		p, err := syscall.UTF16PtrFromString(root)
		if err != nil {
			continue
		}
		t, _, _ := getDriveType.Call(uintptr(unsafe.Pointer(p)))
		const driveRemovable = 2
		if t == driveRemovable {
			out = append(out, root)
		}
	}
	return out
}
