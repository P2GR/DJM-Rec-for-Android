# 🎚️ DJM-Rec for Android

[![AGP](https://img.shields.io/badge/AGP-8.5.2-3DDC84?logo=android&logoColor=white)](https://developer.android.com/build)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![NDK](https://img.shields.io/badge/NDK-26.1-blue?logo=android&logoColor=white)](https://developer.android.com/ndk)
[![CMake](https://img.shields.io/badge/CMake-3.22-064F8C?logo=cmake&logoColor=white)](https://cmake.org/)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-29-32DE84?logo=android&logoColor=white)]()
[![Target SDK](https://img.shields.io/badge/Target%20SDK-34-32DE84?logo=android&logoColor=white)]()
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-FF6F00)]()

**Production-grade Android app** (Kotlin + native C++/NDK) for high-fidelity multichannel
capture from a USB Audio Class 2.0 (UAC2) DJ mixer — specifically the **Pioneer DJM-A9** —
writing **WAV**, **FLAC**, or **320 kbps MP3** without letting Android's audio HAL resample
or downmix the stream.

> **Why this exists:** AAudio/AudioRecord can only ever capture channels 1/2 of a
> multichannel UAC2 interface. The DJM-A9's Master Mix sits on channels 9/10 of its
> 12-channel USB interface — unreachable through Android's audio stack. This app uses
> a root-free `libusb` raw isochronous capture path to grab the full 12-channel stream
> and extract exactly channels 9/10.

---

## 🏗️ Architecture

| Layer | Technology | What it does |
|---|---|---|
| **USB transport** | `libusb` (root-free via `libusb_wrap_sys_device`) | Raw isochronous capture from UAC2 AudioStreaming interface — claims interface, sets alt-setting 1, runs 8-transfer × 16-packet loop |
| **Channel demux** | Native C++ (`UsbIsoAudioSource`) | Strips channels 9/10 from the 12-channel PCM interleaved stream, converts to canonical int32 |
| **Audio engine** | `UsbAudioEngine` (dual-mode) | `SourceMode::UsbIso` for Pioneer multichannel devices; `SourceMode::Oboe` (AAudio exclusive) fallback for standard stereo UAC2 devices |
| **Ring buffer** | Lock-free SPSC (`RingBuffer.h`) | Bridges the native capture thread to the encoder thread without locks |
| **Meters** | `MeterCalculator.h` | Realtime dBFS peak / RMS / clip detection |
| **Encoders** | WAV, FLAC (libFLAC 1.4.3), MP3 (LAME, optional) | Container/encoder back-ends behind `AudioWriter` |
| **UI** | Jetpack Compose + Material 3 | VU meters, format selector, transport controls |
| **Service** | `RecordingService` (foreground) | Wake lock, notification, urgent-audio monitor thread |

### Two capture paths

| Path | Mode | Use case |
|---|---|---|
| **Path A** — `libusb` raw iso | `SourceMode::UsbIso` | Pioneer DJM-A9 & similar multichannel mixers (channels 9/10 extraction) |
| **Path B** — AAudio/Oboe exclusive | `SourceMode::Oboe` | Standard stereo UAC2 devices (DJM-V10, Xone:96, etc.) |

Path A reuses Android's own `UsbManager` permission model — the `UsbDeviceConnection` file
descriptor is handed to libusb via `libusb_wrap_sys_device()`, so **no root is required**.
Path B is the original AAudio MMAP exclusive-mode path (`AAUDIO_SHARING_MODE_EXCLUSIVE` +
`AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`), which bypasses AudioFlinger's mixer/resampler
entirely.

---

## 📦 Building

```bash
# Debug APK (side-by-side installable with release)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/DJM-Rec-for-Android-v0.1-debug.apk

# Release APK (minified + shrunk)
./gradlew assembleRelease
# → app/build/outputs/apk/release/DJM-Rec-for-Android-v0.1-release.apk
```

**Requirements:**
- Android Studio Koala+ or standalone JDK 17 / Gradle 8.7 / NDK 26.1 / CMake 3.22.1
- Physical device on **API 29+** with USB host support (USB-C or USB-A + OTG)
- A UAC2 DJ mixer (tested target: Pioneer DJM-A9)

> ⚠️ USB audio class devices **cannot** be tested on the Android emulator.

### Release signing

For local release builds, the default `signingConfigs.release` falls back to the
`~/.android/debug.keystore`. To sign with a real key, create `keystore.properties`
in the project root:

```properties
storeFile=/path/to/your.keystore
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

This file is gitignored — never commit your keystore or its passwords.

---

## 🚀 Automated releases (GitHub Actions)

Every push to `main`/`master` triggers the
[release workflow](.github/workflows/release.yml), which builds both the debug and release
APKs and publishes them as assets on a
[GitHub Release](https://github.com/P2GR/DJM-REC-for-Android/releases) tagged from
`versionName` in `app/build.gradle.kts` (currently `v0.1`) — no manual tagging required.
Pushing again with the same `versionName` just updates that release's assets in place;
bump `versionName` to cut a new release:

```kotlin
// app/build.gradle.kts
versionName = "0.2"
```

You can also trigger it manually from the **Actions** tab (`workflow_dispatch`), optionally
overriding the version. CI signs the release build with an auto-generated debug keystore
(the same fallback described above), so no signing secrets are required to get APKs into
Releases — add real signing secrets later if you need a Play-Store-ready release build.

---

## 🎛️ Required manual step: LAME (MP3 encoder)

`libFLAC` and `Oboe` are fetched automatically by CMake (`FetchContent`). **LAME is not**,
because it's LGPL-licensed and distributed as an autotools project without first-class CMake
support.

To enable MP3 export:

1. Download the LAME 3.100 source tarball from [SourceForge](https://sourceforge.net/projects/lame/files/lame/3.100/)
2. Copy `libmp3lame/` into `app/src/main/cpp/third_party/lame/libmp3lame/`
3. Copy `include/lame.h` into `app/src/main/cpp/third_party/lame/include/lame/lame.h`
4. Re-run the Gradle/CMake configure step

Without this step, the app still builds and runs — `Mp3Writer::open()` logs a warning and
returns `false`, and the format selector's MP3 option will gracefully fail to start recording.

---

## 📂 Module map

| File | Responsibility |
|---|---|
| `usb/UsbAudioManager.kt` | Attach/detach + permission lifecycle, `openIsoCaptureHandle()` for fd handoff |
| `usb/UsbAudioDescriptorParser.kt` | Raw USB descriptor walk (AS interface, format, iso endpoint, wMaxPacketSize) |
| `usb/UsbAudioDeviceInfo.kt` | Published device snapshot + `requiresIsoCapture` routing flag |
| `usb/PioneerVendorControl.kt` | **Experimental** Path B vendor-control stub (NOT wired in; unverified) |
| `audio/AudioEngine.kt` | JNI boundary — `open()` (AAudio) and `openUsbIso()` (libusb) |
| `service/RecordingService.kt` | Foreground service, dual capture-mode dispatch, wake lock, notification |
| `ui/MainViewModel.kt` | Compose ViewModel wiring USB device → recording lifecycle |
| `cpp/UsbIsoAudioSource.{h,cpp}` | libusb iso transfer loop + 12→2 channel demux |
| `cpp/UsbAudioEngine.{h,cpp}` | Dual-mode engine (Oboe / UsbIso), format normalization, ring buffer hand-off |
| `cpp/RingBuffer.h` | Lock-free SPSC byte ring buffer |
| `cpp/MeterCalculator.h` | Realtime dBFS peak/RMS/clip computation |
| `cpp/writers/{Wav,Flac,Mp3}Writer.*` | Container/encoder back ends behind `AudioWriter` |

---

## ⚠️ Known caveats

- **`MASTER_MIX_CHANNEL_OFFSET = 8`** (channels 9/10, 0-indexed) is an assumption based on
  the DJM-A9's published USB audio spec — it needs **real-hardware verification** with a
  physical DJM-A9.
- **Path B** (`PioneerVendorControl.kt`) is an experimental stub documenting Pioneer's
  vendor protocol. It is **not wired** into the recording flow and would require USB packet
  capture (Wireshark + USBPcap / usbmon) against Pioneer's own software to determine the
  real control-transfer values.
- **No on-device hardware test** has been performed — the codebase compiles cleanly
  (`gradlew assembleDebug` ✅) but has not been run against a physical DJM-A9.

---

## 📄 License

This project is provided as reference code. See individual third-party components
(Oboe, libFLAC, LAME, libusb) for their respective licenses.
