"""Dependency-boundary regression tests for the time-parser sidecar."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PACKAGE_NAME = re.compile(
    r"^([A-Za-z0-9_.-]+)(?:\[.*?\])?==([^\s\\]+)", re.MULTILINE
)

RUNTIME_DIRECT = {"fastapi", "jionlp", "tzdata", "uvicorn"}
DEVELOPMENT_ONLY = {
    "httpx2",
    "license-expression",
    "packaging",
    "pip",
    "pip-audit",
    "pip-licenses",
    "pip-tools",
    "pytest",
}


def declared_packages(filename: str) -> set[str]:
    packages: set[str] = set()
    for raw_line in (ROOT / filename).read_text(encoding="utf-8").splitlines():
        match = PACKAGE_NAME.match(raw_line.strip())
        if match:
            packages.add(match.group(1).lower().replace("_", "-"))
    return packages


def active_directives(filename: str) -> list[str]:
    return [
        line.strip()
        for line in (ROOT / filename).read_text(encoding="utf-8").splitlines()
        if line.strip().startswith("-")
    ]


def locked_versions(filename: str) -> dict[str, str]:
    contents = (ROOT / filename).read_text(encoding="utf-8")
    return {
        match.group(1).lower().replace("_", "-"): match.group(2)
        for match in PACKAGE_NAME.finditer(contents)
    }


def test_runtime_manifest_contains_only_runtime_dependencies() -> None:
    assert declared_packages("requirements.in") == RUNTIME_DIRECT


def test_development_manifest_owns_test_and_tooling_dependencies() -> None:
    assert DEVELOPMENT_ONLY <= declared_packages("requirements-dev.in")


def test_development_manifest_inherits_the_runtime_manifest_once() -> None:
    assert active_directives("requirements-dev.in") == [
        "-r requirements.in",
        "-c requirements.txt",
    ]


def test_runtime_lock_excludes_development_only_packages() -> None:
    assert declared_packages("requirements.txt").isdisjoint(DEVELOPMENT_ONLY)


def test_development_lock_includes_all_development_dependencies() -> None:
    assert DEVELOPMENT_ONLY <= declared_packages("requirements-dev.txt")


def test_development_lock_contains_the_complete_runtime_lock() -> None:
    runtime_versions = locked_versions("requirements.txt")
    development_versions = locked_versions("requirements-dev.txt")
    assert {
        package: development_versions.get(package) for package in runtime_versions
    } == runtime_versions


def test_committed_locks_are_not_generated_in_offline_cache_mode() -> None:
    for filename in ("requirements.txt", "requirements-dev.txt"):
        lock_metadata = (ROOT / filename).read_text(encoding="utf-8")[:1000]
        assert "--no-index" not in lock_metadata


def test_lock_headers_record_the_qualified_generation_environment() -> None:
    for filename in ("requirements.txt", "requirements-dev.txt"):
        lock_metadata = (ROOT / filename).read_text(encoding="utf-8")[:1000]
        assert "pip-compile with Python 3.12" in lock_metadata
        assert "--no-config" in lock_metadata
        assert "--strip-extras" in lock_metadata
        assert "--index-url https://pypi.org/simple" in lock_metadata


def test_every_locked_package_has_at_least_one_sha256_hash() -> None:
    for filename in ("requirements.txt", "requirements-dev.txt"):
        contents = (ROOT / filename).read_text(encoding="utf-8")
        package_starts = list(PACKAGE_NAME.finditer(contents))
        for index, package_start in enumerate(package_starts):
            section_end = (
                package_starts[index + 1].start()
                if index + 1 < len(package_starts)
                else len(contents)
            )
            package_section = contents[package_start.start() : section_end]
            assert "--hash=sha256:" in package_section, package_start.group(1)
