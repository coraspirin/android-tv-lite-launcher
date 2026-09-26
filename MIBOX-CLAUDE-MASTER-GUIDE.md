# Android TV Optimization + Kutu Mirror + Kutu Home + Kutu Transfer
## Master execution guide for Claude Code

> **Read this entire file before doing anything. It is the only file you need.**
>
> This guide describes a workflow developed and fully tested on a **Xiaomi Mi Box S (MIBOX4 / oneday) running Android TV 9 (API 28, armeabi-v7a, 32-bit userland)**. Do not assume the connected device is identical. Inspect the actual Android TV / Google TV / Android box first and adapt package names, ABIs, Android-version requirements and HOME-launcher behaviour.
>
> The human has already completed the prerequisite:
> - Developer Options / debugging is enabled.
> - ADB is installed.
> - `adb devices` shows the TV/box as `device`.
>
> The human may also provide a background image. **If an image is supplied with this guide, use that exact image as the Kutu Home background. Do not redraw or replace it.** If none is supplied and this repository is available, use its `kutu-home-background.png` verbatim.
>
> The result is three small apps plus a reversible debloat:
>
> | App | Package | Purpose |
> |---|---|---|
> | Kutu Mirror | `local.kutu.mirror` | AirPlay receiver "Kutu": screen mirroring + AirPlay Video |
> | Kutu Home | `local.kutu.home` | light/dark liquid-glass launcher, one permission, zero idle CPU |
> | Kutu Aktarım | `local.kutu.transfer` | on-demand, PIN-protected browser file transfer |

---

# 0. Non-negotiable safety rules

1. Start read-only. Do not change the TV before the baseline and restore infrastructure exist.
2. Never use root, bootloader unlocking, fastboot flashing, Magisk or custom firmware.
3. Never modify `/system`.
4. Never uninstall a system package.
5. System debloat must use only `pm disable-user --user 0 <package>`.
6. Every package changed by this project must have an exact `pm enable --user 0 <package>` undo.
7. Preserve packages already disabled before this project. Restore only what this project changed.
8. Do not clear app data or caches as an optimization technique. That includes `pm trim-caches` and `pm clear` on anything but a Kutu app under test.
9. Do not clear or modify `com.android.providers.tv`.
10. Do not blindly copy Xiaomi package names to another manufacturer.
11. Preserve unless the human explicitly says otherwise:
   - Wi-Fi/networking
   - Bluetooth and physical remote
   - Android audio
   - on-screen keyboard/input
   - DRM/Widevine
   - Google Play Services
   - Play Store
   - Chromecast / Google Cast
   - Google Assistant / microphone voice search
   - installed streaming apps

   If the human explicitly asks to disable one of these (for example the voice-search stack), show the exact trade-off first (e.g. "the remote's mic button stops working"), then log it as user-requested.
12. Do not change HDMI-CEC as part of this project.
13. Before disabling a stock HOME launcher, prove the replacement works, create a tested rollback path, and get an explicit "yes" from the human. A shown rollback is not an approval.
14. When ADB drives remote key events, tell the human **HANDS OFF** and do not interleave physical-remote input.
15. Do not turn ADB/debugging off until all work, restore scripts, final tests and the final report are complete.
16. Never expose signing keys, passwords or keystores in git.
17. Prefer the smallest reversible change. If package purpose or dependency risk is unclear, leave it enabled.
18. Do not install interim third-party launchers "just for now". Every extra HOME candidate is another thing to roll back.

---

# 1. Establish host/device variables and project directory

Detect the host OS.

Create a working directory:
- macOS/Linux: `~/tv-debloat`
- Windows: `%USERPROFILE%\tv-debloat` or equivalent

If this guide's repository is already checked out, work inside it.

Identify the connected ADB target with:

```bash
adb devices
```

If exactly one target is `device`, use it. If multiple devices exist, ask the human which one to use.

Use explicit targeting for consequential commands:

```bash
adb -s <DEVICE_SERIAL> shell ...
```

Do not embed a private LAN IP into scripts. Scripts auto-detect the single device or take it as a parameter.

Create **before the first system change**:

| File | Content |
|---|---|
| `RUN-REPORT.md` | the single record of this run: baseline (section 2), change log (section 4), audit (section 7), security review (section 34), final report (section 36) |
| `PROJECT-PACKAGES.txt` | machine-readable list of every package this project disabled = exact restore scope |
| `PRESERVED-PRIOR-DISABLES.txt` | packages already disabled before the project; never touched by restore |
| `RESTORE-ALL.sh` (Unix) / `RESTORE-ALL.ps1` (Windows) | the rollback, driven by `PROJECT-PACKAGES.txt`, with a dry-run mode |
| `evidence/<phase>/` | raw command outputs and screenshots |

Keep one report file, not one per phase. Record deviations from this guide in a "Deviations" table inside `RUN-REPORT.md` the moment they happen, not retroactively.

---

# 1A. Reference implementation

This repository may contain the tested source of all three apps:

| Path | Content |
|---|---|
| `build/kutu-home/` | Kutu Home, plain Java, zero dependencies |
| `build/kutu-mirror/` | Kutu Mirror fork source snapshot (Kotlin + native C/C++) |
| `build/kutu-mirror-fork.bundle` | git bundle of the Kutu Mirror fork history |
| `build/kutu-transfer/` | Kutu Transfer, plain Java, zero dependencies, plus `tools/` page tests |
| `kutu-home-background.png` | the reference background (1672x941) |

