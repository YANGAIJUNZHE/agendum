param([Parameter(Mandatory = $true)][string]$Base)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$settings = Get-Content -LiteralPath (Join-Path $projectRoot '.env') -Raw
$port = [regex]::Match($settings, '(?m)^AGENDUM_WEB_PORT\s*=\s*(\d+)\s*$').Groups[1].Value
if (-not $port) { $port = '8766' }
$local = 'http://127.0.0.1:' + $port
$adminToken = [regex]::Match($settings, '(?m)^AGENDUM_WEB_TOKEN\s*=\s*(.+)$').Groups[1].Value.Trim().Trim('"').Trim("'")
if (-not $adminToken) { throw '验收需要本地管理员令牌。' }
$admin = @{ 'X-Token' = $adminToken }
$checks = [Collections.Generic.List[string]]::new()
function Assert-Check($condition, [string]$label) {
    if (-not $condition) { throw ('验收失败：' + $label) }
    $checks.Add($label)
}
function Call-Json([string]$root, [string]$path, $body = $null, $headers = @{}) {
    $request = @{ Uri = $root + $path; Headers = $headers; TimeoutSec = 25 }
    if ($null -ne $body) { $request.Method = 'POST'; $request.ContentType = 'application/json; charset=utf-8'; $request.Body = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 15 -Compress)) }
    Invoke-RestMethod @request
}
$infoResponse = Invoke-WebRequest ($Base + '/api/v1/info') -TimeoutSec 25
$info = $infoResponse.Content | ConvertFrom-Json
$localInfo = Call-Json $local '/api/v1/info'
Assert-Check ($info.protocol -eq 1 -and $info.server_id -eq $localInfo.server_id) '公网 HTTPS 到达本机 v1 服务'
Assert-Check (($infoResponse.Headers['Cache-Control'] -join ',') -eq 'no-store') '同步响应禁止缓存'
foreach ($path in @('/', '/api/meta', '/api/v1/admin/devices')) {
    $result = Invoke-WebRequest ($Base + $path) -SkipHttpErrorCheck -TimeoutSec 25
    Assert-Check ($result.StatusCode -eq 404) ('公网拒绝 ' + $path)
}
$unauth = Invoke-WebRequest ($Base + '/api/v1/sync/changes') -SkipHttpErrorCheck -TimeoutSec 25
Assert-Check ($unauth.StatusCode -eq 401) '未配对设备不能读取日程'
$pair = $null; $task = $null
try {
    $ticket = Call-Json $Base '/api/v1/pair/start' @{name='临时公网验收设备'}
    Call-Json $local '/api/v1/admin/devices/approve' @{id=$ticket.id} $admin | Out-Null
    $pair = Call-Json $Base '/api/v1/pair/exchange' @{id=$ticket.id;secret=$ticket.secret}
    $device = @{ Authorization = 'Bearer ' + $pair.token }
    Assert-Check ($pair.status -eq 'approved' -and $pair.server_id -eq $info.server_id) '公网申请与本地批准完成独立设备配对'
    $query = '?server_id=' + $info.server_id + '&epoch=' + $info.epoch
    $page = Call-Json $Base ('/api/v1/sync/bootstrap' + $query) $null $device
    Assert-Check ($page.ok) '配对后可全量同步'
    $operation = @{server_id=$info.server_id;epoch=$info.epoch;operation_id=[guid]::NewGuid().ToString();uuid=[guid]::NewGuid().ToString();type='create';base_version=0;base=@{};changes=@{summary='公网链路验收临时日程';notes='仅用于验证，验收结束删除';deadline=@{kind='date';value='2026-11-06'}}}
    $created = Call-Json $Base '/api/v1/mutations' $operation $device
    $task = $created.task
    Assert-Check ($created.ok -and $task.summary -eq $operation.changes.summary) '公网新建日程成功'
    $replayed = Call-Json $Base '/api/v1/mutations' $operation $device
    Assert-Check ($replayed.task.version -eq $task.version -and $replayed.task.uuid -eq $task.uuid) '相同操作重试不重复创建或增加版本'
    $read = Call-Json $local ('/api/v1/tasks/lookup?id=' + $task.id) $null $admin
    Assert-Check ($read.task.notes -eq $operation.changes.notes) '手机写入在电脑读取一致'
    $operation = @{server_id=$info.server_id;epoch=$info.epoch;operation_id=[guid]::NewGuid().ToString();uuid=$task.uuid;type='update';base_version=$task.version;base=@{summary=$task.summary};changes=@{summary='电脑修改后经公网增量获取'}}
    $edited = Call-Json $local '/api/v1/mutations' $operation $admin
    $task = $edited.task
    $changes = Call-Json $Base ('/api/v1/sync/changes' + $query + '&after=' + $page.upper) $null $device
    Assert-Check (@($changes.items | Where-Object { $_.uuid -eq $task.uuid -and $_.summary -eq $task.summary }).Count -gt 0) '电脑修改经公网增量返回'
    foreach ($state in @('done','open')) {
        $operation = @{server_id=$info.server_id;epoch=$info.epoch;operation_id=[guid]::NewGuid().ToString();uuid=$task.uuid;type='update';base_version=$task.version;base=@{status=$task.status};changes=@{status=$state}}
        $updated = Call-Json $Base '/api/v1/mutations' $operation $device
        Assert-Check ($updated.ok -and $updated.task.status -eq $state) ('公网状态变更 ' + $state)
        $task = $updated.task
    }
    $before = @{}; foreach ($field in @('summary','notes','deadline','category','status','pinned','urgent_override','snooze_until')) { $before[$field]=$task.$field }
    $operation = @{server_id=$info.server_id;epoch=$info.epoch;operation_id=[guid]::NewGuid().ToString();uuid=$task.uuid;type='delete';base_version=$task.version;base=$before;changes=@{}}
    $deleted = Call-Json $Base '/api/v1/mutations' $operation $device
    Assert-Check ($deleted.ok -and $deleted.task.deleted) '公网删除返回独立删除记录'
    Call-Json $local '/api/v1/admin/devices/revoke' @{device_id=$pair.device_id} $admin | Out-Null
    $revoked = Invoke-WebRequest ($Base + '/api/v1/sync/changes' + $query) -Headers $device -SkipHttpErrorCheck -TimeoutSec 25
    Assert-Check ($revoked.StatusCode -eq 401) '撤销设备后公网访问被拒绝'
} finally {
    if ($pair) { Call-Json $local '/api/v1/admin/devices/revoke' @{device_id=$pair.device_id} $admin | Out-Null }
}
$evidence = @{time=(Get-Date).ToUniversalTime().ToString('o');base=$Base;server_id=$info.server_id;checks=@($checks);cache_control=($infoResponse.Headers['Cache-Control'] -join ',');cf_cache_status=($infoResponse.Headers['CF-Cache-Status'] -join ',');passed=$true}
$outputRoot = Join-Path $projectRoot '.tmp-qa/mobile-sync'
New-Item -ItemType Directory -Force $outputRoot | Out-Null
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $outputRoot 'public-acceptance.json') -Encoding UTF8
$checks | ForEach-Object { Write-Output ('PASS ' + $_) }
Write-Output '公网验收完成，临时设备已撤销，临时日程已删除。'
