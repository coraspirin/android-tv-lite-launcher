# KUTU-MIRROR.md — build, audit and test record (guide sections 6–14)

Built 2026-09-21 for the Mi Box S (MIBOX4 / oneday, Android 9, API 28, armeabi-v7a).

Source audit of upstream is in `UPSTREAM-AUDIT.md`. This file records what the fork
changed, how it was built, and what was verified.

## Identity

| | |
|---|---|
| Package | `local.kutu.mirror` |
| Application name | Kutu Mirror |
| AirPlay receiver name | **Kutu** |
| Version | 1.0 (versionCode 1) |
| Upstream commit | `c8defdd70d7e6a04f4f1b71d353653682d594106` (jqssun/android-airplay-server) |
| Fork commit | `2710282` |
| Fork history | `8d12d5c` FFmpeg removal -> `8f51bc0` mirroring-only rework -> `19a2e02` decoder restart on size change -> `1d52d92` AirPlay Video restored -> `2710282` media-type inference + stop shim |
| Licence | GPL-3.0, retained. Source for this fork lives in `build/kutu-mirror/` |

Submodule pins actually built:

| Module | Commit |
|---|---|
| UxPlay | `462153392f2e30937424922039ff9f0cda5e7b1a` |
| libplist | `f41b1ea67045e0c09339974d83e389972d84f166` |
| openssl-cmake | `4edd36a8dab5f85a8f92650b5bdf6e0cab13aab8` |
| ffmpeg | **removed from the fork** |

## Build environment

The native half of this project is a POSIX autotools/CMake build and cannot be built
on Windows (see `UPSTREAM-AUDIT.md`). WSL2 Ubuntu 26.04 was used, reproducing
upstream's `ubuntu-latest` CI.

| | Value |
|---|---|
| Host | WSL2 Ubuntu 26.04.1 LTS |
| JDK | OpenJDK 17 (`openjdk-17-jdk-headless`) |
| Gradle | 8.11.1, wrapper pinned with `distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6` (from services.gradle.org) |
| AGP / Kotlin | 8.9.2 / 2.1.0 |
| Android SDK | cmdline-tools `commandlinetools-linux-16111833`, SHA-1 `e025545c62a8e64c7559119566a569fb1dec5f60`, matching Google's `repo2-1.xml` |
| Platform / build-tools | android-36 / 36.0.0 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| OpenSSL | 3.4.4, built from source. Tarball SHA-256 `7bdf55ac20f2779e99e5eca306f824fad2b37dee5a06cc35ed5a8b85a6060010` — **byte-identical to the official openssl.org release hash**, although openssl-cmake fetches it from viaduck's mirror |

Toolchain lives under `~/kutu/sdk` inside WSL, not in the project tree, because the
Windows-side `build/sdk` holds Windows binaries and cannot cross-build the native code.
The canonical build tree is `~/kutu/mirror` in WSL; `build/kutu-mirror/` in this project
is a source snapshot of it (build outputs and `local.properties` excluded).

## What the fork removed (guide 8)

| Removed | Why |
|---|---|
| **FFmpeg** submodule and `BuildFFmpeg.cmake` | only provided a software ALAC decoder; guide 8 drops FFmpeg and ALAC. Also the sole reason the build needed `make`/`configure`. |
| Software ALAC decoder (`FfmpegAlacDecoder`) | same |
| ALAC advertising | `cn=0,2,3` on the wire: PCM, AAC-LC, AAC-ELD. ALAC (1) is absent. |
| Video downloader + `FileProvider` + `file_paths.xml` | nothing is saved to disk |
| ~~HLS / AirPlay Video player~~ | removed first, then **put back** at the human's request - see "AirPlay Video restored" below |
| DACP / music / artwork / metadata / MediaSession | music streaming surface, not mirroring |
| Jetpack Compose (whole `ui/` tree), Hilt, `MainViewModel` | replaced by one plain-View activity |
| Debug overlay and its native debug plumbing hook | |
| Picture-in-Picture | guide 10 |
| `sanitize` build type, HWASan/UBSan wrap.sh | debug-only |
| ABIs arm64-v8a and x86_64 | the box is 32-bit ARM only |
| HEVC | `DEF_H265_ENABLED = false`; the decoder log confirms `hevc=null` |

