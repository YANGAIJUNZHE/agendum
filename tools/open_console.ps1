$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$envFile = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw '请先配置 .env 并启动电脑服务。' }
$config = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*(NOTICEHUB_WEB_TOKEN|QQ_DIGEST_WEB_TOKEN|NOTICEHUB_WEB_PORT|QQ_DIGEST_WEB_PORT)\s*=\s*(.*)$') {
        $config[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
    }
}
$port = $config['NOTICEHUB_WEB_PORT']; if (-not $port) { $port = $config['QQ_DIGEST_WEB_PORT'] }; if (-not $port) { $port = '8766' }
if ($port -notmatch '^\d{1,5}$' -or [int]$port -gt 65535) { throw '无效的网页端口。' }
$token = $config['NOTICEHUB_WEB_TOKEN']; if (-not $token) { $token = $config['QQ_DIGEST_WEB_TOKEN'] }
$url = 'http://127.0.0.1:' + $port + '/'
if ($token) { $url += '?token=' + [Uri]::EscapeDataString($token) }
# 令牌只传给本机浏览器；网页交换为管理员会话后会清除地址栏中的令牌。
Start-Process $url
