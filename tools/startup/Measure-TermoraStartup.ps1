[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $TermoraExe,

    [string] $OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'termora-startup-results'),

    [string] $BaseDataDir,

    [ValidateRange(5, 600)]
    [int] $TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-NormalizedPath {
    param([Parameter(Mandatory = $true)][string] $Path)

    return [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($Path))
}

function Test-PathInside {
    param(
        [Parameter(Mandatory = $true)][string] $Candidate,
        [Parameter(Mandatory = $true)][string] $Parent
    )

    $normalizedCandidate = (Get-NormalizedPath $Candidate).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $normalizedParent = (Get-NormalizedPath $Parent).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    if ($normalizedCandidate.Equals($normalizedParent, [StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }

    return $normalizedCandidate.StartsWith(
        $normalizedParent + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase
    )
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)] $Value
    )

    $json = $Value | ConvertTo-Json -Depth 10
    [IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
}

function Get-StartupEvent {
    param(
        [Parameter(Mandatory = $true)] $Trace,
        [Parameter(Mandatory = $true)][string] $Name
    )

    $matches = @($Trace.events | Where-Object { $_.name -eq $Name } | Select-Object -First 1)
    if ($matches.Count -eq 0) {
        return $null
    }
    return $matches[0]
}

function Get-EventEpochMillis {
    param(
        [Parameter(Mandatory = $true)] $Trace,
        [Parameter(Mandatory = $true)] $Event
    )

    return [double] $Trace.startedEpochMillis + ([double] $Event.elapsedNanos / 1000000.0)
}

function Read-RunTraces {
    param(
        [Parameter(Mandatory = $true)][string] $Directory,
        [Parameter(Mandatory = $true)][string] $RunId
    )

    $escapedRunId = [Regex]::Escape($RunId)
    $traces = @()
    foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter "$RunId-*.json" -File -ErrorAction SilentlyContinue) {
        if ($file.BaseName -notmatch "^$escapedRunId-([0-9]+)$") {
            continue
        }

        try {
            $trace = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
            if ($trace.runId -ne $RunId) {
                continue
            }
            $traces += [pscustomobject]@{
                Path  = $file.FullName
                Trace = $trace
            }
        }
        catch {
            # The application publishes with an atomic move. A read failure here is transient or a malformed trace;
            # keep waiting so the timeout report can retain all usable partial files.
        }
    }

    return @($traces | Sort-Object { [long] $_.Trace.startedEpochMillis }, { [long] $_.Trace.pid })
}

$exePath = Get-NormalizedPath $TermoraExe
if (-not (Test-Path -LiteralPath $exePath -PathType Leaf)) {
    throw "Termora executable does not exist: $exePath"
}
if (-not [IO.Path]::GetFileName($exePath).Equals('Termora.exe', [StringComparison]::OrdinalIgnoreCase)) {
    throw "The ZIP baseline must launch Termora.exe: $exePath"
}

$appDirectory = Split-Path -Parent $exePath
$appConfigPath = Join-Path $appDirectory 'app/Termora.cfg'
$runtimePath = Join-Path $appDirectory 'runtime'
if (-not (Test-Path -LiteralPath $appConfigPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $runtimePath -PathType Container)) {
    throw "The executable is not inside a complete Termora ZIP app-image: $exePath"
}
$outputPath = Get-NormalizedPath $OutputDirectory
if (Test-PathInside -Candidate $outputPath -Parent $appDirectory) {
    throw 'OutputDirectory must be outside the extracted Termora directory to avoid adding I/O to the measured package.'
}
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null

$resolvedBaseDataDir = $null
if ($PSBoundParameters.ContainsKey('BaseDataDir')) {
    $resolvedBaseDataDir = Get-NormalizedPath $BaseDataDir
    if (-not (Test-Path -LiteralPath $resolvedBaseDataDir -PathType Container)) {
        throw "BaseDataDir must be prepared before the measurement run: $resolvedBaseDataDir"
    }
}

