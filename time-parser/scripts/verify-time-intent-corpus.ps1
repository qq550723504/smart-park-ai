# Qualification gate for the JioNLP time-parser sidecar.
#
# Runs, in order:
#   1. installation of the hash-locked development requirements;
#   2. all sidecar correctness and dependency-governance tests;
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

Write-Host "==> Installing locked development requirements" -ForegroundColor Cyan
& $Python -m pip install --quiet --disable-pip-version-check --require-hashes -r requirements-dev.txt
if ($LASTEXITCODE -ne 0) { throw "locked development requirement installation failed" }

Write-Host "==> Running all time-parser tests" -ForegroundColor Cyan
& $Python -m pytest tests -q
if ($LASTEXITCODE -ne 0) { throw "time-parser tests failed" }

Write-Host "==> Auditing dependencies for known vulnerabilities" -ForegroundColor Cyan
$auditCacheRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$auditCache = Join-Path $auditCacheRoot ("smartpark-pip-audit-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $auditCache | Out-Null
try {
    & $Python -m pip_audit -r requirements.txt --strict --progress-spinner off --cache-dir $auditCache
    $auditExitCode = $LASTEXITCODE
}
finally {
    $resolvedAuditCache = [IO.Path]::GetFullPath($auditCache)
    if (-not $resolvedAuditCache.StartsWith($auditCacheRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "refusing to remove audit cache outside the system temp directory"
    }
    Remove-Item -LiteralPath $resolvedAuditCache -Recurse -Force
}
if ($auditExitCode -ne 0) { throw "pip-audit found vulnerabilities or failed" }

Write-Host "==> Checking licenses against the Apache-2.0-compatible allowlist" -ForegroundColor Cyan
& $Python scripts/license_policy.py --requirements requirements.txt
if ($LASTEXITCODE -ne 0) { throw "dependency license check failed" }

Write-Host "==> Qualification passed" -ForegroundColor Green
