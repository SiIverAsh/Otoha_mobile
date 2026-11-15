import importlib.util
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("bump-release-version.py")
SPEC = importlib.util.spec_from_file_location("bump_release_version", SCRIPT)
VERSION_SCRIPT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = VERSION_SCRIPT
SPEC.loader.exec_module(VERSION_SCRIPT)


def source(version="0.2.1", code=5):
    return (
        "android {\n"
        "    defaultConfig {\n"
        f"        versionCode = {code}\n"
        f'        versionName = "{version}"\n'
        '        applicationId = "io.github.otoha.mobile"\n'
        "    }\n"
        "}\n"
    )


class BumpVersionTests(unittest.TestCase):
    def test_each_bump(self):
        for bump, expected in (("patch", "0.2.2"), ("minor", "0.3.0"), ("major", "1.0.0")):
            with self.subTest(bump=bump):
                result = VERSION_SCRIPT.bump_version_text(source(), bump)
                self.assertEqual(result.current_version, "0.2.1")
                self.assertEqual(result.version_name, expected)
                self.assertEqual(result.version_code, 6)
                self.assertEqual(result.tag, f"v{expected}")

    def test_decimal_increment_and_reset(self):
        for bump, expected in (("patch", "1.9.10"), ("minor", "1.10.0"), ("major", "2.0.0")):
            with self.subTest(bump=bump):
                result = VERSION_SCRIPT.bump_version_text(source("1.9.9", 99), bump)
                self.assertEqual(result.version_name, expected)
                self.assertEqual(result.version_code, 100)

    def test_only_values_change_and_crlf_is_preserved(self):
        original = source().replace("\n", "\r\n").replace(
            'versionName = "0.2.1"', 'versionName = "0.2.1" // current version'
        )
        result = VERSION_SCRIPT.bump_version_text(original, "patch")
        expected = original.replace("versionCode = 5", "versionCode = 6").replace(
            'versionName = "0.2.1"', 'versionName = "0.2.2"'
        )
        self.assertEqual(result.updated_text, expected)

    def test_maximum_code_can_be_reached(self):
        result = VERSION_SCRIPT.bump_version_text(source(code=2_099_999_999), "patch")
        self.assertEqual(result.version_code, 2_100_000_000)

    def test_invalid_input_never_changes_file(self):
        invalid_sources = [
            source(code=2_100_000_000),
            source(code=2_100_000_001),
            source(code=0),
            source(code=-1),
            source(code="5 + 1"),
            source(code="05"),
            source() + 'versionName = "0.2.1"\n',
            source() + "versionName = providerValue\n",
            source() + "versionCode = 5\n",
            source() + "versionCode = providerValue\n",
            source().replace('        versionName = "0.2.1"\n', ""),
            source().replace("        versionCode = 5\n", ""),
        ]
        invalid_sources.extend(source(version) for version in (
            "v0.2.1", "0.2", "0.2.1.0", "01.2.1", "0.02.1", "0.2.01", "-1.2.3", "0.2.1-beta", "",
        ))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "build.gradle.kts"
            for original in invalid_sources:
                with self.subTest(source=original):
                    path.write_bytes(original.encode("utf-8"))
                    with self.assertRaises(ValueError):
                        VERSION_SCRIPT.bump_version_file(path, "patch")
                    self.assertEqual(path.read_bytes(), original.encode("utf-8"))
            path.write_text(source(), encoding="utf-8")
            with self.assertRaises(ValueError):
                VERSION_SCRIPT.bump_version_file(path, "invalid")
            self.assertEqual(path.read_text(encoding="utf-8"), source())

    def test_file_replacement_preserves_permissions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "build.gradle.kts"
            path.write_bytes(source().replace("\n", "\r\n").encode("utf-8"))
            path.chmod(0o640)
            result = VERSION_SCRIPT.bump_version_file(path, "minor")
            self.assertEqual(path.read_bytes(), result.updated_text.encode("utf-8"))
            self.assertEqual(path.stat().st_mode & 0o777, 0o640)

    def test_cli_emits_only_github_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "build.gradle.kts"
            path.write_text(source(), encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "patch", "--file", str(path)],
                capture_output=True, text=True, check=True,
            )
            self.assertEqual(completed.stderr, "")
            self.assertEqual(completed.stdout, (
                "current_version=0.2.1\n"
                "version_name=0.2.2\n"
                "version_code=6\n"
                "tag=v0.2.2\n"
            ))

    def test_cli_failure_has_no_outputs_or_file_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "build.gradle.kts"
            original = source(version="not-a-version")
            path.write_text(original, encoding="utf-8")
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), "patch", "--file", str(path)],
                capture_output=True, text=True,
            )
            self.assertNotEqual(completed.returncode, 0)
            self.assertEqual(completed.stdout, "")
            self.assertIn("Invalid versionName", completed.stderr)
            self.assertEqual(path.read_text(encoding="utf-8"), original)


if __name__ == "__main__":
    unittest.main()
