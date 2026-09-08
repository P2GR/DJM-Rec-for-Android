# USB capture profile audit — 2026-09-08

## Findings and implementation

A9 and 750MK2 remain the only models confirmed working by the project owner. Their existing
wire formats, clock workarounds and playback keepalive are preserved. No physical USB mixer was
available for this audit. Windows drivers were inspected statically, not installed or shipped
inside Android. A Windows ASIO driver cannot run on Android.

| Mixer | Driver identity (VID 2B73) | Current recording contract | Validation status |
| --- | --- | --- | --- |
| A9 | 003C | Descriptor PCM; default USB9/10; existing clock/keepalive | Owner confirmed; regression test this build |
| 750MK2 | 001B | 12-channel packed 24-bit, 96 kHz; 10-channel keepalive | Owner confirmed; regression test this build |
| V10 | 0034 | if0/alt1, 12-channel packed 24-bit, 44.1/48/96 kHz; six MIX routes, source 0A | Driver/ALSA contract; hardware pending |
| S11 | 0037 | if2/alt1, 10-channel packed 24-bit, 48 kHz; REC OUT USB5/6; if1 14-channel keepalive | Driver/ALSA contract; hardware pending |
| 900NXS2 | 000A | if0/alt1, 12-channel packed 24-bit; retained 96 kHz capture and 10-channel keepalive | Historical app contract; current hardware retest pending |
| V5 | 0058–005B | Descriptor PCM; four output pairs; MIX source 0E without mic / 0A with mic | Driver-derived routing; all PID variants need hardware tests |

