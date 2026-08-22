# Changelog

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
