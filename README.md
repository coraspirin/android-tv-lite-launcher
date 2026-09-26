
  
<img width="1517" height="849" alt="resim1" src="https://github.com/user-attachments/assets/1f834d22-d0a2-4a14-9865-59fb3375ee92" />

A Claude Code + ADB workflow for optimizing older Android TV devices, adding AirPlay Screen Mirroring and building a lightweight custom launcher.

Originally developed and tested on:

- Xiaomi Mi Box 2/3
- Android TV 9

## Full step-by-step guide

- 🇹🇷 [Türkçe rehber](https://berksim.substack.com/p/mi-box-android-tv-rehberi)
- 🇬🇧 [English guide](https://berksim.substack.com/p/mi-box-android-tv-guide-optimise)

### MIBOX-CLAUDE-MASTER-GUIDE.md

<img width="1513" height="853" alt="resim2" src="https://github.com/user-attachments/assets/a52f4f4e-9624-42ed-b0c6-b199929f65bc" />

Download this file and give it to Claude Code after your Android TV device is connected over ADB. It is the only file needed: the final design and every lesson from the reference run are in it. If you clone the whole repository, Claude starts from the tested source under `build/` and adapts it to your device.

Claude uses it to:

- inspect your specific Android TV device
- create a read-only baseline
- build a reversible rollback system
- perform conservative debloating
- build and test Kutu Mirror (AirPlay screen mirroring + AirPlay Video)
- build and test Kutu Home (liquid-glass launcher with light/dark theme)
- build and test Kutu Transfer (PIN-protected browser file transfer)
- safely replace the stock launcher where appropriate
- perform final security and performance checks

`KUTU-HOME.md`, `KUTU-MIRROR.md` and `KUTU-TRANSFER.md` are the build and test records of the reference run on a Mi Box S; they are not needed to run the guide.

<img width="1502" height="820" alt="resim3" src="https://github.com/user-attachments/assets/27a2305f-068c-4ebd-b98d-9388e2cf1104" />

### Kutu Home background

Download the included background image and give it to Claude Code together with the master guide.

You can also use your own 16:9 image instead.

<img width="1594" height="871" alt="resim5" src="https://github.com/user-attachments/assets/3196156e-eda2-4328-b9cf-89a7b75fbd0c" />

## Important

Do not manually copy Xiaomi package names to another Android TV device.

The master workflow first inspects the actual connected device and adapts the process accordingly.

System-package changes are designed to remain reversible.

<img width="1508" height="840" alt="resim4" src="https://github.com/user-attachments/assets/8bd22a20-9868-4169-ba05-4b7bd7575177" />

## Disclaimer

This is an enthusiast project for technically comfortable users.

Android TV firmware differs by manufacturer and model. Keep rollback access available and do not disable packages whose purpose is unclear.
