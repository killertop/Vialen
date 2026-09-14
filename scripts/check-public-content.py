#!/usr/bin/env python3
"""Conservative release checks; reports filenames, never matching secret values."""
import argparse
from pathlib import Path
import re
import subprocess
import zipfile

PATTERNS = {
    "personal home path": rb"/(?:Users|home)/[A-Za-z0-9_.-]+/",
    "private key": rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    "GitHub token": rb"gh[pousr]_[A-Za-z0-9]{30,}",
    "AWS access key": rb"AKIA[A-Z0-9]{16}",
    "wireless device identifier": rb"adb-[a-zA-Z0-9]+-[a-zA-Z0-9]+\._adb-tls-connect",
}
FORBIDDEN_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx"}

def check(name, data):
    errors = []
    if Path(name).suffix.lower() in FORBIDDEN_SUFFIXES:
        errors.append("signing material")
    for label, pattern in PATTERNS.items():
        if re.search(pattern, data):
            errors.append(label)
    return [f"{name}: {label}" for label in errors]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    errors = []
    if args.apk:
        with zipfile.ZipFile(args.apk) as archive:
            for name in archive.namelist():
                errors.extend(check(name, archive.read(name)))
    else:
        root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
        names = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"]).decode().split("\0")
        for name in sorted(set(names) - {""}):
            path = root / name
            if not path.is_file():
                continue
            errors.extend(check(name, path.read_bytes()))
            if name.startswith("qa/") and not name.startswith("qa/fixtures/") and name != "qa/README.md":
                errors.append(f"{name}: non-public QA evidence")
    for error in errors:
        print(error)
    print(f"Public-content check: {len(errors)} finding(s)")
    raise SystemExit(bool(errors))

if __name__ == "__main__":
    main()
