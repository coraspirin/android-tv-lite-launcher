# SECURITY-REVIEW.md — guide section 34

Reviewed 2026-09-21 against the actual box: Mi Box S (MIBOX4 / oneday), Android 9,
API 28, fingerprint `Xiaomi/oneday/oneday:9/PI/3933:user/release-keys`.

Every listening socket below is attributed by owning **uid** read from
`/proc/net/tcp[6]` and `/proc/net/udp[6]`, then mapped to a package. Nothing here is
guessed.

## Summary

| Severity | Count | |
|---|---|---|
| Critical | 0 | |
| High | 2 | network ADB (closes at section 37); stale firmware (residual, unfixable) |
| Medium | 2 | AirPlay PIN off; third-party install permission |
| Low | 4 | unknown-sources setting; two exported control shims; cleartext AirPlay |
| Informational | 3 | Cast, remote-service and mDNS surfaces, all required by guide rule 11 |

**No automatic fixes were applied.** Everything that could be "hardened" either belongs
to section 37 (ADB), is a deliberate default the guide sets (PIN), or would change how
an app the human uses actually works, which guide 34 forbids changing silently.

## Listening ports, fully attributed

### TCP

| Port | uid | Owner | Verdict |
|---|---|---|---|
| 5555 | 2000 | `com.android.shell` — **adbd, network debugging** | **High**, see F1 |
| 6466, 6467 | 10025 | `com.google.android.tv.remote.service` | Informational — the physical remote; rule 11 requires preserving it |
| **7000** | 10067 | **`local.kutu.mirror`** — the AirPlay receiver | see F3, F6 |
| 8008, 8009, 8443, 9000, 10042 | 10031 | `com.google.android.apps.mediashell` — Chromecast built-in | Informational; rule 11 requires preserving Cast |
| 38333, 45899 | 10031 | same, per-boot ephemeral | Informational |

### UDP

| Port | uid | Owner |
|---|---|---|
| 1900 | 10031 | mediashell — SSDP/UPnP for Cast |
| 5353, 34947, 35644 | 1020 | system mDNS / `NsdService` — carries Kutu Mirror's `_airplay._tcp` and `_raop._tcp` records |
| 5228 | 10004 | `com.google.android.gms` — Play push |
| 33713, 50090, 56926 | 10018 | `com.apple.atve.androidtv.appletv` |
| 44453, 48191 | 10031 | mediashell ephemeral |

### Two baseline uncertainties now closed

- **Port 6091 is gone.** `BASELINE-SUMMARY.md` §7 could only say it was "almost
  certainly `com.mitv.milinkservice`" because the shared system uid hid it. MiLink was
  disabled under section 33 and the port disappeared, which proves the attribution.
- **41313 / 46165** were already shown to be per-boot mediashell ports; this scan shows
  a different pair again (38333 / 45899), consistent with that.

**Added after this review: TCP 8787, `local.kutu.transfer`** - open only while a transfer
session is on screen, behind a per-session PIN, and closed again on BACK or after a
10-minute idle timeout. It must be attributed by uid in the table above when this review
is next redone. Kutu Transfer also holds `REQUEST_INSTALL_PACKAGES`, which makes it the
second app on the box able to install an APK - see F4 - though the install is always a
deliberate press on the TV and never initiated over the network. See KUTU-TRANSFER.md.

**Widened after that, on request: Kutu Transfer now holds `READ_EXTERNAL_STORAGE` and
`WRITE_EXTERNAL_STORAGE`.** The first version could only see its own external files dir,
which needs no permission on API 28. That answered "put a file on the box" but not "which
of the box's files do I want", so the browser page became a folder browser and the whole
internal card is now reachable - read *and* write - for the length of a session. This is
the widest thing any app in this project holds, and it is deliberate: it was asked for
explicitly. What bounds it is unchanged and is now doing more work than before - the
per-session PIN, the private-peer filter, the 10-minute idle timeout, and the fact that the
socket exists only while the session screen is on the TV. The permissions are requested at
runtime from that screen, so a refusal leaves a working app scoped to its own folder, which
is exactly how it shipped first. Recursive delete is deliberately not offered: a directory
is removed only when it is already empty.

Stremio's LAN-facing HTTP ports (11470 / 12470) are absent here only because Stremio was
not running. They come back whenever it does — third-party behaviour, unchanged by this
project, noted so it is not mistaken for something new.

## Findings

### F1 — Network ADB open on port 5555 · **High**

`adb_enabled=1`, `development_settings_enabled=1`, adbd listening on all interfaces.
Anything on the LAN can take a full shell, install packages and read app data.

This is the single largest exposure on the box, and it exists because this project needs
it. Guide 37 closes it as the last action. **Until the human turns debugging off, the
box should be treated as trusting its LAN completely.**

Remediation: section 37 — the human disables USB/network debugging in Developer Options.

### F2 — Firmware security patch level 2021-07-05 · **High (residual, unfixable)**

The box has been out of support since 2021 and the patch level is roughly five years
stale. Every unpatched platform CVE since then applies.

This cannot be remediated inside the project rules: there is no vendor OTA left, and
rules 2 and 3 forbid custom firmware, unlocking and touching `/system`. It is inherent
to keeping this hardware in service and is recorded, not fixed.

### F3 — AirPlay PIN disabled by default · **Medium**

