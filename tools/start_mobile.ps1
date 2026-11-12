param(
    [string]$PythonPath = '',
    [string]$CloudflaredPath = '',
    [switch]$NoTunnel
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $PythonPath) { $PythonPath = Join-Path $projectRoot '.venv/Scripts/python.exe' }
if (-not (Test-Path -LiteralPath $PythonPath)) { throw '找不到项目 Python，请先按 wiki 安装依赖，或传入 -PythonPath。' }
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.env'))) { throw '请先创建 .env，设置电脑管理令牌和 AGENDUM_MOBILE_BASE_URL。' }
$envText = Get-Content -LiteralPath (Join-Path $projectRoot '.env') -Raw
$configuredPort = [regex]::Match($envText, '(?m)^AGENDUM_WEB_PORT\s*=\s*(\d+)\s*$').Groups[1].Value
if (-not $configuredPort) { $configuredPort = [regex]::Match($envText, '(?m)^QQ_DIGEST_WEB_PORT\s*=\s*(\d+)\s*$').Groups[1].Value }
if ($env:AGENDUM_WEB_PORT) { $configuredPort = $env:AGENDUM_WEB_PORT } elseif ($env:QQ_DIGEST_WEB_PORT) { $configuredPort = $env:QQ_DIGEST_WEB_PORT }
if (-not $configuredPort) { $configuredPort = '8766' }
if ($configuredPort -notmatch '^\d{1,5}$' -or [int]$configuredPort -lt 1 -or [int]$configuredPort -gt 65535) { throw '无效的网页端口。' }
$localInfoUrl = 'http://127.0.0.1:' + $configuredPort + '/api/v1/info'
$runtimeRoot = Join-Path $projectRoot 'data'
$logRoot = Join-Path $projectRoot 'logs'
New-Item -ItemType Directory -Force $runtimeRoot, $logRoot | Out-Null
$stateFile = Join-Path $runtimeRoot 'mobile-runtime.json'
if (Test-Path -LiteralPath $stateFile) {
    $previous = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
    foreach ($entry in $previous.processes) {
        $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetimeoffset]$entry.started).UtcDateTime.Ticks) {
            throw '本脚本启动的进程仍在运行。请先执行 tools/stop_mobile.ps1，或使用当前服务。'
        }
    }
}
$created = @()
try {
    # 不接管已有服务；端口可达但协议不对时应先查明来源。
    $localInfo = $null
    try { $localInfo = Invoke-RestMethod $localInfoUrl -TimeoutSec 2 } catch { }
    if (-not $localInfo) {
        $backend = Start-Process -FilePath $PythonPath -ArgumentList @('main.py','run') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $logRoot 'mobile-backend.out.log') -RedirectStandardError (Join-Path $logRoot 'mobile-backend.err.log')
        $created += @{ id = $backend.Id; started = $backend.StartTime.ToUniversalTime().ToString('o'); kind = 'backend' }
        for ($attempt = 0; $attempt -lt 25; $attempt++) {
            Start-Sleep -Milliseconds 400
            if ($backend.HasExited) { throw '电脑服务没有启动，请查看 logs/mobile-backend.err.log。' }
            try { $localInfo = Invoke-RestMethod $localInfoUrl -TimeoutSec 2; break } catch { }
        }
    }
    if (-not $localInfo -or $localInfo.protocol -ne 1) { throw ('端口 ' + $configuredPort + ' 没有返回新版日程接口，请核对端口和 logs/mobile-backend.out.log。') }
    if (-not $NoTunnel) {
        if (-not $CloudflaredPath) {
            $CloudflaredPath = Join-Path $runtimeRoot 'cloudflared.exe'
            if (-not (Test-Path -LiteralPath $CloudflaredPath)) {
                $command = Get-Command cloudflared -ErrorAction SilentlyContinue
                if ($command) { $CloudflaredPath = $command.Source }
            }
        }
        if (-not (Test-Path -LiteralPath $CloudflaredPath)) { throw '找不到 cloudflared，请安装或传入 -CloudflaredPath。' }
        $tokenFile = Join-Path $runtimeRoot 'cloudflared.token'
        if (-not (Test-Path -LiteralPath $tokenFile)) { throw '缺少 data/cloudflared.token，请从本项目隧道配置获取连接令牌，不要提交到仓库。' }
        $connector = Start-Process -FilePath $CloudflaredPath -ArgumentList @('tunnel','--no-autoupdate','run','--token-file',('"' + $tokenFile + '"')) `
            -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot 'mobile-tunnel.out.log') -RedirectStandardError (Join-Path $logRoot 'mobile-tunnel.err.log')
        $created += @{ id = $connector.Id; started = $connector.StartTime.ToUniversalTime().ToString('o'); kind = 'tunnel' }
    }
    @{ processes = @($created); server_id = $localInfo.server_id } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stateFile -Encoding UTF8
    Write-Output '电脑服务已就绪。执行 tools/open_console.ps1 打开管理页面，在设置中完成手机配对。'
    if (-not $NoTunnel) { Write-Output '隧道连接日志：logs/mobile-tunnel.err.log。公网是否可用还需检查 DNS 和 /api/v1/info。' }
} catch {
    # 仅结束本次启动的进程，不结束先前已在运行的服务。
    foreach ($entry in $created) {
        $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetimeoffset]$entry.started).UtcDateTime.Ticks) { & taskkill.exe /PID ([string]$entry.id) /T /F | Out-Null }
    }
    throw
}
