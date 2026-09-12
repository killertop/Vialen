package libcore

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"
)

func TestRollingTailAndOversize(t *testing.T) {
	w := &rollingLog{path: filepath.Join(t.TempDir(), "neko.log")}
	for _, p := range [][]byte{bytes.Repeat([]byte("a"), coreLogLimit-5), []byte("1234567890"), bytes.Repeat([]byte("b"), coreLogLimit*3/4), bytes.Repeat([]byte("z"), coreLogLimit*3)} {
		before, _ := os.ReadFile(w.path)
		n, err := w.Write(p)
		if err != nil || n != len(p) {
			t.Fatalf("write: %d %v", n, err)
		}
		want := append(before, p...)
		got, err := os.ReadFile(w.path)
		if err != nil || len(got) > coreLogLimit || len(got) < min(len(p), coreLogLimit) || !bytes.HasSuffix(want, got) {
			t.Fatalf("incorrect retained tail: %v", err)
		}
	}
	w.clear()
	got, _ := os.ReadFile(w.path)
	if len(got) != 0 {
		t.Fatal("clear failed")
	}
	if _, err := w.Write([]byte("after")); err != nil {
		t.Fatal(err)
	}
	got, _ = os.ReadFile(w.path)
	if string(got) != "after" {
		t.Fatal("stale offset after clear")
	}
}

func TestRollingProcessHelper(t *testing.T) {
	path := os.Getenv("VIALEN_LOG_TEST_PATH")
	if path == "" {
		return
	}
	w := &rollingLog{path: path}
	var wg sync.WaitGroup
	for g := 0; g < 4; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < 80; i++ {
				if i%17 == 0 {
					w.clear()
				}
				if _, err := w.Write(bytes.Repeat([]byte("x"), 4096)); err != nil {
					t.Error(err)
					return
				}
				if st, err := os.Stat(path); err != nil || st.Size() > coreLogLimit {
					t.Errorf("bound violated: %v %v", st, err)
					return
				}
			}
		}()
	}
	wg.Wait()
}

func TestRollingConcurrentProcesses(t *testing.T) {
	path := filepath.Join(t.TempDir(), "neko.log")
	commands := make([]*exec.Cmd, 2)
	for i := range commands {
		commands[i] = exec.Command(os.Args[0], "-test.run=^TestRollingProcessHelper$")
		commands[i].Env = append(os.Environ(), "VIALEN_LOG_TEST_PATH="+path)
		commands[i].Stdout = os.Stdout
		commands[i].Stderr = os.Stderr
		if err := commands[i].Start(); err != nil {
			t.Fatal(err)
		}
	}
	for _, cmd := range commands {
		if err := cmd.Wait(); err != nil {
			t.Fatal(err)
		}
	}
}

func TestRollingTrimsLegacyFile(t *testing.T) {
	w := &rollingLog{path: filepath.Join(t.TempDir(), "neko.log")}
	if err := os.WriteFile(w.path, bytes.Repeat([]byte("q"), coreLogLimit*2), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write(nil); err != nil {
		t.Fatal(err)
	}
	st, _ := os.Stat(w.path)
	if st.Size() == 0 || st.Size() > coreLogLimit/2 {
		t.Fatal(st.Size())
	}
}

func TestRollingLeavesAppendHeadroom(t *testing.T) {
	w := &rollingLog{path: filepath.Join(t.TempDir(), "neko.log")}
	if _, err := w.Write(bytes.Repeat([]byte("a"), coreLogLimit)); err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write([]byte("newest")); err != nil {
		t.Fatal(err)
	}
	rolled, err := os.ReadFile(w.path)
	if err != nil || len(rolled) != coreLogLimit/2+len("newest") || !bytes.HasSuffix(rolled, []byte("newest")) {
		t.Fatalf("missing rollover headroom: size=%d err=%v", len(rolled), err)
	}
	// Subsequent small records must append without discarding existing history.
	for i := 0; i < 32; i++ {
		if _, err := w.Write([]byte("next")); err != nil {
			t.Fatal(err)
		}
	}
	got, err := os.ReadFile(w.path)
	want := append(rolled, bytes.Repeat([]byte("next"), 32)...)
	if err != nil || !bytes.Equal(got, want) {
		t.Fatalf("headroom was not used for appends: %v", err)
	}
}
