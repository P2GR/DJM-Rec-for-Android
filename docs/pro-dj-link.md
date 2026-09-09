# Pro DJ Link investigation and experimental integration

Research date: 2026-09-09. Target: Android recorder, DJM-A9, two CDJ-2000NXS2 players on an Ethernet switch.

## Can the DJM-A9 computer USB port carry this data?

**A separate LAN connection is the implemented path. DJM-A9 computer USB-B track metadata with CDJ-2000NXS2 remains unverified.**

New evidence: official DJM-REC supports mixer-to-mobile titles/artists with the A9 and supported newer players through its digital send/return connection. This supports investigating a private USB path, but does not establish NXS2 or computer USB-B compatibility. See [driver analysis, experimental build and capture guide](djm-a9-usb-research.md).

AlphaTheta documents computer USB audio input/output, DJ-software control and MIDI for the A9. Its published MIDI map describes mixer controls, not a track-title service. Neither establishes a general Ethernet bridge to the attached CDJs. Sources: [A9 USB capabilities](https://support.pioneerdj.com/hc/en-us/articles/15999428147609-What-features-are-available-when-I-connect-my-PC-Mac-to-the-unit-via-a-USB-cable), [A9 MIDI messages](https://www.pioneerdj.com/-/media/pioneerdj/downloads/midi-mapping/djm-a9/djm-a9_midi_message_list_e_10.pdf/).

The A9 separately supports LAN-based Pro DJ Link and Stagehand. Its Wi-Fi capability does not by itself establish a USB-to-LAN bridge. [Official A9 product information](https://www.pioneerdj.com/en/product/dj-mixers/djm-a9/).

There are model-specific exceptions elsewhere: Dysentery documents USB virtual networking on some all-in-one units. That is useful evidence to inspect USB descriptors, but cannot be generalized to the A9. Likewise, an older official guide describes mixer-USB forwarding for DJ software control; controller/HID forwarding does not establish access to standalone media titles. [Startup and USB-network analysis](https://djl-analysis.deepsymmetry.org/djl-analysis/startup.html), [CDJ djay connection guide](https://www.pioneerdj.com/-/media/pioneerdj/software-info/player/cdj-2000nxs2/multiplayer_djay_connection_guide_en.pdf).

Observed locally: no Android device appeared in `adb devices`; no Pioneer/AlphaTheta mixer appeared in Windows' connected-device inventory; the physical Ethernet adapter was disconnected. **No A9 USB capture or real CDJ network test was possible.** Absence of a published tunnel is not proof that no proprietary tunnel exists.

The app now inspects the already-read USB configuration descriptors without claiming additional interfaces or sending vendor requests. CDC Ethernet/EEM/NCM and RNDIS advertisements are reported as candidates, not proof of Link access. Vendor-specific and incomplete descriptors remain explicitly inconclusive. Actual proof requires an Android network interface plus valid Link packets originating from the players.

## How the protocol works

- UDP 50000: device discovery, presence and player-number negotiation.
- UDP 50001: beats and mixer on-air/control messages. Captured as raw evidence in experimental builds; no control messages are sent.
- UDP 50002: detailed player status, sent directly to participating devices.
- TCP 12523 discovers a player's metadata-server port; a separate TCP session retrieves track metadata.

Packet type is interpreted together with destination port. The common prefix is `Qspt1WmJOL`. Audio recording continues through the existing USB audio path. [Packet analysis](https://djl-analysis.deepsymmetry.org/djl-analysis/packets.html).

A virtual participant announces its local IPv4/MAC and an unused player number. NXS2 status provides loaded media identity, playback state, on-air flag, tempo-master flag, BPM/pitch, beat number and packet sequence. Status is not a title broadcast. Beat number is not a precise elapsed-track clock. [Detailed status analysis](https://djl-analysis.deepsymmetry.org/djl-analysis/vcdj.html).

Track identity includes source player, media slot, track type and database ID. A track playing on deck 1 can reside on deck 2's USB: metadata must be requested from deck 2. The database exchange uses tagged numeric/UTF-16 fields, a context handshake, metadata request, and menu rendering. Legacy queries can require an unused real player number 1–4. Two physical players leave capacity; four occupied numbers do not. Non-rekordbox requests use a different message type and may need extra status traffic on older hardware; their availability remains experimental here. [Metadata analysis](https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html).

## Assessment of the supplied projects

| Project | Value to this app | Decision |
| --- | --- | --- |
| [Beat Link Trigger](https://github.com/Deep-Symmetry/beat-link-trigger) | Desktop automation, mature Link behavior and useful external comparison tool | Reference and hardware cross-check; not an Android runtime dependency |
| [OPUS-QUAD analysis](https://github.com/kyleawayan/opus-quad-pro-dj-link-analysis) | Documents lighting-mode handshake and model-specific behavior | Do not apply its track-ID/database assumptions to NXS2 |
| [prolink-connect](https://github.com/evanpurkhiser/prolink-connect) | TypeScript discovery, deck status and metadata implementation | Architectural/reference input; no Node runtime added |
| [prolink-tools](https://github.com/evanpurkhiser/prolink-tools) | Product examples consuming Link data | Product reference; no desktop/web stack bundled |
| [Dysentery](https://github.com/Deep-Symmetry/dysentery) | Public field-level packet and metadata research | Protocol specification used for independent Kotlin implementation |

No code or dependency from these projects is bundled. Their licenses do not become an implied license for this app. Public wire-format facts were implemented independently; the existing app license remains unchanged. Protocol authors are credited in third-party notices.

## App contract and ownership

`DjLinkSource.state: StateFlow<DjLinkState>` is the read-only application contract. `DeckState`, `TrackKey`, `TrackMetadata`, and `nowPlaying()` contain no Android/socket objects. Consumers can substitute another source without interpreting Pro DJ Link packets. `ProLinkClient` implements the transport and selected-network setup; `RemoteDbClient` handles metadata; `ProLinkPackets` handles UDP decoding. An application singleton supplies the same state to recording and streaming. Recording-service destruction disconnects it. Connection is explicit each session; preferences persist, connection does not silently restart.

Implemented behavior:

- User selects a Wi-Fi/Ethernet IPv4 LAN. Sockets bind to that Android `Network`, never the entire process, preserving the default internet route used by RTMP.
- Four seconds of discovery precede choosing a free number 1–4. A subsequent competing claim/conflict or duplicate device number stops participation. No load, fader-start, sync or tempo-master commands.
- Current adapter MAC is used. If Android hides it, discovery remains passive and Settings explains how to enter the current address. A Wi-Fi randomized MAC must match the selected network.
- Legacy CDJ-2000 family status is decoded; the intended NXS2 hardware remains unverified. Other devices can appear in discovery, but no support is inferred from their name alone.
- Old/duplicate status packets are rejected. Deck state expires after three seconds; device presence expires after ten. Disconnect/error clears now-playing data.
- Metadata requests run outside packet reception and audio work, one at a time. Connection/read deadlines, field/count limits, retries and load-generation checks prevent unbounded reads and late responses overwriting a different load. No persistent global track-ID cache.
- Strict default selection is **playing and on-air**, retaining both decks during a blend. Match CDJ player numbers to A9 mixer channels. On-air is a device signal, not proof from the recorded audio. Optional playing-only selection can include headphone cue playback.
- Automatic markers are opt-in. `TrackTimeline` samples the recorder's sample-derived elapsed position every 250 ms and subtracts the current file-part origin. Pause adds no wall-clock time. A marker captures each selected deck/track transition; late metadata updates that event's original position. Both deck identities survive overlaps.
- Existing marker JSON remains readable. New markers retain event ID, deck, full source track key, and structured title/artist/duration. Titles are labels in **Sets → recording menu → Track markers**. They live in app-private sidecars; sharing/exporting audio alone does not include them. No CUE/embedded chapters or remote title logging was added.
- Opt-in banner is rendered through RootEncoder's GL filter into both artwork and camera broadcasts. Settings offers top/bottom, prefix, artist visibility, and dark/light background. Empty/stale metadata hides the banner; long text is ellipsized. Updates are capped at twice per second. Exact orientation and remote rendering need physical validation.

Connection changes require explicit reconnect. Wi-Fi broadcast isolation, missing IPv4, link-local addressing mismatch, another app owning Link ports, hidden MAC, full player slots, unavailable media and network loss are observable limitations. No Android network settings, firewall settings or system-wide routes are modified.

## Hardware acceptance procedure

1. **USB-only baseline:** connect A9 PC/Mac USB to Android, permit audio access, and open Settings → Pro DJ Link. Save the descriptor finding. Check whether a new matching network appears. Disable unrelated LAN connections before attributing any packets to USB. No candidate interface means there is no usable standard USB network path in that setup; vendor tunnels remain unknown.
2. **Known network path:** connect an Android-compatible Ethernet adapter to the existing switch while retaining mixer USB audio, or use Wi-Fi bridged to that switch without client isolation. The phone must have IPv4 on the players' subnet. A switch without DHCP may require deliberate addressing; the app does not assign it.
3. Start all three devices first, with unique CDJ numbers matching mixer channels. Select the phone's LAN in Settings and connect. If requested, enter that adapter's actual current MAC. Expect two CDJs and A9 discovery, followed by both deck states. Compare titles/artists with the CDJ screens using rekordbox-exported USB media, including a track loaded across Link from the other player's USB.
4. Enable markers. Record, mix both decks, pause/resume, reload, stop a deck, and disconnect/reconnect LAN. Inspect markers against audible transitions. Allow packet delivery plus the 250 ms sampling interval; this is not sample-accurate musical alignment. File splits begin their own marker timeline.
5. Enable banner and make a private test broadcast. Verify remote player output for artwork, front/rear camera, portrait/landscape, long/non-Latin names, both positions and themes. Check that LAN loss hides titles while USB recording continues.
6. Test an occupied Link port, a newly conflicting player number, unavailable metadata and media removal/reinsertion. Compare independently with Beat Link Trigger on a different computer/player number, or run it separately so it does not consume the app's slot.

Automated fixtures exercise malformed/truncated packets, sequence wrap, source identity, unknown values, selection, overlap, pause/file boundaries, late metadata, banner text and the full metadata message exchange. They establish software behavior, not hardware compatibility. Build/lint results belong to the accompanying change report.

### Workspace validation

`gradlew.bat testDebugUnitTest assembleDebug --console=plain` completed successfully after the final code changes. The suite has 86 tests, including 16 new Link/USB tests. Debug APK: `app/build/outputs/apk/debug/DJM-Rec-for-Android-v0.41.3-debug.apk`.

`lintDebug` was attempted with the default Kotlin analyzer and again with the invocation-only `-Pandroid.lint.useK2Uast=false` option. Both stalled in the lint source-file scanner (`UastEnvironmentVirtualFileUtils.collectFilePaths`, confirmed by JVM thread dumps) and were cancelled. Lint is **not verified**; no persistent lint settings or suppressions were changed. Physical USB/LAN and remote video acceptance remain pending.
