# Kutu — Android TV Lite Launcher

<img width="1920" height="1080" alt="Screenshot_20260921-193812" src="https://github.com/user-attachments/assets/bab47fdf-7b7f-4f70-b230-599be2343d56" />

This is a Claude Code + ADB workflow that turns an older Android TV box into a fast, quiet appliance. It debloats the box reversibly, adds AirPlay screen mirroring, replaces the stock launcher with a lightweight liquid-glass home screen, and adds browser-based file transfer.

The whole workflow lives in **one file**, [`MIBOX-CLAUDE-MASTER-GUIDE.md`](MIBOX-CLAUDE-MASTER-GUIDE.md). You give it to Claude Code, and Claude inspects your device, adapts the steps to it and carries them out, checking with you at each safety checkpoint.

Developed and tested on:

- Xiaomi Mi Box 3 (original guide)
- Xiaomi Mi Box S 1st gen (MIBOX4) with Android TV 9 (API 28, 32-bit ARM), which is this repository's reference run

<img width="1920" height="1080" alt="Screenshot_20260921-182103" src="https://github.com/user-attachments/assets/9e7857ed-7fbe-478c-9e98-614915bc59f1" />

## What you get

| App | Package | What it does |
|---|---|---|
| **Kutu Home** | `local.kutu.home` | Liquid-glass launcher with a light/dark theme toggle, a centred dock and focus rings coloured by each app's icon. Has one permission, no network access, no background service and 0 % idle CPU |
| **Kutu Mirror** | `local.kutu.mirror` | AirPlay receiver named **Kutu**. Supports iPhone/iPad/Mac screen mirroring with sound, plus AirPlay Video. It is hardware H.264 only and holds no wakelock while idle. It never opens its screen from a bare network connection |
| **Kutu Transfer** | `local.kutu.transfer` | On-demand file transfer from any browser on your network, protected by a PIN. You can upload, download and install APKs, then close the transfer screen and the port closes with it |

Plus:

- **Reversible debloat.** Changes use only `pm disable-user`, each one has an exact undo, and a single restore script covers them all.
- **Safe stock-launcher replacement**, done with a tested rollback and only after your explicit approval.
- **Final security review and before/after performance summary** for your actual box.

<img width="1920" height="1080" alt="Screenshot_20260921-182024" src="https://github.com/user-attachments/assets/1c2bea81-a607-4cec-8e4c-45a441e78a39" />

## Quick start

1. Enable Developer Options and USB/network debugging on the TV box.
2. Install ADB and check that `adb devices` lists the box as `device`.
3. Give Claude Code `MIBOX-CLAUDE-MASTER-GUIDE.md`. If you want your own wallpaper, give it a 16:9 image too.
4. Follow Claude's checkpoints: your app usage, the initial dock, real-remote tests, the iPhone AirPlay test and HOME approval.
5. At the very end, turn debugging off.

If you **clone the whole repository**, Claude starts from the tested source under `build/` and adapts it to your device instead of writing the apps from scratch. Either way, everything is rebuilt and signed locally with your own keys. No prebuilt APK is installed.

<img width="1920" height="1080" alt="Screenshot_20260921-182056" src="https://github.com/user-attachments/assets/bcbdc616-bc92-4697-876f-b9a9b852d44e" />

## Kutu Home background

The included [`kutu-home-background.png`](kutu-home-background.png) is used exactly as supplied: no redrawing and no runtime blur. The dark theme dims it instead of shipping a second copy.

You can give Claude your own 16:9 image instead.

<img width="1920" height="1080" alt="Screenshot_20260921-182032" src="https://github.com/user-attachments/assets/888ccfa4-b419-4c02-8934-b5c0325ec194" />

## Repository layout

| Path | Content |
|---|---|
| `MIBOX-CLAUDE-MASTER-GUIDE.md` | the workflow; the only file needed to run it |
| `build/kutu-home/` | Kutu Home source (plain Java, zero dependencies) |
| `build/kutu-mirror/` | Kutu Mirror source, a GPL-3.0 fork of [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) |
| `build/kutu-transfer/` | Kutu Transfer source (plain Java, zero dependencies) and its page tests |
| `kutu-home-background.png` | default wallpaper |
| `RESTORE-ALL.ps1`, `PROJECT-PACKAGES.txt`, `PRESERVED-PRIOR-DISABLES.txt` | rollback for the reference box |
| `KUTU-HOME.md`, `KUTU-MIRROR.md`, `KUTU-TRANSFER.md` | build and test records of the reference run (background reading, not needed to run the guide) |

## Reference results (Mi Box S)

| | Before | After |
|---|---|---|
| Launcher memory | ~64 MB, plus a recommendations process | ~23 MB, no services |
| Launcher-related processes | ~120 MB | ~20–25 MB |
| AirPlay receiver | none | ~10 MB idle, 0 % CPU |
| `/data` free | 1.9 GB | 2.6–2.8 GB |

Results depend on the device. Don't expect identical numbers on other hardware.

## Important

- Do not copy Xiaomi package names to another Android TV device by hand. The workflow inspects the connected device first and adapts to it.
- Every system-package change is reversible. The restore script re-enables only what this project disabled.
- Back up the `keys/` folder that Claude generates, somewhere outside the repository. Without it the Kutu apps can never be updated in place.
- Building Kutu Mirror on Windows requires WSL2 (Ubuntu). Kutu Home and Kutu Transfer build on Windows directly.

## Credits

- Original guide and background: [bevcko16/kutu-android-tv-guide](https://github.com/bevcko16/kutu-android-tv-guide)
- AirPlay receiver upstream: [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (GPL-3.0)

## Disclaimer

This is an enthusiast project for technically comfortable users.

Android TV firmware differs by manufacturer and model. Keep rollback access available and do not disable packages whose purpose is unclear.
