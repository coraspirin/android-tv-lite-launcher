<#
.SYNOPSIS
    Packages the Kutu apps as a GitHub Release that Kutu Aktarım's updater can install.

.DESCRIPTION
    Collects the three release APKs (already built and signed locally with the keys in
    keys\), reads each one's package name and version, hashes it, and writes
    kutu-versions.json - the file UpdateActivity reads - next to them in release\<Tag>\.

    With the GitHub CLI installed and logged in (gh auth login), it also creates the
    release and uploads the five files (with install.ps1). Otherwise it prints what to upload by hand.

    Signing keys never leave this machine: the APKs are signed before they get here, and
    nothing in this script reads keys\.

    The box only installs an update whose versionCode is higher than what it has, whose
    SHA-256 matches kutu-versions.json, and whose signing certificate matches the installed
    app. So bump versionCode in the app's build.gradle before building a release.

.PARAMETER Tag
    Release tag, e.g. v1.2.

.PARAMETER Notes
    Release notes (Markdown). Optional.

.PARAMETER Build
    Build Kutu Home and Kutu Aktarım first (gradlew assembleRelease). Kutu Mirror is built
    in WSL and taken from build\kutu-mirror.apk as it is.

.PARAMETER DryRun
    Prepare release\<Tag>\ but do not publish.

.EXAMPLE
    .\tools\release.ps1 -Tag v1.2 -Build -Notes "Wi-Fi adı, arka plan seçimi"
#>
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [string]$Notes = "",
    [switch]$Build,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$repo = 'coraspirin/android-tv-lite-launcher'   # must match UpdateActivity.REPO

$apps = @(
    @{ Name = 'kutu-mirror.apk';   Path = 'build\kutu-mirror.apk' },
    @{ Name = 'kutu-home.apk';     Path = 'build\kutu-home\app\build\outputs\apk\release\app-release.apk';     Project = 'build\kutu-home' },
    @{ Name = 'kutu-transfer.apk'; Path = 'build\kutu-transfer\app\build\outputs\apk\release\app-release.apk'; Project = 'build\kutu-transfer' }
)

if ($Build) {
    foreach ($a in $apps) {
        if (-not $a.Project) { continue }
        Write-Host "Derleniyor: $($a.Project)"
        Push-Location (Join-Path $root $a.Project)
        try {
            & .\gradlew.bat assembleRelease --console=plain -q
            if ($LASTEXITCODE -ne 0) { throw "derleme başarısız: $($a.Project)" }
        } finally { Pop-Location }
    }
}

# aapt2 from the SDK reads package and version straight out of the APK
$aapt2 = Get-ChildItem (Join-Path $root 'build\sdk\build-tools') -Recurse -Filter aapt2.exe |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $aapt2) { throw 'aapt2.exe bulunamadı (build\sdk\build-tools)' }

$out = Join-Path $root "release\$Tag"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $out | Out-Null

$versions = [ordered]@{}
foreach ($a in $apps) {
    $src = Join-Path $root $a.Path
    if (-not (Test-Path $src)) { throw "APK yok: $($a.Path)" }
    $badging = & $aapt2.FullName dump badging $src 2>$null | Select-Object -First 1
    if ($badging -notmatch "package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'") {
        throw "sürüm okunamadı: $($a.Path)"
    }
    $pkg = $Matches[1]; $code = [long]$Matches[2]; $name = $Matches[3]
    $dst = Join-Path $out $a.Name
    Copy-Item $src $dst
    $sha = (Get-FileHash $dst -Algorithm SHA256).Hash.ToLowerInvariant()
    $versions[$pkg] = [ordered]@{ versionCode = $code; versionName = $name; asset = $a.Name; sha256 = $sha }
    Write-Host ("{0,-22} {1,-6} (kod {2})  {3}" -f $pkg, $name, $code, $sha)
}

$json = [ordered]@{ release = $Tag; apps = $versions } | ConvertTo-Json -Depth 4
# UTF-8 without BOM: org.json on the box does not expect one
[System.IO.File]::WriteAllText((Join-Path $out 'kutu-versions.json'), $json, (New-Object System.Text.UTF8Encoding $false))
# the one-command installer rides along, so releases/latest/download/install.ps1 is
# always the script that was published with these APKs, never an untested main
Copy-Item (Join-Path $root 'install.ps1') (Join-Path $out 'install.ps1')
Write-Host "Hazır: $out"

if ($DryRun) { Write-Host 'DryRun: yayınlanmadı.'; return }

$gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $gh) {
    Write-Host ''
    Write-Host 'GitHub CLI (gh) yok. Elle yayınlamak için:'
    Write-Host "  1. https://github.com/$repo/releases/new adresinde etiket olarak $Tag girin"
    Write-Host "  2. $out içindeki beş dosyayı yükleyin (3 APK + kutu-versions.json + install.ps1)"
    Write-Host '  3. "Set as the latest release" işaretli olarak yayınlayın'
    return
}

$files = Get-ChildItem $out | ForEach-Object { $_.FullName }
$title = "Kutu $Tag"
if (-not $Notes) { $Notes = "Kutu uygulamaları $Tag. Kutuda: Kutu Home ⚙ → Güncellemeleri denetle." }
& gh release create $Tag @files --repo $repo --title $title --notes $Notes --latest
if ($LASTEXITCODE -ne 0) { throw 'gh release create başarısız' }
Write-Host "Yayınlandı: https://github.com/$repo/releases/tag/$Tag"
