# Automatic diagnostics

Bugfender Android SDK **4.0.1** is included in debug, release and local builds. It initializes
in `DjmRecApplication` before USB discovery, using the application's supplied ingestion key and
the standard API/dashboard URLs. Release collection is enabled; the SDK's console-debug argument
does not control whether release logs upload. `setForceEnabled(true)` enables automatic delivery
while the in-app preference is on, independently of the dashboard's device selection.

## What reaches the portal

- `UsbDescriptors`: bounded configuration descriptor hex, tagged by VID:PID, including devices
  which fail profile selection. String descriptors are excluded. This is USB configuration data,
  not audio payload data.
- `Mixer`: profile, confirmation status, interface/alternate setting, endpoint capacity, channels,
  bit resolution, physical subframe, advertised rates and raw/Android capture mode. Searchable
  device attributes include build type, version, USB identity, profile and channel count.
- Filtered USB/native logs: permission/attach/startup, routing controls, clock negotiation,
  playback keepalive, per-pair signal peaks and chosen pair. Native raw audio packet dumps and
  root/kernel logs are not forwarded.
- `CaptureHealth`: every 30 seconds, or when health changes, the native pipeline snapshot includes
  requested/opened rates, resolved pair, routes, packet/byte/nonzero counts, missed/empty/partial
  packets, resubmit failures, XRuns, stereo peak/RMS, clipping, writer bytes/errors and live PCM
  counters. SDK work runs on a bounded background queue, not the audio callback.
- Recording state, save completion (duration only), recovery notices, streaming status changes and
  named navigation destinations. Recording/streaming failures create issues, limited to one per
  category per ten minutes per process.
- SDK-managed Java/Kotlin uncaught crashes. Android 11+ additionally reports previous native-crash
  or ANR **exit metadata** on the next enabled launch. This does not provide C++ stack symbolication
  or full ANR traces. Mapping upload handles JVM/R8 frames, not native addresses.

The portal may receive data later when offline. Local SDK storage is capped at 5 MiB. No audio files,
waveform samples, screenshots, HTTP bodies or UI field contents are deliberately collected.
Application log messages redact URL values, common credential fields and private storage paths.
SDK crash stacks and SDK-generated device/session metadata follow Bugfender's own handling;
avoid including secrets in exception messages. ANDROID_ID collection is disabled and the display
device name is generic; Bugfender still assigns its own installation/session identifiers.

## Opt-out behavior

Settings → **Automatic diagnostics** defaults on in every build. Turning it off persists the choice,
gates explicit logging and logcat interception immediately, discards this app's pending work, and
restores the pre-SDK uncaught exception handler if Bugfender still owns it.

SDK 4.0.1 has **no public shutdown API**. `setForceEnabled(false)` restores dashboard control; it is
not an opt-out. Already queued SDK uploads, session metadata and network activity can continue until
the process ends. The UI explicitly requires finishing the set, force-stopping through Android app
settings, then reopening. On an opted-out launch the SDK is not initialized at all. Ordinary app
backgrounding, task dismissal or Save & close does not guarantee process death. No recording is
automatically stopped to apply this preference. Opt-out does not delete reports already uploaded.

Manual Diagnostics navigation was removed. Existing local diagnostic code is retained for development;
remote collection does not run the old broad log exporter or query vendor controls a second time.

## Release symbolication

The supplied symbolication token is stored locally in ignored `bugfender.properties`, never in
BuildConfig or APK resources. CI should set `BUGFENDER_SYMBOLICATION_TOKEN` as a secret environment
variable. Environment configuration takes precedence. Example file (replace placeholder locally):

```properties
BUGFENDER_SYMBOLICATION_TOKEN=your-private-upload-token
```

With a token present, pinned `com.bugfender.upload-mapping:1.1.2` uploads release mappings
to `https://dashboard.bugfender.com/`. Local mapping upload is disabled because local and release
share the same version/build and must not overwrite each other's mappings. Debug is not minified.
Without a token, the plugin is not applied and SDK
capture still builds/works; release maintainers must upload mappings separately. Inspect the upload
task output: the vendor plugin can report HTTP errors without failing the overall Gradle build.
Keep the exact APK and its `app/build/outputs/mapping/release/mapping.txt` together.

## Device acceptance test

1. Install debug and release builds; verify distinct build-type attributes in Bugfender Devices.
2. Connect A9/750MK2, then experimental/unknown hardware. Confirm descriptor chunks, profile/rates,
   selected pair and increasing transfer counters; verify left/right signal and per-pair peak logs.
3. Deny permission, retry, switch inputs, record/pause/save, disconnect USB and induce a controlled
   recoverable error. Check the event order, one issue per category and no audio payload dumps.
4. Disable diagnostics during a set: recording must continue and new app log events must stop.
   Finish, force-stop, relaunch offline/online; verify no SDK startup/network traffic while off.
   Re-enable and check logging resumes. Previously uploaded/queued reports are not deletion tests.
5. Verify a controlled JVM crash in a test build and readable release mapping. Test native crash/ANR
   metadata separately on Android 11+; do not deliberately crash a real recording session.

References: [official Kotlin setup](https://docs.bugfender.com/docs/platforms/android/kotlin/),
[SDK source/API](https://javadoc.io/doc/com.bugfender.sdk/android/4.0.1/index.html),
[official mapping plugin](https://github.com/bugfender/gradle-mapping-upload).
