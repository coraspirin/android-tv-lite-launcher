# BASELINE-SUMMARY.md
Mi Box S (MDZ-22-AB) — baseline captured 2026-09-20 13:03 local.

> Retro-documented. Raw captures in `evidence/baseline/`. The network-exposure and
> native-AirPlay sections were collected after the first debloat batches; noted inline.

## 1. Device spec
| | |
|---|---|
| Manufacturer / model / device | Xiaomi / MIBOX4 / oneday |
| Marketing name | Mi Box S 1st gen (MDZ-22-AB) |
| Android / API | 9 (Pie) / 28 |
| Build ID / date | PI / Tue Sep 28 18:09:55 CST 2021 |
| Fingerprint | Xiaomi/oneday/oneday:9/PI/3933:user/release-keys |
| Security patch | **2021-07-05** (~5 years stale) |
| SoC / platform | Amlogic S905X (`ro.board.platform=gxl`) |
| ABI | **armeabi-v7a only** (`abilist=armeabi-v7a,armeabi`) |
| Userland | `zygote32` — 32-bit only |
| Display | 1920x1080 @ 320 dpi |

Build implication: any native build must target **armeabi-v7a**, minSdk<=28. No arm64.

## 2. Memory / storage
| | Value |
|---|---|
| MemTotal | 2 034 840 kB (~1.94 GiB) |
| MemAvailable at baseline (settled) | 1 102 680 kB (~1051 MiB) |
| Swap total | 262 140 kB (zRAM); `/proc/swaps` unreadable without root |
| /data | 5.0 G total, 3.0 G used, **1.9 G free (61% used)** |
| /cache | 232 M total, 17 M used |
| /system | 1 305 380 K total, 61 128 K free (96% used) |
| App code | 2 343 MB |
| App data | 1 126 MB |
| App cache | 589 MB |

## 3. Current HOME at baseline
`com.google.android.tvlauncher/.MainActivity` (priority=2, isDefault=true)

HOME candidates found:
- `com.google.android.tvlauncher/.MainActivity` — stock, priority 2
- `com.android.tv.settings/.system.FallbackHome` — system fallback (safety net)

No third-party launcher was installed at baseline.

## 4. Package counts
| | |
|---|---|
| System packages | 86 |
| Third-party | 9 |
| Disabled before project | 5 |

Pre-existing disables (**preserved, rule 0.7**): `com.android.camera2`,
`com.google.android.youtube.tvmusic`, `com.google.android.videos`,
`com.google.android.play.games`, `com.netflix.ninja`

Third-party: `com.tcl.browser`, `com.trt.tabii.android`, `com.wbd.stream`,
`com.bp.box`, `com.tvonline.filewizard`, `com.rma.speedtesttv`,
`com.turkcell.ott`, `com.apple.atve.androidtv.appletv`, `com.stremio.one`

## 5. Major resident processes at baseline (PSS)
| Process | PSS |
|---|---|
| com.google.android.gms | 169.0 MB |
| com.stremio.one | 81.3 MB |
| system | 74.0 MB |
| com.google.android.tvlauncher | 63.5 MB |
| com.google.android.katniss:interactor | 54.5 MB |
| com.android.vending | 53.3 MB |
| com.google.android.tts | 46.3 MB |
| com.google.android.gms.unstable | 42.6 MB |
| com.google.android.apps.mediashell | 42.0 MB |
| com.google.android.katniss:search | 38.5 MB |
| com.google.android.inputmethod.latin | 37.9 MB |
| com.android.vending:background | 37.1 MB |
| com.android.systemui | 32.7 MB |
| com.google.android.tungsten.setupwraith | 21.4 MB |

Largest storage consumers (app+data+cache): stremio 1111 MB, gms 431 MB,
tts 252 MB, turkcell.ott 252 MB, wbd.stream 178 MB, mitv.tvhome.atv 147 MB,
trt.tabii 143 MB, vending 138 MB, mediashell 109 MB, tcl.browser 107 MB.

## 6. Optimization candidates
### A — likely low-risk (acted on)
Manufacturer analytics/ACR (`com.miui.tv.analytics`, `tv.alphonso.alphonso_eula`),
manufacturer home/feed (`com.mitv.tvhome.atv`, `com.mitv.tvhome.michannel`,
`com.xm.webcontent`), one-shot setup/partner packages, backup/print/dream/
syncadapter stubs, recommendations feed, stock updater.

