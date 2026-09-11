//go:build android

package libcore

import (
	"fmt"
	"golang.org/x/mobile/asset"
	"io"
	"log"
	"os"
	"path/filepath"
)

// Rule sets are managed by sing-box (remote) or explicitly imported (local).
// Only the bundled dashboard is extracted from APK assets.
func extractAssets() {
	if err := extractDashboard(); err != nil {
		log.Println("Extract dashboard:", err)
	}
}

func extractDashboard() error {
	version, err := asset.Open(yacdVersion)
	if err != nil {
		return err
	}
	raw, err := io.ReadAll(version)
	version.Close()
	if err != nil {
		return err
	}
	versionPath := filepath.Join(internalAssetsPath, yacdVersion)
	destination := filepath.Join(internalAssetsPath, yacdDstFolder)
	old, _ := os.ReadFile(versionPath)
	if string(old) == string(raw) {
		if info, err := os.Stat(destination); err == nil && info.IsDir() {
			return nil
		}
	}
	packed, err := asset.Open("yacd.zip")
	if err != nil {
		return err
	}
	defer packed.Close()
	stage, err := os.MkdirTemp(internalAssetsPath, "dashboard-")
	if err != nil {
		return err
	}
	defer os.RemoveAll(stage)
	archive := filepath.Join(stage, "dashboard.zip")
	f, err := os.Create(archive)
	if err != nil {
		return err
	}
	_, err = io.Copy(f, packed)
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = Unzip(archive, stage); err != nil {
		return err
	}
	dirs, err := filepath.Glob(filepath.Join(stage, "Yacd-*"))
	if err != nil || len(dirs) != 1 {
		return fmt.Errorf("invalid dashboard archive")
	}
	backup := filepath.Join(stage, "previous")
	if _, err = os.Stat(destination); err == nil {
		if err = os.Rename(destination, backup); err != nil {
			return err
		}
	}
	if err = os.Rename(dirs[0], destination); err != nil {
		_ = os.Rename(backup, destination)
		return err
	}
	return os.WriteFile(versionPath, raw, 0600)
}
