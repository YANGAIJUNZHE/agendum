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
$configuredPort = ''
foreach ($key in @('AGENDUM_WEB_PORT', 'NOTICEHUB_WEB_PORT', 'QQ_DIGEST_WEB_PORT')) {
    $envVal = [Environment]::GetEnvironmentVariable($key)
    if ($envVal) { $configuredPort = $envVal.Trim().Trim('"').Trim("'"); break }
}
if (-not $configuredPort) {
    foreach ($key in @('AGENDUM_WEB_PORT', 'NOTICEHUB_WEB_PORT', 'QQ_DIGEST_WEB_PORT')) {
        $match = [regex]::Match($envText, '(?m)^\s*' + $key + '\s*=\s*["'']?(\d+)["'']?\s*$')
        if ($match.Success) { $configuredPort = $match.Groups[1].Value; break }
    }
}
if (-not $configuredPort) { $configuredPort = '8766' }
if ($configuredPort -notmatch '^\d{1,5}$' -or [int]$configuredPort -lt 1 -or [int]$configuredPort -gt 65535) { throw '无效的网页端口。' }
$mobileBaseUrl = ''
foreach ($key in @('AGENDUM_MOBILE_BASE_URL', 'NOTICEHUB_MOBILE_BASE_URL', 'QQ_DIGEST_MOBILE_BASE_URL')) {
    $envVal = [Environment]::GetEnvironmentVariable($key)
    if ($envVal) { $mobileBaseUrl = $envVal.Trim().Trim('"').Trim("'"); break }
}
if (-not $mobileBaseUrl) {
    foreach ($key in @('AGENDUM_MOBILE_BASE_URL', 'NOTICEHUB_MOBILE_BASE_URL', 'QQ_DIGEST_MOBILE_BASE_URL')) {
        $match = [regex]::Match($envText, '(?m)^\s*' + $key + '\s*=\s*(.+?)\s*$')
        if ($match.Success) {
            $candidate = $match.Groups[1].Value.Trim().Trim('"').Trim("'")
            if ($candidate) { $mobileBaseUrl = $candidate; break }
        }
    }
}
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
        $tunnelOut = Join-Path $logRoot 'mobile-tunnel.out.log'
        $tunnelErr = Join-Path $logRoot 'mobile-tunnel.err.log'
        $tunnelReady = $false
        foreach ($proto in @('', 'http2')) {
            $argList = @('tunnel', '--no-autoupdate')
            if ($proto) { $argList += @('--protocol', $proto) }
            $argList += @('run', '--token-file', ('"' + $tokenFile + '"'))
            $connector = Start-Process -FilePath $CloudflaredPath -ArgumentList $argList `
                -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $tunnelOut -RedirectStandardError $tunnelErr
            $entry = @{ id = $connector.Id; started = $connector.StartTime.ToUniversalTime().ToString('o'); kind = 'tunnel' }
            $created += $entry
            Start-Sleep -Seconds 2
            $connector.Refresh()
            if ($connector.HasExited) {
                $created = @($created | Where-Object { $_.id -ne $entry.id })
                continue
            }
            if (-not ($mobileBaseUrl -match '^https?://')) {
                $tunnelReady = $true
                break
            }
            $publicInfoUrl = $mobileBaseUrl.TrimEnd('/') + '/api/v1/info'
            for ($attempt = 0; $attempt -lt 8; $attempt++) {
                $connector.Refresh()
                if ($connector.HasExited) { break }
                try {
                    $publicInfo = Invoke-RestMethod $publicInfoUrl -TimeoutSec 3
                    if ($publicInfo -and $publicInfo.protocol -eq 1) {
                        $tunnelReady = $true
                        break
                    }
                } catch { }
                Start-Sleep -Seconds 1
            }
            if ($tunnelReady) { break }
            if (-not $connector.HasExited) { & taskkill.exe /PID ([string]$connector.Id) /T /F | Out-Null }
            $created = @($created | Where-Object { $_.id -ne $entry.id })
        }
        if (-not $tunnelReady) {
            throw ('cloudflared 隧道未能连上公网，请检查 data/cloudflared.token、网络代理和 logs/mobile-tunnel.err.log。')
        }
    }
    @{ processes = @($created); server_id = $localInfo.server_id } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $stateFile -Encoding UTF8
    Write-Output '电脑服务已就绪。执行 tools/open_console.ps1 打开管理页面，在设置中完成手机配对。'
    if (-not $NoTunnel) {
        if ($mobileBaseUrl -match '^https?://') { Write-Output ('公网入口已验证：' + $mobileBaseUrl.TrimEnd('/') + '/api/v1/info') }
        Write-Output '隧道连接日志：logs/mobile-tunnel.err.log。'
    }
} catch {
    # 仅结束本次启动的进程，不结束先前已在运行的服务。
    foreach ($entry in $created) {
        $process = Get-Process -Id $entry.id -ErrorAction SilentlyContinue
        if ($process -and $process.StartTime.ToUniversalTime().Ticks -eq ([datetimeoffset]$entry.started).UtcDateTime.Ticks) { & taskkill.exe /PID ([string]$entry.id) /T /F | Out-Null }
    }
    throw
}
