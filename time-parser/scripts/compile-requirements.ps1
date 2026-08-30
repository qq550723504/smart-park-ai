# Rebuild production and development lock files with the qualified toolchain.

param(
    [string]$Python = "python"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$requiredPython = "3.12"
$requiredPip = "24.3.1"
$requiredPipTools = "7.6.1"
$pythonVersion = & $Python -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
if ($LASTEXITCODE -ne 0) { throw "failed to inspect Python version" }
if ($pythonVersion -ne $requiredPython) {
    throw "lock generation requires Python $requiredPython; found $pythonVersion"
}

$pipVersion = & $Python -c "from importlib.metadata import version; print(version('pip'))"
if ($LASTEXITCODE -ne 0) { throw "pip $requiredPip is required" }
if ($pipVersion -ne $requiredPip) {
    throw "lock generation requires pip $requiredPip; found $pipVersion"
}

$pipToolsVersion = & $Python -c "from importlib.metadata import version; print(version('pip-tools'))"
if ($LASTEXITCODE -ne 0) { throw "pip-tools $requiredPipTools is required" }
if ($pipToolsVersion -ne $requiredPipTools) {
    throw "lock generation requires pip-tools $requiredPipTools; found $pipToolsVersion"
}

function Invoke-LockCompile {
    param(
        [string]$InputFile,
        [string]$OutputFile,
        [switch]$AllowUnsafe
    )

    $arguments = @(
        "-m", "piptools", "compile",
        "--quiet",
        "--no-config",
        "--generate-hashes",
        "--strip-extras",
        "--index-url", "https://pypi.org/simple",
        "--output-file=$OutputFile"
    )
    if ($AllowUnsafe) { $arguments += "--allow-unsafe" }
    $arguments += $InputFile

    # pip-tools supports this variable for deterministic, shareable headers.
    # It also avoids a pip-tools 7.6.1 header bug that renders an inactive
    # --no-index default as though it had been passed explicitly.
    $headerArguments = ($arguments[2..($arguments.Count - 1)] |
        Where-Object { $_ -ne "--quiet" }) -join " "
    $env:CUSTOM_COMPILE_COMMAND = "python -m piptools $headerArguments"

    & $Python @arguments
    if ($LASTEXITCODE -ne 0) { throw "failed to compile $OutputFile" }
}

$previousCompileCommand = $env:CUSTOM_COMPILE_COMMAND
try {
    Invoke-LockCompile -InputFile "requirements.in" -OutputFile "requirements.txt"
    Invoke-LockCompile -InputFile "requirements-dev.in" -OutputFile "requirements-dev.txt" -AllowUnsafe
}
finally {
    [Environment]::SetEnvironmentVariable(
        "CUSTOM_COMPILE_COMMAND",
        $previousCompileCommand,
        [EnvironmentVariableTarget]::Process
    )
}
