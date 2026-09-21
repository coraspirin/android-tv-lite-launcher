# UPSTREAM-AUDIT.md — Kutu Mirror (guide section 7)

Audited 2026-09-20.

## Identity

| | |
|---|---|
| Repo | https://github.com/jqssun/android-airplay-server |
| Commit | `c8defdd70d7e6a04f4f1b71d353653682d594106` |
| Author / date | jqssun / 2026-08-23T18:21:25+01:00 |
| Subject | "update" |
| Stars / open issues | 309 / 10 |
| Archived | no |
| **Licence** | **GPL-3.0** — attribution must be retained; a derivative distributed to others must ship source. Personal-device use imposes no distribution duty. |

## Build requirements declared by upstream

| | Value | Fits our box? |
|---|---|---|
| minSdk | **24** | yes — box is API 28 |
| compileSdk / targetSdk | 36 / 36 | fine |
| AGP | 8.9.2 | needs Gradle 8.11.1 (wrapper pins it) |
| Kotlin / KSP | 2.1.0 / 2.1.0-1.0.29 | |
| JVM target | 17 | JDK 17 installed |
| **ndkVersion** | **27.0.12077973** | supports armeabi-v7a |
| CMake | >= 3.22.1 | |
| ABI filters | arm64-v8a, **armeabi-v7a**, x86_64 | armeabi-v7a is our only usable one |

## Telemetry / ads / accounts / cloud — CLEAN

No firebase, crashlytics, analytics, admob, billing, appsflyer, amplitude, sentry,
mixpanel or google-services plugin in any build file. No remote HTTP endpoints in
the Kotlin sources outside schema/localhost URLs. `dependenciesInfo.includeInApk = false`.
Nothing to strip on this axis.

## Native dependencies (submodules)

| Module | Source | Purpose | Guide section 8 verdict |
|---|---|---|---|
| UxPlay | github.com/FDH2/UxPlay | AirPlay/RAOP core | keep |
| libplist | libimobiledevice/libplist | plist parsing | keep |
| openssl-cmake | viaduck/openssl-cmake | builds **OpenSSL 3.4.4** from source | keep (crypto is required) |
| **ffmpeg** | FFmpeg/FFmpeg | **software ALAC decoder only** | **remove** — guide says drop FFmpeg and ALAC |
| Oboe 1.9.3 | prefab AAR | low-latency audio out | keep |

Six local patches are applied to UxPlay before CMake configure
(`0001`-`0006`: HLS playlist crash, UAF on playlist refresh, on-demand live playlist
refresh, video-sender compat, video reset on abandoned play connection, volume while
audio paused).

## Manifest findings vs guide sections 10-12

| Item | Upstream | Guide requires | Action |
|---|---|---|---|
| `MainActivity` | `exported="true"`, MAIN + LAUNCHER + LEANBACK_LAUNCHER, `supportsPictureInPicture="true"` | mirror activity `exported="false"`, **no launcher categories**, no PiP | **restructure** into non-exported `MirrorActivity` + tiny exported `ReceiverControlActivity` (NoDisplay) |
| `AirPlayService` | `exported="false"` | same | OK as-is |
| `BootReceiver` | **`exported="true"`**, BOOT_COMPLETED + QUICKBOOT_POWERON | non-exported; must also handle `MY_PACKAGE_REPLACED` | **fix**: `exported="false"`, add MY_PACKAGE_REPLACED |
| `FileProvider` | present | no saved screen/audio content | **remove** if nothing needs it |
| `allowBackup` | `false` | `false` | OK |
| `usesCleartextTraffic` | `true` | — | required by AirPlay's plain HTTP; keep, note in security review |

### Permissions

Upstream requests 11. Guide section 12 lists 5 for the Android 9 target.

| Permission | Keep on API 28? |
|---|---|
| INTERNET | keep |
| CHANGE_WIFI_MULTICAST_STATE | keep (discovery) |
| WAKE_LOCK | keep |
| RECEIVE_BOOT_COMPLETED | keep |
| FOREGROUND_SERVICE | keep |
| ACCESS_NETWORK_STATE | drop — not needed for the mirroring path |
| ACCESS_WIFI_STATE | drop |
| FOREGROUND_SERVICE_CONNECTED_DEVICE | drop — API 34+, no-op on 28 |
| FOREGROUND_SERVICE_MEDIA_PLAYBACK | drop — API 34+, no-op on 28 |
| POST_NOTIFICATIONS | drop — API 33+, no-op on 28 |
| **SYSTEM_ALERT_WINDOW** | **drop** — overlay permission, not needed, worst item in the list |

## BLOCKER (RESOLVED 2026-09-21): upstream is Linux-only in practice

`app/src/main/cpp/cmake/BuildFFmpeg.cmake` builds FFmpeg through
`ExternalProject_Add` with `CONFIGURE_COMMAND <SOURCE_DIR>/configure ...`,
`BUILD_COMMAND make -j`, `INSTALL_COMMAND make install`.

That is a POSIX autotools-style build. Upstream CI confirms the intent:
`.github/workflows/apk.yml` -> `runs-on: ubuntu-latest`.

At audit time this Windows host had no `make`, `perl`, `nasm` or `pkg-config`, and
WSL was not installed. OpenSSL's own `Configure` is a Perl script, so `openssl-cmake`
needed Perl too.

**Resolution.** The human installed WSL. Option A was taken and it worked on the first
attempt: WSL2 Ubuntu 26.04 reproduces upstream's `ubuntu-latest` CI. Option B's premise
also held — dropping FFmpeg (which guide 8 wanted gone anyway) removes the
`configure`/`make` dependency entirely, so the fork's native build is now pure CMake
plus OpenSSL's Perl `Configure`.

Toolchain, hashes, what was stripped and what was verified are all in `KUTU-MIRROR.md`.
The source audit below stands unchanged: it was clean, and the manifest and permission
findings were all acted on.

## Options considered at the time

**A. WSL (recommended, and what was done).** `wsl --install` + Ubuntu, then reproduce
upstream CI exactly. Build inside WSL, copy the APK out, install from Windows with adb.

**B. Strip FFmpeg, build natively on Windows.** Guide section 8 already wants FFmpeg
and ALAC gone, which deletes the `configure`/`make` dependency. OpenSSL would still
need Perl + NASM. Cross-compiling OpenSSL for Android from Windows is a poorly-trodden
path.

**C. Defer Mirror, build Kutu Home first.** Kutu Home (sections 15-27) is plain
Java/Views with no native code, so it built on Windows. This inverted the guide's
section 15 ordering, a deviation disclosed in `DEBLOAT-LOG.md`. It is what actually
happened while WSL was being installed.
