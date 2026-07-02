# DJM-Rec for Android

[![AGP](https://img.shields.io/badge/AGP-8.5.2-3DDC84?logo=android&logoColor=white)](https://developer.android.com/build)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![NDK](https://img.shields.io/badge/NDK-26.1-blue?logo=android&logoColor=white)](https://developer.android.com/ndk)
[![Min SDK](https://img.shields.io/badge/minSdk-29-32DE84?logo=android&logoColor=white)]()

Record multichannel USB audio from a Pioneer DJM-A9 mixer to WAV, FLAC, or MP3 on Android.

AAudio can only reach channels 1/2 of a UAC2 interface. The DJM-A9 puts its master mix on
channels 9/10. This app works around that by using `libusb` to do raw isochronous capture —
pulling all 12 channels off the wire and extracting just the stereo pair we need, with no root
required.

## Build

```bash
./gradlew assembleDebug    # → DJM-Rec-for-Android-v0.2-debug.apk
./gradlew assembleRelease  # → DJM-Rec-for-Android-v0.2-release.apk
```

Needs JDK 17, NDK 26.1, CMake 3.22.1. Emulator won't work — you need a physical device with
USB host and a UAC2 mixer plugged in.

Release builds sign with `~/.android/debug.keystore` by default. Drop a `keystore.properties`
in the project root to use a real key (the file is gitignored).

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
| Channel extraction | Native C++ demuxes channels 9/10 from the 12-channel PCM stream |
| Audio engine | Dual-mode: `libusb` iso path for multichannel mixers, AAudio exclusive fallback for plain stereo devices |
| Encoding | WAV, FLAC (libFLAC 1.4.3), MP3 (LAME, optional) |
| UI | Jetpack Compose + Material 3 — stereo VU meter, RGB waveform, format selector |

There's also a `WaveformAnalyzer` (three-band IIR filter bank → red/green/blue waveform)
rendered as a scrolling CDJ-3000-style display while recording.

## Caveats

The channel offset for the DJM-A9 master mix (`MASTER_MIX_CHANNEL_OFFSET = 8`, i.e. channels
9/10) is based on the DJM-A9's published USB spec and hasn't been verified against real
hardware yet. Likewise the vendor-control stub in `PioneerVendorControl.kt` is experimental
and not wired into the recording path.

## License

Source is provided as reference. Oboe, libFLAC, LAME, and libusb each carry their own
licenses.
