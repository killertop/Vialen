package libcore

import (
	"io"
	"log"
	"os"
	"sync"

	"golang.org/x/sys/unix"
)

const coreLogLimit = 256 * 1024

// Never rename/unlink the log: all processes flock the same inode. Each
// operation opens its own descriptor; the mutex also serializes goroutines.
type rollingLog struct {
	mu   sync.Mutex
	path string
}

var coreLog rollingLog

func setupCoreLog(path string) {
	coreLog.mu.Lock()
	coreLog.path = path
	coreLog.mu.Unlock()
	// Trim legacy oversized logs immediately. Do not dup stderr onto this file:
	// raw runtime/native writes cannot participate in the rolling lock protocol.
	_, _ = coreLog.Write(nil)
	log.SetFlags(log.LstdFlags | log.LUTC)
	log.SetOutput(&coreLog)
}

func (w *rollingLog) locked(action func(*os.File) error) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.path == "" {
		return nil
	}
	f, err := os.OpenFile(w.path, os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer f.Close()
	for {
		err = unix.Flock(int(f.Fd()), unix.LOCK_EX)
		if err != unix.EINTR {
			break
		}
	}
	if err != nil {
		return err
	}
	defer unix.Flock(int(f.Fd()), unix.LOCK_UN)
	return action(f)
}

func (w *rollingLog) Write(p []byte) (int, error) {
	n := len(p)
	if len(p) > coreLogLimit {
		p = p[len(p)-coreLogLimit:]
	}
	err := w.locked(func(f *os.File) error {
		st, err := f.Stat()
		if err != nil {
			return err
		}
		keep := int64(coreLogLimit - len(p))
		if st.Size() <= keep {
			_, err = f.WriteAt(p, st.Size())
			return err
		}
		// Leave append headroom instead of rewriting a full file per message.
		// Large records retain less history so the total still fits the cap.
		keep = min(keep, coreLogLimit/2)
		tail := make([]byte, int(keep)+len(p))
		if keep > 0 {
			if _, err = f.ReadAt(tail[:keep], st.Size()-keep); err != nil {
				return err
			}
		}
		copy(tail[keep:], p)
		// Truncate before writing, so even transient file size is bounded.
		if err = f.Truncate(0); err != nil {
			return err
		}
		written, err := f.WriteAt(tail, 0)
		if err == nil && written != len(tail) {
			err = io.ErrShortWrite
		}
		return err
	})
	if err != nil {
		return 0, err
	}
	return n, nil
}

func (w *rollingLog) clear() { _ = w.locked(func(f *os.File) error { return f.Truncate(0) }) }
