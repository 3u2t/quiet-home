#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$PiHost = 'raspberrypi.local',
    [string]$PiUser,
    [int]$SshPort = 22,
    [string]$CookieFile,
    [string]$YouTubeAuthUser,
    [string]$YouTubePageId,
    [string]$AdbTarget,
    [string]$AdbPath,
    [switch]$ResumeExisting
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

function Invoke-External([string]$Command,[string[]]$Arguments) {
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed (exit $LASTEXITCODE)." }
}
function Test-TcpPort([string]$HostName,[int]$Port,[int]$TimeoutMilliseconds = 4000) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect($HostName,$Port,$null,$null)
        if (-not $pending.AsyncWaitHandle.WaitOne($TimeoutMilliseconds,$false)) { return $false }
        try { $client.EndConnect($pending) } catch { return $false }
        return $client.Connected
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

if ($PiHost -notmatch '^[A-Za-z0-9.-]+$') { throw 'PiHost may contain only a host name or IP address.' }
if ($SshPort -lt 1 -or $SshPort -gt 65535) { throw 'SshPort must be between 1 and 65535.' }
if ([string]::IsNullOrWhiteSpace($PiUser)) { $PiUser = Read-Host 'Linux user on the Raspberry Pi (for example pi)' }
if ($PiUser -notmatch '^[a-z_][a-z0-9_-]*$') { throw 'Invalid Linux user name.' }
$ssh = (Get-Command ssh.exe -ErrorAction Stop).Source
$scp = (Get-Command scp.exe -ErrorAction Stop).Source
$target = $PiUser + '@' + $PiHost
$sshPrefix = @('-p',[string]$SshPort)
$scpPrefix = @('-P',[string]$SshPort)
$source = Join-Path $PSScriptRoot 'youtube-bridge'
if (-not (Test-Path -LiteralPath (Join-Path $source 'server.mjs'))) { throw 'The QuietHome-YouTubeBridge folder is missing.' }

Write-Host "Checking SSH at ${PiHost}:$SshPort before requesting the private cookie ..." -ForegroundColor Cyan
if (-not (Test-TcpPort -HostName $PiHost -Port $SshPort)) {
    throw "SSH is not reachable at ${PiHost}:$SshPort. Verify that this is the Raspberry Pi address (not the Fire TV), then enable SSH on the Pi with: sudo systemctl enable --now ssh"
}

