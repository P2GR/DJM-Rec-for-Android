# Changelog

## v0.44.1 (2026-09-25)

- Keep the device awake on every screen while a recording is running or paused: the screen
  can no longer sleep or auto-lock mid-set. Previously the USB audio stream could stall after
  the device idled and the recording stopped minutes later. The "Keep recorder screen awake"
  setting now only applies to monitoring.
- Fix the capture wake lock so its safety timeout is properly renewed, so long recordings no
  longer risk dying at the timeout.
- Add stream quality selection to the Go Live wizard: 720p (5 Mbps), 1080p (8 Mbps) and
  1440p (15 Mbps) presets named by resolution, plus a Custom mode with a resolution picker
  and a 5-30 Mbps bitrate slider. The choice is remembered between sessions and falls back
  automatically when the device encoder cannot handle the requested size.
- Show a DJM-A9 USB port diagram in onboarding: use the USB-B port at the top left of the
  mixer (or the rear USB port) and never the MULTI I/O ports at the top right.

## v0.44.0 (2026-09-24)

- Add a first-run onboarding stepper that walks through microphone, notification, camera,
  Do Not Disturb, background-usage and USB mixer permissions with clear grant status and
  skip options for the optional steps.
- Add MP3 recording at 320 kbps CBR (shine encoder) alongside WAV and FLAC, including
  high-rate input decimation, correct library listing and `audio/mpeg` sharing.
- Make background recording reliable: session opening no longer blocks intents, stale opens
  are abandoned safely, zombie notifications are dismissed, and the notification offers
  Pause/Resume plus Stop & save with proper shutdown while waiting for a mixer.
- Redesign the app shell: top bar with overflow menu and a bottom navigation with mint
  indicator (the double menu is gone), full Material 3 type scale, and animated page/step
  transitions that respect the system reduced-motion setting.
- Rework the Go Live flow: three-step wizard (Connect, Picture, Go live) with YouTube,
  Mixcloud and custom RTMP destinations, plus a camera console with exit pill, live
  indicator, viewer count, share and broadcast controls.
- Replace the waveform with the Pioneer CDJ-3000 style layered three-band rendering and
  path caching for smooth, low-overhead scrolling.
- Add a power-saving mode: fullscreen AMOLED overlay with a blinking red record dot,
  elapsed time and close/stop controls that hides the app chrome entirely.
- Add VU meter peak-hold markers with a latched dB readout.
- Move recordings to the system Trash on delete with an Undo snackbar, and show clear
  success and error feedback (cause plus recovery) for exports, renames and deletes.
- Add Support & diagnostics with manual Firebase bug reports (optional user description,
  mixer properties, redacted logs) and diagnostic report export.
- Add `djmrec://` deep links to Record, Go Live, Recordings, Settings and Support.
- Constrain the mixer gain slider to 0...+12 dB with +12 dB as the default and 0 as the
  minimum.
- Keep the recorder screen awake by default until the battery-optimization exemption is
  granted.
- Remove track markers, the Danger Zone settings section and the dead transport controls
  and device status card components.

## v0.42.3 (2026-09-16)

- Correct DJM-450 MIX/REC OUT routing to honor the selected USB pair after interface and
  sample-rate initialization, without requiring unsupported route readback.
- Enable silent eight-channel playback keepalive for DJM-450 USB capture. Physical mixer
  validation is still required; the profile remains experimental.
- Report DJM-450 setup-command results and retain native startup failures in Firebase
  diagnostics. Restarting capture on the same connection now produces fresh health snapshots.
- Add regression coverage for selected-pair routing, duplex configuration and setup telemetry.
- Fix CI and release SDK setup by skipping the unavailable legacy `tools` package.

## v0.42.2 (2026-09-14)

- Add privacy-controlled Firebase Analytics events for non-crash app, mixer connection,
  recording, streaming and recovery diagnostics.
- Report mixer identity, selected USB format and channel pair, transfer health, active USB
  channels and playback keepalive state to diagnose unsupported or silent mixer captures.
- Keep telemetry disabled until the existing Automatic diagnostics setting is enabled, and
  exclude audio, filenames, credentials, serial numbers and raw USB descriptors.

## v0.42.1 (2026-09-09)

- Remove Pro DJ Link discovery, automatic metadata markers and now-playing banners from
  the main app. These features and USB protocol research belong exclusively to the
  `experimental` branch and its separate application package.
- Retain the USB capture cleanup and removal of obsolete root/ALSA paths.
- Provision the missing repository Firebase configuration required by signed release builds.
  The v0.42.0 release build failed before producing release APKs.

## v0.42.0 (2026-09-09)

