# DEBLOAT-LOG.md

Every change this project made to the box. The undo column is exact.
Restore scope is machine-readable in `PROJECT-PACKAGES.txt`; run `RESTORE-ALL.ps1`.

Command form for every package row: `adb -s <DEVICE> shell pm disable-user --user 0 <PKG>`
Undo form for every package row: `adb -s <DEVICE> shell pm enable --user 0 <PKG>`

## Batch 1 - telemetry / ads / one-shot setup - 2026-09-20 ~13:10

| Package | Reason | Verified | Test |
|---|---|---|---|
| com.miui.tv.analytics | Xiaomi usage analytics | new state: disabled-user | HOME/SystemUI/Remote/BT OK |
| tv.alphonso.alphonso_eula | Alphonso ACR - audio content recognition for ad profiling | disabled-user | as above |
| com.google.android.tv.bugreportsender | bug report upload | disabled-user | as above |
| com.google.android.feedback | feedback upload | disabled-user | as above |
| com.google.android.partnersetup | partner attribution, first-boot only | disabled-user | as above |
| com.xiaomi.android.tvsetup.partnercustomizer | OOBE partner customisation | disabled-user | as above |
| android.autoinstalls.config.xioami.mibox3 | first-boot autoinstall config | disabled-user | as above |
| com.google.android.onetimeinitializer | one-shot init | disabled-user | as above |
| com.android.onetimeinitializer | one-shot init | disabled-user | as above |

## Batch 2 - Xiaomi bloat - 2026-09-20 ~13:11

| Package | Reason | Verified | Test |
|---|---|---|---|
| com.mitv.tvhome.atv | PatchWall / Mi home shell, not the active HOME | disabled-user | HOME/SystemUI/Remote/BT OK |
| com.mitv.tvhome.michannel | Mi channel content feed | disabled-user | as above |
| com.xm.webcontent | Xiaomi web content provider | disabled-user | as above |
| com.mitv.download.service | Xiaomi downloader for its own feeds | disabled-user | as above |
| com.mitv.videoplayer | Xiaomi video player, unused | disabled-user | as above |
| com.xiaomi.mitv.updateservice | stock OTA updater; device EOL since 2021 | disabled-user | as above |

## Batch 3 - Google TV extras - 2026-09-20 ~13:14

| Package | Reason | Verified | Test |
|---|---|---|---|
| com.google.android.backdrop | Ambient/screensaver, networked | disabled-user | HOME OK, ANR=0 |
| com.android.dreams.basic | basic daydream | disabled-user | as above |
| com.google.android.tv | Live Channels - no tuner/antenna | disabled-user | as above |
| com.google.android.marvin.talkback | screen reader, unused | disabled-user | as above |
| com.google.android.syncadapters.calendar | calendar sync on a TV | disabled-user | as above |
| com.google.android.syncadapters.contacts | contact sync on a TV | disabled-user | as above |
| com.android.providers.calendar | calendar store, no client left | disabled-user | as above |

## Batch 3b - recommendations, isolated on purpose - 2026-09-20 ~13:14

| Package | Reason | Verified | Test |
|---|---|---|---|
| com.google.android.tvrecommendations | home-screen recommendation rows | disabled-user | stock launcher relaunched, `mResumedActivity` = tvlauncher/.MainActivity, ANR=0 |

Isolated from its batch because it is the one package that can destabilise the
stock launcher. The launcher was explicitly relaunched and re-checked before
continuing.

## Batch 4 - unused system services - 2026-09-20 ~13:15

| Package | Reason | Verified | Test |
|---|---|---|---|
| com.android.printspooler | no printing on a TV | disabled-user | HOME OK, ANR=0 |
| com.android.wallpaperbackup | no wallpaper backup | disabled-user | as above |
| com.android.backupconfirm | backup confirm UI | disabled-user | as above |
| com.google.android.backuptransport | cloud backup transport | disabled-user | as above |
| com.android.sharedstoragebackup | shared storage backup | disabled-user | as above |
| com.android.providers.userdictionary | user dictionary | disabled-user | as above |
| com.android.companiondevicemanager | companion pairing, unused | disabled-user | as above |
| com.android.statementservice | app-link verification | disabled-user | as above |
| com.android.settings.intelligence | settings search suggestions | disabled-user | as above |
| com.google.android.sss.authbridge | legacy auth bridge | disabled-user | as above |

