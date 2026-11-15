#!/usr/bin/env python3
"""Compile the repository's actual Kotlin Regex literals with the host ICU library.

Android uses ICU rather than the desktop JDK regex implementation. This check is
offline and complements Android runtime tests; a missing ICU is an error, not a pass.
Only Python's standard library and the operating system's ICU library are needed.
"""

from __future__ import annotations

import ctypes
import ctypes.util
import json
from pathlib import Path
import re
import sys
from dataclasses import dataclass


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / "core/src/main/kotlin"
ESCAPED_DOLLAR = "\U000f0000"
OPTIONS = {
    "UNIX_LINES": 1,
    "IGNORE_CASE": 2,
    "COMMENTS": 4,
    "MULTILINE": 8,
    "LITERAL": 16,
    "DOT_MATCHES_ALL": 32,
    "CANON_EQ": 128,
}


class CheckError(Exception):
    pass


class ParseError(ctypes.Structure):
    # ICU's UParseError, including the fixed U_PARSE_CONTEXT_LEN == 16 arrays.
    _fields_ = [
        ("line", ctypes.c_int32),
        ("offset", ctypes.c_int32),
        ("pre_context", ctypes.c_uint16 * 16),
        ("post_context", ctypes.c_uint16 * 16),
    ]


class IcuRegex:
    def __init__(self) -> None:
        candidates = []
        for name in ("icucore", "icui18n"):
            found = ctypes.util.find_library(name)
            if found:
                candidates.append(found)
        candidates += ["/usr/lib/libicucore.A.dylib", "libicui18n.so"]
        for candidate in dict.fromkeys(candidates):
            try:
                library = ctypes.CDLL(candidate)
            except OSError:
                continue
            # macOS exports unversioned names; Linux ICU normally renames them
            # (e.g. uregex_open_72). Resolve all functions with the same suffix.
            suffixes = [""] + [f"_{version}" for version in range(200, 39, -1)]
            for suffix in suffixes:
                try:
                    opened = getattr(library, "uregex_open" + suffix)
                    closed = getattr(library, "uregex_close" + suffix)
                except AttributeError:
                    continue
                self.library = library
                self.open = opened
                self.close = closed
                self.provider = Path(candidate).name
                self.symbol = "uregex_open" + suffix
                self.error_name = getattr(library, "u_errorName" + suffix, None)
                self.open.argtypes = [
                    ctypes.POINTER(ctypes.c_uint16), ctypes.c_int32,
                    ctypes.c_uint32, ctypes.POINTER(ParseError),
                    ctypes.POINTER(ctypes.c_int32),
                ]
                self.open.restype = ctypes.c_void_p
                self.close.argtypes = [ctypes.c_void_p]
                self.close.restype = None
                if self.error_name:
                    self.error_name.argtypes = [ctypes.c_int32]
                    self.error_name.restype = ctypes.c_char_p
                return
        raise CheckError("ICU library or uregex_open symbol is missing; no regex checks performed")

    def compile(self, pattern: str, flags: int = 0) -> tuple[bool, int, str, int]:
        encoded = pattern.encode("utf-16-le", errors="surrogatepass")
        units = [int.from_bytes(encoded[i:i + 2], "little") for i in range(0, len(encoded), 2)]
        buffer = (ctypes.c_uint16 * max(1, len(units)))(*units)
        status = ctypes.c_int32(0)
        location = ParseError()
        regex = self.open(buffer, len(units), flags, ctypes.byref(location), ctypes.byref(status))
        success = bool(regex) and status.value <= 0  # ICU warnings are negative.
        if regex:
            self.close(regex)
        name = self.error_name(status.value).decode("ascii", errors="replace") if self.error_name else "ICU_STATUS"
        return success, status.value, name, location.offset


@dataclass(frozen=True)
class Token:
    kind: str
    value: str
    start: int


def tokens(source: str) -> list[Token]:
    """Skip comments/chars so examples in comments cannot masquerade as real calls."""
    result = []
    i = 0
    while i < len(source):
        if source[i].isspace():
            i += 1
        elif source.startswith("//", i):
            end = source.find("\n", i + 2)
            i = len(source) if end < 0 else end
        elif source.startswith("/*", i):
            depth = 1
            i += 2
            while i < len(source) and depth:
                if source.startswith("/*", i):
                    depth += 1
                    i += 2
                elif source.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            if depth:
                raise CheckError("unterminated Kotlin block comment")
        elif source.startswith('"""', i):
            start = i
            end = source.find('"""', i + 3)
            if end < 0:
                raise CheckError("unterminated Kotlin raw string")
            i = end + 3
            result.append(Token("raw-string", source[start:i], start))
        elif source[i] in ('"', "'"):
            start = i
            quote = source[i]
            i += 1
            while i < len(source):
                if source[i] == "\\":
                    i += 2
                elif source[i] == quote:
                    i += 1
                    break
                else:
                    i += 1
            else:
                raise CheckError("unterminated Kotlin quoted literal")
            result.append(Token("string" if quote == '"' else "char", source[start:i], start))
        elif source[i].isalpha() or source[i] == "_":
            start = i
            i += 1
            while i < len(source) and (source[i].isalnum() or source[i] == "_"):
                i += 1
            result.append(Token("identifier", source[start:i], start))
        else:
            result.append(Token("punctuation", source[i], i))
            i += 1
    return result


