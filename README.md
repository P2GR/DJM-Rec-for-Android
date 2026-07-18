# DJM Rec for Android

[![AGP](https://img.shields.io/badge/AGP-8.5.2-3DDC84?logo=android&logoColor=white)](https://developer.android.com/build)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![NDK](https://img.shields.io/badge/NDK-26.1-blue?logo=android&logoColor=white)](https://developer.android.com/ndk)
[![Min SDK](https://img.shields.io/badge/minSdk-29-32DE84?logo=android&logoColor=white)]()

Open-source Android recorder for capturing DJ sets from Pioneer DJM mixer USB audio outputs.

Android cannot select arbitrary stereo pairs from multichannel DJ mixer inputs. DJM Rec uses
root-free `libusb` isochronous capture, temporarily routes MIX/REC OUT to a USB pair, then
extracts that pair from the wire.

Download signed APKs from [GitHub Releases](https://github.com/P2GR/DJM-Rec-for-Android/releases).

## Mixer support

| Mixer | USB IDs | Status |
|---|---|---|
| DJM-A9 | `2B73:003C` | Hardware validated |
| DJM-V5 | `2B73:0058` through `2B73:005B` | Driver-derived implementation, hardware test needed |
| DJM-900NXS2 | `2B73:000A` | Driver-derived implementation, hardware test needed |
| DJM-750MK2 | `2B73:001B` | Driver-derived implementation, hardware test needed |

V5, 900NXS2, and 750MK2 profiles use each model's extracted vendor-control read format and
MIX/REC OUT source values. Route changes are read, verified, and restored when capture stops.
Unknown devices never receive Pioneer vendor routing requests.

Installed Windows driver binaries were used only for interoperability research. They remain
ignored and are not distributed by this repository.

DJM-A9 supports USB-C to USB-C or USB-B to USB-C. DJM-900NXS2 and DJM-750MK2 require
USB-B to USB-C. Cable must support data and USB host/OTG mode.

## Features

| Module | Description |
|---|---|
| USB recording | Capture multichannel UAC2 audio to WAV or FLAC |
| Live monitoring | Stereo meters and optional CDJ-style RGB waveform |
| Recording library | Browse, share, rename, and delete saved sets |
| Diagnostics | Export USB descriptors, UAC topology, mixer profile, routes, and transfer health |
| Updates | Check GitHub Releases without interrupting active recordings |

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew assembleLocal
```

Requires JDK 17, NDK 26.1, CMake 3.22.1, and an ARM64 Android device with USB host support.
Emulators cannot test physical USB audio capture.

Release builds require `keystore.properties`. They never fall back to debug signing.
`assembleLocal` creates an installable test APK signed with the Android debug key. Never publish
that APK.

## How it works

| Layer | Implementation |
|---|---|
| USB transport | Root-free raw isochronous capture through `libusb_wrap_sys_device()` |
| Mixer routing | Model profiles using verified Pioneer vendor controls |
| Channel extraction | Native C++ demux of one stereo pair from multichannel PCM |
| Audio engine | Raw USB path for supported mixers, AAudio fallback for generic stereo UAC devices |
| Encoding | WAV and FLAC (libFLAC 1.4.3) |
| Diagnostics | Structured USB/UAC/profile/session snapshot plus app logcat in release builds |
| UI | Jetpack Compose, stereo meters, optional RGB waveform, format selector |

The `WaveformAnalyzer` uses a three-band IIR filter bank to render a scrolling
CDJ-3000-style RGB waveform.

## Releases

`version.properties` is the single version source. Bump it on Windows with:

```powershell
.\scripts\bump-version.ps1 -Part patch
# Or: .\scripts\bump-version.ps1 -Version 1.0.0
```

Pushes and pull requests run CI. A tag matching `VERSION_NAME` publishes a signed APK and
SHA-256 checksum:

```powershell
git add version.properties
git commit -m "chore: release v0.35.0"
git tag v0.35.0
git push origin main v0.35.0
```

Required GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_STORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The installed app checks `P2GR/DJM-Rec-for-Android` GitHub Releases for newer versions.
Update prompts wait until recording preparation, recording, or pause has ended.

## License

Licensed under the MIT License. Oboe, libFLAC, tinyalsa, and libusb retain their own licenses.
