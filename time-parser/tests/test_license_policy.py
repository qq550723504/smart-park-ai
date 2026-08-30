"""Fail-closed tests for the runtime dependency license gate."""

import importlib.util
from pathlib import Path


def find_violations(records: list[dict[str, str]], packages: set[str]):
    policy_path = Path(__file__).resolve().parents[1] / "scripts" / "license_policy.py"
    assert policy_path.exists(), "runtime license policy script is missing"
    spec = importlib.util.spec_from_file_location("license_policy", policy_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.find_violations(records, packages)


def test_compound_license_is_not_accepted_by_allowed_substring() -> None:
    records = [{"Name": "unsafe", "License": "MIT AND GPL-3.0"}]

    assert find_violations(records, {"unsafe"}) == records


def test_missing_runtime_package_is_a_policy_violation() -> None:
    assert find_violations([], {"missing"}) == [
        {"Name": "missing", "License": "UNKNOWN (not reported)"}
    ]


def test_exact_allowed_license_passes() -> None:
    records = [{"Name": "safe", "License": "MIT License"}]

    assert find_violations(records, {"safe"}) == []


def test_compound_permissive_license_passes_when_every_term_is_allowed() -> None:
    records = [
        {
            "Name": "safe",
            "License": "BSD-3-Clause AND 0BSD AND MIT AND Zlib AND CC0-1.0",
        }
    ]

    assert find_violations(records, {"safe"}) == []
