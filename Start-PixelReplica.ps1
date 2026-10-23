param([switch]$NoBrowser)
$ErrorActionPreference = 'Stop'
$replicaRoot = $PSScriptRoot
$bundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$pythonCommand = if (Test-Path -LiteralPath $bundledPython) { $bundledPython } else { (Get-Command python -ErrorAction Stop).Source }
$taskFolder = Split-Path (Split-Path $replicaRoot -Parent) -Parent
$engineCandidates = @(
    (Join-Path $replicaRoot '.local-tools\colmap-4.2.1\COLMAP.bat'),
    (Join-Path $taskFolder 'work\dependencies\colmap-4.2.1\COLMAP.bat')
)
if (-not $env:COLMAP_PATH) {
    foreach ($candidate in $engineCandidates) {
        if (Test-Path -LiteralPath $candidate) { $env:COLMAP_PATH = $candidate; break }
    }
}
& $pythonCommand -c 'import numpy, PIL'
if ($LASTEXITCODE -ne 0) { throw 'Python needs NumPy and Pillow. Install tools/object-replica/requirements.txt in your Python environment.' }
$builtPage = Join-Path $replicaRoot 'apps\object-review\dist\index.html'
if (-not (Test-Path -LiteralPath $builtPage)) {
    Push-Location $replicaRoot
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) { & npm ci; if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' } }
        & npm run build:objects
        if ($LASTEXITCODE -ne 0) { throw 'The object review page did not build.' }
    } finally { Pop-Location }
}
$replicaAddress = 'http://127.0.0.1:5190'
$running = $false
try {
    $response = Invoke-RestMethod -Uri "$replicaAddress/api/status" -TimeoutSec 2
    if ($null -ne $response.privacy -and $null -ne $response.colmap) { $running = $true }
} catch { }
if (-not $running) {
    $runtimeFolder = Join-Path $replicaRoot 'data\object-captures\.runtime'
    New-Item -ItemType Directory -Path $runtimeFolder -Force | Out-Null
    $serverScript = Join-Path $replicaRoot 'tools\object-replica\replica.py'
    $process = Start-Process -FilePath $pythonCommand -ArgumentList @('"' + $serverScript + '"', 'serve') -WorkingDirectory $replicaRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $runtimeFolder 'server.log') -RedirectStandardError (Join-Path $runtimeFolder 'server-error.log') -PassThru
    $process.Id | Set-Content -LiteralPath (Join-Path $runtimeFolder 'server.pid')
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($process.HasExited) { throw "Local server stopped. Read $runtimeFolder\server-error.log" }
        try { $response = Invoke-RestMethod -Uri "$replicaAddress/api/status" -TimeoutSec 1; $running = $true; break } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $running) { throw "The local server did not become ready. Read $runtimeFolder\server-error.log" }
}
Write-Output "Pixel Replica is running at $replicaAddress"
if (-not $NoBrowser) { Start-Process $replicaAddress }
