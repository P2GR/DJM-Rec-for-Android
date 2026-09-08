# Mixer validation

DJM-A9 and DJM-750MK2 recording are confirmed by the project owner. Preserve their current
wire contracts. This change has not been run with a physical mixer attached.

| Profile | Capture contract | Playback keepalive | Remaining evidence |
| --- | --- | --- | --- |
| A9 | Descriptor PCM, default USB 9/10 | 10 channels, 24-bit | Regression run on this build |
| 750MK2 | 12 channels, packed 24-bit, 96 kHz | 10 channels, 24-bit | Regression run on this build |
| 900NXS2 | 12 channels, packed 24-bit, 96 kHz | 10 channels, 24-bit | Retest current implementation; historical validation only |
| V10 | 12 channels, packed 24-bit, 44.1/48/96 kHz | 12 channels, 24-bit | Routing, rate negotiation, long session |
| V5 variants | Descriptor PCM | None configured | Descriptors for every PID, routing, long session |
| 450 | 8 channels, packed 24-bit, 48 kHz | None configured | Routing, clock startup, long session |
| S11 | if2/alt1, 10 channels, packed 24-bit, 48 kHz; REC OUT on USB5/6 | if1/alt1, 14 channels, 24-bit | Clock startup, routing, long session |
| XDJ-XZ | Descriptor PCM; master USB5/6 | No vendor keepalive configured | Actual descriptors, startup, master content, long session |
| XDJ-AZ / OPUS-QUAD / OMNIS-DUO | Descriptor PCM; master USB1/2 | No vendor keepalive configured | Actual descriptors, startup, master content, long session |

All-in-one defaults are documented in [DRIVER_PROFILE_AUDIT.md](DRIVER_PROFILE_AUDIT.md).
Vendor-only layouts without PCM descriptors remain unsupported until reviewed. RX3 is recognized
but its documented computer setup has no USB recording input; test the explanatory UI.

The [Linux USB quirk table](https://github.com/torvalds/linux/blob/master/sound/usb/quirks-table.h)
and [ALSA mixer routes](https://github.com/torvalds/linux/blob/master/sound/usb/mixer_quirks.c)
provide independent references for PCM layouts and S11 REC OUT routing. Driver compatibility is evidence
for a profile, not proof that Android host timing and this application work on that mixer.
Do not invent profiles from model names, a matching vendor ID or another mixer's channel count.

## Test each active profile

1. Connect through each supported USB audio port with a data cable; accept USB permission.
   Confirm the model, input rate, bit depth and channel count. Try deny/retry as well.
2. Play identifiable left-only, right-only, stereo and opposite-phase stereo signals.
   Confirm meters, waveform and saved playback. Audition explicit pairs; verify Auto locks
   once signal arrives (S11 Auto uses dedicated REC OUT). Verify MIX/REC OUT content, microphone inclusion and routing restoration.
3. Test the supported clock rates. Compare a 60-second source with the saved file's duration
   and pitch. Check for incorrect channel strides, swapped channels, clipping and dropouts.
4. Record WAV and FLAC; pause/resume, stop/save, play, rename and share both. Check final
   duration and channel count with an independent decoder. Gain 0 dB must preserve amplitude;
   positive gain must match metering and clip only at the documented full-scale boundary.
5. Record at least two hours with the screen locked. Test notifications, backgrounding,
   incoming/outgoing calls, app dismissal, notification Save & close, storage exhaustion,
   USB disconnect during recording, recovery after process termination,
   and reconnect. Verify recovered files and multipart boundaries where applicable.
6. Attach another USB input through a hub; ensure it cannot replace the recording source.
   Detaching another unit of the same model must not stop the active source.
7. With Automatic diagnostics enabled, find UsbDescriptors, Mixer and CaptureHealth entries in
   Bugfender. Record phone/Android/mixer firmware/cable/port/rate/format information and attach
   sample files separately when sharing hardware validation. Promote a profile only after these checks pass.

## Automated checks

`./gradlew testDebugUnitTest assembleDebug lintDebug` covers the Android build, JVM tests,
and static checks. USB parser tests include UAC1, UAC2, vendor endpoints, explicit feedback,
malformed descriptor lengths and every configured vendor capture interface.

The portable native signal regression uses the production analyzer and gain implementation:

```sh
c++ -std=c++17 -Iapp/src/main/cpp app/src/test/cpp/AudioSignalTest.cpp \
  app/src/main/cpp/WaveformAnalyzer.cpp -o audio-signal-test
./audio-signal-test
```

It checks phase inversion, single-channel audio, frequency-band separation at 44.1/48/96 kHz,
history wrap, reset and gain saturation. It does not exercise a USB controller or writer.

`UsbPcmFormatTest.cpp` is a second standalone C++ test (same include path, no analyzer source).
It checks signed 16/24/32-bit decoding, padded generic PCM, preserved legacy DJM padding and
endpoint-frequency control gating for UAC1/UAC2, wrong endpoints and truncated descriptors.
