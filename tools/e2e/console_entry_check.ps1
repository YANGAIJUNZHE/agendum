$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$scripts = @('open_console.ps1', 'start_mobile.ps1', 'stop_mobile.ps1')
foreach ($name in $scripts) {
    $tokens = $null
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $projectRoot ('tools/' + $name)), [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw ('Script parse failed: ' + $name + ' ' + ($parseErrors.ErrorId -join ',')) }
}

# Execute the real entrypoint in an isolated project. Only the browser launch is replaced.
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('noticehub-console-' + [guid]::NewGuid().ToString('N'))
$utf8 = New-Object Text.UTF8Encoding($true)
try {
    [void][IO.Directory]::CreateDirectory((Join-Path $fixture 'tools'))
    Copy-Item -LiteralPath (Join-Path $projectRoot 'tools/open_console.ps1') -Destination (Join-Path $fixture 'tools/open_console.ps1')
    [IO.File]::WriteAllText((Join-Path $fixture '.env'), "NOTICEHUB_WEB_PORT=18766`r`nNOTICEHUB_WEB_TOKEN=console-test`r`n", $utf8)
    $global:noticehubConsoleOpened = $null
    function Start-Process { param([string]$FilePath) $global:noticehubConsoleOpened = $FilePath }
    & (Join-Path $fixture 'tools/open_console.ps1')
    if ($global:noticehubConsoleOpened -ne 'http://127.0.0.1:18766/?token=console-test') { throw 'The browser was not opened with the configured port and session token.' }
    [IO.File]::WriteAllText((Join-Path $fixture '.env'), "QQ_DIGEST_WEB_PORT=18767`r`nQQ_DIGEST_WEB_TOKEN=legacy-test`r`n", $utf8)
    $global:noticehubConsoleOpened = $null
    & (Join-Path $fixture 'tools/open_console.ps1')
    if ($global:noticehubConsoleOpened -ne 'http://127.0.0.1:18767/?token=legacy-test') { throw 'Legacy configuration was not honored.' }
    [IO.File]::WriteAllText((Join-Path $fixture '.env'), "NOTICEHUB_WEB_PORT=invalid`r`n", $utf8)
    $global:noticehubConsoleOpened = $null
    $rejected = $false
    try { & (Join-Path $fixture 'tools/open_console.ps1') } catch { $rejected = $true }
    if (-not $rejected -or $global:noticehubConsoleOpened) { throw 'Invalid port opened a browser instead of failing.' }
    Write-Output ('PASS: 3 scripts parse, configured and legacy URLs open, invalid port rejected; PowerShell ' + $PSVersionTable.PSVersion)
} finally {
    # The absolute target was constructed below the system temporary directory and carries our GUID.
    $resolved = [IO.Path]::GetFullPath($fixture)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path $resolved -Leaf) -notmatch '^noticehub-console-[0-9a-f]{32}$') { throw 'Unexpected fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
