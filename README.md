# DJM Rec for Android

[![AGP](https://img.shields.io/badge/AGP-8.5.2-3DDC84?logo=android&logoColor=white)](https://developer.android.com/build)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![NDK](https://img.shields.io/badge/NDK-26.1-blue?logo=android&logoColor=white)](https://developer.android.com/ndk)
[![Min SDK](https://img.shields.io/badge/minSdk-29-32DE84?logo=android&logoColor=white)]()

DJ toolbox for Android — record multichannel USB audio from a Pioneer DJM mixer, play samples
through an RMX-1000-style effects simulator, and detect BPM from live audio.

AAudio cannot select an arbitrary pair from the DJM-A9's 12-channel UAC2 input. This app uses
root-free `libusb` isochronous capture, asks the mixer to route MIX (REC OUT) to the selected
USB pair, then extracts that pair from the wire.

## Build

```bash
./gradlew assembleDebug    # → DJM-Rec-for-Android-v0.33-debug.apk
./gradlew assembleRelease  # → DJM-Rec-for-Android-v0.33-release.apk
```

Needs JDK 17, NDK 26.1, CMake 3.22.1. Emulator won't work — you need a physical device with
USB host and a UAC2 mixer plugged in.

Release builds sign with `~/.android/debug.keystore` by default. Drop a `keystore.properties`
in the project root to use a real key (the file is gitignored).

## Features

| Module | Description |
|---|---|
| USB Recording | Capture multichannel UAC2 audio from Pioneer DJM mixers to WAV/FLAC/MP3 |
| RMX Simulator | Play one-shot samples through a full RMX-1000-style effects chain with beat-synced looping, key shift, and scene/release FX |
| BPM Detect | Tap tempo, manual BPM, or auto-detect BPM from the built-in mic |

## Releases

Every push to `main` kicks off the [workflow](.github/workflows/release.yml), which builds
debug and release APKs and attaches them to a GitHub Release tagged from `versionName` in
`build.gradle.kts` (currently `v0.2`). Same version on multiple pushes just updates the
existing release's assets. Bump `versionName` to cut a new one.

## MP3 support

FLAC and Oboe are fetched by CMake automatically. LAME is not — it's LGPL and distributed
as an autotools project, so you need to drop the source in yourself:

1. Grab [LAME 3.100](https://sourceforge.net/projects/lame/files/lame/3.100/)
2. Copy `libmp3lame/` → `app/src/main/cpp/third_party/lame/libmp3lame/`
3. Copy `include/lame.h` → `app/src/main/cpp/third_party/lame/include/lame/lame.h`
4. Rebuild

Without it the app still works — the MP3 option just won't start recording.

## How it works

| Layer | What |
|---|---|
| USB transport | `libusb` raw isochronous capture (root-free, uses Android's existing `UsbManager` permission) |
| Mixer routing | Verified DJM-A9 vendor controls route MIX (REC OUT) to the selected USB pair |
| Channel extraction | Native C++ demuxes the selected pair from the 12-channel PCM stream |
| Audio engine | Dual-mode: `libusb` iso path for multichannel mixers, AAudio exclusive fallback for plain stereo devices |
| Encoding | WAV, FLAC (libFLAC 1.4.3), MP3 (LAME, optional) |
| UI | Jetpack Compose + Material 3 — stereo VU meter, RGB waveform, format selector |

There's also a `WaveformAnalyzer` (three-band IIR filter bank → red/green/blue waveform)
rendered as a scrolling CDJ-3000-style display while recording.

## DJM-A9 routing

The app defaults to USB channels 9/10, reads their current mixer route, temporarily selects
`MIX (REC OUT without MIC)`, verifies the change, and restores the previous route after capture.
The request layout and route table come from Pioneer `DJM-A9_Setup.dll` version 1.100.002.0.
Routing is enabled only for VID `0x2B73`, PID `0x003C`; other mixers remain unchanged.

## License

Source is provided as reference. Oboe, libFLAC, LAME, and libusb each carry their own
licenses.
