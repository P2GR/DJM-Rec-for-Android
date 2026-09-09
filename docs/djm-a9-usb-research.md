# DJM-A9 USB metadata research and experimental build

Research date: 2026-09-09. Hardware target: DJM-A9 and two CDJ-2000NXS2 players.

## Findings

USB metadata is a credible hypothesis, but **USB-B track titles with this hardware combination remain unverified**.

The [official DJM-REC page](https://www.pioneerdj.com/en/product/software-interfaces/djm-rec/) documents fader-derived timestamps and title/artist display for DJM-A9 with supported newer players. It lists CDJ-3000, CDJ-3000X and CDJ-1500X for track information, not CDJ-2000NXS2. Its connection instructions use the top-panel digital send/return mobile-device port. That is evidence for mixer-to-mobile metadata, not evidence that the computer USB-B interface exposes the same service. An unsupported player combination and a different USB interface are two independent uncertainties.

The [computer USB support page](https://support.pioneerdj.com/hc/en-us/articles/15999428147609-What-features-are-available-when-I-connect-my-PC-Mac-to-the-unit-via-a-USB-cable) documents audio and DJ-software/MIDI functions. No general Pro DJ Link Ethernet bridge is established by that description. See [the Link investigation](pro-dj-link.md) for the implemented Ethernet metadata path and canonical application API.

No mixer or Android device was available for live capture during this investigation. Static binary analysis does not establish which requests a firmware version accepts, whether private MIDI/SysEx carries titles, or whether a subscription handshake is needed. A silent receive-only capture cannot establish that no metadata service exists.

## Static Windows driver analysis

Analyzed locally installed files without loading or executing them. No proprietary executable or driver is included in this repository. Reports contain hashes, imports, selected strings and focused disassembly, with a reproducible inspection script.

| File | SHA-256 | Relevant result |
| --- | --- | --- |
| `DJM-A9Audio64.sys` | `fe37ab6bbdc274d6064d288a78134ac0ec86033e54b0ca1b0d30eb43a9a3f31d` | Windows audio/PortCls/USB driver; no clear track-title service found |
| `DJM-A9_Config.exe` | `43c855c6e6a0476fc3d83e646526ebeaac6999295ebabfa23846a383bd68faa0` | Private PDJ API names are useful leads; some may be generic optional functions |
| `DJM-A9_Setup64.dll` | `f72997add9e289dd06d3abacdc056f566938a06bf8a2d0b8a5c7b0776b506371` | Mixer state and routing APIs; no exported title/artist/track API found |
| `DJM-A9_ASIO64.dll` | `6a9143e517ee33adb5c092339a61fc244e5b79ef163c59484663e320916bf7db` | Audio streaming/configuration surface; no positive metadata evidence |

The INF matches `USB\VID_2B73&PID_003C&MI_00`, class `MEDIA`, DriverVer `01/31/2023,1.100.002.0`. This describes the installed function driver, not every interface the physical mixer might expose. Generic driver strings such as `MULTITRACK_RECORDER` are audio terminology, not song metadata evidence. The installed package is not assumed to be the newest available release; official packages are available through [A9 software support](https://www.pioneerdj.com/en/support/software/mixer/djm-a9/).

### Recovered read candidates

These setup packets are inferred from the x64 setup utility's code, not captured from hardware:

| Purpose | bmRequestType | bRequest | wValue | wIndex | wLength |
| --- | --- | --- | --- | --- | --- |
| Mixer input-selector state, channels 1–4 | `C0` | `00` | `0001`–`0004` | `8002` | `0002` |
| Serato-active state | `C0` | `00` | `0000` | `8004` | `0002` |

All fields above are hexadecimal. Multi-byte setup fields are little-endian. `C0` is vendor/device recipient, IN direction. These are **not** track metadata requests.

Evidence chain in `DJM-A9_Setup64.dll`, image base `0x180000000`:

- Export `PDJ_GetMixerInputSelectorStatus`, RVA `0x9850`, maps input channels to 1–4 and calls RVA `0x7620`. That helper supplies `C0/00/value=channel/index=8002/length=2`; checks the first returned byte against the channel and exposes the second byte.
- Export `PDJ_GetMixerSeratoActiveStatus`, RVA `0x9910`, calls `0x79d0` with `C0/00/0/8004/2`; the wrapper accepts a state below 2.
- Common helper RVA `0x3560` forwards these arguments to `0x3310`. At `0x340c` the IN path packs type, request, value, index and length into the eight-byte setup layout. This supports interpreting the constants as USB setup fields rather than arbitrary API arguments.
- `PDJ_SetUSBInputAudioDirect` calls `0x80a0`, an OUT routing operation using request `03`, index `8002`. The research probe never invokes it. The recorder's existing audio configuration still performs its established control operations, which are now observable.

The experimental probe sends exactly the five listed IN requests once, with 500 ms timeouts and two-byte buffers. It requires one permitted A9, active trace capture, and a closed native audio stream. It does not claim interfaces, write routing state, scan unknown registers, or automatically poll. Negative/short results remain raw evidence; no guessed track names enter the application API.

Reports: [driver](research/djm-a9-driver.json), [settings executable](research/djm-a9-settings.json), [setup DLL](research/djm-a9-setup.json), [ASIO DLL](research/djm-a9-asio.json).

Reproduce against your own installed files:

```powershell
python -m pip install pefile capstone
python scripts/research/inspect_pe.py C:/Windows/System32/DJM-A9_Setup64.dll --output setup-analysis.json --rva 0x7620 --rva 0x79d0 --rva 0x80a0 --rva 0x3560 --rva 0x3696 --rva 0x3310
```

The script performs linear disassembly, not complete control-flow recovery. Arbitrary RVAs must be checked against instruction boundaries. String absence does not prove feature absence; compressed/encrypted data, indirect calls, firmware and the mobile accessory protocol remain outside this analysis.

## Experimental build

The local `experimental` branch lives in `C:\Web\djmrec-experimental`. It started from the current source snapshot, including the existing uncommitted Link and USB work, while leaving the original `main` worktree untouched.

```powershell
cd C:\Web\djmrec-experimental
.\scripts\build-experimental.ps1
# For an explicitly scoped local build if lint is blocked:
.\scripts\build-experimental.ps1 -SkipLint
```

Equivalent tasks: `lintExperimental testExperimentalUnitTest assembleExperimental`.

- Package: `com.audiopro.djmrec.experimental`; label: **DJM REC Experimental**. Separate app data and install from stable/debug.
- APK: `app/build/outputs/apk/experimental/`. Debug-signed, debuggable; development use only.
- `BuildConfig.PROTOCOL_RESEARCH=true` and native `DJMREC_PROTOCOL_RESEARCH=1` only in this variant. Regular variants default to false/0.
- Firebase disabled; raw traces are never uploaded automatically. Google OAuth is deliberately empty for the unregistered experimental package/signing identity. Google sign-in/YouTube API setup needs a separately registered client; custom RTMP does not depend on that client.
- Stable update checks are disabled for the experimental package.
- `.github/workflows/experimental.yml` runs on experimental pushes/PRs and manual dispatch on that branch. It runs lint/tests/build, produces APK checksums/source identity and retains artifacts for 14 days. After successful branch/manual builds, a separate job publishes the APK, checksum and source commit to the rolling `experimental-latest` prerelease. Pull requests never publish. This prerelease does not become the stable latest release. It runs remotely only after the branch is pushed.
- CI debug keys can differ between runners and from your local key. Until dedicated experimental signing is provisioned, Android may require uninstalling an older experimental build before installing one from a different signer. Export recordings first.

## Capture coverage and limits

Open **Support & diagnostics → Protocol lab**. Capture is off after process startup and starts only by user action. A new capture replaces the previous RAM session. Export stops capture and writes `cache/logs/protocol-trace.ndjson`; the share sheet lets the user choose a destination. Clear removes both RAM capture and that cached export. Closing the app loses unexported RAM evidence.

Captured:

- UDP traffic received from other hosts on the selected Link subnet, destination ports 50000/50001/50002, including unrecognized packets, before decoding; app-generated discovery sends. These are app socket observations, not a promiscuous LAN sniff. Destination port is shown for receives; remote destination port for sends.
- TCP metadata-server discovery and query reads/writes, including undecodable responses. TCP events are byte-stream chunks, not packets or complete messages. Failed writes are not presented as successfully transmitted bytes.
- The app's Android USB control calls and six explicit native libusb control call sites: setup bytes, return result, received IN bytes or attempted OUT payload. Android's internal traffic and libusb-internal descriptor/configuration/alternate-setting transfers are not intercepted.
- USB descriptors already read by the app, plus a manual snapshot of currently permitted devices. No extra interface claims are made for snapshots.
- Optional receive-only A9 USB MIDI through Android's MIDI service. These are Android MIDI bytes, not the original four-byte USB-MIDI event containers. No initialization/SysEx messages are transmitted. Android must expose an output port and make it available; absence or silence is inconclusive.
- User notes with wall-clock milliseconds and monotonic nanoseconds for aligning track-load/play/fader experiments. Native batches also carry native steady-clock timestamps; do not assume Android MIDI timestamps share a clock without checking.

No PCM/audio payloads, generic bulk/interrupt endpoint capture, full USB bus capture or traffic belonging to other apps are included. Raw metadata/IP/MAC/USB identifiers can be sensitive. Capture is intended for short controlled experiments; logging and MIDI access still need real-device audio regression testing.

RAM capture stops at 10 minutes, an 8 MiB accounting budget (payload plus estimated event overhead), or 16,384 records. Individual payloads cap at 65,535 bytes and preserve original/captured lengths. The native staging buffer caps at 60,000 bytes, with drop counts; native control payloads cap at 4,096 bytes, with setup requested length and transfer result retained. A limit stops new evidence rather than overwriting old events. These are bounded observations, not a claim to capture every bus byte. Exported hexadecimal text is larger than RAM payloads.

Inspect an export locally:

```powershell
python scripts/research/read_trace.py protocol-trace.ndjson
python scripts/research/read_trace.py protocol-trace.ndjson --show
```

## Hardware experiment

1. Record mixer/player firmware, driver/app version, USB connector used, player numbers and topology. Use tracks with distinctive known titles/artists. Stop recording/monitoring for the state probe.
2. Connect Android to computer USB-B. Start trace; capture descriptors; start MIDI listening. Add notes before loading, playing, pausing and unloading each track, then before each fader/input-selector change. Run the fixed state reads before/after changing input selectors. Export.
3. Repeat with the same USB connection while the app's Link connection is disconnected. Keep the CDJs and mixer linked through their switch. This distinguishes information received over the app's LAN socket from information received over USB.
4. Repeat with Android Ethernet connected to that switch and Link enabled. Correlate canonical deck state/metadata with timestamped notes and raw Link frames. Load a track stored on the other player's media as well.
5. Compare with a Windows USBPcap/Wireshark capture of the official settings utility on computer USB-B. Capture enumeration, utility startup and the same controlled actions. This can expose initialization and transfers this Android recorder does not perform. Filter the A9 device and control/non-audio endpoints; full captures can contain recorded audio and unrelated USB traffic. See [USBPcap](https://github.com/desowin/usbpcap) and [Wireshark USB capture guidance](https://wiki.wireshark.org/CaptureSetup/USB).
6. If access to a supported newer player and DJM-REC mobile setup is available, compare that separately. A Windows USB-B capture cannot reveal transactions on the mixer's separate mobile port; capturing that bus requires an appropriate analyzer/setup. Do not conflate the results.

Acceptance for a USB metadata provider: repeatable bytes tied to known track identity on this exact setup, an understood initialization sequence, robust framing/length/encoding, both decks and overlapping playback, unload/disconnect invalidation, timestamp alignment, and proven non-interference with recording. Only then implement a USB-backed `DjLinkSource` (or rename the canonical source interface if needed), feeding the existing markers and banner. Until then, the canonical metadata source remains Ethernet Pro DJ Link; raw research bytes never masquerade as verified titles.

## Validation

JVM tests cover trace opt-in/stop, byte/record budgets, explicit truncation, ownership of captured input, concurrent producers, ten-minute expiry, and TCP stream preservation/no duplicate capture, alongside the existing protocol/recording tests. Build verification must cover experimental and normal variants so native tracing cannot leak into regular builds. Device-side MIDI, USB probes, audio continuity and actual CDJ metadata remain hardware acceptance checks.

Local validation: 93 JVM tests passed in each of experimental and debug variants; `lintExperimental` and both APK builds passed. Native compile commands confirm tracing is 1 for experimental and 0 for debug. No Android device was attached for UI or hardware verification.