def decode_literal(raw: str) -> str:
    # Ordinary Kotlin string escapes match JSON except for escaped $ and '.
    # Preserve escaped dollars until interpolation has been expanded.
    converted = ['"']
    i = 1
    while i < len(raw) - 1:
        if raw[i] == "\\" and i + 1 < len(raw) - 1:
            escaped = raw[i + 1]
            if escaped == "$":
                converted.append("\\udb80\\udc00")
            elif escaped == "'":
                converted.append("'")
            else:
                converted.extend(("\\", escaped))
            i += 2
        else:
            converted.append(raw[i])
            i += 1
    converted.append('"')
    try:
        return json.loads("".join(converted))
    except json.JSONDecodeError as error:
        raise CheckError(f"unsupported Kotlin string escape at character {error.pos}") from error


def patterns(path: Path) -> list[tuple[int, str, int]]:
    source = path.read_text(encoding="utf-8")
    lexed = tokens(source)
    constants = {}
    for i in range(len(lexed) - 4):
        if [token.value for token in lexed[i:i + 2]] == ["const", "val"]:
            name, equals, value = lexed[i + 2:i + 5]
            if equals.value == "=" and value.kind == "string":
                if name.value in constants:
                    raise CheckError(f"ambiguous same-file string constant {name.value}")
                constants[name.value] = decode_literal(value.value)

    interpolation = re.compile(r"\$(?:\{([A-Za-z_][A-Za-z_0-9]*)\}|([A-Za-z_][A-Za-z_0-9]*))")

    def expand(value: str, resolving: tuple[str, ...] = ()) -> str:
        def replace(match: re.Match[str]) -> str:
            name = match.group(1) or match.group(2)
            if name not in constants:
                raise CheckError(f"Regex interpolation is not a same-file const val: {name}")
            if name in resolving:
                raise CheckError(f"cyclic Regex string interpolation: {name}")
            return expand(constants[name], resolving + (name,))
        expanded = interpolation.sub(replace, value)
        if "${" in expanded:
            raise CheckError("unsupported nonconstant Kotlin Regex interpolation")
        return expanded

    result = []
    for i, token in enumerate(lexed):
        if token.kind != "identifier" or token.value != "Regex" or i + 1 >= len(lexed) or lexed[i + 1].value != "(":
            continue
        line = source.count("\n", 0, token.start) + 1
        end = i + 2
        depth = 1
        arguments = []
        while end < len(lexed) and depth:
            current = lexed[end]
            if current.kind == "punctuation":
                if current.value == "(":
                    depth += 1
                elif current.value == ")":
                    depth -= 1
            if depth:
                arguments.append(current)
            end += 1
        if depth or not arguments:
            raise CheckError(f"line {line}: incomplete Regex constructor")
        first = 2 if len(arguments) >= 3 and arguments[0].value == "pattern" and arguments[1].value == "=" else 0
        if arguments[first].kind != "string":
            raise CheckError(f"line {line}: Regex pattern must be an ordinary quoted literal for this checker")
        following = arguments[first + 1:]
        if following and following[0].value != ",":
            raise CheckError(f"line {line}: unsupported Regex pattern concatenation")
        flags = 0
        for offset, option in enumerate(following):
            if option.value == "RegexOption":
                if offset + 2 >= len(following) or following[offset + 1].value != ".":
                    raise CheckError(f"line {line}: unsupported RegexOption expression")
                name = following[offset + 2].value
                if name not in OPTIONS:
                    raise CheckError(f"line {line}: unknown RegexOption {name}")
                flags |= OPTIONS[name]
        pattern = expand(decode_literal(arguments[first].value)).replace(ESCAPED_DOLLAR, "$")
        result.append((line, pattern, flags))
    return result


def main() -> int:
    try:
        icu = IcuRegex()
        print(f"ICU provider={icu.provider} symbol={icu.symbol}")
        # This is the exact former ASS pattern: JDK accepts it, Android ICU rejects
        # the unescaped closing brace. Prove the checker detects that regression.
        success, status, name, _ = icu.compile(r"\{[^}]*}")
        if success:
            raise CheckError("negative control unexpectedly compiled; this ICU cannot detect the known Android regression")
        if status != 66305:  # U_REGEX_RULE_SYNTAX, rather than an unrelated ICU failure.
            raise CheckError(f"negative control failed for an unexpected reason: status={status} name={name}")
        print(f"negative-control: PASS rejected former ASS pattern status={status} name={name}")

        files = sorted(SOURCE_ROOT.rglob("*.kt"))
        if not files:
            raise CheckError("no core Kotlin sources found")
        total = 0
        failures = 0
        for path in files:
            relative = path.relative_to(ROOT)
            try:
                extracted = patterns(path)
            except CheckError as error:
                raise CheckError(f"{relative}: {error}") from error
            for line, pattern, flags in extracted:
                total += 1
                success, status, name, offset = icu.compile(pattern, flags)
                if success:
                    print(f"{relative}:{line}: PASS")
                else:
                    failures += 1
                    print(f"{relative}:{line}: FAIL status={status} name={name} offset={offset}")
        if not total:
            raise CheckError("no Kotlin Regex literals were checked")
        print(f"Checked {total} Kotlin Regex literals; failures={failures}")
        return 1 if failures else 0
    except (CheckError, OSError, UnicodeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
