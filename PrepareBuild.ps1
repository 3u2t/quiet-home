#Requires -Version 5.1
# Download-only prerequisite step. Does not connect to or change the TV.
[CmdletBinding()]
param([string]$ToolsDirectory)
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
if ([string]::IsNullOrWhiteSpace($ToolsDirectory)) { $ToolsDirectory = Join-Path $PSScriptRoot '.tools\android-toolchain' }
$tools = [IO.Path]::GetFullPath($ToolsDirectory)
New-Item -ItemType Directory -Path $tools -Force | Out-Null
$repository = Join-Path $tools 'repository2-1.xml'
Write-Host 'Downloading official Google SDK package metadata...'
Invoke-WebRequest -UseBasicParsing -Uri 'https://dl.google.com/android/repository/repository2-1.xml' -OutFile $repository
[xml]$xml = Get-Content -LiteralPath $repository -Raw
$toolPaths = @{}
foreach ($packageName in @('build-tools;35.0.0','platforms;android-30')) {
    $package = $xml.SelectSingleNode("//*[local-name()='remotePackage' and @path='$packageName']")
    if (-not $package) { throw "Official repository does not contain $packageName" }
    $archives = $package.SelectNodes("./*[local-name()='archives']/*[local-name()='archive']")
    $selected = $archives | Where-Object {
        $hostOs = $_.SelectSingleNode("./*[local-name()='host-os']")
        (-not $hostOs) -or $hostOs.InnerText -eq 'windows'
    } | Select-Object -First 1
    if (-not $selected) { throw "No Windows archive for $packageName" }
    $complete = $selected.SelectSingleNode("./*[local-name()='complete']")
    $relative = $complete.SelectSingleNode("./*[local-name()='url']").InnerText
    $checksum = $complete.SelectSingleNode("./*[local-name()='checksum']")
    $uri = [Uri]::new([Uri]'https://dl.google.com/android/repository/', $relative)
    if ($uri.Scheme -ne 'https' -or $uri.Host -ne 'dl.google.com') { throw 'Unexpected SDK download host.' }
    $file = Join-Path $tools ([IO.Path]::GetFileName($uri.LocalPath))
    $expected = $checksum.InnerText.Trim().ToUpperInvariant()
    $algorithm = if ($checksum.GetAttribute('type') -eq 'sha256' -or $expected.Length -eq 64) { 'SHA256' } else { 'SHA1' }
    $valid = (Test-Path -LiteralPath $file) -and ((Get-FileHash -LiteralPath $file -Algorithm $algorithm).Hash -eq $expected)
    if (-not $valid) {
        Write-Host "Downloading $packageName..."
        Invoke-WebRequest -UseBasicParsing -Uri $uri.AbsoluteUri -OutFile $file
        if ((Get-FileHash -LiteralPath $file -Algorithm $algorithm).Hash -ne $expected) { throw "Checksum mismatch: $packageName" }
    }
    $destination = Join-Path $tools ($packageName -replace '[;]','-')
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Expand-Archive -LiteralPath $file -DestinationPath $destination -Force
    $toolPaths[$packageName] = $destination
}
$java = Get-ChildItem -LiteralPath $tools -Filter javac.exe -File -Recurse | Select-Object -First 1
if (-not $java) {
    $jdkArchive = Join-Path $tools 'microsoft-jdk-17-windows-x64.zip'
    Write-Host 'Downloading Java 17 from Microsoft...'
    Invoke-WebRequest -UseBasicParsing -Uri 'https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip' -OutFile $jdkArchive
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath $tools -Force
    $java = Get-ChildItem -LiteralPath $tools -Filter javac.exe -File -Recurse | Select-Object -First 1
}
if (-not $java) { throw 'Java compiler not found after extraction.' }
$aapt = Get-ChildItem -LiteralPath $toolPaths['build-tools;35.0.0'] -Filter aapt2.exe -File -Recurse | Select-Object -First 1
$android = Get-ChildItem -LiteralPath $toolPaths['platforms;android-30'] -Filter android.jar -File -Recurse | Select-Object -First 1
if (-not $aapt -or -not $android) { throw 'Required SDK tools are missing.' }
@{
    javaBin = $java.Directory.FullName
    buildTools = $aapt.Directory.FullName
    androidJar = $android.FullName
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $tools 'toolchain.json') -Encoding UTF8
Write-Host 'Build tools ready. Nothing on the TV was changed.' -ForegroundColor Green