- Add opt-in Ethernet Pro DJ Link discovery and track metadata for CDJ-2000NXS2 players,
  with a shared application API for deck status and now-playing information.
- Align automatic track markers with recorded audio and add customizable top/bottom
  now-playing banners to camera and artwork livestreams.
- Add network selection and USB descriptor checks. DJM-A9 computer USB-B track metadata
  remains unverified; this integration uses a separate Ethernet connection.
- Remove obsolete root/ALSA capture paths and their settings, retaining the Android USB
  capture pipeline and diagnostics.
- USB protocol probes and raw packet research tools remain in the separate experimental
  branch and app build. Live mixer/player validation is still required.

## v0.41.3 (2026-09-08)

- Show the installed app version in Settings and add a manual update check with clear current,
  available, download, and error states.
- Download the latest signed release APK from GitHub, verify its published SHA-256 checksum and
  package identity, then open Android's installer for user confirmation.

## v0.41.2 (2026-09-08)

- Fix stretched, square-looking portrait livestreams by keeping RootEncoder's video preparation
  as the single owner of encoder dimensions and camera rotation. Portrait output is now 720x1280.
- Let YouTube automatically detect ingest resolution and frame rate so 9:16 streams are recognized
  as vertical instead of being constrained by a fixed 720p landscape declaration.

## v0.41.1 (2026-09-08)

- Improve livestream setup UI with visible broadcast title input, clearer selected options,
  simpler YouTube setup, and automatic USB mixer arming from the Go live flow.
- Lock portrait livestream output to the selected orientation instead of allowing sensor
  auto-rotation to produce landscape frames with side bars.
- Rescan USB audio on Activity resume and from the input picker so detection and monitoring
  no longer depend on opening the Mixer USB section first.

## v0.41.0 (2026-09-08)

- Cap waveform drawing at 60 fps, reduce snapshots to about 30 Hz, and stop native
  waveform analysis/polling while hidden. Background meters update once per second;
  recording, USB keepalive, streaming, and safety checks remain active.
- Resolve USB rates from packet cadence instead of short wall-clock estimates. Never
  use uncertain measurements such as 99,271 Hz as a recording/encoder format.
- Convert high-rate mixer PCM to 44.1/48 kHz for streaming with anti-alias filtering.
  Report separate audio and camera preparation errors and lower artwork video to 15 fps.
- Replace the streaming form with Connect, Picture, and Go live steps, a fixed action
  button, mixer meters, and retryable memory-only credentials. Preserve planned YouTube
  broadcasts after pre-live failures and start authorization lifecycle from current state.
- Report silent selected channels accurately even when USB packets contain low-level noise.
- Real broadcast playback and measured battery savings still require device validation.

## v0.40.2 (2026-09-08)

- Stop repeating healthy Bugfender capture reports. Log connection/health changes,
  one settled snapshot, and initial payload/signal detection; retain fault reporting.
- Replace waveform morphing with cursor-based scrolling on the display frame clock.
  Preserve historical peaks, protect concurrent snapshots, and keep live history when
  recording starts. Waveform updates no longer recompose the full recording page.
- Use additive RGB shading: red bass, green mids, blue highs; mixed bands produce
  yellow, cyan, magenta, and white. Remove the flickering white waveform outline.
- Open active camera streams in a full recording workspace with local stereo meters,
  gain, stream/record timers, camera switching, framing guides, and confirmed stop.
  These controls and overlays are not included in the video sent to viewers.
- Give queued livestream PCM frames their own buffers and sample-based timestamps.
  Detect stalled PCM and outgoing AAC/H.264, clean up unexpected disconnections,
  and preserve the stream timer across reconnects.
- Add regression coverage for quiet diagnostics, RGB colors, waveform timing and
  concurrent history, PCM timestamps, and stalled media delivery.
- Device rendering performance and end-to-end camera broadcasts still need physical
  validation; livestreaming remains experimental.

## v0.40.1 (2026-09-08)

- Add detailed Bugfender mixer connection reports: detected model, USB IDs, selected
  profile, transport, routing defaults, interfaces, and endpoints.
- Report advertised channel counts, PCM formats, sample rates in kHz, and clock
  capabilities; distinguish profile defaults from descriptor and runtime evidence.
- Measure raw input activity per channel before gain and stereo selection, with
  approximate one-second peak windows, dBFS levels, and snapshot age.
- Correlate connection, permission, capture, and failure logs using a connection ID
  and code locations, including unknown devices to help diagnose future support.
- Bound recurring health/error reports and retain the existing diagnostics opt-out.
- Add channel-activity and diagnostic-report regression tests and contributor guidance.
- Generate GitHub release notes directly from this changelog.

