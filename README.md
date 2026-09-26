
  
<img width="1920" height="1080" alt="Screenshot_20260921-193812" src="https://github.com/user-attachments/assets/bab47fdf-7b7f-4f70-b230-599be2343d56" />

A Claude Code + ADB workflow for optimizing older Android TV devices, adding AirPlay Screen Mirroring and building a lightweight custom launcher.

Originally developed and tested on:

- Xiaomi Mi Box 2/3
- Android TV 9

<img width="1920" height="1080" alt="Screenshot_20260921-182103" src="https://github.com/user-attachments/assets/9e7857ed-7fbe-478c-9e98-614915bc59f1" />

## Full step-by-step guide

- 🇹🇷 [Türkçe rehber](https://berksim.substack.com/p/mi-box-android-tv-rehberi)
- 🇬🇧 [English guide](https://berksim.substack.com/p/mi-box-android-tv-guide-optimise)

### MIBOX-CLAUDE-MASTER-GUIDE.md

<img width="1920" height="1080" alt="Screenshot_20260921-182024" src="https://github.com/user-attachments/assets/1c2bea81-a607-4cec-8e4c-45a441e78a39" />

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

<<<<<<< HEAD
`KUTU-HOME.md`, `KUTU-MIRROR.md` and `KUTU-TRANSFER.md` are the build and test records of the reference run on a Mi Box S; they are not needed to run the guide.

<img width="1502" height="820" alt="resim3" src="https://github.com/user-attachments/assets/27a2305f-068c-4ebd-b98d-9388e2cf1104" />
=======
<img width="1920" height="1080" alt="Screenshot_20260921-182056" src="https://github.com/user-attachments/assets/bcbdc616-bc92-4697-876f-b9a9b852d44e" />
>>>>>>> 833bc63e049bbc237ddc2bd5001695408806d704

### Kutu Home background

Download the included background image and give it to Claude Code together with the master guide.

You can also use your own 16:9 image instead.

<img width="1920" height="1080" alt="Screenshot_20260921-182032" src="https://github.com/user-attachments/assets/888ccfa4-b419-4c02-8934-b5c0325ec194" />

## Important

Do not manually copy Xiaomi package names to another Android TV device.

The master workflow first inspects the actual connected device and adapts the process accordingly.

System-package changes are designed to remain reversible.

## Disclaimer

This is an enthusiast project for technically comfortable users.

Android TV firmware differs by manufacturer and model. Keep rollback access available and do not disable packages whose purpose is unclear.
