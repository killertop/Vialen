//go:build android

package libcore

import (
	"golang.org/x/sys/unix"
	"time"
)

func diagnosticNow() (time.Time, error) {
	var ts unix.Timespec
	if err := unix.ClockGettime(unix.CLOCK_BOOTTIME, &ts); err != nil {
		return time.Time{}, err
	}
	// Synthetic epoch represents boot time including suspend, unaffected by
	// user/NTP wall-clock adjustments. Never fall back to a different clock.
	return time.Unix(ts.Sec, ts.Nsec), nil
}
