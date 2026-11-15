#!/usr/bin/env python3
"""Increment the committed Android version without changing other Gradle text."""

import argparse
from dataclasses import dataclass
import os
from pathlib import Path
import re
import stat
import sys
import tempfile


MAX_VERSION_CODE = 2_100_000_000
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
DEFAULT_BUILD_FILE = Path(__file__).resolve().parents[1] / "app/build.gradle.kts"


@dataclass(frozen=True)
class VersionBump:
    current_version: str
    version_name: str
    version_code: int
    updated_text: str

    @property
    def tag(self):
        return f"v{self.version_name}"

    def github_output(self):
        return (
            f"current_version={self.current_version}\n"
            f"version_name={self.version_name}\n"
            f"version_code={self.version_code}\n"
            f"tag={self.tag}\n"
        )


def _assignment_value(source, name, value_pattern):
    assignments = list(re.finditer(rf"(?m)^[ \t]*{name}[ \t]*=[^\r\n]*", source))
    if len(assignments) != 1:
        raise ValueError(f"Expected exactly one {name} assignment")
    assignment = assignments[0]
    value = re.fullmatch(
        rf"[ \t]*{name}[ \t]*=[ \t]*(?P<value>{value_pattern})[ \t]*(?://[^\r\n]*)?",
        assignment.group(),
    )
    if value is None:
        raise ValueError(f"Invalid {name} assignment")
    start, end = value.span("value")
    return value.group("value"), assignment.start() + start, assignment.start() + end


def bump_version_text(source, bump):
    """Validate source and return a version bump, leaving the input untouched."""
    if bump not in ("patch", "minor", "major"):
        raise ValueError("Bump must be patch, minor, or major")
    quoted_version, name_start, name_end = _assignment_value(
        source, "versionName", rf'"{VERSION_PATTERN}"'
    )
    code_text, code_start, code_end = _assignment_value(source, "versionCode", r"[1-9][0-9]*")
    current_version = quoted_version[1:-1]
    current_code = int(code_text)
    if current_code >= MAX_VERSION_CODE:
        raise ValueError(f"Incremented versionCode must not exceed {MAX_VERSION_CODE}")

    major, minor, patch = map(int, current_version.split("."))
    if bump == "major":
        major, minor, patch = major + 1, 0, 0
    elif bump == "minor":
        minor, patch = minor + 1, 0
    else:
        patch += 1
    version_name = f"{major}.{minor}.{patch}"
    version_code = current_code + 1
    updated_text = source
    for start, end, replacement in sorted(
        ((name_start, name_end, f'"{version_name}"'), (code_start, code_end, str(version_code))),
        reverse=True,
    ):
        updated_text = updated_text[:start] + replacement + updated_text[end:]
    return VersionBump(current_version, version_name, version_code, updated_text)


def bump_version_file(path, bump):
    """Validate first, then atomically replace the file while preserving its mode."""
    path = Path(path)
    source = path.read_bytes().decode("utf-8")
    result = bump_version_text(source, bump)
    mode = stat.S_IMODE(path.stat().st_mode)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as temporary:
            temporary.write(result.updated_text.encode("utf-8"))
            os.fchmod(temporary.fileno(), mode)
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bump", choices=("patch", "minor", "major"))
    parser.add_argument("--file", type=Path, default=DEFAULT_BUILD_FILE)
    args = parser.parse_args(argv)
    try:
        result = bump_version_file(args.file, args.bump)
    except (OSError, UnicodeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    print(result.github_output(), end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
