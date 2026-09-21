# FINAL-REPORT.md — guide sections 35 and 36

**Project complete, 2026-09-21.** All sections closed, including section 37: the human
turned debugging off and it was verified from the host — port 5555 actively refuses
connections and `adb devices` is empty.

Authoritative detail lives in the per-area files; this report is the single place that
ties them together:

| File | Covers |
|---|---|
| `BASELINE-SUMMARY.md` | the untouched box |
| `DEBLOAT-LOG.md` | every change and its undo |
| `PROJECT-PACKAGES.txt` | machine-readable restore scope |
| `UPSTREAM-AUDIT.md` | Kutu Mirror's upstream source audit |
| `KUTU-MIRROR.md` | Kutu Mirror build, audit and tests |
| `KUTU-HOME.md` | Kutu Home build and tests |
| `SECURITY-REVIEW.md` | section 34 in full |
| `RESTORE-ALL.ps1` | the rollback |

## 1. Device

| | |
|---|---|
| Model | Xiaomi Mi Box S 1st gen (MDZ-22-AB), MIBOX4 / oneday |
| Android / API | 9 (Pie) / 28 |
| Fingerprint | `Xiaomi/oneday/oneday:9/PI/3933:user/release-keys` |
| Build date | 2021-09-28 |
| Security patch | **2021-07-05** — unchanged, see risk R1 |
| SoC / ABI | Amlogic S905X / **armeabi-v7a only**, 32-bit userland |
| Display | 1920x1080 |

## 2. What changed

### 2.1 Packages disabled — 39, all with the same undo

Every row below was disabled with:

```
adb -s <DEVICE> shell pm disable-user --user 0 <PKG>
```

and every one is undone with:

```
adb -s <DEVICE> shell pm enable --user 0 <PKG>
```

| Batch | Packages |
|---|---|
| 1 · telemetry / ads / one-shot setup | `com.miui.tv.analytics`, `tv.alphonso.alphonso_eula`, `com.google.android.tv.bugreportsender`, `com.google.android.feedback`, `com.google.android.partnersetup`, `com.xiaomi.android.tvsetup.partnercustomizer`, `android.autoinstalls.config.xioami.mibox3`, `com.google.android.onetimeinitializer`, `com.android.onetimeinitializer` |
| 2 · Xiaomi bloat | `com.mitv.tvhome.atv`, `com.mitv.tvhome.michannel`, `com.xm.webcontent`, `com.mitv.download.service`, `com.mitv.videoplayer`, `com.xiaomi.mitv.updateservice` |
| 3 · Google TV extras | `com.google.android.backdrop`, `com.android.dreams.basic`, `com.google.android.tv`, `com.google.android.marvin.talkback`, `com.google.android.syncadapters.calendar`, `com.google.android.syncadapters.contacts`, `com.android.providers.calendar` |
| 3b · recommendations | `com.google.android.tvrecommendations` |
| 4 · unused system services | `com.android.printspooler`, `com.android.wallpaperbackup`, `com.android.backupconfirm`, `com.google.android.backuptransport`, `com.android.sharedstoragebackup`, `com.android.providers.userdictionary`, `com.android.companiondevicemanager`, `com.android.statementservice`, `com.android.settings.intelligence`, `com.google.android.sss.authbridge` |
| 6 · setup wizard | `com.google.android.tungsten.setupwraith` |
| 7 · voice search — **user-requested** | `com.google.android.katniss`, `com.google.android.speech.pumpkin`, `com.google.android.tts` |
| 8 · stock launcher | `com.google.android.tvlauncher` |
| 33 · final cleanup | `com.mitv.milinkservice` |

Reconciliation on the box right now: **44 packages disabled = 39 in project scope + 5
that were already disabled before this project and were never touched** (rule 0.7:
`com.android.camera2`, `com.google.android.youtube.tvmusic`,
`com.google.android.videos`, `com.google.android.play.games`, `com.netflix.ninja`).

`com.amazon.amazonvideo.livingroom` was disabled during batch 5 and **re-enabled by the
human the same day** because they use Prime Video. Its undo is already applied, it is
out of restore scope, and `RESTORE-ALL.ps1` must not touch it.

### 2.2 Non-package changes

