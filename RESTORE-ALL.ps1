<#
  RESTORE-ALL.ps1 - Mi Box S (MIBOX4/oneday, Android 9)
  Undoes ONLY what this project changed. Idempotent.

  Guide rules honoured:
    0.6 every project change has an exact undo
    0.7 packages disabled before this project are NOT touched
    1   no private LAN IP embedded; device is auto-detected or passed in

  Usage:
    .\RESTORE-ALL.ps1                          # auto-detect single adb device
    .\RESTORE-ALL.ps1 -Device 192.168.1.50:5555
    .\RESTORE-ALL.ps1 -WhatIf                  # dry run
#>
[CmdletBinding(SupportsShouldProcess)]
param(
  [string]$Device,
  [string]$Adb = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe"
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path $Adb)) { throw "adb not found at $Adb - pass -Adb <path>" }

if (-not $Device) {
  $lines = @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
  if ($lines.Count -eq 0) { throw "No adb device. Connect first: adb connect <ip>:5555" }
  if ($lines.Count -gt 1) { throw "Multiple devices. Re-run with -Device <serial>:`n$($lines -join "`n")" }
  $Device = ($lines[0] -split "`t")[0]
}
Write-Host "Target: $Device" -ForegroundColor Cyan

$pkgFile = Join-Path $PSScriptRoot 'PROJECT-PACKAGES.txt'
if (-not (Test-Path $pkgFile)) { throw "PROJECT-PACKAGES.txt missing next to this script." }
$pkgs = Get-Content $pkgFile | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object { $_.Trim() }

Write-Host "`n[1/4] Re-enabling $($pkgs.Count) project-disabled packages..." -ForegroundColor Yellow
foreach ($p in $pkgs) {
  if ($PSCmdlet.ShouldProcess($p, 'pm enable --user 0')) {
    $r = (& $Adb -s $Device shell "pm enable --user 0 $p" 2>&1) -join ' '
    if ($r -match 'new state: enabled') { Write-Host "  OK      $p" }
    else { Write-Host "  CHECK   $p -> $r" -ForegroundColor DarkYellow }
  }
}

Write-Host "`n[2/4] Restoring stock HOME (com.google.android.tvlauncher)..." -ForegroundColor Yellow
if ($PSCmdlet.ShouldProcess('HOME', 'set-home-activity -> tvlauncher')) {
  & $Adb -s $Device shell "cmd package set-home-activity com.google.android.tvlauncher/.MainActivity" | Out-Host
}

Write-Host "`n[3/4] Restoring animation scales to 1.0 ..." -ForegroundColor Yellow
if ($PSCmdlet.ShouldProcess('animation scales', 'set 1.0')) {
  & $Adb -s $Device shell "settings put global window_animation_scale 1.0"
  & $Adb -s $Device shell "settings put global transition_animation_scale 1.0"
  & $Adb -s $Device shell "settings put global animator_duration_scale 1.0"
}

Write-Host "`n[4/4] Verifying..." -ForegroundColor Yellow
if ($WhatIfPreference) { Write-Host "  (skipped in -WhatIf)" -ForegroundColor Gray; return }
& $Adb -s $Device shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME" | Select-Object -Last 1 | Out-Host
$stillDisabled = (& $Adb -s $Device shell "pm list packages -d") -replace 'package:','' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
$leaked = $stillDisabled | Where-Object { $pkgs -contains $_ }
if ($leaked) { Write-Host "STILL DISABLED: $($leaked -join ', ')" -ForegroundColor Red }
else { Write-Host "All project packages re-enabled." -ForegroundColor Green }
Write-Host "Preserved pre-existing disables (untouched): $($stillDisabled.Count - $leaked.Count) package(s)" -ForegroundColor Gray

Write-Host @"

This script re-enabled the stock launcher and pinned HOME back to it, so the box
boots to the Google TV launcher again.

NOT RESTORED BY THIS SCRIPT (cannot be):
  - com.tcl.browser : fully uninstalled (pm uninstall). Reinstall from Play Store.
  - app caches      : cleared with pm trim-caches; they regenerate on use.
  - com.amazon.amazonvideo.livingroom : deliberately re-enabled by you, left alone.
  - me.efesser.flauncher : uninstalled; APK kept at build\flauncher.apk.
  - local.kutu.home : still installed but no longer HOME. Remove it if unwanted:
        adb -s $Device uninstall local.kutu.home
  - local.kutu.mirror : AirPlay receiver, still installed. Remove it if unwanted:
        adb -s $Device uninstall local.kutu.mirror

Reboot the box to finish.
"@ -ForegroundColor Gray
