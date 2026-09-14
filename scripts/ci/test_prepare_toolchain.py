"""Exercise diagnostic failures with synthetic executables; never install an SDK."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class PrepareToolchainTest(unittest.TestCase):
    def run_fixture(self, api="37.0", install_exit=0):
        with tempfile.TemporaryDirectory(prefix="vialen-toolchain-test-") as directory:
            root = Path(directory)
            sdk = root / "sdk"
            binaries = root / "bin"
            binaries.mkdir()
            def executable(path, body):
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("#!/bin/sh\n" + body + "\n")
                path.chmod(0o755)
            executable(sdk / "cmdline-tools/latest/bin/sdkmanager", f"exit {install_exit}")
            executable(binaries / "go", "echo go1.27.1")
            executable(binaries / "java", "echo '    java.version = 25.0.2' >&2")
            ndk = sdk / "ndk/28.1.13356709"
            ndk.mkdir(parents=True)
            (ndk / "source.properties").write_text("Pkg.Revision=28.1.13356709\n")
            tools = sdk / "build-tools/36.0.0"
            tools.mkdir(parents=True)
            (tools / "source.properties").write_text("Pkg.Revision=36.0.0\n")
            platform = sdk / "platforms/android-37.0"
            platform.mkdir(parents=True)
            (platform / "source.properties").write_text(f"AndroidVersion.ApiLevel={api}\n")
            (platform / "android.jar").touch()
            for path in [tools / "zipalign", tools / "aapt2", ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"]:
                executable(path, "echo synthetic-tool")
            report = root / "report.txt"
            env = dict(os.environ, ANDROID_HOME=str(sdk), GITHUB_ENV=str(root / "env"), GITHUB_PATH=str(root / "path"), PATH=str(binaries) + ":/usr/bin:/bin")
            result = subprocess.run(["bash", str(Path(__file__).with_name("prepare-toolchain.sh")), str(report)], env=env, capture_output=True, text=True)
            return result.returncode, report.read_text()

    def test_pinned_platform_minor_version_passes(self):
        status, report = self.run_fixture()
        self.assertEqual(0, status, report)
        self.assertIn("SDK API level expected=37.0 actual=37.0", report)
        self.assertIn("All pinned toolchain checks completed", report)

    def test_version_failure_preserves_expected_and_actual(self):
        status, report = self.run_fixture(api="36")
        self.assertNotEqual(0, status)
        self.assertIn("SDK API level expected=37.0 actual=36", report)
        self.assertIn("[FAIL] SDK API level", report)

    def test_install_failure_preserves_stage(self):
        status, report = self.run_fixture(install_exit=9)
        self.assertEqual(9, status)
        self.assertIn("[FAIL] install Android SDK", report)


if __name__ == "__main__":
    unittest.main()
