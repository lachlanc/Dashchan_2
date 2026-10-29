"""Check the fork build/sync flow without Gradle, an SDK or the live APK repository."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "fork-release.sh"
BADGING = (
    "package: name='io.dashchan2' versionCode='11532' versionName='3.2.72-f1' "
    "platformBuildVersionName='16' compileSdkVersion='37'\n"
    "native-code: 'arm64-v8a'\n"
)


class ForkReleaseTest(unittest.TestCase):
    def setUp(self):
        scratch = Path("/tmp/opencode")
        scratch.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="slooop-release-test-", dir=scratch)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.share = self.root / "share"
        self.fdroid = self.root / "fdroid"
        self.bin = self.root / "bin"
        self.sdk = self.root / "sdk"
        self.java = self.root / "java"
        for directory in [self.repo, self.fdroid / "repo", self.bin, self.java / "bin"]:
            directory.mkdir(parents=True)
        tools = self.sdk / "build-tools" / "36.0.0"
        tools.mkdir(parents=True)
        self.keystore = self.root / "fork.jks"
        self.keystore.write_bytes(b"fake keystore")
        self.repo.joinpath("fork-version.properties").write_text("forkVersion=0\n")
        self.repo.joinpath("keystore.properties").write_text(
            f"store.file={self.keystore}\nstore.password=pw\nkey.alias=slooop\nkey.password=pw\n")
        self.repo.joinpath("local.properties").write_text(f"sdk.dir={self.sdk}\n")
        self.java.joinpath("bin/java").write_text("#!/bin/sh\nexit 0\n")
        self.java.joinpath("bin/java").chmod(0o755)
        self.gradle = self.repo / "gradlew"
        self.stub_gradle(0)
        self.stub(self.bin / "fdroid",
                  'echo update >> "$FDROID_DIR/calls"\nexit "${TEST_FDROID_EXIT:-0}"')
        self.stub(self.bin / "curl", '''
for arg; do url=$arg; done
case "$url" in */fdroid/repo/*) ;; *) exit 1 ;; esac
if [ "${TEST_BAD_DOWNLOAD:-0}" = 1 ]; then echo wrong; exit 0; fi
cat "$FDROID_DIR/repo/${url##*/}"
''')
        self.stub(tools / "zipalign", 'cp "$4" "$5"')
        self.stub(tools / "apksigner", '''
if [ "$1" = verify ]; then exit 0; fi
out=
previous=
for arg; do
  if [ "$previous" = --out ]; then out=$arg; fi
  previous=$arg
  input=$arg
done
cp "$input" "$out"
''')
        self.stub(tools / "aapt2", 'echo "$AAPT_BADGING"')
        self.env = os.environ | {
            "FORK_REPO": str(self.repo),
            "SHARE_DIR": str(self.share),
            "FDROID_DIR": str(self.fdroid),
            "FDROID_BASE_URL": "http://127.0.0.1:8099/fdroid/repo",
            "JAVA_HOME": str(self.java),
            "GRADLEW": str(self.gradle),
            "PATH": str(self.bin) + os.pathsep + os.environ["PATH"],
            "AAPT_BADGING": BADGING,
        }

    def stub(self, path, body):
        path.write_text("#!/bin/sh\nset -eu\n" + body + "\n")
        path.chmod(0o755)

    def stub_gradle(self, exit_code):
        out = self.repo / "build/outputs/apk/github/release"
        out.mkdir(parents=True, exist_ok=True)
        body = f'''
out="{out}"
if [ "{exit_code}" = 0 ]; then
  printf 'unsigned' > "$out/app-arm64-v8a-unsigned.apk"
  exit 0
fi
exit {exit_code}
'''
        self.stub(self.gradle, body)

    def run_script(self, command, **env):
        return subprocess.run(["bash", str(SCRIPT), command], env=self.env | env,
                              text=True, capture_output=True)

    def fork_version(self):
        return self.repo.joinpath("fork-version.properties").read_text().strip()

    def test_build_stages_a_signed_versioned_apk_and_bumps_the_counter(self):
        result = self.run_script("build")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("forkVersion=1", self.fork_version())
        self.assertTrue((self.share / "Slooop-3.2.72-f1-arm64-v8a.apk").is_file())
        self.assertTrue((self.share / "version.properties").read_text().endswith("abi=arm64-v8a\n"))
        self.assertTrue((self.share / "SHA256SUMS").is_file())

    def test_failed_build_reverts_the_counter(self):
        self.stub_gradle(1)
        result = self.run_script("build")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("forkVersion=0", self.fork_version())
        self.assertFalse(list(self.share.glob("*.apk")) if self.share.exists() else [])

    def test_missing_keystore_properties_stops_before_gradle(self):
        self.repo.joinpath("keystore.properties").unlink()
        result = self.run_script("build")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("never generate a new key", result.stderr)
        self.assertEqual("forkVersion=0", self.fork_version())

    def test_wrong_package_is_rejected(self):
        result = self.run_script("build", AAPT_BADGING=BADGING.replace("io.dashchan2", "com.other"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unexpected package name", result.stderr)

    def stage(self, version="3.2.72-f1", abi="arm64-v8a", content=b"apk"):
        self.share.mkdir(parents=True, exist_ok=True)
        name = f"Slooop-{version}-{abi}.apk"
        (self.share / name).write_bytes(content)
        (self.share / "version.properties").write_text(f"versionName={version}\nabi={abi}\n")
        return name

    def test_sync_publishes_the_staged_version_and_verifies_the_served_bytes(self):
        self.stage()
        result = self.run_script("sync")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b"apk", (self.fdroid / "repo" / "Slooop-3.2.72-f1.apk").read_bytes())
        self.assertEqual(["update"], (self.fdroid / "calls").read_text().splitlines())

    def test_sync_rejects_ambiguous_or_mismatched_staging(self):
        self.stage()
        self.stage(abi="x86")
        self.assertNotEqual(0, self.run_script("sync").returncode)
        (self.share / "Slooop-3.2.72-f1-x86.apk").unlink()
        self.share.joinpath("version.properties").write_text("versionName=3.2.72-f2\n")
        result = self.run_script("sync")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not match", result.stderr)

    def test_failed_index_update_fails_and_rerun_retries_it(self):
        self.stage()
        failed = self.run_script("sync", TEST_FDROID_EXIT="42")
        self.assertNotEqual(0, failed.returncode)
        success = self.run_script("sync")
        self.assertEqual(0, success.returncode, success.stderr)
        self.assertEqual(["update", "update"], (self.fdroid / "calls").read_text().splitlines())

    def test_incorrect_served_content_fails(self):
        self.stage()
        result = self.run_script("sync", TEST_BAD_DOWNLOAD="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum mismatch", result.stderr)


if __name__ == "__main__":
    unittest.main()
