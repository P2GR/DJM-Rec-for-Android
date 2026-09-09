# DJM Rec for Android

Record your DJ sets from a compatible USB mixer directly to your Android phone.

**[Download the latest release APK](https://github.com/P2GR/DJM-Rec-for-Android/releases/latest)**

Requires Android 10 or newer, a 64-bit ARM device with USB host support, and a USB data cable.
Choose the **release APK** for everyday use. The debug APK is for testing.

## Supported devices

| Device | Recording support |
| --- | --- |
| **DJM-A9, DJM-750MK2** | Confirmed working by the project owner |
| DJM-900NXS2 | Previously tested; needs retesting with this version |
| DJM-V10, DJM-V5, DJM-S11, DJM-450 | Experimental mixer profiles; hardware testing needed |
| XDJ-XZ | Experimental USB capture; automatic master selection on USB 5/6 |
| XDJ-AZ, OPUS-QUAD, OMNIS-DUO | Experimental USB capture; automatic master selection on USB 1/2 |
| XDJ-RX3 | Recognized, but its documented USB connection has no recording input |
| Other USB audio interfaces | May work when they expose a compatible USB audio input |

Experimental support is not a guarantee. All-in-one units must expose a compatible USB audio
input to Android; some vendor-specific modes still need further work. RX/RX2/RR have no dedicated
profiles. For RX3, use onboard USB recording or an external USB audio interface connected to its
analog output.

## Start recording

1. Connect your mixer's **PC/Mac USB audio port** to your phone with a data cable. A USB storage
   port or Link Export connection does not provide recording audio.
2. Open DJM Rec and allow USB access. Tap the source name to choose an input if several are connected.
3. Play audio and check both meters. Automatic arming starts monitoring, not recording.
4. Open **Recording setup** to choose WAV/FLAC, gain and a USB channel pair. Use **0 dB gain** to
   preserve input level; the current default is +12 dB. Make a short test recording first.
5. Press **Record**, then **Save set** when finished. Find your files in **Sets** and `Music/DJMRec`.

## Features

- Recorder controls and setup access without scrolling the recording page.
- RGB waveform: red bass, green mids, blue highs, with blended colors and up to 60 fps scrolling.
  Waveform processing sleeps when the display is hidden.
- Stereo meters and clipping indication.
- WAV/FLAC recording, pause/resume and track markers.
- Saved-set search, playback, sharing, export, rename and deletion.
- Settings for automatic arming, waveform animation, screen wake and stop confirmation.
- Background recording with a persistent notification. **Save & close** saves and ends capture.
- Experimental livestreaming: YouTube with Google sign-in, Mixcloud and custom RTMP/RTMPS.
  Follow **Connect, Picture, Go live**, then check the service preview.
  Camera streams open a full preview with local meters, timers, gain controls and confirmed stop.
  Provider setup and real broadcasts still need validation.

Keep USB connected during a set. Force-stop, reboot, cable loss and some Android battery/call
restrictions can interrupt recording. Track markers identify moments in a stereo recording;
independent multitrack recording is not implemented.

## Experimental Pro DJ Link

Experimental **Pro DJ Link** is available in Settings: CDJ discovery and track metadata, automatic
recording markers, and a customizable now-playing livestream banner. Connect Android to the
players' LAN as well as mixer USB for audio. **DJM-A9 USB-only Link data is unverified**, and physical
NXS2/banner validation is still required. See [setup, protocol research and limitations](docs/pro-dj-link.md).

## Diagnostics and privacy

Automatic Firebase Crashlytics diagnostics help improve mixer compatibility. Enabled by default
in production builds, they send bounded USB configuration, channel activity, recording health,
non-fatal error and crash reports, not your recorded audio.

Disable **Automatic diagnostics** in Settings to stop collection. Please identify your mixer,
Android version, cable/port and what happened when reporting a problem.

## License

MIT. Bundled libraries retain their own licenses.
