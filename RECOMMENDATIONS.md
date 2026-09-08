# DJM Rec follow-up work

## Before the next production release

- **Validate Bugfender on devices.** Run `docs/BUGFENDER.md` on debug and signed release APKs.
  Check portal delivery, release mapping, offline queues and opt-out after force-stop/relaunch.
  Native crashes/ANRs currently provide Android exit metadata, not symbolicated C++ traces.

- **Validate mixer hardware.** Run `docs/HARDWARE_VALIDATION.md` on A9 and 750MK2 as regressions,
  then V10, V5 variants, 900NXS2, 450 and S11. Save diagnostics and independently decoded recordings.
  Keep experimental labels until routing, stereo content, timing and long sessions pass.
- **Validate restored S11 support.** Its ALSA-derived profile now uses capture interface 2,
  playback interface 1, 48 kHz packed 24-bit and dedicated REC OUT on USB5/6. Clock startup,
  routing and long-session behavior still need physical validation.
- **Visually test the recorder.** No usable emulator system image or physical Android device was
  available for this change. Check portrait/landscape, small screens, large fonts, TalkBack,
  setup sheet, error states and transport visibility. A two-column wide layout is implemented.
  Installing an emulator image also failed with the available SDK manager.
- **Run real recording lifecycle tests.** Cover permission denial, screen lock, backgrounding,
  calls, task dismissal, notification Save & close, cable removal during save, storage exhaustion,
  process termination, recovery, file splitting and concurrent livestreaming. Check marker timing
  before/after pause and rollover. Confirm WAV/FLAC duration/sample integrity independently.
- **Validate Google/YouTube end to end.** Verify Android package/signing SHA configuration,
  enabled API, consent-screen publishing and channel live eligibility. Test login cancellation,
  expired tokens, network retry and broadcast completion after app dismissal. Google authorization
  and RTMP exist; production streaming is not certified.

## Capture reliability

- **Unify session transitions.** Monitoring and recording still construct similar service intents
  separately. Centralize the source/format configuration and stop/open sequence; test rapid taps,
  reconnects and pair changes so stale work cannot restart the wrong session.
- **Hardware-test the new input picker.** Verify hub selection, permission denial/retry and reconnect.
  Selection now stops monitoring first and is locked during recording, saving and streaming.
- **Validate all-in-one capture.** XZ, AZ, OPUS-QUAD and OMNIS-DUO now have verified identities and
  documented master-pair defaults, with descriptor-driven capture. Obtain actual descriptors before
  adding vendor-only wire layouts. RX3's documented USB setup has no recording input. See
  `docs/DRIVER_PROFILE_AUDIT.md` for package hashes, source links and remaining limitations.
- **Remove callback allocations.** `UsbAudioEngine.cpp` grows scratch vectors in audio callbacks.
  Allocate bounded buffers before capture and test packet-size changes and long-session load.
- **Strengthen descriptor parsing.** Add fuzz/property tests for topology parsing, truncated
  class-specific descriptors, continuous sample-rate ranges and unsupported PCM encodings.
- **Keep Kotlin/native profiles synchronized.** Generate both registries from one reviewed source,
  or add a parity test for IDs, routing, wire formats and playback keepalive requirements.
- **Review routing side effects.** Verify that fallback routing and restoration affect only intended
  outputs, including failure and disconnect paths, before promoting experimental mixers.

## Product polish and maintenance

- Extend the implemented post-save/library flow with bulk selection, persistent export jobs,
  progress/cancellation, audio-focus-aware playback and custom save folders.
- Add indexing for large legacy libraries and marker cleanup on file deletion.
- Assess whether new installs should default to unity gain. The previous +12 dB default remains;
  a one-tap 0 dB reset is implemented.
- Consolidate user-facing strings into Android resources for localization and consistent wording.
- Address remaining Android lint warnings and review target SDK / distribution requirements before
  store publication; do not equate an installable debug APK with a release certification.
- Add CI jobs for the native signal regression and representative Compose screenshot tests.

## Multitrack and livestream roadmap

- True multitrack is feasible where mixer USB routes expose independent channels. Current capture
  extracts one stereo pair and writers encode stereo. Add shared-clock, bounded-queue distribution
  before stereo extraction, per-pair writers, a session manifest, synchronized pause/rollover/recovery
  and per-track meters. Twelve channels at 96 kHz/24-bit need about 12.4 GB/hour uncompressed.
  Validate sustained storage throughput and routing first. Current Track markers are timestamps,
  not isolated audio tracks or stems.
- Add marker labels, waveform seeking, nondestructive trims and CUE/JSON export. Preserve original
  audio and link multipart track lists into one session.
- Add built-in stream artwork, remembered non-secret destination preferences, service-owned drafts
  and credential reauthorization. Other platform SSO needs a supported provider API;
  Mixcloud/custom RTMP continue using user-provided stream keys.
- Move remaining blocking recovery/open/error-finalization work off the main thread with one session owner.

## Platform limits

Foreground capture protects ordinary background recording; it cannot defeat force-stop, reboot,
USB loss or every vendor battery policy. Raw USB avoids Android microphone sharing, but
Android-managed USB input can be silenced during calls. Report interruptions and retain recoverable audio.

References: [audio-input sharing](https://developer.android.com/media/platform/sharing-audio-input),
[foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[Google authorization](https://developer.android.com/identity/authorization),
[ALSA USB contracts](https://github.com/torvalds/linux/blob/master/sound/usb/quirks-table.h),
[ALSA mixer routes](https://github.com/torvalds/linux/blob/master/sound/usb/mixer_quirks.c).

## Verification for this change

Verification on this checkout: 50 JVM tests pass; native signal and USB PCM/control regressions pass; Android debug
and signed release APKs build; lint completes with 0 errors and 50 warnings. Physical mixer certification, visual
device testing and real provider broadcasts remain outstanding.