Local INF identities and installed setup-DLL recording-source labels were inspected for V10,
S11, 900NXS2 and V5. This corroborates recognition and source intent; it does not independently
prove every USB control transaction. Existing layouts and routing are cross-referenced against
the [Linux USB quirk table](https://github.com/torvalds/linux/blob/master/sound/usb/quirks-table.h)
and [ALSA mixer implementation](https://github.com/torvalds/linux/blob/master/sound/usb/mixer_quirks.c).
The 900NXS2 app's fixed 96 kHz contract is deliberately retained despite broader ALSA rate support.

## All-in-one units

Official Windows driver INFs establish these identities. VirtualDJ's hardware integration
documentation lists the record pairs below. These are recognition/default-route profiles,
**not verified proprietary wire-format overrides**. The app opens capture only when the attached unit
exposes a supported PCM isochronous IN interface. Vendor-only layouts without PCM descriptors still
need descriptor dumps and a reviewed format/startup contract before recording can work.

| Unit | PID / interface in INF | Documented USB audio setup | Implemented behavior |
| --- | --- | --- | --- |
| XDJ-XZ | 002D / MI00 | 12 outputs, 8 inputs; master record IN5/6 | Recognize; descriptor capture; Auto USB5/6 |
| XDJ-AZ | 004A / MI00 | 10 outputs, 6 inputs; record IN1/2 | Recognize; descriptor capture; Auto USB1/2 |
| OPUS-QUAD | 0043 / MI00 | 10 outputs, 2 inputs; record IN1/2 | Recognize; descriptor capture; Auto USB1/2 |
| OMNIS-DUO | 0048 / MI00 | 4 outputs, 2 inputs; record IN1/2 | Recognize; descriptor capture; Auto USB1/2 |
| XDJ-RX3 | 003D / MI00 | 4 outputs, **0 inputs** | Recognize and explain limitation; no invented capture format |

Sources: VirtualDJ's own integration instructions for
[XZ](https://virtualdj.com/manuals/hardware/pioneer/xdjxz/advanced.html),
[AZ](https://virtualdj.com/manuals/hardware/alphatheta/xdjaz/advanced.html),
[OPUS](https://virtualdj.com/manuals/hardware/pioneer/opusquad/installation.html),
[OMNIS](https://virtualdj.com/manuals/hardware/alphatheta/omnisduo/advanced.html), and
[RX3](https://virtualdj.com/manuals/hardware/pioneer/xdjrx3/setup.html).
Counts describe those documented computer setups, not a claim that Android sees identical descriptors.
Use the PC/Mac audio port and a data cable. USB storage/Link Export is not USB capture.
For RX3, use its onboard USB recording or an external USB interface connected to an analog output.
Older RX/RX2/RR units have no new model-specific profile in this change; standard PCM discovery still applies.

### Reproducible package evidence

Packages downloaded from AlphaTheta's official server and extracted without running their installers.
Archive hashes identify exactly which files were inspected; binaries remain ignored research artifacts.

| Package | SHA-256 |
| --- | --- |
| [XDJXZ1010exe.zip](https://downloads.support.alphatheta.com/drivers/all-in-one-dj-systems/XDJ-XZ/XDJXZ1010exe.zip) | `41b3be19daf6a69a7fc11515570e2a5a6cfbf06dba1a1d4341477ba69f4168c7` |
| [XDJRX31110exe.zip](https://downloads.support.alphatheta.com/drivers/all-in-one-dj-systems/XDJ-RX3/XDJRX31110exe.zip) | `3db66f95199b22aa3115decf0ed03549761ca6f29a4cf113fa583c6da891c4e0` |
| [OPUSQUAD1100exe.zip](https://downloads.support.alphatheta.com/drivers/all-in-one-dj-systems/OPUS-QUAD/OPUSQUAD1100exe.zip) | `bd916118621d6a70eac81a1bfa7274c37ac351f08fb228c4b03715256cc76d8b` |
| [OMNISDUO1000exe.zip](https://downloads.support.alphatheta.com/drivers/all-in-one-dj-systems/OMNIS-DUO/OMNISDUO1000exe.zip) | `4a3e98f8b45604ccc00936ed3f56ef78fd9f8078d47ff738bbb148482c3b7c06` |
| [XDJAZ1000exe.zip](https://downloads.support.alphatheta.com/drivers/all-in-one-dj-systems/XDJ-AZ/XDJAZ1000exe.zip) | `6469f543482f5c328e669feeb2e82782342a35fbe195995de9355f6ddae40a4f` |

## Automatic detection and UX

- Source header opens an input picker with connected-device, permission and selection status.
  Refresh/retry and connection guidance are accessible without scrolling the recorder.
- Switching input closes monitoring first; recording, saving, preparing and streaming lock switching.
- Selection validates PCM format, endpoint and channel availability. Reviewed vendor contracts cannot
  overwrite an explicit conflicting PCM descriptor. Unknown devices receive no DJM vendor routing.
- UAC1 rates belong to the selected interface, not unrelated playback interfaces. UAC2 rate ranges
  respect their advertised step. Standard endpoint frequency requests require UAC1 endpoint support.
- Generic multichannel interfaces can use raw USB capture. New generic PCM decoding respects
  [USB's left-justified subframe format](https://www.usb.org/sites/default/files/frmts10.pdf);
  existing DJM-specific padded decoding remains unchanged.
- All-in-one Auto uses the documented master pair; generic Auto uses the first pair. It does not
  chase whichever channel happens to be loudest. Existing DJM Auto behavior remains model-specific.

Automatic detection cannot guarantee recording on every device: vendor-only formats, internal routing,
clock selectors, USB host behavior and firmware require hardware evidence. Never promote a device to
confirmed from its name, INF identity, successful compilation or moving meters alone.

## Next hardware pass

Follow [HARDWARE_VALIDATION.md](HARDWARE_VALIDATION.md). Capture descriptors and diagnostics for each
all-in-one before adding any vendor fallback. Verify master content (including standalone playback,
external inputs and microphones), stereo separation, sample rate/pitch and long-session continuity.
Test hub source switching, permission denial/retry and reconnect while monitoring. For UAC2 devices
with clock selectors, verify the active clock path before adding selector control support.
