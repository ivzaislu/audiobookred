#!/usr/bin/env python3
"""Fail when tracked text files are not UTF-8 or contain common mojibake."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

TEXT_SUFFIXES = {
    ".cs",
    ".csproj",
    ".css",
    ".env",
    ".example",
    ".html",
    ".ini",
    ".js",
    ".json",
    ".md",
    ".props",
    ".ps1",
    ".sh",
    ".sql",
    ".targets",
    ".toml",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}

TEXT_NAMES = {
    ".dockerignore",
    ".editorconfig",
    ".gitattributes",
    ".gitignore",
    "Dockerfile",
    "LICENSE",
    "Makefile",
}

# These pairs are characteristic of UTF-8 Cyrillic decoded as Windows-1251.
MOJIBAKE_FRAGMENTS = (
    "\u0420\u00b0",
    "\u0420\u00b1",
    "\u0420\u00b5",
    "\u0420\u00b7",
    "\u0420\u00bb",
    "\u0420\u0405",
    "\u0420\u0406",
    "\u0420\u0451",
    "\u0420\u0454",
    "\u0420\u0455",
    "\u0420\u0456",
    "\u0420\u0457",
    "\u0420\u0458",
    "\u0420\u0491",
    "\u0420\u2116",
    "\u0421\u0402",
    "\u0421\u0403",
    "\u0421\u040a",
    "\u0421\u040b",
    "\u0421\u0453",
    "\u0421\u2018",
    "\u0421\u201a",
    "\u0421\u201e",
    "\u0421\u2021",
    "\u0421\u2026",
    "\u0421\u2030",
    "\u0421\u2039",
    "\ufffd",
)


def is_text_path(path: Path) -> bool:
    return path.name in TEXT_NAMES or path.suffix.lower() in TEXT_SUFFIXES


def main() -> int:
    root = Path(
        subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"], text=True
        ).strip()
    )
    tracked = subprocess.check_output(
        ["git", "-C", str(root), "ls-files", "-z"]
    ).split(b"\0")

    failures: list[str] = []
    for raw_name in tracked:
        if not raw_name:
            continue
        relative = Path(raw_name.decode("utf-8", errors="strict"))
        if not is_text_path(relative):
            continue

        absolute = root / relative
        if not absolute.is_file():
            continue
        data = absolute.read_bytes()
        if b"\0" in data:
            failures.append(f"{relative}: NUL byte in a tracked text file")
            continue

        try:
            text = data.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            failures.append(f"{relative}: invalid UTF-8 at byte {exc.start}")
            continue

        for line_number, line in enumerate(text.splitlines(), start=1):
            fragment = next(
                (item for item in MOJIBAKE_FRAGMENTS if item in line), None
            )
            if fragment is not None:
                escaped = fragment.encode("unicode_escape").decode("ascii")
                failures.append(
                    f"{relative}:{line_number}: probable mojibake ({escaped})"
                )

    if failures:
        print("Text encoding validation failed:", file=sys.stderr)
        for failure in failures:
            print(f"  {failure}", file=sys.stderr)
        return 1

    print("Tracked text files are valid UTF-8 and contain no known mojibake.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
