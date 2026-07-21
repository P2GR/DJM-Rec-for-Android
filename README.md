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
| DJM-900NXS2 | `2B73:000A` | Hardware validated (v0.36) |
| DJM-750MK2 | `2B73:001B` | Vendor-capture profile configured, hardware test needed |

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
| Livestreaming | Send mixer audio plus custom artwork, rear camera, or front camera over RTMP/RTMPS |
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

## Livestreaming (Work in progress)

Connect a mixer and wait for USB monitoring, then open **Go Live**. Choose a custom image, rear
camera, or front camera. Artwork mode has no built-in image; select one through Android's document
picker. Its read permission is retained for later streams. Camera output and preview follow device
rotation. Mixer PCM is converted to stable stereo PCM16 blocks and encoded as AAC. Video is H.264
at 720p/30 fps with a 2-second keyframe interval. WAV/FLAC recording can continue while streaming.
The live status card shows PCM throughput and peak level so silent input is visible.

- YouTube: Google authorization creates and binds a broadcast, waits for active RTMP ingest,
  transitions it to live, and provides a shareable viewer link. Stopping finalizes the broadcast.
- Mixcloud: the app opens `mixcloud.com/live/new`; paste the reusable key. Its server and 320 kbps
  music-audio preset are already configured.
- Custom: paste any RTMP or RTMPS server and key.

Stream keys and provider access tokens stay in memory and are excluded from diagnostics.


## Releases

`version.properties` is the single version source. Bump it on Windows with:

```powershell
.\scripts\bump-version.ps1 -Part patch
# Or: .\scripts\bump-version.ps1 -Version 1.0.0
```

Signed APKs are published on [GitHub Releases](https://github.com/P2GR/DJM-Rec-for-Android/releases).

## License

Licensed under the MIT License. RootEncoder, Oboe, libFLAC, tinyalsa, and libusb retain their own licenses.