## v0.40.0 (2026-09-08)

- Redesign the recording workspace with accessible input/setup controls, stereo
  meters, smoother three-band waveforms, and a layout for wider screens.
- Improve automatic arming, USB format selection, per-mixer channel preferences,
  recording saves/recovery, and the notification's Save & close action.
- Improve recorded-set search, playback, seeking, sharing, export, rename/delete,
  and track markers within stereo recordings.
- Add customization settings and improve experimental livestreaming and YouTube
  authorization/broadcast handling.
- Expand mixer profiles and all-in-one recognition with descriptor-based capture
  where supported. DJM-A9 and DJM-750MK2 remain the hardware-confirmed devices;
  other profiles require physical testing. Recognition alone does not confirm capture.
- Add automatic Bugfender diagnostics for debug and release builds, crash reporting,
  release mapping uploads, and an in-app opt-out. Audio payloads are not uploaded.
- Simplify the README with supported-device status and recording guidance.

## v0.36.6 (2026-08-22)

- Correct the input-meter scale so every dB label aligns with the measured peak position
- Add the installed DJM-S11 Windows driver-derived VID/PID profile (`2B73:0037`)
- Add the S11 vendor-class 14-channel playback / 10-channel capture contract and playback
  keepalive required by its clocking
- Route S11 MIX/REC OUT to USB 5/6 with the validated Pioneer vendor request

## v0.36.5 (2026-08-14)

- Update the live waveform independently at 50 fps for smoother visual response
- Keep meter, health, and notification polling on their existing schedules
- Stop waveform polling automatically when monitoring/recording ends

## v0.36.4 (2026-08-14)

- Add driver-derived DJM-V10 and DJM-450 capture profiles and Windows driver archives
- Keep DJM-A9 recording hardware-validated; mark other mixer profiles for physical testing
- Name normal recordings `mix_YYYYMMDD_HHmmss` without a misleading part suffix
- Retain part suffixes only for genuine WAV rollover files

## v0.36.2 (2026-07-24)

- Add RTMP/RTMPS livestreaming with direct DJM USB audio
- Add optional rear/front camera and custom artwork video modes
- Correct camera and preview rotation at startup and while device orientation changes
- Add persisted custom artwork selection with sampled preview; remove built-in artwork
- Add YouTube, Mixcloud, Twitch, TikTok, and custom RTMP destination setup
- Add Google authorization with automatic YouTube broadcast/RTMPS provisioning
- Map public Google OAuth client IDs to local and release build variants
- Show installed package and signing SHA-1 when Google OAuth registration is missing
- Start and complete YouTube broadcasts after confirming active RTMP ingest
- Add shareable YouTube watch links and broadcast lifecycle status
- Feed AAC with stable stereo PCM16 blocks and expose mixer-audio telemetry
- Fix black camera preview caused by stream startup clearing its pending SurfaceView
- Upgrade RootEncoder to 2.7.2 for monotonic A/V timestamps and GL lifecycle fixes
- Upgrade Android build tools for Kotlin 2.3-compatible release shrinking
- Upgrade Compose runtime and lint rules for Kotlin 2.3 metadata support
- Require sent AAC and H.264 packets before reporting a stream as live
- Report mixer PCM, AAC, camera, or H.264 startup failures directly in stream status
- Add Twitch device authorization, stream-key retrieval, and official ingest discovery
- Keep WAV/FLAC recording available while streaming
- Correct DJM-750MK2 capture framing to 12-channel packed 24-bit PCM

## v0.35.0 (2026-07-18)

- Add driver-derived DJM-V5, DJM-900NXS2, and DJM-750MK2 capture profiles
- Add release-safe USB descriptor, UAC topology, route protocol, and native session diagnostics
- Add read-only route probes when capture is idle and live transfer health verdicts
- Verify and restore mixer routes without changing unknown devices
- Keep recording formats focused on WAV and FLAC

## v0.34.1 (2026-07-17)

- Restore Gradle wrapper execution on Linux CI runners
- Configure stable release signing for installable GitHub APKs

## v0.34.0 (2026-07-17)

- USB isochronous capture via libusb (root-free FD handoff)
- DJM-A9 vendor control protocol for MIX routing
- Duplex playback activation (silent OUT stream to keep mixer clock alive)
- Multi-strategy fallback ladder for non-zero audio capture
- WAV/FLAC encoding
- Optional battery-saving live waveform setting
- Root USB assist for rooted devices (Type-C role forcing)
- In-app diagnostic log export
- GitHub Releases update checker
