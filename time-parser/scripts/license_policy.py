"""Evaluate licenses for exactly the packages in the production lock."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable

from license_expression import ExpressionParseError, Licensing
from packaging.utils import canonicalize_name


ALLOWED_LICENSE_TERMS = {
    "Apache Software License",
    "Apache-2.0",
    "Apache License 2.0",
    "MIT",
    "MIT License",
    "BSD",
    "BSD License",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "0BSD",
    "ISC",
    "ISC License (ISCL)",
    "Python Software Foundation License",
    "PSF-2.0",
    "Python-2.0",
    "The Unlicense",
    "Unlicense",
    "CC0",
    "CC0-1.0",
    "Public Domain",
    "Zlib",
}
LOCKED_PACKAGE = re.compile(r"^([A-Za-z0-9_.-]+)(?:\[.*?\])?==", re.MULTILINE)
LICENSING = Licensing()


def locked_package_names(lock_path: Path) -> set[str]:
    contents = lock_path.read_text(encoding="utf-8")
    return {canonicalize_name(match.group(1)) for match in LOCKED_PACKAGE.finditer(contents)}


def license_is_allowed(expression: str) -> bool:
    try:
        parsed = LICENSING.parse(expression.strip(), validate=False)
    except ExpressionParseError:
        return False
    terms = {str(symbol) for symbol in LICENSING.license_symbols(parsed)}
    return bool(terms) and terms <= ALLOWED_LICENSE_TERMS


def find_violations(
    records: Iterable[dict[str, str]], requested_packages: set[str]
) -> list[dict[str, str]]:
    requested = {canonicalize_name(name) for name in requested_packages}
    reported: set[str] = set()
    violations: list[dict[str, str]] = []

    for record in records:
        name = canonicalize_name(record["Name"])
        if name not in requested:
            continue
        reported.add(name)
        if not license_is_allowed(record.get("License", "")):
            violations.append(record)

    for missing in sorted(requested - reported):
        violations.append({"Name": missing, "License": "UNKNOWN (not reported)"})

    return violations


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--requirements", type=Path, required=True)
    args = parser.parse_args()

    packages = locked_package_names(args.requirements)
    if not packages:
        print("No packages found in production lock.", file=sys.stderr)
        return 2

    completed = subprocess.run(
        [
            sys.executable,
            "-m",
            "piplicenses",
            "--format=json",
            "--packages",
            *sorted(packages),
        ],
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        print(completed.stderr, file=sys.stderr, end="")
        return completed.returncode

    violations = find_violations(json.loads(completed.stdout), packages)
    for violation in violations:
        print(
            f"DISALLOWED LICENSE: {violation['Name']} -> {violation['License']}",
            file=sys.stderr,
        )
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