### B — usage-dependent (user decided)
Voice search stack (`com.google.android.katniss`, `.speech.pumpkin`,
`com.google.android.tts`) — **user explicitly opted to disable**, satisfying the
rule-11 "unless the human explicitly says otherwise" exception.
`com.amazon.amazonvideo.livingroom`, `com.google.android.tv` (Live Channels) — unused.
`com.tcl.browser` — user opted for full uninstall (third-party, no SYSTEM flag).

### C — critical / do not touch
`com.google.android.gms`, `com.google.android.gsf`, `com.android.vending`,
`com.google.android.tv.remote.service`, `com.android.bluetooth`, `com.droidlogic*`,
`com.android.systemui`, `android`, `com.android.shell`,
`com.google.android.inputmethod.latin`, `com.android.location.fused`,
`com.google.android.webview`, `com.android.providers.{settings,media,downloads,tv}`,
`com.android.tv.settings`, `com.google.android.apps.mediashell` (Cast),
`com.mitv.milinkservice`, `mitv.service`, `com.xiaomi.mitv.res`.

## 7. Network exposure (§2 — collected post-debloat, see caveat above)

**Resolved 2026-09-21.** `netstat -p` gives no PID without root, but `/proc/net/tcp[6]`
exposes each socket's owning **uid**, and `pm list packages --uid <uid>` maps that to
packages. Every listening TCP port is now attributed by uid rather than guessed.

| Port(s) | Proto | uid | Owner |
|---|---|---|---|
| 5555 | tcp6 | 2000 | `com.android.shell` — **adbd, network debugging. High risk until project end (§34)** |
| 6466, 6467 | tcp6 | 10025 | `com.google.android.tv.remote.service` — Android TV Remote |
| 8008, 8009, 8443, 9000, 10042, + 2 ephemeral | tcp6 | 10031 | `com.google.android.apps.mediashell` — Chromecast built-in |
| 6091 | tcp6 | 1000 | shared system uid; almost certainly `com.mitv.milinkservice` (Xiaomi MiLink discovery, whose documented port this is). Not provable without root — confirm at §33 by checking whether the port disappears when MiLink is disabled. |
| 11470, 12470 | tcp | — | **Stremio local HTTP server, LAN-reachable.** Absent from the 2026-09-21 scan because Stremio was not running. |
| 1900 | udp | — | SSDP / UPnP |
| 5353 | udp | — | mDNS |
| 5228 | udp6 | — | GMS push |

The 41313 / 46165 recorded on 2026-09-20 were ephemeral mediashell ports; they
reappeared as 36227 / 36585 after reboot, confirming they are per-boot and not a
separate service.

So the only unexpected LAN-facing surface is ADB on 5555 and, when Stremio runs, its
two HTTP ports. Everything else is remote control and Cast, both of which the guide
requires preserving.

## 8. Native AirPlay check (§6) — NEGATIVE
- No package matching `airplay|raop|shairport`.
- Nothing listening on 7000 / 5000 / 7100 / 49152.
- `com.mitv.milinkservice` enabled (`enabled=0`) throughout the check, so Xiaomi
  discovery was not suppressed during the test.

Conclusion: the box has **no native AirPlay receiver**. Kutu Mirror must be built.

## 9. Existing settings recorded (not changed at baseline)
- USB/network debugging: enabled by the human before the project
- Animation scales: 1.0 / 1.0 / 1.0
- HDMI-CEC: not read, not touched (rule 12)
- Full `settings list global` dump: `evidence/baseline/baseline_global_*.txt`

## 10. Uncertainties
1. ~~Ports 9000, 8443, 6091, 41313, 46165 unattributed.~~ **Resolved 2026-09-21** by
   uid attribution — see section 7. Only port 6091's exact owner inside the shared
   system uid remains unproven.
2. `/proc/swaps` unreadable — zRAM behaviour inferred from `SwapTotal` only.
3. Baseline PSS was captured on a box with days of uptime; post-change PSS was
   captured shortly after reboot. MemAvailable deltas are indicative only (§35).
4. `com.mitv.milinkservice` retained pending the Kutu Mirror decision (§33).