$processName = [IO.Path]::GetFileNameWithoutExtension($exePath)
$existing = @(Get-Process -Name $processName -ErrorAction SilentlyContinue)
if ($existing.Count -gt 0) {
    $ids = ($existing.Id | Sort-Object) -join ', '
    throw "Termora is already running (PID: $ids). Close every instance before collecting a baseline."
}

$singletonCreated = $false
$singletonProbe = $null
try {
    $singletonProbe = [Threading.Mutex]::new($false, 'Termora', [ref] $singletonCreated)
    if (-not $singletonCreated) {
        throw 'The Termora singleton mutex already exists. Close packaged and IDE/gradlew Termora instances before collecting a baseline.'
    }
}
finally {
    if ($null -ne $singletonProbe) {
        $singletonProbe.Dispose()
    }
}

$runId = [Guid]::NewGuid().ToString('N')
$traceDirectoryVariable = 'TERMORA_STARTUP_TRACE_DIR'
$runIdVariable = 'TERMORA_STARTUP_RUN_ID'
$baseDataVariable = 'TERMORA_BASE_DATA_DIR'
$skipAotVariable = 'TERMORA_STARTUP_SKIP_AOT'
$oldTraceDirectory = [Environment]::GetEnvironmentVariable($traceDirectoryVariable, 'Process')
$oldRunId = [Environment]::GetEnvironmentVariable($runIdVariable, 'Process')
$oldBaseDataDir = [Environment]::GetEnvironmentVariable($baseDataVariable, 'Process')
$oldSkipAot = [Environment]::GetEnvironmentVariable($skipAotVariable, 'Process')

$launchBeforeUtc = $null
$launcherStartEpochMillis = $null
$stopwatch = $null
$launchedProcess = $null
$launchedProcessStartUtc = $null
$launchedProcessStartEpochMillis = $null

try {
    [Environment]::SetEnvironmentVariable($traceDirectoryVariable, $outputPath, 'Process')
    [Environment]::SetEnvironmentVariable($runIdVariable, $runId, 'Process')
    [Environment]::SetEnvironmentVariable($baseDataVariable, $resolvedBaseDataDir, 'Process')
    [Environment]::SetEnvironmentVariable($skipAotVariable, 'true', 'Process')

    $launchBeforeUtc = [DateTimeOffset]::UtcNow
    $launcherStartEpochMillis = $launchBeforeUtc.ToUnixTimeMilliseconds()
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    $launchedProcess = Start-Process -FilePath $exePath -WorkingDirectory $appDirectory -PassThru
    try {
        $processStartTimeUtc = $launchedProcess.StartTime.ToUniversalTime()
        $launchedProcessStartUtc = $processStartTimeUtc.ToString('O')
        $launchedProcessStartEpochMillis = ([DateTimeOffset] $processStartTimeUtc).ToUnixTimeMilliseconds()
    }
    catch {
        $launchedProcessStartUtc = $null
        $launchedProcessStartEpochMillis = $null
    }
}
finally {
    [Environment]::SetEnvironmentVariable($traceDirectoryVariable, $oldTraceDirectory, 'Process')
    [Environment]::SetEnvironmentVariable($runIdVariable, $oldRunId, 'Process')
    [Environment]::SetEnvironmentVariable($baseDataVariable, $oldBaseDataDir, 'Process')
    [Environment]::SetEnvironmentVariable($skipAotVariable, $oldSkipAot, 'Process')
}

$completedTraceEntry = $null
while ($stopwatch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    $runTraces = @(Read-RunTraces -Directory $outputPath -RunId $runId)
    $completedTraceEntry = $runTraces |
        Where-Object { $_.Trace.completed -eq $true } |
        Sort-Object { [long] $_.Trace.startedEpochMillis } |
        Select-Object -First 1
    if ($null -ne $completedTraceEntry) {
        break
    }
    Start-Sleep -Milliseconds 100
}
$stopwatch.Stop()