**If the source exists, start from it** and adapt to the actual device rather than rewriting it:
- minSdk/targetSdk, ABI filters, foreground-service types for the actual Android version
- the Settings fallback component (only if the standard action fails)
- the dock seed (ask the human, section 16; never ship another user's dock)
- signing: generate new local keystores (section 12); never reuse or commit anyone else's

Read the source before building it. It still goes through the audits and tests in this guide; being in the repo does not exempt it.

**If the source does not exist**, write the apps from the specifications in this guide. Sections 8-12, 15-23 and 28A are complete enough to reproduce the final design, including the pitfalls that were found the hard way.

Never install prebuilt APKs from the repo or upstream onto a device without rebuilding and re-signing them locally.

---

# 2. Phase 1: complete READ-ONLY baseline

Do not reboot, kill processes or change settings.

Collect and save raw outputs for:

## Device identity
- `getprop`
- manufacturer/model
- Android version/API level
- build number/date
- security patch
- CPU/SoC
- ABI(s)
- 32/64-bit userland (`zygote32` vs `zygote64_32`)
- GPU/OpenGL
- display resolution/density

The ABI and API level decide the whole build: e.g. `armeabi-v7a` only means no arm64 libraries; API 28 means no `RenderEffect` blur (API 31).

## Memory
- `/proc/meminfo`
- `dumpsys meminfo`
- zRAM/swap
- top memory users
- PSS of major processes

## Storage
- `df -h`
- `/data`
- largest app-data consumers where readable

## Packages
- all packages
- system packages
- third-party packages
- currently disabled packages → `PRESERVED-PRIOR-DISABLES.txt`
- package paths
- installer/source where available

## Running state
- processes
- services
- foreground services
- wakelocks
- CPU snapshot
- unusually active idle processes

## Launcher
- currently resolved HOME
- all HOME candidates
- intent priorities where observable (a stock launcher with a `priority=2` HOME filter can beat `set-home-activity`)
- Leanback launchers
- already-installed third-party launchers

## Important TV subsystems
Identify packages/components for:
- Bluetooth remote
- Android TV Remote
- TV input
- HDMI/CEC
- Chromecast
- Google Play Services
- Play Store
- keyboard/input
- audio
- Wi-Fi/networking
- DRM/Widevine
- Assistant/voice
- updater
- manufacturer analytics
- manufacturer home/content services
- manufacturer phone-remote/discovery/mirroring
- recommendations/content feeds

## Network exposure
Record listening TCP/UDP ports from `/proc/net/tcp[6]` and `/proc/net/udp[6]` and attribute each one by owning **uid**, then map uid → package. Do not guess owners.

## Existing settings
Record, but do not change:
- debugging/ADB state (USB and network, port 5555)
- animation scales
- CEC state
- `install_non_market_apps` / apps holding `REQUEST_INSTALL_PACKAGES`
- unusual prior changes

Write the baseline into `RUN-REPORT.md`:
1. device spec
2. RAM/storage
3. current HOME
4. package counts
5. major resident processes with PSS/CPU
6. potential optimization candidates:
   - A: likely low-risk
   - B: usage-dependent
   - C: critical / do not touch
7. network exposure table
8. uncertainties needing investigation

Be conservative.

---

# 3. One usage checkpoint before debloat

Show the human:
- installed user-facing media/streaming apps
- conditional features such as YouTube Music, TalkBack, screensaver/Ambient mode, calendar sync, manufacturer phone remote, manufacturer content feeds and analytics

Ask:

> Which installed apps do you actively use, and do you use voice search, TalkBack, YouTube Music, screensavers/Ambient mode, calendar sync or the manufacturer's phone-remote feature?

Default to **preserve** when unsure.

Regardless of the answer, preserve Assistant, Chromecast, Bluetooth, networking, DRM, keyboard and Play Store unless explicitly requested otherwise (rule 11).

---

# 4. Restore infrastructure BEFORE the first modification

Snapshot:
- every package enabled/disabled state
- current HOME resolution
- current preferred HOME where observable

Build `RESTORE-ALL` so every future project disable is added automatically via `PROJECT-PACKAGES.txt`.

Scope it to **only what this project changes** — not "everything that was enabled at baseline".

It must:
1. re-enable only packages listed in `PROJECT-PACKAGES.txt`
2. never touch `PRESERVED-PRIOR-DISABLES.txt`
3. restore the original stock HOME if changed, **before** anything else that could leave the box without a HOME
4. undo non-package changes (animation scales etc.)
5. be idempotent, and support a dry run (`-WhatIf` / `--dry-run`)
6. auto-detect a single ADB device or accept `-Device <serial>`

If the human later re-enables a package themselves (e.g. a streaming app they turn out to use), remove it from `PROJECT-PACKAGES.txt` with a comment explaining why.

Maintain the change log in `RUN-REPORT.md` with:
- timestamp
- package / change
- reason
- exact command
- exact undo
- test result

Changes with no undo (anything outside rule 5, e.g. uninstalling a user app at the human's request) are logged with **"undo: none"** and the manual recovery.

---

# 5. Phase 2A: conservative first debloat

Use the actual baseline and usage answers.

## Xiaomi Mi Box reference only

Packages the reference run disabled in total (all with `pm disable-user --user 0`, all restorable):

```text
# telemetry / ads / one-shot setup
com.miui.tv.analytics
tv.alphonso.alphonso_eula                 # ACR audio content recognition for ad profiling
com.google.android.tv.bugreportsender
com.google.android.feedback
com.google.android.partnersetup
com.xiaomi.android.tvsetup.partnercustomizer
android.autoinstalls.config.xioami.mibox3
com.google.android.onetimeinitializer
com.android.onetimeinitializer
com.google.android.tungsten.setupwraith   # OOBE wizard still resident ~21 MB after boot
# unused system services
com.android.printspooler
com.android.dreams.basic
com.google.android.backdrop
com.google.android.marvin.talkback
com.google.android.syncadapters.calendar
com.google.android.syncadapters.contacts
com.android.providers.calendar
com.android.wallpaperbackup
com.android.backupconfirm
com.google.android.backuptransport
com.android.sharedstoragebackup
com.android.providers.userdictionary
com.android.companiondevicemanager
com.android.statementservice
com.android.settings.intelligence
com.google.android.sss.authbridge
# manufacturer / preinstalled content (final-cleanup phase, section 33)
com.mitv.tvhome.atv
com.mitv.tvhome.michannel
com.xm.webcontent
com.mitv.download.service
com.mitv.videoplayer
com.xiaomi.mitv.updateservice
com.google.android.tv
com.google.android.tvrecommendations
com.mitv.milinkservice                    # only after Kutu Mirror works
com.google.android.tvlauncher             # stock HOME, only per section 29
# voice stack: ONLY because that human explicitly chose it (rule 11)
com.google.android.katniss
com.google.android.speech.pumpkin
com.google.android.tts
```

**Do not apply this list blindly to another device.**

Even on the same Mi Box model, verify:
- package exists
- label/purpose matches
- package is currently enabled
- the human does not use the feature
- nothing the human keeps depends on it (e.g. `tts` clients)

Do not touch in the first batch:
- stock launcher
- recommendations
- PatchWall/manufacturer feeds
- MiLink/manufacturer remote or mirroring service
- `com.android.providers.tv`
- Assistant
- Chromecast
- Play Services/Store
- updater/download service unless separately justified

Isolate recommendations in their own batch when you get to them, so a regression is attributable.

For each approved candidate:

```bash
adb -s <DEVICE> shell pm disable-user --user 0 <PACKAGE>
```

Append it to `PROJECT-PACKAGES.txt` and verify package state immediately.

After the batch:
1. verify state
2. reboot
3. wait for `sys.boot_completed=1`
4. verify state again
5. diff against pre-change snapshot

If firmware unexpectedly re-enables something, diagnose instead of changing unrelated components.

Ask the human to test:
- physical remote
- HOME
- voice search
- streaming apps they use
- audio
- Wi-Fi
- Chromecast

Stop if anything breaks.

An optional non-package tweak the reference run kept: animation scales `1.0 → 0.5` (`settings put global window_animation_scale|transition_animation_scale|animator_duration_scale 0.5`), undo with `1.0`. Log it like a package.

---

# 6. Check whether native AirPlay already exists

**Before** building anything, keep manufacturer discovery services enabled and check whether the TV already advertises:

```text
_airplay._tcp
_raop._tcp
```

If native Apple Screen Mirroring already works reliably, report it before building Kutu Mirror.

If not, continue.

---

# 7. Kutu Mirror: audit upstream

Use:

```text
https://github.com/jqssun/android-airplay-server
```

Record the exact upstream commit and submodule commits. Reference run: upstream `c8defdd70d7e6a04f4f1b71d353653682d594106`, UxPlay `462153392f2e`, libplist `f41b1ea67045`, openssl-cmake `4edd36a8dab5`. Licence **GPL-3.0**: keep attribution; a derivative distributed to others must ship source.

Audit:
- licence/attribution
- Gradle/manifest
- minSdk/targetSdk
- ABI support
- native libraries
- OpenSSL download/source
- FFmpeg
- Media3/ExoPlayer
- Compose/Hilt
- analytics/telemetry/ads/billing/accounts/cloud
- remote endpoints
- service lifecycle
- boot behaviour
- exported components (upstream's `BootReceiver` and main activity are exported)
- AirPlay/RAOP discovery
- H.264 path
- mirrored audio path
- HEVC/HLS/PiP/music features
- wakelocks/multicast locks

Known upstream defects this fork must fix (the reference audit found all of them):
- **UI opened from `onConnectionInit()`**, the pre-auth TCP signal: a bare connect to port 7000 puts a full-screen activity over whatever is playing.
- **`PARTIAL_WAKE_LOCK` taken in `startServer()` and held for the receiver's whole life.**
- `VideoPipeline._bindDisplay()` repaints the last decoded frame onto any new surface → stale frames across sessions unless torn down.
- the decoder is configured once at the first frame's size (see section 14).
- transitive `androidx.profileinstaller` receiver exported; `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` auto-added.

Write the audit into `RUN-REPORT.md`. Do not trust it only because it is open source.

## Build environment

The native half is a **POSIX autotools/CMake build** (upstream CI is `ubuntu-latest`). It does not build on a plain Windows host (needs `make`, Perl for OpenSSL's `Configure`, etc.).

- On Windows: ask the human to install **WSL2 + Ubuntu** (`wsl --install`) and build Kutu Mirror inside it, toolchain under the WSL home (e.g. `~/kutu/sdk`), not the Windows tree. Copy the APK out and install from Windows ADB. Kutu Home and Kutu Transfer have no native code and build directly on Windows with `gradlew`.
- On macOS/Linux: build natively.
- Removing FFmpeg (section 8) deletes the `configure`/`make` dependency; the remaining native build is CMake plus OpenSSL's Perl `Configure`.

If Android build tools are missing, ask permission before downloading JDK/SDK/NDK/CMake/Gradle.

If approved:
- prefer official/trusted sources
- keep toolchain in project dir (or WSL home) where practical; git-ignore it
- pin the Gradle wrapper `distributionSha256Sum`
- verify cmdline-tools against Google's `repository2-1.xml` hash
- verify the OpenSSL tarball against the official openssl.org SHA-256, even when fetched from a mirror

Reference toolchain: JDK 17, Gradle 8.11.1 (`f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`), AGP 8.9.2, Kotlin 2.1.0, platform/build-tools 36, NDK 27.0.12077973, CMake 3.22.1, OpenSSL 3.4.4 (`7bdf55ac20f2779e99e5eca306f824fad2b37dee5a06cc35ed5a8b85a6060010`).

Do not install upstream APK binaries.

---

# 8. Build final Kutu Mirror directly

Create:

```text
Package: local.kutu.mirror
Application name: Kutu Mirror
AirPlay receiver name: Kutu
```

Adapt ABI/minSdk/targetSdk to the actual device.

Original tested target:
- Android 9 / API 28 (minSdk 28, targetSdk 28, compileSdk 36)
- armeabi-v7a only
- H.264 only
- max 60 fps

Do not force those values on incompatible hardware.

For newer Android, comply with foreground-service types and notification requirements rather than copying Android 9 manifest assumptions.

## Keep
- AirPlay screen mirroring
- `_airplay._tcp`
- `_raop._tcp`
- H.264
- AAC-LC/AAC-ELD (+PCM) — advertise `cn=0,2,3`
- hardware MediaCodec H.264 decode where supported
- efficient Android audio output (Oboe)
- iPhone/iPad native Screen Mirroring
- macOS native Screen Mirroring
- **AirPlay Video** (Media3 ExoPlayer + HLS) — see below

## Remove
- HEVC on old/unsupported devices (`DEF_H265_ENABLED = false`)
- FFmpeg submodule and its CMake
- ALAC (software decoder and advertising)
- video downloader, `FileProvider`, `file_paths.xml` — nothing is saved to disk
- Compose (whole `ui/` tree), Hilt, ViewModel → one plain-View `MirrorActivity`
- PiP
- DACP / music / artwork / metadata / MediaSession
- debug overlays and sanitizer build types
- unused ABIs
- analytics/telemetry/ads/billing/accounts/cloud

Do not sacrifice mirroring audio.

## Why AirPlay Video stays

iOS has two AirPlay paths:

| | Sender | Receiver |
|---|---|---|
| Control Centre → Screen Mirroring | encodes the screen as H.264 | decodes and displays |
| AirPlay button inside a video | hands over a media URL | fetches and plays it |

Because the box advertises AirPlay audio, "Kutu" appears in the in-video list either way. With the HLS player removed it appears and **does nothing when picked**, which looks broken. So keep it:
- `nativeSetHlsEnabled(true)` (sets dns-sd feature bits 0 and 4)
- `AirPlayVideoPlayer` (Media3 ExoPlayer + HLS source)
- wire `onVideoPlay` / `onVideoScrub` / `onVideoRate` / `onVideoStop`; report position/duration/rate via `nativeUpdatePlaybackInfo`
- the service owns the single Surface and routes it to whichever consumer is live (mirroring decoder or player) — never two producers on one Surface
- **media-type inference**: UxPlay serves rewritten HLS playlists from its loopback httpd with **no `.m3u8` in the URL**, so ExoPlayer's extension-based guess picks progressive and fails. Open loopback and `.m3u8` URLs as HLS outright; whichever type is tried first, retry once with the other; log both attempts. A sender that hands over a web page (`/embed/…`) instead of media cannot be played; do not scrape.
- `onVideoSessionPoll` stays **empty**: senders poll `/playback-info` about a second before `/play`, and section 10 forbids polls from opening UI. Open the activity only on a genuine `/play`.
- during AirPlay Video the remote drives play/pause and ±10 s seek; BACK stops playback

Cost, disclose it: +~1.1 MB APK (4.6 → 5.65 MB), Media3 dependency, and ExoPlayer's manifest adds `ACCESS_NETWORK_STATE` (normal-level; do not strip it, ExoPlayer reads it). DRM apps block AirPlay Video anyway and YouTube uses Cast; this path helps Safari, Photos and web video.

If the human explicitly does not want AirPlay Video, it may be dropped — but then say that "Kutu" will still be listed in videos and do nothing.

---

# 9. Kutu Mirror lifecycle/power

Requirements:

1. Start after boot once activated (boot auto-start flag, see section 11).
2. Advertise `Kutu` while awake/on LAN.
3. Multicast lock for the run, as discovery needs.
4. **No partial CPU wakelock while idle.**
5. Acquire the mirroring wakelock (`kutu:mirror`) only in `onMirrorRunning(true)` / real playback.
6. Release it on every session end.
7. Video decoder only when needed.
8. Release audio output when the last client disconnects.
9. Losing discovery while the box is actually asleep is acceptable.

Targets verified in the reference run: idle CPU 0.0 %, idle PSS 10–12 MB, no `kutu:` entry in `dumpsys power` while idle.

---

# 10. Prevent known Kutu Mirror privacy/UX bugs from the start

Final production `MirrorActivity`:
- no intent filter at all: no MAIN / LAUNCHER / LEANBACK_LAUNCHER
- `android:exported="false"`
- `excludeFromRecents` + `autoRemoveFromRecents`
- absent from launcher lists
- opened internally only during genuine mirroring, genuine AirPlay Video `/play`, or a genuine PIN prompt

## Critical network-trigger rule

A bare TCP connection to port 7000 must not open UI.

These must not open MirrorActivity:
- port scan
- TCP connect/disconnect
- `/info`, `/server-info`, `/playback-info`
- discovery
- negotiation that never reaches active mirroring

Open the Activity from exactly:
1. `onMirrorRunning(true)`
2. `onDisplayPin()` — a genuinely minted pairing PIN
3. AirPlay Video `/play`

Never from `onConnectionInit()`. If MirrorActivity binds and none of those is active, finish immediately.

## Stale-frame prevention

On every termination path:
- Stop Mirroring
- Mac disconnect
- BACK disconnect
- network loss
- decoder error
- service teardown
- Activity finish

do complete cleanup:
- `videoRenderer.stopSession()` **and** `videoRenderer.release()`: stop/flush/release the decoder, destroy the GL pipeline, SurfaceTexture and input Surface (this removes the "repaint last frame" state)
- stop/release audio decoder/output
- blank the Activity background to black and detach the Surface before finishing
- save no bitmap/frame/screenshot cache
- finish Activity
- release mirroring wakelock

A later session/Activity must never show the previous sender's final frame.

---

# 11. Receiver control and update behaviour

Two tiny shims, both:
- no launcher category
- `Theme.NoDisplay`
- exported (Kutu Home is a separate package)
- no parameters, accept no commands, finish before drawing

| Shim | Does |
|---|---|
| `ReceiverControlActivity` | starts the receiver service; writes boot auto-start = on |
| `ReceiverStopActivity` | stops the receiver service; writes boot auto-start = off, so "off" survives a reboot |

Disclose that any app on the box can stop the receiver via the exported stop shim (nuisance only; nothing exposed).

Do not export MirrorActivity or `AirPlayService`.

Use a **non-exported** `BootReceiver` for:
- `BOOT_COMPLETED`
- `MY_PACKAGE_REPLACED`

After Kutu Mirror updates, the receiver must return without requiring a full reboot. (Platform caveat, not a bug: installing over a *force-stopped* package delivers no broadcasts until it is started once.)

---

# 12. Kutu Mirror security/build

Final release:
- release-signed (v2), not debuggable
- `minifyEnabled` + `shrinkResources`
- `allowBackup=false`
- private storage
- no saved screen/audio content
- exported components: only the two shims
- `androidx.profileinstaller` excluded; `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` removed with `tools:node="remove"`
- no analytics/ads/cloud
- no remote networking beyond local AirPlay; `usesCleartextTraffic=true` is required for AirPlay's plain-HTTP control channel, local only

Android 9 permissions (6):
- INTERNET
- CHANGE_WIFI_MULTICAST_STATE
- WAKE_LOCK
- RECEIVE_BOOT_COMPLETED
- FOREGROUND_SERVICE
- ACCESS_NETWORK_STATE (from ExoPlayer, AirPlay Video)

Drop everything else upstream requests (e.g. `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `ACCESS_WIFI_STATE`, extra FGS types on API 28). Use the minimum required for the actual Android version.

PIN support may remain available but default **off**. Warn that with PIN off, another device on the same LAN can start mirroring unchallenged.

Signing, for all three Kutu apps:
- one local keystore per app (e.g. PKCS12, RSA 4096) under `keys/`, **outside the Gradle project tree**
- passwords in `local.properties` / `keys/*.credentials.txt`, all git-ignored
- tell the human to back up `keys/` outside the repo: losing a keystore means that app can never be updated in place again

Suggested `.gitignore` core: `keys/`, `*.jks`, `*.keystore`, `*credentials*`, `local.properties`, toolchain dirs, Gradle build outputs, upstream clone.

---

# 13. Install/test Kutu Mirror

Before install report:
- upstream commit
- local fork commit
- APK path/size/SHA-256
- package/version
- minSdk/targetSdk
- ABI(s)
- permissions
- exported components
- native libraries (reference: `libairplay_native.so`, `libcrypto.so` ~3 MB, `libc++_shared.so`, `liboboe.so`)
- signing cert SHA-256

Install only after audit succeeds.

For a newly installed/stopped package, start it via ADB rather than adding a launcher tile:

```bash
adb -s <DEVICE> shell am start -n local.kutu.mirror/.ReceiverControlActivity
```

Verify:
- service running, foreground
- port 7000 listening (`/proc/net/tcp` state `0A`)
- `_airplay._tcp` = `Kutu`
- valid `_raop._tcp` (`<MAC>@Kutu`, `cn=0,2,3`, no ALAC)
- AirPlay Video feature bits set (or clear, if the human declined it)
- H.264 hardware decoder chosen, `hevc=null` on an H.264-only build
- no launcher icon (`query-activities` for LAUNCHER and LEANBACK_LAUNCHER returns nothing)
- no crash loop
- no idle mirroring wakelock, idle CPU ~0

---

# 14. Mandatory real AirPlay tests

Automated network probes do not replace these. Observe HANDS OFF: read logcat only.

## iPhone/iPad
Ask human:
1. open recognizable content with sound
2. Control Centre → Screen Mirroring → Kutu
3. verify portrait aspect-fit
4. rotate/open landscape
5. verify audio
6. Stop Mirroring

Measure/check:
- `Mirroring started` is logged **before** the MirrorActivity launch, never after a bare connection
- H.264 hardware decoder if available
- no HEVC on H.264-only build
- audio path (AAC-ELD for mirroring)
- session RAM/CPU (reference ~16 MB PSS)
- wakelock taken and released
- return HOME
- receiver re-advertises
- no stale frame

**Known defect to prevent — rotation quality collapse.** The stream changes size when the sender rotates (e.g. 500x1080 ↔ 1920x888). If the decoder is configured once at the first frame's size, adaptive decoders get that size as `KEY_MAX_WIDTH/HEIGHT` and the larger landscape stream decodes badly. `setResolution()` must drop the codec when dimensions change; `feedFrame` rebuilds it at the new size on the next keyframe (senders emit one on rotation). Expected cost: ~230 ms black on rotation, no codec errors.

## AirPlay Video
Ask human to press the AirPlay button inside a Safari/Photos video and pick Kutu. Verify it plays, remote play/pause/seek works, BACK stops it and the activity closes cleanly.

## Mac
Ask human to connect via native macOS Screen Mirroring, verify picture/sound, disconnect, then verify cleanup. If no Mac is available, record "not run" — do not claim it from reasoning.

## BACK test
Ask human:
1. start iPhone mirroring
2. press BACK once on TV remote
3. sender disconnects
4. TV returns HOME
5. Kutu re-advertises (reference: ~700 ms)

## Port-probe regression
With no active mirroring: five bare connect/close cycles, one idle-held connection, and `GET /info`, `/server-info`, `/playback-info`.

Verify:
- zero MirrorActivity launches
- foreground app not interrupted
- receiver still works

If any probe creates a black screen, fix it before continuing.

## Reboot
Reboot with no manual start: receiver auto-starts, re-advertises, no idle wakelock.

---

# 15. Build Kutu Home only after Kutu Mirror is stable

If Kutu Mirror is blocked (e.g. waiting for WSL), Kutu Home may be built and tested as a normal app first, but it does not become HOME until section 28 passes. Log the reordering as a deviation.

Create:

```text
App: Kutu Home
Package: local.kutu.home
```

Architecture:
- plain Java / platform Views (`android.widget` only)
- `android.useAndroidX=false`, `dependencies { }` empty
- no ads/recommendations/Play Next
- no analytics/telemetry/cloud/accounts
- **no network permission**
- no background service
- no wakelock
- no boot receiver
- no database (SharedPreferences only)
- no GMS dependency
- essentially zero idle CPU (reference: 0.0 % over 60 s)
- release: `minifyEnabled`, `shrinkResources`, `allowBackup=false`, `debuggable=false`

The **only** permission is `REQUEST_DELETE_PACKAGES`, for Android's uninstall-confirmation UI.

Never silently uninstall.

These rules are Kutu Home's own. Kutu Home reaches Kutu Mirror and Kutu Transfer only by starting their exported shims by explicit `ComponentName`; it gains no permission from them.

Reference source layout (`build/kutu-home/app/src/main/java/local/kutu/home/`):

| Class | Role |
|---|---|
| `HomeActivity` | clock, theme button, shelf, chips, move mode, focus memory |
| `AllAppsActivity` | grid of launchable apps |
| `MirrorPanelActivity` | receiver state + switch |
| `AppRepository` | app discovery; `MIRROR_PKG`, `TRANSFER_PKG` exclusions |
| `DockStore` | persistence |
| `IconNormalizer` | normalized icon bitmaps + cache |
| `TileGlass` | per-app glass drawables and icon-coloured focus ring |
| `TileBehaviour` | focus animation, long press |
| `ThemeStore` | light/dark override |
| `KutuMenu` | glass context menu |

---

# 16. Ask which apps belong in initial dock

Do not hard-code any previous user's apps.

Read launchable TV apps from the actual device.

Prefer Leanback launch intents, with safe normal-launcher fallback.

Ask:

> Which apps do you want in the initial Kutu Home dock, and in what order?

Allow any number.

Seed these only if no saved dock exists. Never overwrite user layout on update.

---

# 17. Use supplied background exactly

If a background image is supplied (or the repo's `kutu-home-background.png` is used):
- use that exact image, record its SHA-256
- do not redraw/reinterpret
- preserve composition
- locally optimize only if visually indistinguishable
- bundle in `res/drawable-nodpi/`
- render with `centerCrop` to fill 16:9 without stretching (a near-16:9 image crops under a pixel)
- no network loading
- no animated wallpaper
- no runtime blur
- no continuous processing

The dark theme dims the same image through a scrim token; do not ship a second copy.

---

# 18. Kutu Home final visual spec — liquid glass

## Material

- **Light glass, dark text.** On a light, smooth background, dark glass cannot be both translucent and readable (at glass-like opacity it lands near `#8893A6`, white text ~2.5:1). Every glass surface is white-tinted; every "on glass" text colour is dark navy.
- **No blur.** `RenderEffect` is API 31 and rule 17 forbids runtime blur anyway. A smooth gradient background has no detail to blur; layered white translucency plus a vertical sheen reproduces the effect at no GPU cost.
- Every glass drawable is **one `<shape>` with a `<gradient>` + `<stroke>`**, not a `<layer-list>`: same overdraw as a flat fill (matters on weak GPUs), and a lone `GradientDrawable` reports a clean rounded-rect outline, which the shelf clipping relies on. `dither="true"` everywhere (8-bit 1080p bands on shallow gradients).
- All drawables and layouts read **`@color` tokens, never literals**, so one palette file re-skins every screen and the dark theme is a second palette file.

Light palette (reference, `values/colors.xml`):

| Token | Value | Use |
|---|---|---|
| `ink` / `ink_dim` | `#FF16233A` / `#FF55647C` | text on wallpaper |
| `ink_on_glass` / `_dim` | `#FF101C30` / `#FF4A5A72` | text on glass |
| `bg_scrim` | `#00000000` | wallpaper dim (dark theme only) |
| `glass_edge` / `glass_specular` | `#99FFFFFF` / `#E6FFFFFF` | hairlines, sheen |
| `glass_shelf_top/mid/bottom` | `#73FFFFFF` / `#4DFFFFFF` / `#38FFFFFF` | shelf |
| `tile_face_top/bottom` | `#59FFFFFF` / `#26FFFFFF` | resting tile (composites over shelf) |
| `tile_focus_top/bottom` | `#F2FFFFFF` / `#BFFFFFFF` | focused tile |
| `tile_glass_base` | `#FFFFFFFF` | base `TileGlass` mixes from |
| `tile_transparent_logo_bg` | `#FF0E1520` | near-black plate behind transparent logos; **no night value** |
| `accent` / `accent_deep` | `#FF7FC4F5` / `#FF2F7FB8` | focus accent / strokes that must read |
| `accent_glow` | `#A62B6E9E` | lift shadow (pale blue is invisible on light glass) |
| `chip_glass_top/bottom` | `#66FFFFFF` / `#33FFFFFF` | chips |
| `chip_focus_top/bottom` | `#F2FFFFFF` / `#BFFFFFFF` | focused chip |
| `switch_thumb_off` / `switch_track_off` / `switch_track_on` | `#FF8C99AB` / `#3316233A` / `#807FC4F5` | mirror switch |
| `veil` | `#B3DCE3ED` | light frost behind menus/panels |
| `menu_glass_top/bottom` | `#F2FFFFFF` / `#D9F4F8FD` | menus/panels |
| `menu_row_focused` | `#597FC4F5` | focused menu row (tinted, not brightened) |
| `divider` | `#1F16233A` | |
| `state_ready/stopped/unknown/missing` | `#FF2E8B57` / `#FFB26A14` / `#FF4A5A72` / `#FFC0392B` | mirror state line |

Dark palette (`values-night/colors.xml`): dark glass (roughly the pre-liquid-glass dark palette, `~#C20D1626` shelf), light text, dark `bg_scrim`, dark `veil`. Every token above except `tile_transparent_logo_bg` gets a night value.

## Layout (1920x1080, overscan-safe margins 48dp horizontal / 27dp vertical)

- **Top-left:** round theme button (44dp, icon 22dp), mirroring the clock block.
- **Top-right:** time (34sp) and date (15sp, localized, e.g. `20 Eylül Pazar`), dark ink.
- **Centre:** one translucent light-glass shelf, `layout_centerInParent` in a `RelativeLayout`. The shelf depends on nothing, so nothing a sibling does can move it. Verify by measuring a `screencap`: shelf centre must be y=540 of 1080, including in move mode and in dark theme.
- **Above the shelf:** focused app name (20sp), shown once.
- **Below the shelf:** `move_hint`, anchored below the shelf; then the chips row, anchored **below `move_hint`** (not below the shelf). `RelativeLayout` walks past a GONE anchor, so chips sit under the shelf when the hint is hidden and under the hint when it shows, never overlapping it. Do not "simplify" this anchor.
- **Chips**, in order: `Ekran Yansıtma`, `Ayarlar`, `Dosya Aktarımı`, `Tüm Uygulamalar` (14sp, 20dp/9dp padding, radius 20dp, 12dp gap).
- minimal text.

## Tiles (shelf and All Apps use the same structure)

Each tile is **two layers**:
- outer `FrameLayout`: the focusable view, padding `tile_inset` = 12dp, no background
- inner card: holds the background, gets scaled/lifted, `duplicateParentState="true"` so the focus selector still applies

Reason: Android scrolls a newly focused child into view by its **unscaled** bounds, so a single-layer tile at the shelf edge has its 114 % scale and glow clipped; padding on the scrolling row cannot fix it. The inset puts the growth room inside the bounds Android scrolls to.

Geometry: card 88dp, icon 56dp, radius 22dp, inset 12dp → outer 112dp, tile gap 0 (neighbouring insets give 24dp breathing room). 7 slots = 816dp including shelf/row padding, inside the 864dp safe width. All Apps: card 108dp, icon 60dp, outer 116dp. Shelf radius 30dp, padding 12dp/6dp.

## Icons

Do not use inconsistent TV banners.

For each app:
1. prefer TV/Leanback activity icon if better
2. else app icon
3. correctly render adaptive icons
4. normalize to consistent rounded-square cards (`IconNormalizer`, cached)
5. transparent logos get the near-black `tile_transparent_logo_bg` plate baked into the bitmap (a light plate would erase light logos)
6. avoid firmware masks/cropping
7. monogram fallback only if needed

Resting tiles get a hairline (`glass_edge`): without it a white icon card dissolves into the pane.

## Focus

Focused tile:
- brightens (`tile_focus_*` face)
- scales ~114 %
- lifts 6dp via `translationZ` (shadow in `accent_glow`) — **never `bringToFront()`**, which physically reorders a `LinearLayout` and silently rearranges the dock
- **icon-coloured ring**, see below
- label shown once above the shelf

Chips and the theme button share one focus animation (`attachGlassFocus`): lift + scale **1.06** (a chip is wider than tall), same timing as tiles. The chips row and its parent must not clip children, or the shadow is cut.

Use short ~150–180 ms animations (`TileBehaviour.ANIM_MS`). On weak GPUs, reduce glow/elevation first.

**Resting icons must not be tinted by their focus glow.** Resting tiles are colourless glass; colour appears only on the focused tile.

## Icon-coloured focus ring (`TileGlass`)

One colour per app, derived from its normalized icon, cached per package, cleared together with `IconNormalizer`'s cache on package-change broadcasts.

Extraction (all three points are load-bearing):
- average **hue as a vector**, not arithmetically (359° and 1° must average to red, not cyan)
- a pixel must clear a **saturation threshold** to vote at all; muted fields otherwise win on count (a small yellow mark on a big navy field must come out yellow)
- the **length** of the hue vector measures agreement; a multi-colour logo (Play Store, Apple TV) cancels out and stays **neutral white**
- keep hue and roughly the vividness; **discard brightness** and use a fixed light value (most TV icons are dark; a near-black ring reads as a hole)
- clamp ring saturation with a floor and a cap

Drawable: a `StateListDrawable` per tile. Focused state = `LayerDrawable`: tinted face, **1.5dp outline**, then **seven 1dp rings stepping inwards on a falling alpha ramp** (the haze). Not a radial gradient: `GradientDrawable`'s radial radius is in pixels and the card size is unknown when the drawable is built. Layer 0 is the only full-bounds layer, so the outline and the lift shadow come from it. Base colours come from `@color/tile_glass_base` and `@color/glass_edge` (not `Color.WHITE`), so the ring follows the theme. An unavailable app keeps the plain XML `tile_bg`.

## Light / dark theme

- Theme button in the top-left toggles light ↔ dark and persists the choice.
- No AndroidX `AppCompatDelegate`; `UiModeManager.setNightMode` needs a signature permission. Instead `ThemeStore.override` is applied in `attachBaseContext` via `applyOverrideConfiguration`, before resources resolve:
  - **always write** the night bits (never inherit the system's own night setting, or the button works half the time)
  - **preserve the uiMode type bits** (writing only the night flag tells the resource system it is no longer a TV)
- `bg_scrim` is applied as `android:foreground` on the wallpaper `ImageView` — not on the root, which would dim the glass and text too. All Apps already has its `veil`.
- Icon via qualifier: `drawable/ic_theme` = moon, `drawable-night/ic_theme` = sun. Draw the crescent as one closed outline (evenOdd subtraction draws a blob).
- Tiles `setNextFocusUpId(R.id.theme_toggle)`; the button shares no horizontal overlap with the shelf.
- After `recreate()`, a **static** flag returns focus to the button (instance fields do not survive; `onResume`'s rebuild would override a plain `requestFocus`).
- Applies to every screen: Home, All Apps, context menu, mirror panel (including the switch track).

---

# 19. Editable dock/persistence

SharedPreferences file `home`, key `dock_v1`, as ordered rows `package \u001f rememberedLabel`.

Persist:
- ordered package list
- last-known human-readable label for each favourite

Requirements:
- add from All Apps (long press → Ana ekrana ekle)
- remove from Home without uninstalling
- reorder
- survive restart/reboot/APK update
- never reset custom layout

Missing app:
- no crash
- dim unavailable tile
- remembered label if available
- long press can remove

Zero favourites:
- a single `+` tile (`ic_add`, label "Uygulama ekle") opening All Apps. Only in this case — a permanent `+` tile was tried and rejected by the human.

Fewer than 7:
- shelf centred, shrinks to content (`clampShelfWidth()`)

More than 7:
- single row, ~7 visible slots
- horizontal scroll inside shelf
- clip hard to the rounded shelf outline (`clipToOutline`, `clipToPadding="true"`)
- **no fading edge**: `requiresFadingEdge` only makes overflow semi-transparent, so half-icons stay visible under the gradient
- never draw off-shelf icons

---

# 20. Robust physical-remote long press

Handle:
- DPAD_CENTER
- ENTER
- system long-press flag
- repeat events
- system timeout / ~500–550 ms

Do not rely only on `setOnLongClickListener`.

**Long press must never fall through into app launch.**

**Key-UP swallowing.** When OK (commit) or BACK (cancel) ends move mode on ACTION_DOWN, record it in `swallowUpKeyCode` and discard that key's next ACTION_UP in `dispatchKeyEvent` **before** checking move state. Otherwise the UP reaches the newly focused tile as a short press and launches an app (the reference run shipped exactly this bug once).

Verify on real remote.

---

# 21. Context menus

## Home app
- Taşı
- Ana ekrandan kaldır
- Uygulama bilgisi
- Uygulamayı kaldır — only for a normal uninstallable user app

## All Apps
If absent from Home:
- Ana ekrana ekle
- Uygulama bilgisi
- Uygulamayı kaldır if allowed

If already on Home:
- Ana ekrandan kaldır
- Uygulama bilgisi
- Uygulamayı kaldır if allowed

In All Apps the focused label reads `<app>  ·  ana ekranda` for an app already on the shelf, so the differing menu is explained.

UI (`KutuMenu`):
- light glass (`menu_glass_*`), 360dp wide, radius 22dp, matching the shelf
- focused row tinted `menu_row_focused`
- D-pad focus trapped
- BACK closes
- light frost `veil`
- transparent window background, no elevation on the card → no square shadow artifact
- centred wrapped text

Use Android standard App Info and uninstall confirmation.

Never silently uninstall/disable apps.

---

# 22. Move mode

1. long press app
2. choose `Taşı`
3. selected tile lifts
4. LEFT/RIGHT moves
5. OK saves
6. BACK cancels/restores
7. leaving before confirm cancels safely

Hint:

```text
◀ ▶ taşı · OK onayla · GERİ iptal
```

After commit **and** after cancel, focus stays on the moved tile: capture its package before clearing move state and pass it to the re-render. The OK/BACK UP event is swallowed (section 20).

## Focus memory (applies everywhere)

A null focus target must never silently mean "first tile":
- `lastFocusedPkg` / `lastFocusedChip` are written by tile and chip focus listeners; `onResume`'s rebuild restores the chip if the person came from a chip, else the tile.
- focus placement lives in one `applyFocus`, with a `KEEP_FOCUS` sentinel when the caller places focus itself.
- removing a favourite focuses its neighbour (the tile that slid into the gap, or the new last tile).
- pressing HOME clears the memory first, so HOME always lands on a clean Kutu Home.

---

# 23. All Apps / Settings / Screen Mirroring panel / File Transfer

## All Apps
List user-facing launchable TV apps.

Exclude:
- Kutu Home
- Kutu Mirror (`AppRepository.MIRROR_PKG`)
- Kutu Transfer (`AppRepository.TRANSFER_PKG`)
- internal/non-user-facing components

Same two-layer tile, same normalized icons and icon-coloured ring. Centred title/wrapped labels.

## Settings
Use standard `Settings.ACTION_SETTINGS` first. Detect a firmware-specific fallback only if required. Do not hard-code a Xiaomi component on a non-Xiaomi device. On failure show "Ayarlar açılamadı".

## Screen Mirroring panel
Do **not** open MirrorActivity.

`MirrorPanelActivity`: a centred light-glass card, 360dp wide, with only:
- title `Ekran Yansıtma`
- a state line
- an on/off `Switch` (no visible label; "Alıcı" goes in `contentDescription`), tinted with the switch tokens, focusable, OK toggles

No sender instructions on the panel.

State line:
- green `Alıcı hazır` — port 7000 listening
- orange `Alıcı kurulu ama çalışmıyor`
- neutral `Alıcı kurulu, durum okunamadı`
- red `Alıcı kurulu değil` — switch hidden

Read state from local `/proc/net/tcp[6]` (no permission needed). On newer Android where restricted, show neutral. Do not add network permission just for status.

Switch:
- checked = the receiver is **genuinely listening**, not a remembered preference
- on → `local.kutu.mirror.ReceiverControlActivity`; off → `ReceiverStopActivity`; never MirrorActivity
- after either, re-read the port; the real state wins
- if the shim cannot be reached, snap back and show `Alıcı değiştirilemedi`
- if state is unreadable, leave the switch where the user put it

BACK closes, focus returns to the Ekran Yansıtma chip.

## File Transfer chip
`Dosya Aktarımı` starts `local.kutu.transfer.TransferControlActivity` by explicit `ComponentName`. No panel and no switch: the transfer screen itself is the session (section 28A). If the app is missing, show `Aktarım uygulaması kurulu değil`.

---

# 24. Build/test Kutu Home as NORMAL APP first

Generate a local signing key outside the repo/project tree and back it up.

Report build metadata/hash/permissions/components/dependencies (reference: ~1 MB APK, 1 permission, zero dependencies).

Install Kutu Home.

**Do not make it default HOME yet.**
**Do not disable stock launcher yet.**
**Do not disable recommendations/PatchWall/MiLink yet.**

Installing a second `CATEGORY_HOME` activity can make HOME resolve to `ResolverActivity` (a chooser). Check `resolve-activity` right after install; if it shows the resolver, immediately re-pin the current HOME with `set-home-activity` until section 29.

Launch through Leanback.

Test:
- visual quality, light and dark theme
- overscan
- background
- clock/date
- shelf centred (y=540 measured from `screencap`; `uiautomator dump` may return null on old boxes)
- focus on every app, ring colour per app, full ring at both shelf ends
- D-pad, theme button reachable (UP from tiles)
- Settings
- All Apps
- Screen Mirroring panel and switch
- Dosya Aktarımı chip
- app launches
- BACK, and focus returning to the chip/tile that was left
- missing app
- >7 scrolling, hard clip, no off-shelf icon drawing
- no translucent shadow rectangle

---

# 25. Hands-off protocol

When sending ADB key events, tell human:

> HANDS OFF: put the remote down until I say this test block is finished.

Do not combine ADB navigation with human remote input.

---

# 26. Mandatory real-remote test

Ask human:
1. short OK opens app
2. hold OK ~1 sec opens context menu, app does not launch
3. BACK closes menu
4. Taşı works
5. BACK cancels move
6. OK saves move — **no app launches, focus stays on the moved tile**
7. App Info opens
8. uninstall confirmation opens on normal user app
9. cancel uninstall
10. add an app from All Apps via long press
11. remove an app — focus lands on its neighbour
12. theme button toggles and persists

Do not proceed to HOME replacement until this passes.

---

# 27. Persistence tests

Verify:
- custom dock survives reboot
- custom dock survives `adb install -r`
- remembered labels survive update
- theme choice survives force-stop and relaunch
- Kutu Mirror restarts after its APK update via `MY_PACKAGE_REPLACED`

Use/remove throwaway test app only if needed.

---

# 28. Final Kutu Mirror hardening verification

Verify:
- MirrorActivity exported=false
- no launcher categories
- explicit external launch denied where testable
- internal AirPlay still opens it
- absent from app lists
- no Recents snapshot
- iPhone works
- AirPlay Video works (if kept)
- Mac works (or recorded as not run)
- BACK works
- no stale frame
- port 7000 probe opens no UI
- boot auto-start works
- switch "off" survives a reboot
- update auto-restart works

If any privacy test fails, do not switch HOME.

---

# 28A. Kutu Transfer (third app, on-demand file transfer)

Two-way file transfer from any browser on the LAN, on demand, behind a PIN; carries APKs, media and general files.

```text
App: Kutu Aktarım
Package: local.kutu.transfer
Port: 8787
```

Build: plain Java, platform Views, **zero dependencies**, no native code, builds on Windows with `gradlew assembleRelease` (~41 KB APK). Reference source: `build/kutu-transfer/` (`TransferActivity`, `TransferService`, `TransferServer`, `Http`, `Multipart`, `Page`, `Shared`, `SharedFileProvider`, `Net`, `TransferControlActivity`, `TransferStopActivity`). Own keystore (section 12).

## Permissions
`INTERNET`, `FOREGROUND_SERVICE`, `REQUEST_INSTALL_PACKAGES`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (storage requested at runtime, see below). **Not** `RECEIVE_BOOT_COMPLETED`, **not** `WAKE_LOCK`: it only runs because someone asked seconds ago.

## Components
- `TransferControlActivity`, `TransferStopActivity`: exported shims, `Theme.NoDisplay`, parameterless, one fixed action (section 11 pattern)
- `TransferActivity`, `TransferService`: not exported
- no launcher entry; excluded from Kutu Home's All Apps

## Lifecycle — the session screen is the session
- opening `TransferActivity` starts the server; leaving it (BACK) stops it. The port cannot outlive what the human sees.
- `TransferServer` holds no reference to any Activity and cannot start one. Nothing on the network can open UI (section 10's rule holds by construction).
- 10-minute idle timeout closes an abandoned session.
- the TV screen shows the URL (`http://<box-ip>:8787`), the PIN, the storage scope, and "GERİ tuşu aktarımı kapatır".

## Security
- **PIN:** six digits, fresh per session, shown only on the TV. Correct PIN mints a 128-bit token in a session cookie; constant-time comparison; eight wrong attempts lock the session. Old cookies and old PINs fail against a new session.
- **Client filter:** peers outside RFC1918 / CGNAT / link-local get no reply at all (the box's firmware is unpatchable; it must not be reachable from the internet).
- **Virtual paths:** the client only ever names `<rootId>/<relative>` (e.g. `sd/Download/film.mp4`); empty = root list. Resolve by canonicalising and re-checking against that root's canonical path; `..`, encodings, `....//`, absolute paths and symlinks all fall outside and get 404. Paths returned to the browser are rebuilt from the canonical path.

## Storage roots

| Root | Path | Permission | Write |
|---|---|---|---|
| `kutu` | the app's own external files dir | none | always |
| `sd` | the whole internal card | `READ`/`WRITE_EXTERNAL_STORAGE` | while granted |

Request storage at runtime **only from the session screen** (one D-pad press grants the group). If refused, fall back to `kutu` only and say so on the TV. Revoking in Settings returns the app to single-folder scope.

## Transfer rules
- uploads go to the folder the browser is in (`POST /up?p=<dir>`); no `p` → `kutu`
- refuse writes to read-only roots
- refuse an upload that would leave the volume below **256 MB** free
- listing capped at 2000 entries
- delete: files, and directories **only when empty** (non-empty → 409); no recursive delete
- `Range` requests supported (206 + `Content-Range`)
- no `mkdir` (upload into existing folders)

## Streaming multipart (write carefully)
Parse `multipart/form-data` **streaming straight to disk** in a 64 KB window, holding back only (boundary length − 1) bytes per pass. Never buffer a file in RAM (the box has <2 GiB).

Pitfall: `PushbackInputStream.read(b,off,len)` drains pushback then **calls through to the socket** for the rest; when a small body is fully in pushback it blocks forever. Never request more than `available()` while anything is buffered. Small files are the demanding test, not large ones.

## Browser page (`Page`)
- a folder browser: roots → folders → files, crumb trail, "Üst klasör" row, upload (drag-drop, file picker, progress), download, delete, free-space line
- **no path is ever pasted into a quoted JS string or inline `onclick`.** Every clickable carries its target in `data-go` / `data-rm`; one delegated listener reads it with `decodeURIComponent`. Check `data-rm` **before** `data-go` (a folder row contains its delete button).
- escape every file name before `innerHTML`
- "Bu klasör boş" is decided from the entries, not from whether the built HTML is empty
- test the page, not just the server: extract the served HTML/JS (`tools/extract.py`) and drive it under jsdom with a fake `fetch` (`tools/browse-test.js`) using hostile names (`<img src=x onerror=…>`, `Bob's notes.txt`, `#`, `&`)

## On the TV
- opening a file from the TV list hands it to Android via a hand-written, read-only `SharedFileProvider` scoped to the shared roots (no AndroidX `FileProvider`)
- APK → Android's own installer. It still needs the per-app "Install unknown apps" toggle; tell the human where it is and let them decide

## Tests
- port 8787 absent before a session, `LISTEN` during, gone after BACK
- section 10 probe regression against 8787: bare connect/close ×5, held idle connection, `GET /`, `/api/list`, `/dl`, `/up`, `/rm` with no session → 0 Activity launches, all refused, foreground unchanged
- unauthenticated 401, wrong PIN 401, correct PIN 200 + cookie, stale cookie/PIN 401
- round trips with identical SHA-256: 1-byte file, ~45 KB APK, multi-file request, ≥1 GB file; heap stays flat
- traversal set above → all 404, for list, download and upload
- delete rules (409 on non-empty dir), apostrophe/`#` names
- real browser: navigate, download, upload via picker and drag-drop, progress bar
- idle CPU 0.0 %, no service, no wakelock after a session
- Kutu Home: 4 chips, shelf still centred, chip → session → BACK

## Risks to disclose
- a second app on the box can request APK installs (always a deliberate press on the TV, Android confirmation still gates it; dropping the permission is a one-line change)
- a writable LAN port reaching the whole card exists during a session; bounded by PIN, idle timeout, private-peer filter and the session-screen lifetime
- add port 8787 to the attributed port table in the security review

---

# 29. Attempt default HOME safely

Snapshot:
- current HOME
- package states
- stock launcher PSS
- Kutu Home PSS
- recommendations PSS
- MemAvailable

Try:

```bash
adb -s <DEVICE> shell cmd package set-home-activity local.kutu.home/.HomeActivity
```

Verify:

```bash
adb -s <DEVICE> shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Also test physical HOME, including from inside a streaming app.

If Kutu Home becomes HOME normally, do not disable stock launcher.

If the command says Success but stock HOME still wins (e.g. its HOME filter has `priority=2`):

1. identify exact stock HOME package/activity on this device
2. confirm it can be re-enabled
3. add rollback to `RESTORE-ALL`, ordered first:
   ```bash
   pm enable --user 0 <STOCK_HOME_PACKAGE>
   cmd package set-home-activity <STOCK_HOME_ACTIVITY>
   ```
4. verify Kutu Home installed/enabled
5. show human exact disable and rollback
6. **ask for explicit approval and wait for it**

Approve **Disable stock launcher** only if all Kutu Home tests passed and rollback is shown.

If approved:

```bash
adb -s <DEVICE> shell pm disable-user --user 0 <VERIFIED_STOCK_HOME_PACKAGE>
adb -s <DEVICE> shell cmd package set-home-activity local.kutu.home/.HomeActivity
```

Never uninstall stock launcher.

Verify immediately. If failure, restore stock HOME immediately.

---

# 30. Reboot after HOME switch

Reboot without manually launching Kutu Home.

Verify:
- normal boot
- Kutu Home auto-HOME
- no chooser (`ResolverActivity` count 0)
- no persistent black screen
- no crash loop
- remote works
- dock persists
- theme persists
- Kutu Mirror starts
- AirPlay returns

Remaining HOME candidates should be Kutu Home plus the system `FallbackHome` safety net. Temporary `FallbackHome` for a few seconds during boot is normal.

---

# 31. Measure real HOME savings

After settling:
- Kutu Home PSS
- stock launcher state/PSS
- recommendations PSS
- MemAvailable
- Kutu Home CPU ~60 s
- services
- wakelocks

No synthetic benchmarks.

Reference only, original Mi Box:
- stock HOME ~59–64 MB (+ separate recommendations process)
- settled Kutu Home ~10.5–23 MB (varies with icons cached), idle CPU 0.0 %
- Kutu Mirror idle ~10–12 MB, ~16 MB during a session

---

# 32. Mandatory normal-use checkpoint

Stop and ask human to use normally:
- HOME
- major streaming apps
- voice search (if kept)
- Settings
- Screen Mirroring
- File Transfer

Do not do final debloat until they confirm all normal.

---

# 33. Final low-risk cleanup

Inspect actual candidates.

Possible categories:
- stock recommendations
- manufacturer content/home feed
- manufacturer channel feed
- manufacturer web content / downloader / video player / updater
- manufacturer phone-remote/discovery/mirroring (only now that Kutu Mirror works)
- unused alternative launcher

Mi Box reference: see the list in section 5.

Verify identity, dependency/client, residency and feature usage before disabling. A listening port that disappears when a package is disabled confirms attribution (MiLink owned port 6091).

Use only `pm disable-user --user 0`.

Add undo to restore scope.

Preserve:
- Assistant (unless the human chose otherwise)
- Chromecast
- DRM
- Bluetooth
- networking
- `com.android.providers.tv`
- CEC

Short smoke test:
- HOME
- voice search
- representative streaming app
- YouTube/video app
- one AirPlay connection
- one file transfer session
- Settings

---

# 34. Final security review

Focus on this actual box:
- every listening TCP/UDP port, attributed by uid
- ADB/debugging (network ADB on 5555)
- Kutu exported components (Mirror: 2 shims; Transfer: 2 shims)
- permissions
- port 7000 (Mirror) and 8787 (Transfer, only during a session)
- shims
- screen/frame storage
- Recents
- release/debuggable flags
- `usesCleartextTraffic`
- world-readable files
- unnecessary services
- apps allowed to install unknown APKs, `install_non_market_apps`
- firmware patch age

Classify:
- Critical
- High
- Medium
- Low
- Informational

Auto-fix only low-risk hardening that cannot reasonably break TV use.

Do not automatically revoke third-party updater/install permissions if that changes app function; report them.

Reference findings from the original run:
- network ADB open: High until section 37
- firmware security patch years old: High, residual, unfixable → keep the box off the public internet
- AirPlay PIN off: Medium on an untrusted LAN
- apps holding `REQUEST_INSTALL_PACKAGES` (third-party file manager, Kutu Transfer): Medium
- unknown-sources setting on: Low
- exported control shims: Low, they only start/stop
- cleartext AirPlay control channel: Low, local only
- Cast, remote-service, mDNS surfaces: Informational, required by rule 11
- port 7000 probe opens no UI: verified, no finding

Write it into `RUN-REPORT.md`, including what could not be checked.

---

# 35. Final performance summary

Use existing measurements, no long benchmark.

Compare baseline vs final:
- stock HOME vs Kutu Home PSS
- recommendations
- manufacturer content/home
- manufacturer remote/discovery
- analytics
- Kutu Mirror idle PSS
- Kutu Home idle CPU
- `/data` free
- MemAvailable only as indicative if uptimes differ

Original Mi Box reference:
- launcher-related processes roughly 120 MB → 20–25 MB
- roughly 95 MB less resident memory while adding AirPlay
- `/data` free 1.9 GB → 2.6–2.8 GB

Do not promise identical results elsewhere.

---

# 36. Final report and rollback

Finish `RUN-REPORT.md` with:
- device/firmware
- baseline
- every package changed
- every undo
- every deviation from this guide, and why
- final HOME
- Kutu Home build/hash
- Kutu Mirror build/hash (+ upstream and fork commits)
- Kutu Transfer build/hash
- iPhone/Mac/AirPlay Video tests
- real remote test
- file transfer tests
- reboot
- performance before/after
- security findings/fixes
- remaining risks
- signing-key locations
- exact rollback
- still-outstanding items (e.g. Mac test not run)

Syntax-check `RESTORE-ALL` and dry-run it against the live box.

If removing Kutu apps:
1. restore stock HOME first (`pm enable` + `set-home-activity`)
2. then uninstall Kutu user apps if requested

Tell human to back up signing keys.

---

# 37. Last action: turn ADB/debugging off

Only after:
- final report exists
- restore script is complete
- no more ADB work is needed
- final real iPhone AirPlay session passes

tell the human to turn off USB/network debugging on the TV/box, and optionally Developer Options.

Do not turn it off yourself if it would strand the session.

Once the human confirms, verify from the host (`adb connect` to 5555 is refused, `adb devices` empty), then mark project complete. Note in the report that any future update or rollback needs debugging switched back on (Settings → Device Preferences → Developer options).

---

# 38. Stop conditions

Stop and report rather than improvise if:
- ADB disappears repeatedly for unknown reason
- package purpose is unclear
- a system disable breaks boot/HOME
- AirPlay performance is unacceptable
- previous mirrored content can reappear
- port 7000 or 8787 probe opens UI
- real remote long press, or confirming a move, launches an app
- Kutu Home cannot reliably return HOME
- HOME resolves to a chooser and cannot be pinned
- stock launcher rollback cannot be proven
- source/build integrity is questionable

The goal is a stable, reversible, lightweight TV appliance, not maximum debloat.
