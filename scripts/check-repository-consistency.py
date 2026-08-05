#!/usr/bin/env python3
"""Check repository documentation and runtime configuration for stale contracts."""

from __future__ import annotations

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

FORBIDDEN_ALL = {
    "RuTracker Atom runtime type": re.compile(
        r"\bRuTrackerAtom(?:Client|Importer|Repository|State|Worker|Models?)\b"
    ),
    "RuTracker Atom environment variable": re.compile(
        r"\bRUTRACKER_ATOM_[A-Z0-9_]+\b"
    ),
    "Removed RuTracker Atom endpoint": re.compile(
        r"/api/v1/(?:rutracker|sources/rutracker)/atom(?:/|\b)"
    ),
}

FORBIDDEN_DOCS = {
    "Stale RuTracker Atom documentation": re.compile(
        r"(?:RuTracker[^\n]{0,120}\bAtom\b|\bAtom\b[^\n]{0,120}RuTracker)",
        re.IGNORECASE,
    ),
    "Old multi-page incremental recommendation": re.compile(
        r"(?:первых\s+двух\s+страниц|до\s+[23]\s+страниц\s+на\s+категорию)",
        re.IGNORECASE,
    ),
}

OLD_DAILY_INCREMENTAL = re.compile(
    r"17\s+4\s+\*\s+\*\s+\*\s+root\b.*"
    r"audiobookred-source\s+rutracker\s+latest"
)

STALE_DOCUMENTS = {
    "docs/source-readiness.md":
        "superseded by docs/source-module-contract.md",
}

TEXT_SUFFIXES = {
    ".cs", ".csproj", ".json", ".md", ".py", ".sh", ".yml", ".yaml",
    ".env", ".example", ".txt",
}


def repository_files() -> list[pathlib.Path]:
    result = subprocess.run(
        ["git", "-C", str(ROOT), "ls-files", "-z"],
        check=True,
        stdout=subprocess.PIPE,
    )
    files = [
        ROOT / item.decode("utf-8")
        for item in result.stdout.split(b"\0")
        if item
    ]

    self_path = pathlib.Path(__file__).resolve()
    if self_path not in files:
        files.append(self_path)

    return files


def report_matches(
    problems: list[str],
    relative: str,
    text: str,
    patterns: dict[str, re.Pattern[str]],
) -> None:
    for label, pattern in patterns.items():
        for match in pattern.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            problems.append(
                f"{relative}:{line}: {label}: {match.group(0)!r}"
            )


def main() -> int:
    problems: list[str] = []

    for relative, reason in STALE_DOCUMENTS.items():
        if (ROOT / relative).exists():
            problems.append(f"{relative}: stale document: {reason}")

    for path in repository_files():
        if not path.is_file():
            continue
        if path.suffix.lower() not in TEXT_SUFFIXES and path.name not in {
            ".env.example", "Dockerfile"
        }:
            continue

        text = path.read_text(encoding="utf-8")
        relative = path.relative_to(ROOT).as_posix()

        report_matches(problems, relative, text, FORBIDDEN_ALL)

        if relative.startswith("docs/") or relative in {
            "README.md", "README.txt"
        }:
            report_matches(problems, relative, text, FORBIDDEN_DOCS)

        for match in OLD_DAILY_INCREMENTAL.finditer(text):
            line = text.count("\n", 0, match.start()) + 1
            line_text = text.splitlines()[line - 1]

            if (
                relative == "install.sh"
                and "old_latest_daily=" in line_text
            ):
                continue

            problems.append(
                f"{relative}:{line}: old active daily incremental schedule: "
                f"{match.group(0)!r}"
            )

    if problems:
        print("Repository consistency check failed:", file=sys.stderr)
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        return 1

    print("Repository documentation and runtime contracts are consistent.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