Kotlin sources went from **37 files / 7 186 lines** to **17 files**. Dex method
references: **1 980**.

## What the fork changed (guide 9–12)

### Lifecycle and power (guide 9)

Upstream took a `PARTIAL_WAKE_LOCK` in `startServer()` and held it for the entire life
of the receiver — a permanent CPU wakelock while idle, which guide 9.4 forbids.

In this fork the wakelock is acquired in `onMirrorRunning(true)` and released on every
termination path. Verified on device: with the receiver running and idle, `dumpsys power`
holds **no** `kutu:` wakelock, and idle CPU over 20 s is **0.0 %**.

The multicast lock is still taken for the whole run, which is what discovery needs
(guide 9.3). Audio output is released when the last client disconnects (guide 9.8).

### The port-7000 UI trigger (guide 10)

**Upstream opened its UI from `onConnectionInit()`** — the pre-auth TCP signal. Any TCP
connection to port 7000, including a port scan, would therefore have put a full-screen
activity over whatever was playing.

This fork never launches UI from `onConnectionInit()`. `MirrorActivity` is opened from
exactly two places:

1. `onMirrorRunning(true)` — genuine mirroring
2. `onDisplayPin()` — a genuinely minted pairing PIN

and if it binds while neither is true it finishes immediately.

### Stale frames (guide 10)

`VideoPipeline._bindDisplay()` repaints the last decoded frame onto any newly attached
surface ("an idle source sends no new frames, so repaint last one"). Without a teardown
that clears it, a later session would show the **previous sender's final frame**.

Every session end now calls `videoRenderer.stopSession()` **and** `videoRenderer.release()`,
which stops and releases the decoder and destroys the GL pipeline, SurfaceTexture and
input Surface. `MirrorActivity` detaches its Surface and blacks its background before
finishing. Nothing is ever written to disk.

### Components and permissions (guide 11, 12)

| Component | Upstream | Kutu Mirror |
|---|---|---|
| `MainActivity` | `exported=true`, MAIN + LAUNCHER + LEANBACK_LAUNCHER, PiP | **gone** |
| `MirrorActivity` | — | `exported=false`, no intent filter, no PiP, `excludeFromRecents`, `autoRemoveFromRecents` |
| `ReceiverControlActivity` | — | `exported=true`, `Theme.NoDisplay`, starts the service and finishes, accepts no commands |
| `ReceiverStopActivity` | — | the matching off switch, same contract - see "On/off switch" below |
| `AirPlayService` | `exported=false` | same |
| `BootReceiver` | **`exported=true`** | `exported=false`, plus `MY_PACKAGE_REPLACED` |
| `FileProvider` | present | removed |
| `androidx.profileinstaller` receiver | exported=true (transitive) | dependency excluded |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | auto-added by androidx.core | removed via `tools:node="remove"` |

Permissions cut from 11 to 5, exactly guide 12's list:

```
INTERNET  CHANGE_WIFI_MULTICAST_STATE  WAKE_LOCK  RECEIVE_BOOT_COMPLETED  FOREGROUND_SERVICE
```