| Change | Undo |
|---|---|
| Animation scales 1.0 → 0.5 | same `settings put global` commands with `1.0` |
| Default HOME → `local.kutu.home/.HomeActivity` | `pm enable com.google.android.tvlauncher` then `cmd package set-home-activity com.google.android.tvlauncher/.MainActivity` |
| Installed Kutu Home 1.0 | `adb uninstall local.kutu.home` |
| Installed Kutu Mirror 1.0 | `adb uninstall local.kutu.mirror` |
| Uninstalled TCL Browser (`com.tcl.browser`) | **none** — reinstall from Play Store |
| `pm trim-caches` | **none** — caches regenerate |
| FLauncher installed then removed | reinstall `build/flauncher.apk` if wanted |

## 3. Final state

| | |
|---|---|
| HOME | `local.kutu.home/.HomeActivity`, no chooser, survives reboot |
| HOME candidates remaining | Kutu Home and the system's `com.android.tv.settings/.system.FallbackHome` safety net |
| AirPlay receiver | `local.kutu.mirror`, advertises as **"Kutu"** |
| Disabled packages | 44 (39 ours, 5 pre-existing) |
| Crashes in the Kutu apps since the final reboot | 0 |

### 3.1 Kutu Home 1.0

| | |
|---|---|
| APK | `build/kutu-home/app/build/outputs/apk/release/app-release.apk` |
| Size | 1 045 000 bytes (1.02 MB) |
| SHA-256 | `8e5f305b0a453a8f8465c7d1172d3380decb5495a5d5f144f6cc706f677cc6b6` |
| min / target / compile SDK | 28 / 28 / 36 |
| Signing cert SHA-256 | `22ae3b8a7f585010da25bcd5b0cc60f748a61de1e4936b9ff30e03585a6ef9b2` |
| Keystore | `keys\kutu-home.jks`, password in `keystore.properties` |
| Permissions | **1** — `REQUEST_DELETE_PACKAGES` |
| Services / wakelocks / network | none / none / **no network permission at all** |

The signing certificate is byte-identical to the one recorded when Kutu Home was first
built, so every rebuild since has installed as an in-place update.

### 3.2 Kutu Mirror 1.0

| | |
|---|---|
| APK | `build/kutu-mirror.apk` |
| Size | 5 924 126 bytes (5.65 MB) |
| SHA-256 | `66f66916dda34ac3068f78cf6f073c5b8ff155cb3b6a1d467ed6b406c02b918e` |
| min / target / compile SDK | 28 / 28 / 36 |
| ABI | armeabi-v7a only |
| Signing cert SHA-256 | `7cbb1d95e6ac556c608cc0de4408a447b55a5d85e5d972a40aeb78600463e8ba` |
| Keystore | `keys\kutu-mirror.jks`, password in `keys\kutu-mirror.credentials.txt` |
| Upstream | `jqssun/android-airplay-server` @ `c8defdd7` (GPL-3.0, attribution retained) |
| Fork source | `build/kutu-mirror/`, canonical tree in WSL at `~/kutu/mirror` |
| Permissions | 6 — the 5 from guide 12 plus `ACCESS_NETWORK_STATE` (ExoPlayer) |

## 4. Tests

### 4.1 Real AirPlay — iPhone · **passed**

Run by the human on a physical iPhone. Mirroring started, MirrorActivity opened **only
after** `Mirroring started` and never on connection; portrait 500x1080 letterboxed
correctly; rotation to landscape 1920x888; audio arrived as AAC-ELD
(`ct=8 spf=480 screen=true`) and was heard; H.264 decoded in hardware by
`OMX.amlogic.avc.decoder.awesome`; no HEVC on this H.264-only build; Stop Mirroring
returned to Kutu Home with no stale frame.

One defect surfaced here and was fixed: **landscape picture quality collapsed after
rotating the phone**, because the decoder was configured once at the first frame's size
and never rebuilt. `setResolution()` now drops and rebuilds the codec on a size change,
costing about 230 ms of black. The human confirmed the fix.

### 4.2 BACK test · **passed**

One BACK press during mirroring: sender disconnected, box returned to Kutu Home, and
"Kutu" re-advertised on both `_airplay._tcp` and `_raop._tcp` about 700 ms later.

### 4.3 Port-probe regression · **passed**

Five bare TCP connect/close cycles, one held idle connection, and `GET /info`,
`/server-info`, `/playback-info` against port 7000 with no session running:
**zero** MirrorActivity launches, foreground app never interrupted, no black screen.