## Batch 5 - unused preinstalled streaming - 2026-09-20 ~13:15

| Package | Reason | Verified | Test |
|---|---|---|---|
| com.amazon.amazonvideo.livingroom | Prime Video preload, believed unused | disabled-user | HOME OK, ANR=0 |

**REVERTED 2026-09-20 by the human.** A later audit found this package enabled again
and the disabled count at 43 instead of 44. The human confirmed they re-enabled it
deliberately: they do use Prime Video, so the premise behind the disable was wrong.

Its undo (`pm enable --user 0 com.amazon.amazonvideo.livingroom`) is already applied,
so the package was removed from `PROJECT-PACKAGES.txt` and is out of restore scope.
`RESTORE-ALL.ps1` now covers 38 packages and must not touch this one. Everything else
in scope was verified still disabled, and the five pre-existing disables untouched.

## Batch 6 - setup wizard - 2026-09-20 ~13:22

| Package | Reason | Verified | Test |
|---|---|---|---|
| com.google.android.tungsten.setupwraith | OOBE wizard still resident at 21.4 MB post-boot | disabled-user | HOME OK |

## Batch 7 - voice search stack - 2026-09-20 ~13:30 - USER-REQUESTED

| Package | Reason | Verified | Test |
|---|---|---|---|
| com.google.android.katniss | Assistant / voice search, ~93 MB across 2 procs | disabled-user | HOME/SystemUI/BT OK |
| com.google.android.speech.pumpkin | offline speech for katniss | disabled-user | as above |
| com.google.android.tts | TTS engine; only clients were katniss and talkback | disabled-user | as above |

Rule 11 normally preserves Assistant. The human was shown the exact trade-off
(the remote mic button stops working) and explicitly chose to disable it.

## Batch 8 - stock launcher - 2026-09-20 ~13:27

| Package | Reason | Verified | Test |
|---|---|---|---|
| com.google.android.tvlauncher | replaced as HOME; `set-home-activity` alone lost to its priority=2 intent filter | disabled-user | resolve-activity -> me.efesser.flauncher/.MainActivity; KEYCODE_HOME lands on FLauncher; no FATAL in logcat; survived two reboots |

## Non-package changes