Dropped: `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, and **`SYSTEM_ALERT_WINDOW`**.

`usesCleartextTraffic=true` is kept: AirPlay's control channel is plain HTTP. It is
local-only traffic to the box's own port 7000.

PIN is available but **off by default** (guide 12). With PIN off, any device on the same
LAN can start mirroring to this box without being challenged.

## Release build

| | |
|---|---|
| APK | `build/kutu-mirror.apk` |
| Size | **5 924 126 bytes (5.65 MB)** |
| SHA-256 | `66f66916dda34ac3068f78cf6f073c5b8ff155cb3b6a1d467ed6b406c02b918e` |
| minSdk / targetSdk / compileSdk | 28 / 28 / 36 |
| ABI | armeabi-v7a only |
| Build type | release, `minifyEnabled`, `shrinkResources`, `debuggable=false`, `allowBackup=false` |
| Signing | v2 scheme, cert SHA-256 `7cbb1d95e6ac556c608cc0de4408a447b55a5d85e5d972a40aeb78600463e8ba` |
| Keystore | `keys\kutu-mirror.jks` (PKCS12, RSA 4096), password in `keys\kutu-mirror.credentials.txt` |

**Back the keystore up.** Losing it means Kutu Mirror can never be updated in place again.
`local.properties`, which carries the password, is git-ignored and was not copied into
`build/kutu-mirror/`.

Native libraries shipped:

| Library | Size |
|---|---|
| `libairplay_native.so` | 403 356 B |
| `libcrypto.so` (OpenSSL 3.4.4) | 3 034 096 B |
| `libc++_shared.so` | 872 872 B |
| `liboboe.so` | 191 588 B |

`libcrypto.so` is two thirds of the APK. It is a full OpenSSL build; trimming it to the
algorithms AirPlay actually uses was not attempted.

## Verified on device (guide 13)

All checks run against `192.168.61.115:5555` on 2026-09-21.

| Check | Result |
|---|---|
| Installs | pass |
| No LAUNCHER activity | pass — `query-activities` returns nothing |
| No LEANBACK_LAUNCHER activity | pass — same |
| Starts via `am start -n local.kutu.mirror/.ReceiverControlActivity` | pass, and the box returned straight to Kutu Home with no visible UI |
| Service running, foreground | pass — `isForeground=true`, notification channel `kutu_mirror` |
| Port 7000 listening | pass — `/proc/net/tcp` state `0A` |
| `_airplay._tcp` instance name | pass — **`Kutu`**, `srcvers=220.68`, `model=AppleTV3,2`, `pw=false` |
| `_raop._tcp` instance name | pass — **`ECFA5CE4E13C@Kutu`**, `cn=0,2,3`, `sr=44100`, `ss=16`, `ch=2` |
| ALAC not advertised | pass — `cn` has no `1` |
| AirPlay Video not advertised | pass — feature bits 0 and 4 clear in `0x5A7FFEE6` |
| H.264 hardware decoder | pass — `avc=OMX.amlogic.avc.decoder.awesome` |
| HEVC off | pass — `hevc=null` |
| No crash loop | pass — empty crash buffer, no FATAL |
| **No idle mirroring wakelock** | pass — no `kutu:` entry in `dumpsys power` |
| Idle CPU | **0.0 %** |
| Idle PSS | **12.0 MB** |
| Update without reboot | pass — `install -r` over the running receiver; it came back on its own and re-registered as `Kutu` |

### Port-probe regression (guide 14)

With no mirroring in progress, from the host: five bare TCP connect/close cycles, one
idle-held connection, and `GET /info`, `/server-info`, `/playback-info`.

| | |
|---|---|
| MirrorActivity launches | **0** |
| Foreground app before | `local.kutu.home/.HomeActivity` |
| Foreground app after | `local.kutu.home/.HomeActivity` — unchanged |
| Black screen / interruption | none |
| Receiver still working afterwards | yes |

The receiver logged each connect and disconnect and put nothing on screen. This is the
behaviour upstream did **not** have.

### Update-while-force-stopped

Installing over a force-stopped package does not restart the receiver, because Android
withholds broadcasts from a package in the stopped state (`stopped=true` in
`dumpsys package`). That is platform behaviour, not a defect: the realistic path,
updating while the receiver runs, does come back without a reboot.

## Kutu Home integration

Kutu Home's `MirrorPanelActivity` already targets
`local.kutu.mirror.ReceiverControlActivity` and deliberately never touches
`MirrorActivity` (guide 23). `AppRepository.MIRROR_PKG` matches this package, and Kutu
Mirror has no launcher activity, so it cannot appear in All Apps either way.

## AirPlay Video restored (deviation from guide 8, requested by the human)

iOS has two AirPlay paths and they are different protocols:

| | What the sender does | What the receiver does |
|---|---|---|
| Control Centre -> Screen Mirroring | encodes the screen as H.264 and streams it | decodes and displays it |
| The AirPlay button **inside a video** | hands over the media URL | fetches and plays that URL itself |

Guide 8 says to remove the HLS player, so the second path was disabled
(`nativeSetHlsEnabled(false)`, which clears dns-sd feature bits 0 and 4). But the box
still advertises AirPlay audio, so **"Kutu" kept appearing in the in-video target list
and did nothing when picked** - it simply looked broken. Confirmed on the wire: with
video not advertised the iPhone never even opened a connection.

The human was shown the trade-off and asked for it to work, so it was restored:

- `AirPlayVideoPlayer` (Media3 ExoPlayer + HLS source) recovered from commit `8d12d5c`
- `nativeSetHlsEnabled(true)`
- `onVideoPlay` / `onVideoScrub` / `onVideoRate` / `onVideoStop` wired up, and playback
  position/duration/rate reported back through `nativeUpdatePlaybackInfo`
- `MirrorActivity` now serves both session kinds. The service owns the single Surface
  and routes it to whichever consumer is live, so two producers never share it.
- BACK stops playback for AirPlay Video (the sender syncs from its next
  `/playback-info` poll) and cycles the receiver for mirroring, as before
- the remote drives play/pause and +/-10 s seek during AirPlay Video, because here the
  box is the player rather than a passive screen

**`onVideoSessionPoll` is still deliberately empty.** Guide 10 names `/playback-info`
as something that must never put anything on screen, and the sender starts polling it
about a second before `/play`. The activity is opened from a genuine `/play` only.

### Costs of this deviation

| | Before | After |
|---|---|---|
| APK | 4 810 873 B (4.59 MB) | **5 924 126 B (5.65 MB)** |
| Permissions | 5 | **6** - ExoPlayer's manifest reinstates `ACCESS_NETWORK_STATE` |
| Dependencies | core-ktx, lifecycle-service, coroutines, oboe | + media3-exoplayer, media3-exoplayer-hls |
| Exported components | 1 (`ReceiverControlActivity`) | unchanged, still 1 |

`ACCESS_NETWORK_STATE` is a normal-level permission that only reveals connectivity
type. Removing it with `tools:node="remove"` was considered and rejected: ExoPlayer
genuinely reads it, and breaking playback to save a non-dangerous permission is a bad
trade.

Note that DRM apps (Netflix, Disney+ and similar) block AirPlay Video regardless, and
YouTube uses Google Cast rather than AirPlay. This path mainly helps Safari, the Photos
app and web video.

## Media type inference for AirPlay Video

ExoPlayer decides how to read a stream from the url's extension alone. UxPlay rewrites
HLS master playlists and serves them from its own loopback httpd, and **that url carries
no `.m3u8`**, so `DefaultMediaSourceFactory` fell back to the progressive extractors and
every one of them failed to sniff a playlist. Legitimate HLS senders would all have hit
this.

`AirPlayVideoPlayer` now opens loopback and `.m3u8` urls as HLS outright, and whichever
type it tries first, it retries once with the other kind before giving up. Both attempts
are logged.

Confirmed on device, using a sender that handed over a web page rather than media - the
worst case, where neither type can work:

```
None of the available extractors could read the stream   <- progressive attempt
Input does not start with the #EXTM3U header             <- automatic HLS retry
AirPlay Video ended                                      <- clean teardown, activity closed
```

Both paths ran, the activity opened and closed itself, and nothing crashed. A sender
that hands over a page url instead of a media url cannot be played by any receiver, and
no attempt is made to scrape one out of the page.

## On/off switch (deviation from guide 11, requested by the human)

Guide 11 describes a start shim only, so the receiver could be turned on from Kutu Home
but never off. The human asked for a switch in the mirror panel, so
`ReceiverStopActivity` was added as the other half.

It keeps the same contract as `ReceiverControlActivity`: exported, `Theme.NoDisplay`,
no parameters, no commands, finishes before it can be drawn. Each shim also writes the
boot auto-start flag - on when started, off when stopped - so "off" survives a reboot
instead of silently coming back.

**The deviation:** being exported, any app on the box can stop the receiver. That is a
nuisance at worst; nothing is exposed and no data is at risk. The alternative, keeping
the receiver unstoppable from the launcher, was worse for the person using it.

Kutu Home reads the real state from `/proc/net/tcp`, which needs no permission, so the
switch reflects whether the receiver is genuinely listening rather than a remembered
preference. Verified over ADB:

| Action | Port 7000 | Services |
|---|---|---|
| `ReceiverControlActivity` | LISTENING | 1 |
| `ReceiverStopActivity` | not listening | 0 |
| `ReceiverControlActivity` | LISTENING | 1 |

Not yet verified: that "off" really survives a reboot.

## Real AirPlay tests (guide 14)

Run by the human on a physical iPhone, 2026-09-21. **HANDS OFF was observed**: no ADB
key events were sent during the test, only logcat was read.

### iPhone mirroring

| Step | Result |
|---|---|
| "Kutu" appears in Control Centre -> Screen Mirroring | pass |
| Mirroring starts | pass — `Mirroring started`, then MirrorActivity opened **after** it, never before |
| MirrorActivity opened on the connection, not on mirroring | **did not happen** — the launch line always follows `Mirroring started` |
| Portrait aspect-fit | pass — stream 500x1080, letterboxed, centred |
| Rotate to landscape | pass after a fix, see below — stream 1920x888 |
| Audio | pass — `Audio format: ct=8 spf=480 screen=true` (AAC-ELD, mirroring audio), heard on the TV |
| H.264 hardware decoder | pass — `OMX.amlogic.avc.decoder.awesome` |
| No HEVC on this H.264-only build | pass — `hevc=null` |
| Stop Mirroring | pass — `Mirroring stopped` then `Client disconnected (0)` |
| Returns HOME | pass — resumed activity back to `local.kutu.home` |
| No stale frame | pass — confirmed by the human, TV went straight back to Kutu Home |
| Crashes / ANRs | none in the whole session |
| Session PSS | 16.1 MB |
| Wakelock released after the session | pass — no `kutu:` entry in `dumpsys power` |
| MirrorActivity gone after the session | pass — no ActivityRecord left |

### BACK test

| Step | Result |
|---|---|
| BACK pressed once during mirroring | pass |
| Sender disconnects | pass — `Session ended locally, restarting receiver` -> `Client disconnected (0)` |
| TV returns HOME | pass |
| Receiver re-advertises | pass — `RAOP registered: ECFA5CE4E13C@Kutu` and `AirPlay registered: Kutu`, ~700 ms after BACK |

### Defect found and fixed during the test

**Landscape picture quality collapsed after rotating the phone.**

Reported by the human. The mirrored stream changes dimensions when the sender rotates
(500x1080 portrait <-> 1920x888 landscape), but `VideoRenderer` configured the decoder
once, at the size of the first frame, and `feedFrame` only rebuilt it on an
H.264/H.265 switch. On adaptive decoders `_format()` also declares that first size as
`KEY_MAX_WIDTH`/`KEY_MAX_HEIGHT`, so the 1920x888 stream exceeded the maximum the
Amlogic decoder had been given and it produced a badly degraded picture.

Fix (`19a2e02`): `setResolution()` drops the codec when the dimensions change;
`feedFrame` rebuilds it at the new size on the next keyframe, which the sender emits on
rotation anyway.

Verified on device after the fix:

```
video size changed to 500x1080, restarting decoder
Video codec started: video/avc 500x1080 (H.264 (OMX.amlogic.avc.decoder.awesome))
video size changed to 1920x888, restarting decoder
Video codec started: video/avc 1920x888 (H.264 (OMX.amlogic.avc.decoder.awesome))
```

Rotation now costs about 230 ms of black, with no dropped frames and no codec errors.
The human confirmed landscape quality is correct.

### Reboot test (guide 9.1)

The box was rebooted with no manual start afterwards.

| | Result |
|---|---|
| Receiver auto-started | pass — `Server started on port 7000 as "Kutu"` |
| Re-advertised | pass — `AirPlay registered: Kutu`, `RAOP registered: ECFA5CE4E13C@Kutu` |
| HOME | `local.kutu.home/.HomeActivity`, resumed, no chooser |
| Idle wakelock | none |
| Idle CPU over 20 s | **0.0 %** |
| Idle PSS | **10.2 MB** |
| MemAvailable | 1 260 120 kB (~1.20 GiB) |
| /data free | 2.8 GB |
| Crashes since boot | 0 |

### Not yet run

- **Mac**: native macOS Screen Mirroring, picture + sound, disconnect, cleanup. No Mac
  was available.

## Steady-state cost

| | Idle | During a session |
|---|---|---|
| PSS | 10.2 MB | ~16 MB |
| CPU | 0.0 % | decoder-bound |
| Wakelocks | none | one `kutu:mirror` partial, released on teardown |
| Listening ports | 7000 | 7000 |
| Background services | 1 foreground service | same |
