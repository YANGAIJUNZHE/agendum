$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$stateFile = Join-Path $projectRoot 'data/mobile-runtime.json'
if (-not (Test-Path -LiteralPath $stateFile)) { Write-Output '没有本脚本记录的进程。'; exit 0 }
$runtime = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
foreach ($entry in @($runtime.processes) | Sort-Object kind -Descending) {
    $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetimeoffset]$entry.started).UtcDateTime.Ticks) {
        & taskkill.exe /PID ([string]$entry.id) /T /F | Out-Null
        if ($LASTEXITCODE -ne 0) { throw ('未能停止本次启动的 ' + $entry.kind) }
        Write-Output ('已停止本脚本启动的 ' + $entry.kind + '。')
    }
}
Remove-Item -LiteralPath $stateFile