$runTraces = @(Read-RunTraces -Directory $outputPath -RunId $runId)
if ($null -eq $completedTraceEntry) {
    $initialExitCode = $null
    try {
        $launchedProcess.Refresh()
        if ($launchedProcess.HasExited) {
            $initialExitCode = $launchedProcess.ExitCode
        }
    }
    catch {
        # Keep the timeout artifact useful even if the original launcher process is no longer queryable.
    }

    $failure = [ordered]@{
        schemaVersion            = 1
        status                   = 'timeout'
        runId                    = $runId
        layout                   = 'zip'
        executable               = $exePath
        launchBeforeUtc          = $launchBeforeUtc.ToString('O')
        launcherStartEpochMillis = $launcherStartEpochMillis
        launchedPid              = $launchedProcess.Id
        launchedProcessStartUtc  = $launchedProcessStartUtc
        processStartEpochMillis  = $launchedProcessStartEpochMillis
        aotMode                  = 'skipped'
        initialExitCode          = $initialExitCode
        timeoutSeconds           = $TimeoutSeconds
        partialTraceFiles        = @($runTraces | ForEach-Object { $_.Path })
    }
    $failurePath = Join-Path $outputPath "$runId-launcher.json"
    Write-JsonFile -Path $failurePath -Value $failure
    throw "Timed out waiting for the interactive startup event. Diagnostic metadata: $failurePath"
}

$trace = $completedTraceEntry.Trace
$mainEnter = Get-StartupEvent -Trace $trace -Name 'main-enter'
$aotSkipped = Get-StartupEvent -Trace $trace -Name 'aot-skipped'
$firstPaint = Get-StartupEvent -Trace $trace -Name 'first-paint'
$interactive = Get-StartupEvent -Trace $trace -Name 'interactive'
if ($null -eq $mainEnter -or $null -eq $aotSkipped -or $null -eq $firstPaint -or $null -eq $interactive) {
    throw "Completed trace is missing a required event: $($completedTraceEntry.Path)"
}

$firstProcessMainEpochMillis = $null
foreach ($entry in $runTraces) {
    $candidateMain = Get-StartupEvent -Trace $entry.Trace -Name 'main-enter'
    if ($null -eq $candidateMain) {
        continue
    }
    $candidateEpochMillis = Get-EventEpochMillis -Trace $entry.Trace -Event $candidateMain
    if ($null -eq $firstProcessMainEpochMillis -or $candidateEpochMillis -lt $firstProcessMainEpochMillis) {
        $firstProcessMainEpochMillis = $candidateEpochMillis
    }
}

$mainEpochMillis = Get-EventEpochMillis -Trace $trace -Event $mainEnter
$firstPaintEpochMillis = Get-EventEpochMillis -Trace $trace -Event $firstPaint
$interactiveEpochMillis = Get-EventEpochMillis -Trace $trace -Event $interactive

$totalCpuMillis = $null
$peakWorkingSetMiB = $null
try {
    $interactiveProcess = Get-Process -Id ([int] $trace.pid) -ErrorAction Stop
    $interactiveProcess.Refresh()
    $totalCpuMillis = [Math]::Round($interactiveProcess.TotalProcessorTime.TotalMilliseconds, 3)
    $peakWorkingSetMiB = [Math]::Round($interactiveProcess.PeakWorkingSet64 / 1MB, 3)
}
catch {
    # The timing trace remains valid if the process exits before resource counters are sampled.
}

$bootUtc = $null
try {
    $bootUtc = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().ToString('O')
}
catch {
    # Boot time is metadata only; failing to query CIM must not invalidate the timing sample.
}