### 4.4 AirPlay Video · **works; one sender cannot be supported**

The AirPlay button inside a video is a different protocol from screen mirroring — the
sender hands over a media URL for the box to play. It was disabled per guide 8 and
restored at the human's request, with the costs disclosed in `KUTU-MIRROR.md`.

A media-type inference bug was found and fixed along the way: UxPlay serves rewritten
HLS playlists from its loopback httpd with no `.m3u8` in the url, so ExoPlayer fell back
to the progressive extractors and failed on every legitimate HLS stream. Loopback and
`.m3u8` urls are now opened as HLS outright, with a single retry as the other type.

One tested source cannot work: a streaming site whose player hands over its own `/embed/`
**web page** instead of a media url. Both attempts were logged and both correctly
refused it. No receiver can play a web page, and no attempt is made to scrape a stream
out of one.

### 4.5 Mac · **not run**

No Mac was available. Guide 14's macOS Screen Mirroring test remains outstanding. The
protocol path is identical to the iPhone one that passed, but that is reasoning, not a
test result.

### 4.6 Real remote (section 26) · **passed**

Confirmed by the human on the physical remote. Five defects came out of it and were all
fixed: move-commit launching an app, no discoverable way to add an app, clipped focus
effects with icons bleeding through the shelf fade, focus jumping after a move, and the
permanent `+` tile the human did not want. See `KUTU-HOME.md`.

A later round found three more focus defects — returning from Settings, returning from
the mirror panel, and removing a favourite all threw focus to the first icon. All three
had one cause (`onResume` → `renderDock(null)` → `focusFirstTile()`) and were fixed and
verified with screenshots at each step.

### 4.7 Reboots · **passed**

| Reboot | Result |
|---|---|
| After promoting Kutu Home to HOME | Kutu Home resumed with no chooser, dock intact, Bluetooth and remote healthy |
| With the receiver on | receiver auto-started and re-advertised as "Kutu" without being asked |
| With the receiver switched off | receiver **stayed off**, 0 services, HOME still Kutu Home — "off" genuinely survives a reboot |

### 4.8 Normal-use checkpoint (section 32) · **confirmed by the human**

The human reported HOME, streaming apps, Settings and Screen Mirroring all behaving
normally before the final cleanup ran.

## 5. Performance — baseline vs final (guide 35)

### Launcher

| | Baseline | Final |
|---|---|---|
| HOME | stock `com.google.android.tvlauncher` | `local.kutu.home` |
| Launcher process PSS | **63.5 MB** | **22.3 MB** |
| Separate recommendations process | yes (`com.google.android.tvrecommendations`) | **gone** |
| Launcher idle CPU | not measured | **0.0 %** |
| Launcher services | yes | **zero** |
| Launcher permissions | many | **1** |
| Launcher network access | yes | **none — no network permission** |

### Processes removed from the resident set

Gone from the baseline's top-PSS table, by disabling their packages:

| Process | Baseline PSS |
|---|---|
| `com.google.android.katniss:interactor` | 54.5 MB |
| `com.google.android.tts` | 46.3 MB |
| `com.google.android.katniss:search` | 38.5 MB |
| `com.google.android.tungsten.setupwraith` | 21.4 MB |
| `com.google.android.tvlauncher` | 63.5 MB |

Plus the manufacturer home/feed (`com.mitv.tvhome.atv`, `.michannel`, `com.xm.webcontent`),
the recommendations service, MiLink discovery and the analytics/ACR packages, none of
which were resident at the moment the baseline table was taken but all of which run on
demand.

### The AirPlay receiver added

| | Value |
|---|---|
| Kutu Mirror idle PSS | **12.1 MB** |
| Idle CPU over 20 s | **0.0 %** |
| Idle wakelocks | **none** — upstream held a permanent partial wakelock |
| Services | one foreground service, only while the receiver is on |

So the launcher stack went from roughly 63.5 MB plus a separate recommendations process
down to 22.3 MB, and AirPlay was added for 12.1 MB — with the voice-search stack
(~139 MB across three processes) removed on top, at the human's explicit request.

### Indicative only

| | Baseline | Final |
|---|---|---|
| MemAvailable | 1 102 680 kB (~1.05 GiB) | 1 260 944 kB (~1.20 GiB) |
| /data free | 1.9 GB | **2.81 GB** |

