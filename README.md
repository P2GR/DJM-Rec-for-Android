# DJM Rec for Android

Record your DJ sets from a compatible USB mixer directly to your Android phone.

**[Download the latest release APK](https://github.com/P2GR/DJM-Rec-for-Android/releases/latest)**

Requires Android 10 or newer, a 64-bit ARM device with USB host support, and a USB data cable.
Choose the **release APK** for everyday use. The debug APK is for testing.

## Supported devices

| Device | Recording support |
| --- | --- |
| **DJM-450, DJM-750MK2, DJM-900NXS2, DJM-A9** | Fully supported |
| DJM-V10, DJM-V5, DJM-S11 | Experimental mixer profiles; hardware testing needed |
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

## Diagnostics and privacy

Pro DJ Link and USB protocol research are available only on the
[`experimental` branch](https://github.com/P2GR/DJM-Rec-for-Android/tree/experimental),
which builds a separate app. They are not included in main releases.

Automatic Firebase diagnostics help improve mixer compatibility. Enabled by default in production
builds, Google Analytics receives bounded events for mixer connections, USB configuration, channel
selection, recording state and capture health. Crashlytics continues to receive non-fatal errors and
crash reports. Recorded audio, filenames, authentication data, USB serial numbers and advertising
IDs are not collected.

Disable **Automatic diagnostics** in Settings to stop collection. Please identify your mixer,
Android version, cable/port and what happened when reporting a problem.

Production telemetry is available in Firebase under **Analytics > Events**. The main events are
`mixer_connected`, `mixer_disconnected`, `usb_connection`, `capture_health`, `recording_state`,
`recording_saved` and `diagnostic_issue`. Register frequently used parameters such as `mixer_name`,
`profile`, `usb_product`, `health_level`, `connection_id` and `resolved_pair` as event-scoped custom
dimensions; register numeric fields such as `opened_rate`, `nonzero_bytes` and `packets_missed` as
custom metrics when needed. For raw event rows and longer-term queries, enable the Google Analytics
BigQuery export from **Firebase project settings > Integrations**.

DJM-450 capture initializes the selected MIX/REC OUT pair after activating its USB interface,
with silent eight-channel playback traffic to keep the duplex stream active. AUTO uses USB 1/2;
manual USB 3/4 and 5/6 configure their respective MIX routes. USB 7/8 retains its existing fixed
route. This path is confirmed working on DJM-450 hardware. The `capture_setup` event reports
`rate_set_result` (3 means accepted), `route_value` (266/522/778 for USB 1/2, 3/4, 5/6), and
`route_set_result` (0 means accepted). Negative results are USB errors; -999 means not attempted.
An accepted route write is not readback verification. `capture_health` confirms whether audio
actually arrives. No route-read command or guessed restoration is used for this model.

## License

MIT. Bundled libraries retain their own licenses.
