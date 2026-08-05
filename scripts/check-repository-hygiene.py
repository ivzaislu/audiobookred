#!/usr/bin/env python3
"""Reject accidentally tracked command output, generated files, and unsafe paths."""

from __future__ import annotations

import re
import subprocess
import sys
import unicodedata
from pathlib import Path, PurePosixPath

FORBIDDEN_DIRECTORIES = {
    ".idea",
    ".vs",
    ".vscode",
    "__pycache__",
    "bin",
    "obj",
    "testresults",
    "coverage",
}

FORBIDDEN_FILENAMES = {
    "compose.json",
    "nul",
    "con",
    "prn",
    "aux",
}

FORBIDDEN_SUFFIXES = {
    ".7z",
    ".bak",
    ".dump",
    ".log",
    ".nupkg",
    ".orig",
    ".pyc",
    ".rej",
    ".snupkg",
    ".tar",
    ".tgz",
    ".tmp",
    ".temp",
    ".zip",
}

WINDOWS_FORBIDDEN_CHARACTERS = set('<>:"\\|?*')
COMMAND_FRAGMENT = re.compile(
    r"(?i)(?:^|\s)(?:dotnet|docker|git|bash|pwsh|powershell(?:\.exe)?)(?:\s|$)"
    r"|(?:^|\s)-(?:c|f|o)\s+"
    r"|--(?:configuration|project|output)\b"
    r"|&&|\|\||[<>|]"
)

KNOWN_OUTPUT_FRAGMENTS = (
    "warning: in the working copy of ",
    "LF will be replaced by CRLF the next time Git touches it",
)

ARCHIVE_FIXTURE_PREFIXES = (
    "tests/fixtures/",
)


def run_git(root: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(root), *args])


def git_paths(root: Path, *arguments: str) -> set[str]:
    raw = run_git(root, *arguments, "-z")
    return {
        item.decode("utf-8", errors="strict")
        for item in raw.split(b"\0")
        if item
    }


def is_allowed_archive_fixture(path: str) -> bool:
    lowered = path.casefold()
    return any(lowered.startswith(prefix) for prefix in ARCHIVE_FIXTURE_PREFIXES)


def validate_path(relative: str) -> list[str]:
    failures: list[str] = []
    posix = PurePosixPath(relative)
    components = posix.parts
    basename = posix.name
    lowered_basename = basename.casefold()

    if len(relative.encode("utf-8")) > 240:
        failures.append("path is longer than 240 UTF-8 bytes")

    for character in relative:
        category = unicodedata.category(character)
        if category in {"Cc", "Cf", "Cs", "Co"}:
            failures.append(
                f"unsafe Unicode character U+{ord(character):04X} ({category})"
            )
            break

    for component in components:
        if component.endswith((" ", ".")):
            failures.append("path component ends with a space or dot")
        if any(character in WINDOWS_FORBIDDEN_CHARACTERS for character in component):
            failures.append("path contains a character invalid on Windows")
        if component.casefold() in FORBIDDEN_DIRECTORIES:
            failures.append(f"generated directory is tracked: {component}")

    if lowered_basename in FORBIDDEN_FILENAMES:
        failures.append(f"generated or reserved filename is tracked: {basename}")

    suffix = posix.suffix.casefold()
    if suffix in FORBIDDEN_SUFFIXES and not is_allowed_archive_fixture(relative):
        failures.append(f"generated/archive suffix is tracked: {suffix}")

    if ".csproj" in lowered_basename and not lowered_basename.endswith(".csproj"):
        failures.append("filename contains text after .csproj")

    if COMMAND_FRAGMENT.search(basename):
        failures.append("filename resembles a pasted shell command")

    return failures


def validate_content(root: Path, relative: str) -> list[str]:
    path = root / Path(relative)
    failures: list[str] = []

    if not path.exists():
        return ["tracked path does not exist in the working tree"]
    if not path.is_file():
        return failures
    if path.stat().st_size > 64 * 1024:
        return failures

    data = path.read_bytes()
    if b"\0" in data:
        return failures

    try:
        text = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        return failures

    nonempty_lines = [line.strip() for line in text.splitlines() if line.strip()]
    if (
        len(nonempty_lines) <= 3
        and all(fragment in text for fragment in KNOWN_OUTPUT_FRAGMENTS)
        and nonempty_lines[0].startswith("warning:")
    ):
        failures.append("file contains captured Git line-ending warning output")

    return failures


def main() -> int:
    root = Path(
        subprocess.check_output(
            ["git", "rev-parse", "--show-toplevel"], text=True
        ).strip()
    )

    failures: list[str] = []
    try:
        paths = git_paths(root, "ls-files")
        deleted = git_paths(root, "ls-files", "--deleted")
    except UnicodeDecodeError as exc:
        print(
            f"Repository hygiene validation failed: tracked path is not UTF-8: {exc}",
            file=sys.stderr,
        )
        return 1

    # During local validation a removed tracked artifact is still present in
    # the index until git add/commit. Validate the prospective working tree.
    for relative in sorted(paths - deleted):
        reasons = validate_path(relative)
        reasons.extend(validate_content(root, relative))
        for reason in reasons:
            failures.append(f"{relative}: {reason}")

    ignored_tracked = run_git(
        root, "ls-files", "-ci", "--exclude-standard", "-z"
    ).split(b"\0")
    for item in ignored_tracked:
        if item:
            relative = item.decode("utf-8", errors="replace")
            if relative not in deleted:
                failures.append(
                    f"{relative}: tracked file is matched by .gitignore"
                )

    if failures:
        print("Repository hygiene validation failed:", file=sys.stderr)
        for failure in sorted(set(failures)):
            print(f"  {failure}", file=sys.stderr)
        return 1

    print("Tracked paths contain no known repository artifacts or unsafe names.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