`Prefs.REQUIRE_PIN` defaults to false, which is what guide 12 specifies. Consequence:
**any device on the same LAN can start mirroring to this box without being challenged.**
On a home network with known devices this is a convenience trade the guide deliberately
makes. On a shared or untrusted network it is a real exposure.

The PIN path is built and reachable — `onDisplayPin` mints a code and MirrorActivity
displays it. Kutu Mirror ships no settings UI, so turning it on means writing the
preference directly:

```
adb shell am force-stop local.kutu.mirror
# set require_pin=true in local.kutu.mirror's shared_prefs, then restart the receiver
```

Recommended only if the box moves to a network the human does not control.

### F4 — `com.tvonline.filewizard` holds `REQUEST_INSTALL_PACKAGES` · **Medium**

Version 1.5.5, installed **from the Play Store** (`installerPackageName=com.android.vending`)
on 2025-12-14 — it predates this project and was not sideloaded.

A file manager with install permission can install any APK the human opens with it. That
is the app's advertised function, so guide 34 explicitly says to report it rather than
revoke it: revoking would break what it is for.

Remediation, if wanted: Settings → Apps → File Wizard → "Install unknown apps" → off.

Only two packages on the box hold this permission: `android` (the framework) and this one.

### F5 — `install_non_market_apps = 1` · **Low**

Unknown sources is allowed at the legacy global setting. On API 28 the per-app
`REQUEST_INSTALL_PACKAGES` permission is the real gate, and only F4's app holds it, so
this setting adds little on its own. It also has to stay for the Kutu apps to be
installed or updated by hand.

### F6 — `ReceiverControlActivity` and `ReceiverStopActivity` exported · **Low**

Both are exported so Kutu Home, a separate package, can reach them. Each is
`Theme.NoDisplay`, takes no parameters, accepts no commands, does exactly one fixed
thing and finishes before it can be drawn.

Worst case: another app on the box starts or stops an AirPlay receiver. No data is
exposed and nothing is destroyed — a nuisance, not a breach. The stop half is a
disclosed deviation from guide 11, which describes a start shim only; it exists because
the human asked for an on/off switch in the launcher.

`MirrorActivity` itself is **not** exported, carries no launcher category, and is
`excludeFromRecents` + `autoRemoveFromRecents`.

### F7 — `usesCleartextTraffic="true"` on Kutu Mirror · **Low**

AirPlay's control channel is plain HTTP; the receiver cannot work without it. The
traffic is LAN-local, to the box's own port 7000, from an Apple device the human
initiated the session from. No credentials cross it.

### F8 — Port 7000 opens no UI on probe · **verified, no finding**

Guide 10's rule was tested directly: five bare TCP connect/close cycles, one held idle
connection, and `GET /info`, `/server-info`, `/playback-info` against port 7000 with no
session in progress.

Result: **zero** `MirrorActivity` launches, the foreground app (`local.kutu.home`)
was never interrupted, no black screen, receiver still working afterwards. Upstream
would have failed this test — it opened its UI from `onConnectionInit()`.

## Checklist items with no finding

| Item | Result |
|---|---|
| Kutu exported components | 1 in Kutu Home (its launcher activity), 2 in Kutu Mirror (F6). `MirrorActivity`, `AirPlayService` and `BootReceiver` are all `exported=false`. |
| Permissions | Kutu Home: **1** (`REQUEST_DELETE_PACKAGES`). Kutu Mirror: **6** — the 5 from guide 12 plus `ACCESS_NETWORK_STATE`, which ExoPlayer's manifest reinstates (disclosed in `KUTU-MIRROR.md`). |
| `debuggable` flag | Neither app is debuggable. `flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]` on both — note the absence of `DEBUGGABLE` and `ALLOW_BACKUP`. |
| `allowBackup` | Disabled on both, confirmed by the same flag dump. |
| Screen / frame storage | Kutu Mirror writes only `filesDir/airplay.pem` (its AirPlay key) and SharedPreferences. No bitmap, frame or screenshot cache exists in the source: the downloader, `FileProvider` and cover-art cache were all removed. Neither app has an `Android/data` directory on external storage — confirmed absent on device. |
| Recents | `MirrorActivity` is excluded from and auto-removed from Recents, so a finished mirroring session leaves no thumbnail of the sender's screen. |
| Unnecessary services | Kutu Mirror runs exactly one foreground service, and only while the receiver is on. Kutu Home runs **zero** services. |
| Idle wakelocks | None. The partial wakelock is taken only for a live session and released on every teardown path. |

## What could not be checked

`/data/data/<pkg>` contents cannot be enumerated without root, and `run-as` does not
work against release-signed, non-debuggable builds. World-readability of files inside
each app's private directory is therefore asserted from the source and from Android's
default private-mode file creation, not from a directory listing. Both apps use
`MODE_PRIVATE` SharedPreferences and no `MODE_WORLD_*` flag appears anywhere in either
source tree.

## Residual risk after section 37

Once debugging is off, F1 closes and the standing risks are:

1. **F2** — stale firmware. Inherent to the hardware. The practical mitigation is to
   keep the box on a normal home LAN and not expose it to the internet.
2. **F3** — AirPlay PIN off. Acceptable on a trusted home network; turn the PIN on if
   that stops being true.
3. **F4** — a Play-installed file manager can install APKs.
