#!/usr/bin/env python3
"""Small CI provenance/unsigned-artifact checks; no signing or publishing."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd or ROOT, text=True).strip()


def require(ok, message):
    if not ok:
        raise SystemExit(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def record(path):
    return {"sha256": digest(path), "bytes": path.stat().st_size}


def write(path, data):
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")


def source():
    require(not run("git", "status", "--porcelain=v1", "--untracked-files=no"),
            "Tracked source changed during CI")
    files = {}
    for name in filter(None, run("git", "ls-files", "-z").split("\0")):
        path = ROOT / name
        raw = os.readlink(path).encode() if path.is_symlink() else path.read_bytes()
        files[name] = {"sha256": hashlib.sha256(raw).hexdigest(),
                       "mode": oct(path.lstat().st_mode & 0o777),
                       "symlink": path.is_symlink()}
    return {"schema": 1, "source_sha": run("git", "rev-parse", "HEAD"),
            "scope": "tracked checkout only; build outputs recorded separately", "files": files}


def check_archive(path, prefix):
    with zipfile.ZipFile(path) as archive:
        require(archive.testzip() is None, "Corrupt archive")
        names = archive.namelist()
        libraries = [n for n in names if n.endswith(".so")]
        require(libraries, "No native libraries in artifact")
        require(all(n.startswith(prefix + "/arm64-v8a/") for n in libraries),
                "Artifact contains non-ARM64 or unexpected native libraries")
        # Supplement the existing shell alignment check: reject absent LOADs or
        # malformed ELF headers instead of allowing a vacuous alignment PASS.
        for name in libraries:
            elf = archive.read(name)
            require(elf[:6] == b"\x7fELF\x02\x01", "Expected 64-bit little-endian ELF")
            require(struct.unpack_from("<H", elf, 18)[0] == 183, "Expected AArch64 ELF")
            start = struct.unpack_from("<Q", elf, 32)[0]
            size, count = struct.unpack_from("<HH", elf, 54)
            require(size >= 56 and start + size * count <= len(elf), "Invalid ELF program headers")
            loads = [struct.unpack_from("<Q", elf, start + i * size + 48)[0]
                     for i in range(count) if struct.unpack_from("<I", elf, start + i * size)[0] == 1]
            require(loads and all(a >= 16384 and a & (a - 1) == 0 for a in loads),
                    "Missing LOAD segments or invalid/sub-16KB ELF alignment")
        if prefix == "lib":
            require(not any(re.match(r"META-INF/[^/]+\.(RSA|DSA|EC|SF)$", n, re.I) for n in names),
                    "APK has JAR signature entries; unsigned build required")
    if prefix == "lib":
        raw = path.read_bytes()
        end = raw.rfind(b"PK\x05\x06")
        require(end >= 0 and len(raw) >= end + 22, "APK ZIP end record missing")
        central = struct.unpack_from("<I", raw, end + 16)[0]
        require(raw[central - 16:central] != b"APK Sig Block 42", "APK has a signing block")


def verify_core(folder):
    manifest_file = folder / "core-manifest.json"
    require(digest(manifest_file) == os.environ["EXPECTED_CORE_MANIFEST_SHA"],
            "Core manifest differs from producing job digest")
    manifest = json.loads(manifest_file.read_text())
    require(manifest["source_sha"] == os.environ["EXPECTED_SOURCE_SHA"] == source()["source_sha"],
            "Core/source SHA mismatch")
    require(set(manifest["files"]) == {"libcore.aar", "source-manifest.json", "toolchain.txt",
                                     "go-modules.json", "go-module-verification.txt"},
            "Unexpected core manifest file set")
    for name, expected in manifest["files"].items():
        require(record(folder / name) == expected, "Core handoff file mismatch: " + name)
    require(json.loads((folder / "source-manifest.json").read_text()) == source(),
            "Checkout contents differ from producing job")
    check_archive(folder / "libcore.aar", "jni")
    return manifest


def core(folder):
    require(json.loads((folder / "source-manifest.json").read_text()) == source(),
            "Source content changed while building core")
    check_archive(folder / "libcore.aar", "jni")
    pin_file = (ROOT / "buildScript/lib/core/get_source_env.sh").read_text()
    pins = dict(re.findall(r'export COMMIT_(\w+)="([0-9a-f]{40})"', pin_file))
    repositories = {}
    for name, key in [("sing-box", "SING_BOX"), ("libneko", "LIBNEKO")]:
        repo = ROOT.parent / name
        require(run("git", "rev-parse", "HEAD", cwd=repo) == pins[key], "Replace repository pin mismatch")
        require(not run("git", "status", "--porcelain=v1", cwd=repo), "Replace repository is dirty")
        repositories[name] = {"commit": pins[key]}
    tools = {}
    for name in ["gomobile-matsuri", "gobind-matsuri"]:
        path = Path(os.environ["GOPATH"]) / "bin" / name
        info = run("go", "version", "-m", str(path))
        require("vcs.revision=" + pins["GOMOBILE"] in info and "vcs.modified=false" in info,
                "Gomobile tool revision mismatch")
        require("go1.26.5" in info.splitlines()[0], "Gomobile tool built with unexpected Go")
        tools[name] = {**record(path), "build_info": info}
    module_json = run("go", "list", "-mod=readonly", "-m", "-json", "all", cwd=ROOT / "libcore")
    # go list emits a JSON stream, not a single JSON array.
    decoder, modules = json.JSONDecoder(), []
    rest = module_json
    while rest.strip():
        value, end = decoder.raw_decode(rest.lstrip())
        rest = rest.lstrip()[end:]
        modules.append(value)
    write(folder / "go-modules.json", modules)
    (folder / "go-module-verification.txt").write_text(
        run("go", "mod", "verify", cwd=ROOT / "libcore") + "\n")
    generated = {}
    for path in (ROOT / "libcore/.build").rglob("go.*"):
        if path.name in {"go.mod", "go.sum"}:
            generated[str(path.relative_to(ROOT))] = {**record(path), "text": path.read_text()}
    require(generated, "Generated gomobile module files missing; provenance incomplete")
    require(json.loads((folder / "source-manifest.json").read_text()) == source(),
            "Module verification changed tracked inputs")
    names = ["libcore.aar", "source-manifest.json", "toolchain.txt", "go-modules.json", "go-module-verification.txt"]
    write(folder / "core-manifest.json", {
        "schema": 1, "source_sha": source()["source_sha"],
        "kind": "traceable CI build; not bit-for-bit reproducibility",
        "files": {name: record(folder / name) for name in names},
        "replace_repositories": repositories, "gomobile_tools": tools,
        "generated_module_files": generated,
        "generated_module_verify": "not run; source-module verify is separate from generated local-replace limitations",
        "bind": {"target": "android/arm64", "api": 21, "flags_source": "libcore/build.sh"}})



def resource_block(resources, identifier):
    lines = resources.splitlines()
    starts = [i for i, line in enumerate(lines) if line.lstrip().startswith("resource ")
              and identifier in line.split()]
    require(len(starts) == 1, "Missing or ambiguous resource: " + identifier)
    start = starts[0]
    end = next((i for i in range(start + 1, len(lines)) if lines[i].lstrip().startswith("resource ")), len(lines))
    return "\n".join(lines[start:end])


def check_locales(path, badging):
    # AAPT reports default-language resources as --_--, not necessarily en.
    # Establish English from the APK localeConfig AND actual default strings.
    locales = re.search(r"^locales:(.*)$", badging, re.M)
    require(locales is not None, "Missing locale metadata")
    names = set(re.findall(r"'([^']*)'", locales.group(1)))
    chinese = {"zh-CN", "zh-HK", "zh-TW"}
    require(chinese <= names and names <= chinese | {"--_--", "en", "en-US"},
            "Missing Chinese locale or unexpected APK language")
    manifest = run("aapt2", "dump", "xmltree", str(path), "--file", "AndroidManifest.xml")
    config_ids = re.findall(r":localeConfig\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)", manifest)
    require(len(config_ids) == 1, "APK localeConfig resource reference missing or ambiguous")
    resources = run("aapt2", "dump", "resources", str(path))
    config_files = re.findall(r"\(file\) (res/\S+\.xml) type=XML", resource_block(resources, config_ids[0]))
    require(config_files, "APK localeConfig XML missing")
    configs = {}
    for config_file in config_files:
        tree = run("aapt2", "dump", "xmltree", str(path), "--file", config_file)
        language_names = re.findall(r':name\(0x01010003\)="([^"]+)"', tree)
        require("E: locale-config " in tree and len(language_names) == 4 and
                set(language_names) == chinese | {"en-US"}, "Unexpected localeConfig languages")
        configs[config_file] = language_names
    properties = dict(line.split("=", 1) for line in (ROOT / "app/src/main/res/resources.properties").read_text().splitlines() if "=" in line)
    require(properties.get("unqualifiedResLocale") == "en-US", "Default source locale is not en-US")
    filters = re.findall(r'resourceConfigurations\s*\+=\s*listOf\(([^)]*)\)', (ROOT / "app/build.gradle.kts").read_text())
    require(len(filters) == 1 and set(re.findall(r'"([^"]+)"', filters[0])) == {"en", "zh-rCN", "zh-rHK", "zh-rTW"},
            "Unexpected source resourceConfigurations")
    strings = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
    source_strings = {item.get("name"): "".join(item.itertext()) for item in strings.findall("string")}
    english = {"connect": "Connect", "vialen_release_unconfigured": "Vialen release channel is not configured."}
    for name, expected in english.items():
        defaults = re.findall(r'^\s+\(\) (".*")$', resource_block(resources, "string/" + name), re.M)
        require(len(defaults) == 1 and json.loads(defaults[0]) == expected and source_strings.get(name) == expected,
                "Default English string not proven: " + name)
    return {"aapt_locales": sorted(names), "locale_config_resource": config_ids[0], "locale_config_variants": configs,
            "source_default_locale": "en-US", "default_english_strings_verified_in_apk_and_source": english,
            "source_resource_configurations": ["en", "zh-rCN", "zh-rHK", "zh-rTW"],
            "resource_dump_sha256": hashlib.sha256(resources.encode()).hexdigest()}


def apk(folder):
    require(len(list(folder.glob("*.apk"))) == 1, "Expected exactly one APK")
    path = next(folder.glob("*.apk"))
    require(path.name.endswith("-arm64-v8a-unsigned.apk"), "Expected explicit unsigned APK name")
    check_archive(path, "lib")
    badging = (folder / "badging.txt").read_text()
    require("package: name='com.vialen.app'" in badging, "Unexpected package ID")
    require("application-debuggable" not in badging, "Debuggable APK")
    require("native-code: 'arm64-v8a'" in badging, "Unexpected badging ABI")
    write(folder / "locale-evidence.json", check_locales(path, badging))
    props = dict(line.split("=", 1) for line in (ROOT / "nb4a.properties").read_text().splitlines() if "=" in line)
    require("versionCode='" + str(int(props["VERSION_CODE"]) * 5) + "'" in badging and
            "versionName='" + props["VERSION_NAME"] + "'" in badging, "APK version differs from source")
    require((folder / "mapping.txt").stat().st_size > 0, "R8 mapping is missing")
    current = source()
    require(json.loads((folder.parent / "core/source-manifest.json").read_text()) == current,
            "Source changed during APK build")
    write(folder / "release-manifest.json", {
        "schema": 1, "source_sha": current["source_sha"], "signing": "unsigned", "publishable": False,
        "files": {p.name: record(p) for p in sorted(folder.iterdir()) if p.is_file() and p.name != "release-manifest.json"},
        "core_manifest_sha256": digest(folder.parent / "core/core-manifest.json"),
        "runtime_validation": "not performed by this workflow; no device/OEM/real-subscription claim",
        "formal_release_requires": ["approved signing identity and signed-artifact revalidation", "controlled release URL", "explicit publication approval"]})


if __name__ == "__main__":
    require(len(sys.argv) == 3, "Usage: artifacts.py source|core|verify-core|apk <output path>")
    command, location = sys.argv[1], Path(sys.argv[2]).resolve()
    if command == "source":
        write(location, source())
    elif command == "core":
        core(location)
    elif command == "verify-core":
        verify_core(location)
    elif command == "apk":
        apk(location)
    else:
        raise SystemExit("Unknown command")