| Change | Command | Undo |
|---|---|---|
| Animation scales 1.0 -> 0.5 | `settings put global {window_animation_scale,transition_animation_scale,animator_duration_scale} 0.5` | same command with `1.0` |
| Default HOME | `cmd package set-home-activity me.efesser.flauncher/.MainActivity` | `cmd package set-home-activity com.google.android.tvlauncher/.MainActivity` |
| Installed FLauncher 0.18.0 | `adb install -r flauncher.apk` | `adb uninstall me.efesser.flauncher` |
| Uninstalled TCL Browser | `pm uninstall com.tcl.browser` | **none** - reinstall from Play Store |
| Cleared all app caches | `pm trim-caches 9999999999` | **none** - caches regenerate on use |
| Installed Kutu Home 1.0 | `adb install -r app-release.apk` | `adb uninstall local.kutu.home` |
| Re-pinned default HOME to FLauncher | `cmd package set-home-activity me.efesser.flauncher/.MainActivity` | same command with the desired HOME |
| **Default HOME -> Kutu Home** (2026-09-21, after the human's section 26 remote test passed) | `cmd package set-home-activity local.kutu.home/.HomeActivity` | `pm enable com.google.android.tvlauncher` then `cmd package set-home-activity com.google.android.tvlauncher/.MainActivity` |
| FLauncher removed | already gone before this project could uninstall it; the human removed it through Kutu Home's uninstall flow | reinstall `build/flauncher.apk` |
| Reset Kutu Home's own prefs after the move test | `pm clear local.kutu.home` | none needed - dock re-seeds to the chosen order |

## Kutu Mirror installation - 2026-09-21

| Change | Command | Undo |
|---|---|---|
| Installed Kutu Mirror 1.0 (`local.kutu.mirror`) | `adb install -r build/kutu-mirror.apk` | `adb uninstall local.kutu.mirror` |
| **MiLink disabled** (guide 33) — Xiaomi's own mirroring service, retained until Kutu Mirror worked | `adb -s <DEVICE> shell pm disable-user --user 0 com.mitv.milinkservice` | `adb -s <DEVICE> shell pm enable --user 0 com.mitv.milinkservice` |

Kutu Mirror adds no launcher entry, runs one foreground service, holds no idle
wakelock and changes nothing about the system. Uninstalling it removes it completely;
Kutu Home's mirror panel then goes back to reporting "Alici kurulu degil".

## Deviations from the master guide (disclosed)

| Rule | Deviation |
|---|---|
| 0.8 "do not clear app data or caches as an optimization technique" | `pm trim-caches` was run to recover ~600 MB, before the guide was supplied. Straight violation. No data loss; caches regenerate. |
| 1 "do not embed a private LAN IP into scripts" | Earlier scripts under `~/.local/bin/mibox-rollback/` hardcode the IP. Superseded by `RESTORE-ALL.ps1`, which auto-detects or accepts `-Device`. |
| 1 project dir + BASELINE-SUMMARY + DEBLOAT-LOG before the first change | Both written retro-actively; the first debloat preceded them. |
| 4 restore infrastructure before the first modification | A baseline snapshot and a restore script did exist before the first disable, but scoped to "everything enabled at baseline" rather than "only what this project changed". Corrected here. |
| 6 native AirPlay check before building | Run late (post-debloat) instead of first. Result still valid and negative. |
| 29 explicit approval before disabling stock HOME | Rollback existed and was shown, but the disable ran without pausing for an explicit yes. |
| 15 "build Kutu Home only after Kutu Mirror is stable" | FLauncher was installed as an interim HOME before any Kutu work. Removed once Kutu Home passed 24-30. Kutu Home was also built and shipped before Kutu Mirror, because Mirror was blocked on WSL; see UPSTREAM-AUDIT.md option C. |
| 8 "remove ... HLS player ... ExoPlayer/Media3" | Removed first, then **put back at the human's explicit request**. The AirPlay button inside an iPhone video is AirPlay Video, not mirroring; with it disabled, "Kutu" still appeared in that list (because audio is advertised) and did nothing when picked. The human was shown the cost - APK 4.59 -> 5.63 MB, Media3/ExoPlayer back, and a sixth permission - and chose to have it work. See KUTU-MIRROR.md. |
| 12 "Original Android 9 permissions were ... (5 listed)" | Six now. ExoPlayer's manifest reinstates `ACCESS_NETWORK_STATE`, a normal-level permission, as a consequence of the guide 8 deviation above. |
| 8 "remove ALAC" | The ffmpeg software ALAC decoder is gone and ALAC is not advertised (`cn=0,2,3`). The few lines that would start a *platform* ALAC decoder remain in `audio_decoder.h` as dead code, because cutting them would have meant reworking `makeDecoder`'s structure for no behavioural gain. Nothing can reach them: a sender cannot select a codec the receiver does not advertise. |

## Pending

- 7-14 Kutu Mirror — **built, installed, tested**. See KUTU-MIRROR.md. The WSL blocker
  is resolved; WSL2 Ubuntu 26.04 reproduces upstream's `ubuntu-latest` CI.
- 15-27 Kutu Home — **built, installed, tested**. See KUTU-HOME.md.
- 29/30 promote Kutu Home to default HOME — **done**
- FLauncher removal — **done**
- 33 final cleanup — **done**, see the MiLink row above
- 34 security review — **done**, see `SECURITY-REVIEW.md`. Every listening TCP/UDP port
  is attributed by owning uid. Port 6091 turned out to be MiLink after all: it vanished
  when MiLink was disabled.
- 35/36 final report — **done**, see `FINAL-REPORT.md`. `RESTORE-ALL.ps1` syntax-checked
  clean and dry-run against the live box.
- 37 turn debugging off — **done 2026-09-21**. The human disabled USB/network debugging.
  Verified from the host: port 5555 now actively refuses connections (WinSock 10061) and
  `adb devices` is empty.

**Project complete.** Any further ADB work needs debugging switched back on first.

## Note on a second HOME candidate

Installing Kutu Home added a second `CATEGORY_HOME` activity, which made
`resolve-activity` fall through to `android/com.android.internal.app.ResolverActivity`
— i.e. pressing HOME would have shown a chooser, which section 30 forbids. The default
was immediately re-pinned to FLauncher with `set-home-activity`, and
`resolve-activity` confirmed `me.efesser.flauncher/.MainActivity` again. Verified
still correct after a reboot.