$cookie = ''
$identityJson = ''
if ($ResumeExisting) {
    Write-Host 'Reusing the cookie and access key already transferred to the Raspberry Pi ...' -ForegroundColor Cyan
    $keyOutput = & $ssh @($sshPrefix + @($target,'cat "$HOME/quiet-home-youtube/data/access.key"'))
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the existing bridge access key. Run again without -ResumeExisting.' }
    $accessKey = (($keyOutput | Out-String).Trim())
    if ($accessKey -notmatch '^[a-f0-9]{64}$') { throw 'The existing bridge access key is invalid. Run again without -ResumeExisting.' }
} else {
    Write-Host ''
    Write-Host 'YouTube login (one time)' -ForegroundColor Cyan
    Write-Host '1. Open a new private/incognito browser window, sign in at youtube.com and select the YouTube profile you actually use.'
    Write-Host '2. Open https://www.youtube.com/feed/history and verify that your watched videos are visible there.'
    Write-Host '3. Open Developer Tools (F12), choose Network, filter for browse, then reload the history page.'
    Write-Host '4. Select the POST request containing /youtubei/v1/browse.'
    Write-Host '5. Under Request Headers copy the complete Cookie value. Also note X-Goog-AuthUser and X-Goog-PageId (if present).'
    Write-Host '6. Paste the values below, then close the private window without signing out.'
    Write-Host 'The value is transferred only through SSH and is never written into the APK.'
    Write-Host ''

    if (-not [string]::IsNullOrWhiteSpace($CookieFile)) {
        $cookiePath = (Resolve-Path -LiteralPath $CookieFile).Path
        $cookie = [IO.File]::ReadAllText($cookiePath,[Text.Encoding]::UTF8).Trim()
    } else {
        $secureCookie = Read-Host 'Paste Cookie header value' -AsSecureString
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureCookie)
        try { $cookie = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    }
    $cookie = ($cookie -replace '^\s*Cookie\s*:\s*','').Trim()
    if ($cookie -notmatch '(?i)(^|;\s*)(SAPISID|__Secure-3PAPISID)=') { throw 'The pasted value does not look like a complete signed-in YouTube Cookie header.' }
    if ($cookie -notmatch '(?i)(^|;\s*)LOGIN_INFO=') { throw 'The Cookie header is not an active signed-in YouTube session because LOGIN_INFO is missing. Copy Request Headers > Cookie from the /youtubei/v1/browse history request.' }

    if ([string]::IsNullOrWhiteSpace($YouTubeAuthUser)) { $YouTubeAuthUser = Read-Host 'X-Goog-AuthUser value (press Enter for 0)' }
    if ([string]::IsNullOrWhiteSpace($YouTubeAuthUser)) { $YouTubeAuthUser = '0' }
    if ($YouTubeAuthUser -notmatch '^\d$') { throw 'X-Goog-AuthUser must be one digit from 0 to 9.' }
    if ([string]::IsNullOrWhiteSpace($YouTubePageId)) { $YouTubePageId = Read-Host 'X-Goog-PageId value (press Enter if that header is absent)' }
    $YouTubePageId = ([string]$YouTubePageId).Trim()
    if ($YouTubePageId -and $YouTubePageId -notmatch '^\d{10,30}$') { throw 'X-Goog-PageId must be a numeric page ID, or empty when the header is absent.' }
    $identityJson = @{ accountIndex = [int]$YouTubeAuthUser; pageId = $YouTubePageId } | ConvertTo-Json -Compress

    $random = New-Object byte[] 32
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($random) } finally { $generator.Dispose() }
    $accessKey = [BitConverter]::ToString($random).Replace('-','').ToLowerInvariant()
}
$utf8 = New-Object Text.UTF8Encoding($false)
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$stage = Join-Path $tempRoot ('quiet-home-youtube-' + [Guid]::NewGuid().ToString('N'))
$stageFull = [IO.Path]::GetFullPath($stage)
if (-not $stageFull.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing an unsafe temporary path.' }
New-Item -ItemType Directory -Path $stageFull | Out-Null

try {
    $files = @('Dockerfile','docker-compose.yml','package.json','server.mjs','history-parser.mjs','README.md')
    foreach ($name in $files) {
        $item = Join-Path $source $name
        if (-not (Test-Path -LiteralPath $item)) { throw "Missing bridge file: $name" }
        Copy-Item -LiteralPath $item -Destination (Join-Path $stageFull $name)
    }
    if (-not $ResumeExisting) {
        [IO.File]::WriteAllText((Join-Path $stageFull 'youtube.cookie'),$cookie,$utf8)
        [IO.File]::WriteAllText((Join-Path $stageFull 'access.key'),$accessKey,$utf8)
        [IO.File]::WriteAllText((Join-Path $stageFull 'youtube.identity.json'),$identityJson,$utf8)
        $cookie = $null
        $identityJson = $null
    }

    Write-Host "Checking Docker on $target ..." -ForegroundColor Cyan
    Invoke-External $ssh ($sshPrefix + @($target,'docker compose version'))
    Invoke-External $ssh ($sshPrefix + @($target,'mkdir -p "$HOME/quiet-home-youtube/data" && chmod 700 "$HOME/quiet-home-youtube/data"'))
    foreach ($name in $files) { Invoke-External $scp ($scpPrefix + @((Join-Path $stageFull $name),($target + ':quiet-home-youtube/' + $name))) }
    if (-not $ResumeExisting) {
        Invoke-External $scp ($scpPrefix + @((Join-Path $stageFull 'youtube.cookie'),($target + ':quiet-home-youtube/data/youtube.cookie')))
        Invoke-External $scp ($scpPrefix + @((Join-Path $stageFull 'access.key'),($target + ':quiet-home-youtube/data/access.key')))
        Invoke-External $scp ($scpPrefix + @((Join-Path $stageFull 'youtube.identity.json'),($target + ':quiet-home-youtube/data/youtube.identity.json')))
    }
    Invoke-External $ssh ($sshPrefix + @($target,'chmod 600 "$HOME/quiet-home-youtube/data/youtube.cookie" "$HOME/quiet-home-youtube/data/access.key" && if [ -f "$HOME/quiet-home-youtube/data/youtube.identity.json" ]; then chmod 600 "$HOME/quiet-home-youtube/data/youtube.identity.json"; fi && cd "$HOME/quiet-home-youtube" && echo "PUID=$(id -u)" > .env && echo "PGID=$(id -g)" >> .env && chmod 600 .env && docker compose up -d --build'))

    $bridgeUrl = 'http://' + $PiHost + ':8787/api/continue-watching'
    $healthUrl = 'http://' + $PiHost + ':8787/health'
    Write-Host 'Waiting for the private bridge ...' -ForegroundColor Cyan
    $ready = $false
    for ($attempt = 0; $attempt -lt 15 -and -not $ready; $attempt++) {
        try {
            $health = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 4
            $ready = $health.ok -eq $true -and $health.configured -eq $true
        } catch { }
        if (-not $ready) { Start-Sleep -Seconds 2 }
    }
    if (-not $ready) { throw "The bridge did not become ready. Check: ssh -p $SshPort $target 'cd ~/quiet-home-youtube && docker compose logs --tail=80'" }
    try {
        $preview = Invoke-RestMethod -Uri $bridgeUrl -Headers @{'X-Quiet-Home-Key'=$accessKey} -TimeoutSec 90
    } catch {
        throw "YouTube did not accept the cookie or history request. Check: ssh -p $SshPort $target 'cd ~/quiet-home-youtube && docker compose logs --tail=80'"
    }
    if ($preview.version -ne 1 -or $null -eq $preview.items) { throw 'The bridge returned an unexpected response.' }
    Write-Host ("Bridge works: {0} unfinished YouTube video(s) found." -f @($preview.items).Count) -ForegroundColor Green
    if (@($preview.items).Count -eq 0 -and $null -ne $preview.diagnostics) {
        $diagnostics = $preview.diagnostics
        Write-Warning 'YouTube returned no usable unfinished videos. The privacy-safe diagnosis is shown below.'
        Write-Host ("Diagnosis: status={0}; pages={1}; video cards={2}; with progress={3}; eligible={4}" -f $diagnostics.status,$diagnostics.pages,$diagnostics.candidates,$diagnostics.withProgress,$diagnostics.eligible) -ForegroundColor Yellow
        Write-Host ("Progress values: {0}" -f (@($diagnostics.progressValues) -join ', '))
        Write-Host ("Renderer types: {0}" -f (($diagnostics.rendererTypes | ConvertTo-Json -Compress)))
        if ($null -ne $diagnostics.profilesTested) {
            Write-Host ("Account scan: profiles={0}; Google account slots={1}; YouTube channels={2}; selected={3}" -f $diagnostics.profilesTested,$diagnostics.accountIndicesTested,$diagnostics.delegatedProfilesFound,$diagnostics.selectedProfile)
        }
        if ($null -ne $diagnostics.capturedIdentityUsed) {
            Write-Host ("Captured browser identity used: {0}" -f $diagnostics.capturedIdentityUsed)
        }
        Write-Host 'This diagnosis contains no cookie, video title, video ID, or channel name.' -ForegroundColor DarkGray
    }

    if ([string]::IsNullOrWhiteSpace($AdbTarget)) {
        Write-Host ''
        Write-Host 'Bridge setup is complete.' -ForegroundColor Green
        Write-Host 'Run this script again with -ResumeExisting -AdbTarget FIRE_TV_IP:5555 to connect Quiet Home.'
    } else {
        if ($AdbTarget -notmatch '^[A-Za-z0-9.-]+(?::[0-9]{1,5})?$') { throw 'AdbTarget must be a host name or IP address, optionally followed by a port.' }
        if ([string]::IsNullOrWhiteSpace($AdbPath)) {
            $adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
            if ($null -eq $adbCommand) { $adbCommand = Get-Command adb -ErrorAction SilentlyContinue }
            if ($null -eq $adbCommand) { throw 'ADB was not found. Install Android platform-tools or pass -AdbPath.' }
            $adb = $adbCommand.Source
        } else {
            $adb = (Resolve-Path -LiteralPath $AdbPath).Path
        }
        Write-Host "Connecting Quiet Home at $AdbTarget ..." -ForegroundColor Cyan
        Invoke-External $adb @('connect',$AdbTarget)
        Invoke-External $adb @('-s',$AdbTarget,'shell','am','start','-W','-n','de.four04.quiethome/.MainActivity','--ez','quietSetup','true','--es','youtubeBridgeUrl',$bridgeUrl,'--es','youtubeBridgeKey',$accessKey,'--es','page','continue')
        $package = & $adb @('-s',$AdbTarget,'shell','dumpsys','package','de.four04.quiethome')
        if ($LASTEXITCODE -ne 0) { throw 'ADB could not read the Quiet Home package state.' }
        if (($package | Out-String) -notmatch '(?m)\bversionCode=11\s') { throw 'Quiet Home 2.0 is not installed; the bridge is running but the TV still needs the new APK.' }
        Write-Host ''
        Write-Host 'Done: Quiet Home is connected to the private YouTube history bridge.' -ForegroundColor Green
        Write-Host 'Open Continue Watching on the TV. Videos between 2% and 94% watched are shown.'
    }
} finally {
    $cookie = $null
    $identityJson = $null
    if (Test-Path -LiteralPath $stageFull) {
        $verified = [IO.Path]::GetFullPath($stageFull)
        if ($verified.StartsWith($tempRoot,[StringComparison]::OrdinalIgnoreCase) -and $verified -ne $tempRoot) { Remove-Item -LiteralPath $verified -Recurse -Force }
    }
}