MemAvailable is **indicative only**: the baseline was captured on a box with days of
uptime, the final figure one minute after a reboot. The /data delta is real and is
mostly `pm trim-caches` plus the uninstalled browser.

These numbers are this box's. Nothing here should be promised for different hardware.

## 6. Security (guide 34)

Full review in `SECURITY-REVIEW.md`. Headline: **0 Critical, 2 High, 2 Medium, 4 Low.**
Every listening TCP and UDP port on the box is attributed by owning uid — nothing is
guessed — and two uncertainties carried in the baseline are now closed, including proof
that port 6091 belonged to MiLink (it disappeared when MiLink was disabled).

No automatic fixes were applied. Each candidate either belongs to section 37 (ADB), is a
deliberate guide default (AirPlay PIN off), or would change how an app the human uses
actually works, which guide 34 forbids changing silently.

### Remaining risks

| | Risk | Severity |
|---|---|---|
| R1 | Firmware security patch stuck at 2021-07-05, ~5 years stale. Unfixable within the rules — no vendor OTA, and custom firmware is forbidden. Keep the box on a normal home LAN. | **High, residual** |
| R2 | ~~Network ADB on port 5555~~ — **closed 2026-09-21.** Debugging is off; the port actively refuses connections. | resolved |
| R3 | AirPlay PIN off by default, so any device on the LAN can start mirroring unchallenged. Fine on a trusted home network. | Medium |
| R4 | `com.tvonline.filewizard` (Play-installed, predates this project) can install APKs. Reported, not revoked, per guide 34. | Medium |
| R5 | Two exported control shims can start or stop the receiver. Nuisance only. | Low |

## 7. Signing keys — back these up

| App | Keystore | Password |
|---|---|---|
| Kutu Home | `keys\kutu-home.jks` | `keystore.properties` in `build/kutu-home/` |
| Kutu Mirror | `keys\kutu-mirror.jks` | `keys\kutu-mirror.credentials.txt` |

**Losing either file means that app can never be updated in place again** — the only way
back would be uninstalling it, which takes its settings with it. Neither keystore nor
either password file is inside a git repository, and Kutu Mirror's `local.properties`,
which carries its password, is git-ignored and excluded from the source snapshot.

## 8. Rollback

`RESTORE-ALL.ps1` sits next to `PROJECT-PACKAGES.txt` and reads its scope from it.
**Syntax-checked with the PowerShell parser: clean.** It covers 39 packages and supports
`-WhatIf` for a dry run, verified against the live box.

```powershell
# dry run first
.\RESTORE-ALL.ps1 -Device 192.168.61.115:5555 -WhatIf

# then for real
.\RESTORE-ALL.ps1 -Device 192.168.61.115:5555
```

With no `-Device` it auto-detects a single adb target. No private LAN IP is embedded in
the script.

What it does: re-enables all 39 packages, restores the stock launcher and pins HOME back
to it, and resets the animation scales.

What it deliberately does not do: touch the 5 pre-existing disables, touch
`com.amazon.amazonvideo.livingroom`, reinstall the uninstalled TCL Browser, or uninstall
the Kutu apps.

### Removing the Kutu apps (guide 36 order)

Stock HOME must be restored **before** the launcher is uninstalled, or the box is left
with no HOME:

```
# 1. stock HOME first
adb -s <DEVICE> shell pm enable --user 0 com.google.android.tvlauncher
adb -s <DEVICE> shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity

# 2. only then
adb -s <DEVICE> uninstall local.kutu.home
adb -s <DEVICE> uninstall local.kutu.mirror
```

`RESTORE-ALL.ps1` performs step 1 for you and prints step 2 rather than running it.

## 9. Section 37 — debugging off · **done**

The human disabled USB/network debugging on the box on 2026-09-21. Verified from the
host rather than taken on trust: `adb connect` and a direct TCP probe to 192.168.61.115:5555
both get an active refusal (WinSock 10061), and `adb devices` lists nothing. R2 is closed.

**Any future work on this box — updating either Kutu app, running `RESTORE-ALL.ps1`, or
any diagnosis — needs debugging switched back on first**, in Settings → Device
Preferences → Developer options.

## 10. Still outstanding

1. **Mac AirPlay test** — not run, no Mac was available. The protocol path is the same
   one the iPhone test exercised, but that is reasoning, not a test result.