$launcher = [ordered]@{
    schemaVersion            = 1
    status                   = 'complete'
    runId                    = $runId
    layout                   = 'zip'
    executable               = $exePath
    outputDirectory          = $outputPath
    baseDataDir              = $resolvedBaseDataDir
    bootUtc                  = $bootUtc
    launchBeforeUtc          = $launchBeforeUtc.ToString('O')
    launcherStartEpochMillis = $launcherStartEpochMillis
    launchedPid              = $launchedProcess.Id
    launchedProcessStartUtc  = $launchedProcessStartUtc
    processStartEpochMillis  = $launchedProcessStartEpochMillis
    interactivePid           = [long] $trace.pid
    aotMode                  = 'skipped'
    baselineEligible         = ($runTraces.Count -eq 1)
    traceObservedMillis      = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 3)
    traceFiles               = @($runTraces | ForEach-Object { $_.Path })
}
$launcherPath = Join-Path $outputPath "$runId-launcher.json"
Write-JsonFile -Path $launcherPath -Value $launcher

$metrics = [ordered]@{
    processStartToFirstMainMillis       = if ($null -eq $launchedProcessStartEpochMillis -or $null -eq $firstProcessMainEpochMillis) { $null } else { [Math]::Round($firstProcessMainEpochMillis - $launchedProcessStartEpochMillis, 3) }
    processStartToInteractiveMainMillis = if ($null -eq $launchedProcessStartEpochMillis) { $null } else { [Math]::Round($mainEpochMillis - $launchedProcessStartEpochMillis, 3) }
    processStartToFirstPaintMillis      = if ($null -eq $launchedProcessStartEpochMillis) { $null } else { [Math]::Round($firstPaintEpochMillis - $launchedProcessStartEpochMillis, 3) }
    processStartToInteractiveMillis     = if ($null -eq $launchedProcessStartEpochMillis) { $null } else { [Math]::Round($interactiveEpochMillis - $launchedProcessStartEpochMillis, 3) }
    launcherToFirstMainMillis       = if ($null -eq $firstProcessMainEpochMillis) { $null } else { [Math]::Round($firstProcessMainEpochMillis - $launcherStartEpochMillis, 3) }
    launcherToInteractiveMainMillis = [Math]::Round($mainEpochMillis - $launcherStartEpochMillis, 3)
    launcherToFirstPaintMillis      = [Math]::Round($firstPaintEpochMillis - $launcherStartEpochMillis, 3)
    launcherToInteractiveMillis     = [Math]::Round($interactiveEpochMillis - $launcherStartEpochMillis, 3)
    mainToFirstPaintMillis          = [Math]::Round($firstPaintEpochMillis - $mainEpochMillis, 3)
    mainToInteractiveMillis         = [Math]::Round($interactiveEpochMillis - $mainEpochMillis, 3)
}
$summary = [ordered]@{
    schemaVersion          = 1
    runId                  = $runId
    layout                 = 'zip'
    aotMode                = 'skipped'
    baselineEligible       = ($runTraces.Count -eq 1)
    processTraceCount      = $runTraces.Count
    launchedPid            = $launchedProcess.Id
    interactivePid         = [long] $trace.pid
    metrics                = $metrics
    resourcesAtTraceObserved = [ordered]@{
        totalCpuMillis    = $totalCpuMillis
        peakWorkingSetMiB = $peakWorkingSetMiB
    }
    completedTrace         = $completedTraceEntry.Path
    launcherMetadata       = $launcherPath
}
$summaryPath = Join-Path $outputPath "$runId-summary.json"
Write-JsonFile -Path $summaryPath -Value $summary

$phaseRows = foreach ($event in $trace.events) {
    [pscustomobject]@{
        Phase         = $event.name
        MainElapsedMs = [Math]::Round(([double] $event.elapsedNanos - [double] $mainEnter.elapsedNanos) / 1000000.0, 3)
        LaunchMs      = [Math]::Round((Get-EventEpochMillis -Trace $trace -Event $event) - $launcherStartEpochMillis, 3)
        Thread        = $event.thread
    }
}

Write-Host ''
Write-Host "Termora ZIP startup baseline complete: $runId"
[pscustomobject] $metrics | Format-List | Out-Host
$phaseRows | Format-Table -AutoSize | Out-Host
Write-Host "Summary: $summaryPath"
Write-Host 'Termora was left running; close it normally when finished.'

[pscustomobject] $summary
