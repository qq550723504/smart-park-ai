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
    $ids = @($results | ForEach-Object {
            [string](Get-ShowcasePropertyValue -InputObject $_ -PropertyName 'scenarioId')
        })

    if ($results.Count -ne 4 -or @($ids | Sort-Object -Unique).Count -ne 4) {
        throw 'Showcase preflight must return four unique scenarios.'
    }

    if (@(Compare-Object ($expectedIds | Sort-Object) ($ids | Sort-Object)).Count -ne 0) {
        throw 'Showcase preflight scenario set is incomplete.'
    }

    $iso8601Timestamp = '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,7})?(?:Z|[+-]\d{2}:\d{2})$'
    foreach ($result in $results) {
        $scenarioId = [string](Get-ShowcasePropertyValue -InputObject $result -PropertyName 'scenarioId')
        $status = [string](Get-ShowcasePropertyValue -InputObject $result -PropertyName 'status')
        if ($status -ne 'READY') {
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
