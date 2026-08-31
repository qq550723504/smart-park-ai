[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. "$PSScriptRoot/verify-showcase.ps1"

$expected = @(
    'ALERT_WORKFLOW',
    'EXPERT_COLLABORATION',
    'OPERATIONS_ANALYSIS',
    'VOICE_ASSISTANT'
)

function New-ReadyResult([string] $ScenarioId) {
    [pscustomobject]@{
        scenarioId = $ScenarioId
        status = 'READY'
        verifiedAt = '2026-08-31T10:00:00Z'
    }
}

function Copy-Results([object[]] $Results) {
    @($Results | ForEach-Object { $_.PSObject.Copy() })
}

function Assert-Rejected([object] $Report, [string] $CaseName) {
    try {
        Assert-ShowcaseReport -Report $Report
        throw "Verifier accepted invalid case: $CaseName"
    } catch {
        if ($_.Exception.Message -like 'Verifier accepted invalid case:*') {
            throw
        }
    }
}

$ready = [pscustomobject]@{
    results = @($expected | ForEach-Object { New-ReadyResult $_ })
}

Assert-ShowcaseReport -Report $ready

Assert-Rejected -Report ([pscustomobject]@{ results = @($ready.results | Select-Object -First 3) }) -CaseName 'missing id'
Assert-Rejected -Report ([pscustomobject]@{
        results = @($ready.results[0], $ready.results[1], $ready.results[2], (New-ReadyResult 'UNEXPECTED_SCENARIO'))
    }) -CaseName 'extra id'
Assert-Rejected -Report ([pscustomobject]@{
        results = @($ready.results[0], $ready.results[1], $ready.results[2], $ready.results[0])
    }) -CaseName 'duplicate id'

$lowercaseIds = Copy-Results $ready.results
$lowercaseIds | ForEach-Object { $_.scenarioId = $_.scenarioId.ToLowerInvariant() }
Assert-Rejected -Report ([pscustomobject]@{ results = $lowercaseIds }) -CaseName 'lowercase ids'

$mixedCaseIds = Copy-Results $ready.results
$mixedCaseIds[0].scenarioId = 'Alert_Workflow'
Assert-Rejected -Report ([pscustomobject]@{ results = $mixedCaseIds }) -CaseName 'mixed-case id'

$caseVariantIds = Copy-Results $ready.results
$caseVariantIds[1].scenarioId = 'alert_workflow'
Assert-Rejected -Report ([pscustomobject]@{ results = $caseVariantIds }) -CaseName 'case-variant duplicate id'

$notReady = Copy-Results $ready.results
$notReady[0].status = 'NOT_READY'
Assert-Rejected -Report ([pscustomobject]@{ results = $notReady }) -CaseName 'not ready'

$lowercaseReady = Copy-Results $ready.results
$lowercaseReady[0].status = 'ready'
Assert-Rejected -Report ([pscustomobject]@{ results = $lowercaseReady }) -CaseName 'lowercase ready status'

$mixedCaseReady = Copy-Results $ready.results
$mixedCaseReady[0].status = 'ReAdY'
Assert-Rejected -Report ([pscustomobject]@{ results = $mixedCaseReady }) -CaseName 'mixed-case ready status'

$malformedTimestamp = Copy-Results $ready.results
$malformedTimestamp[0].verifiedAt = 'not-a-time'
Assert-Rejected -Report ([pscustomobject]@{ results = $malformedTimestamp }) -CaseName 'malformed timestamp'

$nonIsoTimestamp = Copy-Results $ready.results
$nonIsoTimestamp[0].verifiedAt = 'August 31, 2026 10:00:00 +00:00'
Assert-Rejected -Report ([pscustomobject]@{ results = $nonIsoTimestamp }) -CaseName 'parseable non-ISO timestamp'

$missingTimestamp = Copy-Results $ready.results
$missingTimestamp[0] = [pscustomobject]@{ scenarioId = 'ALERT_WORKFLOW'; status = 'READY' }
Assert-Rejected -Report ([pscustomobject]@{ results = $missingTimestamp }) -CaseName 'missing timestamp'

$nullTimestamp = Copy-Results $ready.results
$nullTimestamp[0].verifiedAt = $null
Assert-Rejected -Report ([pscustomobject]@{ results = $nullTimestamp }) -CaseName 'null timestamp'

$blankTimestamp = Copy-Results $ready.results
$blankTimestamp[0].verifiedAt = ' '
Assert-Rejected -Report ([pscustomobject]@{ results = $blankTimestamp }) -CaseName 'blank timestamp'

Assert-Rejected -Report ([pscustomobject]@{}) -CaseName 'missing results'
