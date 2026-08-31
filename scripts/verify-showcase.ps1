[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://127.0.0.1:8080'
)

function Get-ShowcasePropertyValue {
    param(
        [object] $InputObject,
        [string] $PropertyName
    )

    if ($null -eq $InputObject) {
        return $null
    }

    $property = $InputObject.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Assert-ShowcaseReport {
    param(
        [Parameter(Mandatory)]
        [object] $Report
    )

    $expectedIds = @(
        'ALERT_WORKFLOW',
        'EXPERT_COLLABORATION',
        'OPERATIONS_ANALYSIS',
        'VOICE_ASSISTANT'
    )
    $results = @(Get-ShowcasePropertyValue -InputObject $Report -PropertyName 'results')
    $expectedIdSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $actualIdSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($expectedId in $expectedIds) {
        $null = $expectedIdSet.Add($expectedId)
    }
    foreach ($result in $results) {
        $scenarioId = [string](Get-ShowcasePropertyValue -InputObject $result -PropertyName 'scenarioId')
        $null = $actualIdSet.Add($scenarioId)
    }

    if ($results.Count -ne 4 -or $actualIdSet.Count -ne 4) {
        throw 'Showcase preflight must return four unique scenarios.'
    }

    if (-not $expectedIdSet.SetEquals($actualIdSet)) {
        throw 'Showcase preflight scenario set is incomplete.'
    }

    # java.time.Instant serializes nanosecond precision (up to 9 digits),
    # while DateTimeOffset rounds it to the platform's 100 ns precision.
    $iso8601Timestamp = '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$'
    foreach ($result in $results) {
        $scenarioId = [string](Get-ShowcasePropertyValue -InputObject $result -PropertyName 'scenarioId')
        $status = [string](Get-ShowcasePropertyValue -InputObject $result -PropertyName 'status')
        if ($status -cne 'READY') {
            throw "Showcase scenario is not ready: $scenarioId"
        }

        $verifiedAt = [string](Get-ShowcasePropertyValue -InputObject $result -PropertyName 'verifiedAt')
        $parsed = [DateTimeOffset]::MinValue
        if ($verifiedAt -notmatch $iso8601Timestamp -or -not [DateTimeOffset]::TryParse($verifiedAt, [ref] $parsed)) {
            throw "Showcase scenario has no valid verification time: $scenarioId"
        }
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        $uri = "$($BaseUrl.TrimEnd('/'))/api/showcase/preflight"
        $report = Invoke-RestMethod -Method Post -Uri $uri -Headers @{ 'X-Demo-Role' = 'ADMIN' }
        Assert-ShowcaseReport -Report $report
        $report.results | Select-Object scenarioId, status, verifiedAt | Format-Table -AutoSize
    } catch {
        Write-Error 'Showcase verification failed.'
        exit 1
    }
}
