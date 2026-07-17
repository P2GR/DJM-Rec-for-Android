# DJM Rec for Android

[![AGP](https://img.shields.io/badge/AGP-8.5.2-3DDC84?logo=android&logoColor=white)](https://developer.android.com/build)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![NDK](https://img.shields.io/badge/NDK-26.1-blue?logo=android&logoColor=white)](https://developer.android.com/ndk)
[![Min SDK](https://img.shields.io/badge/minSdk-29-32DE84?logo=android&logoColor=white)]()

Dedicated Android recorder for capturing DJ sets from a Pioneer DJM mixer's USB audio output.

AAudio cannot select an arbitrary pair from the DJM-A9's 12-channel UAC2 input. This app uses
root-free `libusb` isochronous capture, asks the mixer to route MIX (REC OUT) to the selected
USB pair, then extracts that pair from the wire.

## Build

```bash
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew assembleLocal      # installable local test APK, signed with ~/.android/debug.keystore
```

Needs JDK 17, NDK 26.1, CMake 3.22.1. Emulator won't work — you need a physical device with
USB host and a UAC2 mixer plugged in.

Release builds are unsigned unless `keystore.properties` is present. They never fall back to a
debug signing key. Use `assembleLocal` for phone testing; never publish its debug-signed APK.

## Features

| Module | Description |
|---|---|
| USB Recording | Capture multichannel UAC2 audio from Pioneer DJM mixers to WAV/FLAC/MP3 |
| Live monitoring | Stereo input meters and an optional CDJ-style RGB waveform |
| Recording library | Browse and manage saved sets from the app |

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
git commit -m "chore: release v0.34.0"
git tag v0.34.0
git push origin main v0.34.0
```

Configure these GitHub Actions secrets before tagging a release:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release `.jks`
- `ANDROID_STORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Create the release key once, then keep multiple secure backups. Losing this key prevents future
versions from updating existing installations:

```powershell
keytool -genkeypair -v -keystore decklab-release.jks -alias decklab -keyalg RSA -keysize 4096 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("decklab-release.jks")) | Set-Clipboard
```

Paste the clipboard value into `ANDROID_KEYSTORE_BASE64`; add the remaining values in GitHub
under **Settings > Secrets and variables > Actions**. The workflow verifies the APK signature
before publishing it.

The installed app checks `P2GR/DJM-Rec-for-Android` GitHub Releases for a newer version. Update prompts
are deferred while a recording is being prepared, recorded, or paused.

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
