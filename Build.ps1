#Requires -Version 5.1
[CmdletBinding()]
param([string]$ToolsDirectory)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ToolsDirectory)) { $ToolsDirectory = Join-Path $PSScriptRoot '.tools\android-toolchain' }
$tools = [IO.Path]::GetFullPath($ToolsDirectory)
$configPath = Join-Path $tools 'toolchain.json'
if (-not (Test-Path -LiteralPath $configPath)) { throw 'Run PrepareBuild.ps1 first. This build runs offline.' }
$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
$java = Join-Path $config.javaBin 'java.exe'
$javac = Join-Path $config.javaBin 'javac.exe'
$keytool = Join-Path $config.javaBin 'keytool.exe'
$aapt = Join-Path $config.buildTools 'aapt2.exe'
$align = Join-Path $config.buildTools 'zipalign.exe'
$d8 = Join-Path $config.buildTools 'lib\d8.jar'
$signer = Join-Path $config.buildTools 'lib\apksigner.jar'
$source = Join-Path $PSScriptRoot 'app'
$build = Join-Path $tools ('build-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$classes = Join-Path $build 'classes'
$generated = Join-Path $build 'generated'
$dex = Join-Path $build 'dex'
$buildTemp = Join-Path $build 'tmp'
New-Item -ItemType Directory -Path $classes,$generated,$dex,$buildTemp -Force | Out-Null
function Run-Tool([string]$Exe,[string[]]$Arguments) {
    $toolName = [IO.Path]::GetFileNameWithoutExtension($Exe)
    if ($toolName -eq 'java') { $Arguments = @("-Djava.io.tmpdir=$buildTemp") + $Arguments }
    elseif ($toolName -in @('javac','jar','keytool')) { $Arguments = @("-J-Djava.io.tmpdir=$buildTemp") + $Arguments }
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $Exe (exit $LASTEXITCODE)" }
}
$assets = Join-Path $source 'assets'
if (-not (Test-Path -LiteralPath $assets)) { throw 'The app assets directory is missing.' }
Run-Tool $aapt @('compile','--dir',(Join-Path $source 'res'),'-o',(Join-Path $build 'resources.zip'))
$unsigned = Join-Path $build 'unsigned.apk'
# Add assets below with explicit ZIP paths. Windows aapt2 can emit backslashes
# in local asset headers, which Android rejects even when the directory lists them.
Run-Tool $aapt @('link','-o',$unsigned,'--manifest',(Join-Path $source 'AndroidManifest.xml'),'-I',$config.androidJar,'--java',$generated,'--auto-add-overlay',(Join-Path $build 'resources.zip'))
$sources = @(Get-ChildItem (Join-Path $source 'src'),$generated -File -Recurse -Filter '*.java' | ForEach-Object FullName)
$bootClasspath = (Join-Path $config.buildTools 'core-lambda-stubs.jar') + [IO.Path]::PathSeparator + $config.androidJar
$compilerArgs = @('-encoding','UTF-8','-source','8','-target','8','-bootclasspath',$bootClasspath,'-d',$classes) + $sources
Run-Tool $javac $compilerArgs
$classJar = Join-Path $build 'classes.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::CreateFromDirectory($classes,$classJar)
Run-Tool $java @('-cp',$d8,'com.android.tools.r8.D8','--release','--min-api','26','--lib',$config.androidJar,'--output',$dex,$classJar)
$archive = [IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try {
    [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $dex 'classes.dex'),'classes.dex')
    foreach ($asset in Get-ChildItem -LiteralPath $assets -File -Recurse) {
        $relative = $asset.FullName.Substring($assets.Length).TrimStart([char[]]'\/').Replace('\','/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$asset.FullName,('assets/' + $relative))
    }
}
finally { $archive.Dispose() }
$aligned = Join-Path $build 'aligned.apk'
Run-Tool $align @('-f','4',$unsigned,$aligned)
$keystore = Join-Path $tools 'quiethome-private-signing.p12'
$passwordFile = Join-Path $tools 'quiethome-signing-password.txt'
if (-not (Test-Path -LiteralPath $keystore)) {
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $bytes = New-Object byte[] 32
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [IO.File]::WriteAllText($passwordFile,[Convert]::ToBase64String($bytes))
    $env:QUIET_SIGNING_PASSWORD = [IO.File]::ReadAllText($passwordFile)
    try { Run-Tool $keytool @('-genkeypair','-keystore',$keystore,'-storetype','PKCS12','-alias','quiet','-storepass:env','QUIET_SIGNING_PASSWORD','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Quiet Home Local Build') }
    finally { Remove-Item Env:QUIET_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
}
if (-not (Test-Path -LiteralPath $passwordFile)) { throw 'Signing password missing. Keep the existing signing key; do not silently replace it.' }
$dist = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Path $dist -Force | Out-Null
$output = Join-Path $dist 'QuietHome.apk'
$env:QUIET_SIGNING_PASSWORD = [IO.File]::ReadAllText($passwordFile)
try { Run-Tool $java @('-jar',$signer,'sign','--ks',$keystore,'--ks-key-alias','quiet','--ks-pass','env:QUIET_SIGNING_PASSWORD','--out',$output,$aligned) }
finally { Remove-Item Env:QUIET_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
Run-Tool $java @('-jar',$signer,'verify','--verbose',$output)
# Validate the local headers as well as the central directory: ordinary ZIP
# readers can conceal a separator mismatch between the two.
$zip = [IO.Compression.ZipFile]::OpenRead($output)
$reader = New-Object IO.BinaryReader([IO.File]::OpenRead($output))
try {
    $reader.BaseStream.Position = 0
    while ($reader.ReadUInt32() -eq 0x04034b50) {
        $reader.BaseStream.Position += 2
        $flags = $reader.ReadUInt16()
        $reader.BaseStream.Position += 10
        $compressedSize = $reader.ReadUInt32()
        $reader.BaseStream.Position += 4
        $nameLength = $reader.ReadUInt16()
        $extraLength = $reader.ReadUInt16()
        $name = [Text.Encoding]::UTF8.GetString($reader.ReadBytes($nameLength))
        if ($name.Contains('\') -or $null -eq $zip.GetEntry($name)) { throw "Invalid APK local entry path: $name" }
        if (($flags -band 8) -ne 0) { throw "Unexpected streaming ZIP entry: $name" }
        $reader.BaseStream.Position += $extraLength + $compressedSize
    }
    if ($null -ne $zip.GetEntry('assets/LOCAL-PHOTOS.json') -or $null -ne $zip.GetEntry('assets/PHOTO-CREDITS.txt')) { throw 'Location-specific photo metadata must not be packaged.' }
    $mediaEntries = @($zip.Entries | Where-Object { $_.FullName -match '(?i)\.(jpe?g|png|webp|mp4|m4v|webm|mkv)$' })
    if ($mediaEntries.Count -ne 0) { throw "Public APK unexpectedly contains bundled media: $($mediaEntries.FullName -join ', ')" }
}
finally { $reader.Dispose(); $zip.Dispose() }
Run-Tool $aapt @('dump','badging',$output)
Get-FileHash -LiteralPath $output -Algorithm SHA256 | Format-List
Write-Host "Built and signature-verified: $output" -ForegroundColor Green
Write-Host 'APK ready. Install with: adb install -r dist\QuietHome.apk'
