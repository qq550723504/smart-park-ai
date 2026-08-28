# Qualification gate for the JioNLP time-parser sidecar.
#
# Runs, in order:
#   1. installation of the hash-locked requirements;
#   2. sidecar contract tests and the golden-corpus suite;
#   3. pip-audit against known vulnerabilities;
#   4. pip-licenses with an Apache-2.0-compatible allowlist.
#
# Any correctness, security, or license failure stops qualification and
# requires evaluating Duckling against the same corpus. Production behavior
# is never switched automatically by this script.

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$Python = "python"
if (Test-Path ".venv/Scripts/python.exe") {
    $Python = ".venv/Scripts/python.exe"
}

Write-Host "==> Installing locked requirements" -ForegroundColor Cyan
& $Python -m pip install --require-hashes -r requirements.txt
if ($LASTEXITCODE -ne 0) { throw "locked requirement installation failed" }

Write-Host "==> Running contract and golden-corpus tests" -ForegroundColor Cyan
& $Python -m pytest tests/test_contract.py tests/test_golden_corpus.py -q
if ($LASTEXITCODE -ne 0) { throw "contract/corpus tests failed" }

Write-Host "==> Auditing dependencies for known vulnerabilities" -ForegroundColor Cyan
& $Python -m pip install --quiet pip-audit
& $Python -m pip_audit -r requirements.txt --strict --progress-spinner off
if ($LASTEXITCODE -ne 0) { throw "pip-audit found vulnerabilities or failed" }

Write-Host "==> Checking licenses against the Apache-2.0-compatible allowlist" -ForegroundColor Cyan
& $Python -m pip install --quiet pip-licenses
$allowlist = @(
    "Apache Software License", "Apache-2.0", "Apache License 2.0",
    "MIT", "MIT License",
    "BSD", "BSD License", "BSD-2-Clause", "BSD-3-Clause",
    "ISC", "ISC License (ISCL)",
    "Python Software Foundation License", "PSF-2.0", "Python-2.0",
    "The Unlicense", "Unlicense", "CC0", "Public Domain"
)
$json = & .venv/Scripts/pip-licenses --format=json 2>$null | ConvertFrom-Json
$violations = @($json | Where-Object {
        $name = $_.License
        -not ($allowlist | Where-Object { $name -like "*$_*" })
    })
if ($violations.Count -gt 0) {
    $violations | ForEach-Object {
        Write-Host ("DISALLOWED LICENSE: {0} -> {1}" -f $_.Name, $_.License) -ForegroundColor Red
    }
    throw "dependency license check failed"
}

Write-Host "==> Qualification passed" -ForegroundColor Green
