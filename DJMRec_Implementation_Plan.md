# USB Audio Recorder PRO — Complete Mechanism & DJMRec Implementation Plan

> **Goal:** Replicate USB Audio Recorder PRO's exact USB audio capture mechanism in DJMRec.  
> **Scope:** Ground-up implementation plan. Existing code may be kept, refactored, or scrapped.  
> **Hardware targets:** Pioneer DJM-A9, DJM-900NXS2 (primary)

## Verified DJM-A9 findings (2026-07-15, through 19:30 report)

Hardware logs prove raw transport is correct: interface 2 / alt 1 / endpoint `0x83`, 12-channel
packed 24-bit PCM. Capture-only produced 48 kHz cadence; activating playback interface 1 / alt 1
changed the capture cadence to the mixer's actual 96 kHz, 3,456,000 bytes/sec. Every payload byte
was still zero. Duplex interface activation therefore changes mixer state, but requires real OUT
traffic rather than alt-setting alone.

Reverse engineering installed Pioneer `DJM-A9_Setup.dll` 1.100.002.0 found the exact protocol:

```text
GET route: bmRequestType=0xC0, bRequest=0x00, wValue=output(0..4), wIndex=0x8002, length=2
SET route: bmRequestType=0x40, bRequest=0x03, wValue=route table value, wIndex=0x8002, length=0
```

For each USB output pair, MIX without MIC uses low byte `0x0A`; MIX with MIC uses `0x09`.
GET returns `[output index, hardware source code]`, not the logical index into the Windows UI
route table. The latest report verifies USB output 5 already reads back as MIX (`0x0A`), so
routing is no longer the blocker.

The rear USB connection indicator blinking only while Record is pressed is **not** a successful
audio connection. AlphaTheta documents blinking as host recognition without a functioning audio
driver; USB audio is unavailable until the indicator remains lit. Record correctly owns the USB
stream only for the recording session, but the light must become steady during that session.

Reverse engineering `DJM-A9Audio64.sys` 1.100.002.0 found the stream-format request:

```text
SET sample rate: bmRequestType=0x22, bRequest=0x01, wValue=0x0100,
                 wIndex=capture endpoint (0x83), data=80 BB 00 (48000 LE24)
```

The 19:13 hardware report proved that request alone is insufficient: the A9 rejected both the
UAC2 clock controls and endpoint sampling-frequency control with `LIBUSB_ERROR_PIPE`. It also
confirmed a hybrid descriptor set with a UAC2 AudioControl interface and a separate UAC1
AudioControl interface. A single final `audioControlInterface` value had hidden this distinction.

Driver disassembly also contains this conditional generic request:

```text
bmRequestType=0x41, bRequest=0x00, wValue=0x0000,
wIndex=streaming interface (1, then 2), length=0
```

The 19:25 hardware report proved this branch does not apply to the DJM-A9: interface 1 rejected it
with `LIBUSB_ERROR_PIPE`. The Android kernel `shutdown state` messages were from the disconnected
earlier app process, not a mixer-reported state. Both generic control requests are therefore
best-effort or skipped, not mandatory A9 initialization.

The A9 exposes playback interface 1 (10 channels) and capture interface 2 (12 channels).
Rekordbox opens the ASIO device duplex. The implementation now runs an in-session fallback ladder
instead of requiring a new APK for each safe strategy:

1. Activate both interfaces and discover the playback OUT endpoint from raw descriptors.
2. Read endpoint rate when supported; otherwise update from measured capture cadence.
3. Continuously submit zeroed playback isochronous packets sized for 10-channel packed 24-bit at
   the active rate (360 bytes per high-speed microframe at 96 kHz).
4. Monitor raw capture payload for one full second.
5. If still entirely zero, route MIX to all five configurable USB output pairs and continue.
6. Mark the active strategy successful as soon as any capture payload byte is non-zero; otherwise
   log explicit strategy exhaustion.

Implemented fix:

1. Track every AudioControl interface and its `bcdADC` independently.
2. Skip incompatible UAC2 clock probes for this device.
3. Activate playback interface 1 / alt 1 and capture interface 2 / alt 1.
4. Stream rate-correct silent playback while capturing.
5. Automatically broaden MIX routing only if raw capture remains all-zero.
6. Restore both interfaces to alt 0 and individually restore every changed route on stop.

No rekordbox identity spoof is indicated. The native Windows driver performs USB control
transfers; cloning that driver initialization is the direct implementation.

---

## Part A: How USB Audio Recorder PRO Actually Works

*Derived from decompiling `USBAudioRecorderPROTrial_1617.apk` (apktool + native .so string analysis)*

### A.1 The Core Trick: FD Handoff

The entire architecture pivots on ONE Android API call:

```
UsbDeviceConnection.getFileDescriptor() → int fd
```

This returns a **Linux file descriptor** for `/dev/bus/usb/BBB/DDD`. The native library then uses this FD with **direct Linux kernel ioctls** — exactly the same ioctls a desktop Linux USB driver would use. Android's Java `UsbDeviceConnection` class does NOT expose isochronous transfer methods (only `bulkTransfer()` and `controlTransfer()` exist), so **native code with the raw FD is mandatory** for USB audio.

```
┌─────────────────────────────────────────────────────────────────┐
│                    THE FD HANDOFF BRIDGE                         │
│                                                                 │
│  Kotlin/Java (Android SDK)          Native C++ (libaeusb.so)    │
│  ─────────────────────────          ──────────────────────      │
│                                                                 │
│  UsbManager.getDeviceList()                                     │
│  UsbManager.requestPermission()                                 │
│  UsbManager.openDevice(device)                                  │
│       ↓                                                         │
│  UsbDeviceConnection connection                                 │
│  connection.getFileDescriptor() ────→ int fd ──→ libusb_wrap_sys_device()  │
│  connection.getRawDescriptors() ───→ byte[] ──→ UAC2 parser (native C++)   │
│                                                                 │
│  connection kept open for                                        │
│  entire capture session ────────────→ fd stays valid ──→ isochronous URBs  │
│  (do NOT close until done!)                                     │
└─────────────────────────────────────────────────────────────────┘
```

### A.2 Native Library Architecture

UARP's native side is split across multiple .so files:

| Library | Role | Evidence |
|---------|------|----------|
| `libaeusb.so` | libusb + custom USB audio driver | Contains `libusb_*` symbols, `ioctl`, `URB`, `claim interface`, `descriptor`, `endpoint` strings |
| `libCore.so` | Audio engine, UAC2 descriptor parser, SWIG JNI bridge, audio processing | Contains 886 JNI exports, `Terminal`, `AudioStreaming`, `Clock Source`, `Feature Unit`, `Mixer Unit`, `Selector Unit` strings |
| `libaeresample.so` | Sample rate conversion | Separate resampling library |
| `libaesndfile.so` | File I/O (WAV, FLAC, AIFF) | libsndfile-based |
| `libaemp3.so` | MP3 encoding | LAME-based |
| `libaeogg.so`, `libaevorbis.so` | OGG/Vorbis | |

### A.3 Exact Capture Sequence (Reconstructed From Native String Evidence)

```
STEP 1: USB DEVICE ENUMERATION (Java)
─────────────────────────────────────
UsbManager usbManager = context.getSystemService("usb")
HashMap<String, UsbDevice> devices = usbManager.getDeviceList()

Filter: deviceClass ∈ {0x00, 0x01, 0xEF, 0xFF}
  (composite, audio, misc, vendor-specific)
Exclude: vendor 0x5C6, (0x1519, 0x452), (0x424, 0xEC00)

STEP 2: PERMISSION (Java)
─────────────────────────
PendingIntent pi = PendingIntent.getBroadcast(context, 0,
  new Intent("com.extreamsd.uarp.USB_PERMISSION"), FLAG_IMMUTABLE)
usbManager.requestPermission(device, pi)

// Wait for broadcast with EXTRA_PERMISSION_GRANTED == true

STEP 3: OPEN DEVICE + GET FD (Java)
────────────────────────────────────
UsbDeviceConnection conn = usbManager.openDevice(device)
int fd = conn.getFileDescriptor()                    // Linux fd
byte[] rawDescriptors = conn.getRawDescriptors()     // Full config descriptor blob

STEP 4: NATIVE DEVICE INIT (C++ via JNI)
─────────────────────────────────────────
// Called: GlobalSession_InitUSBDeviceByName(
//   fd, deviceName, productId, vendorId,
//   [askToReset], useInput, useOutput, true,
//   rawDescriptors, rawDescriptors.length, busSpeed
// )

Inside native code:

4a. Wrap the fd:
    libusb_init_context(&ctx, [NO_DEVICE_DISCOVERY])
    libusb_wrap_sys_device(ctx, fd, &handle)
    // ^^ This is THE trick. libusb never calls open().
    //    Android already opened /dev/bus/usb/* for us.

4b. Parse UAC2 descriptors from raw byte blob:
    Walk the configuration descriptor tree:
    ├── Interface Descriptor (bInterfaceClass=0x01 AUDIO)
    │   ├── bInterfaceSubClass == 0x01? → AudioControl
    │   │   ├── CS_INTERFACE: HEADER, INPUT_TERMINAL, OUTPUT_TERMINAL,
    │   │   │                MIXER_UNIT, SELECTOR_UNIT, FEATURE_UNIT,
    │   │   │                CLOCK_SOURCE, CLOCK_SELECTOR
    │   │   └── (Builds full UAC topology: clock sources, mixer controls,
    │   │        volume/mute controls, input/output routing)
    │   │
    │   └── bInterfaceSubClass == 0x02? → AudioStreaming
    │       ├── bAlternateSetting == 0? → Zero-bandwidth (no endpoint)
    │       ├── bAlternateSetting > 0?  → Active (has isochronous endpoint)
    │       │   ├── CS_INTERFACE: AS_GENERAL (bNrChannels, bmControls, bFormatType)
    │       │   ├── CS_INTERFACE: FORMAT_TYPE (bSubslotSize, bBitResolution,
    │       │   │                               bSamFreqType, tSamFreq[])
    │       │   └── ENDPOINT: isochronous IN (bmAttributes=0x01, dir=IN)
    │       │       └── wMaxPacketSize
    │       └── (Select best alternate: prefer exact stereo, fallback to narrowest)

    Evidence strings from libCore.so:
    "FindInterface num interfaces = %u"
    "audioStreamingDescriptorInitialized = %d"
    "AUDIOSTREAMING"
    "bDescriptorSubtype"
    "FORMAT_TYPE"
    "bNrChannels"
    "bSubslotSize"
    "bBitResolution"
    "Terminal"
    "Clock Source"
    "Clock Selector"
    "Feature Unit" 
    "Mixer Unit"
    "Selector Unit"
    "Requested sample rate %d, but this endpoint has no freq control!"
    "CLOCK WAS NOT VALID!!!! valid = %u"
    "Warning: more than one clock selector, taking first!"
    "Added sample rate 48000 to Helix!"

4c. Build device topology:
    For each Feature Unit found:
      → Create IVolumeController (name, min/max/current volume, mute state)
    For each Selector Unit found:
      → Create USBSelectorUnit (number of inputs, current selection)
    For each Clock Source found:
      → Store supported sample rates
    For each Clock Selector found:
      → Store clock routing

4d. Select the audio streaming interface:
    Choose alternate setting with:
    - Exactly 2 channels preferred, narrowest >= 1 channel as fallback
    - Highest bit resolution
    - Largest subframe size

STEP 5: CLAIM INTERFACE + ACTIVATE ALT SETTING (C++)
──────────────────────────────────────────────────
libusb_set_auto_detach_kernel_driver(handle, 1)  // best-effort
libusb_claim_interface(handle, interfaceNumber)
libusb_set_interface_alt_setting(handle, interfaceNumber, alternateSetting)
// ^^ SET_INTERFACE control transfer: switches from alt0 (no endpoint)
//    to the active alt setting (isochronous endpoint appears)

Evidence strings:
"claim interface"
"claim interface %d for inspecting clock units!"
"claim interface for HID, m_interfaceNumber = %u"

STEP 6: START ISOCHRONOUS TRANSFERS (C++)
───────────────────────────────────────────
Transport_StartUSBTransfers()  // JNI call from Java

Inside:
- Allocate N isochronous transfers (likely 8 transfers × 16 packets)
- Each transfer buffer = wMaxPacketSize × numPackets
- libusb_fill_iso_transfer(transfer, handle, endpointAddress, buffer, bufferSize,
    numPackets, callback, userData, timeout)
- libusb_set_iso_packet_lengths(transfer, wMaxPacketSize)
- libusb_submit_transfer(transfer) for each

- Start event thread:
  while (running || outstandingTransfers > 0) {
      libusb_handle_events_timeout_completed(ctx, &tv_100ms, NULL)
  }

Evidence strings:
"ISOCH"
"isoch"
"URB"
"urb"
"ioctl"
"Failed to allocate feedback transfer %d!"
"Max packets only!USAGE_TYPE_FEEDBACK"

STEP 7: DATA FLOW (C++, real-time)
────────────────────────────────────
For each completed isochronous transfer:
  For each completed packet:
    unsigned char* data = libusb_get_iso_packet_buffer_simple(transfer, i)
    int actualLength = transfer.iso_packet_desc[i].actual_length
    
    // Stitch partial frames across packet boundaries
    // (UAC2 frames don't align to USB microframes)
    // Decode each sample: 8/16/24/32-bit → canonical int32
    // Extract selected channel pair
    // Push to audio engine ring buffer

STEP 8: AUDIO PROCESSING + FILE WRITE (C++)
─────────────────────────────────────────────
Ring buffer (PCM int32 stereo)
  → Resampler if needed (libaeresample.so)
  → File encoder (libaesndfile.so for WAV/FLAC, libaemp3.so for MP3)
  → Disk write

STEP 9: STOP (C++ → Java)
──────────────────────────
Transport_StopUSBTransfers()
  → Cancel all outstanding transfers
  → Join event thread
  → libusb_release_interface()
  → libusb_close()
  → libusb_exit()

Java side:
  → connection.close()  // Only after native is fully stopped!
```

### A.4 Additional Capabilities Found in UARP

**Volume/Mixer Control:**
- Reads Feature Unit descriptors for per-channel volume/mute
- Reads Selector Unit descriptors for input switching
- Exposes these as `IVolumeController` and `USBSelectorUnit` Java objects via SWIG

**Clock Source Handling:**
- Reads Clock Source descriptors to discover supported sample rates
- Handles Clock Selector for devices with switchable clocks
- Falls back gracefully if no frequency control exists

**Device-Specific Quirks:**
```
"Forcing AKAI EIE PRO to be of audio class"
"Added sample rate 48000 to Helix!"
"Input %d%d 99 %d/axefx2_ac2_mc.hex"      // Axe-FX II firmware loading
```

**Root-Mode Firmware Loading:**
```
"su -c \"%s/libaemagic.so %s\""
// Loads device firmware via root for devices that need it
```

---

## Part B: DJMRec Implementation Plan

### B.1 What DJMRec Already Has (Keep)

| Component | File | Verdict |
|-----------|------|---------|
| USB device enumeration + permission | `UsbAudioManager.kt` | ✅ **KEEP** — Complete and working |
| BroadcastReceiver (attach/detach/permission) | `UsbAudioManager.kt` | ✅ **KEEP** |
| FD handoff (`openIsoCaptureHandle`) | `UsbAudioManager.kt` | ✅ **KEEP** |
| UAC2 descriptor parser | `UsbAudioDescriptorParser.kt` | ⚠️ **ENHANCE** — Works but only parses AudioStreaming. Add AudioControl parsing for clock sources, feature units, mixer topology |
| libusb source build | `CMakeLists.txt` | ✅ **KEEP** |
| libusb_wrap_sys_device | `UsbIsoAudioSource.cpp` | ✅ **KEEP** |
| Isochronous transfer submission | `UsbIsoAudioSource.cpp` | ✅ **KEEP** |
| Channel demuxing + auto-detect | `UsbIsoAudioSource.cpp` | ✅ **KEEP** |
| Ring buffer (lock-free SPSC) | `RingBuffer.h` | ✅ **KEEP** |
| VU metering | `MeterCalculator.h` | ✅ **KEEP** |
| RGB waveform | `WaveformAnalyzer.cpp` | ✅ **KEEP** |
| File writers (WAV, FLAC, MP3) | `writers/` | ✅ **KEEP** |
| Foreground service | `RecordingService.kt` | ✅ **KEEP** |
| AAudio/Oboe path (for stereo devices) | `UsbAudioEngine.cpp` | ✅ **KEEP** — Fallback for non-Pioneer devices |
| Root USB assist | `RootUsbHostController.kt` | ✅ **KEEP** |
| Diagnostic log export | `LogExporter.kt` | ✅ **KEEP** |

### B.2 What Needs Enhancement

| Item | Current State | Target State | Priority |
|------|--------------|--------------|----------|
| **UAC2 descriptor parser** | AudioStreaming only | Full UAC topology (AudioControl, Clock Sources, Feature Units, Selector Units, Mixer Units) | 🔴 P0 |
| **Sample rate discovery** | From AudioManager device list | From UAC2 Clock Source descriptors in raw bytes | 🔴 P0 |
| **Clock source handling** | None | Read Clock Source/Selector, validate rate compatibility | 🟡 P1 |
| **Isochronous feedback endpoint** | Not handled | For async-mode devices that need a feedback pipe | 🟢 P2 |
| **Interface claiming** | Single interface | May need to claim AudioControl interface too | 🟡 P1 |
| **Pioneer vendor control** | UNVERIFIED stubs | Reverse-engineer from USB traffic capture | 🟢 P2 |
| **Error/logging granularity** | Basic | Per-packet status, drop counters, sync loss detection | 🟡 P1 |

### B.3 What Can Be Scrapped

| Item | Reason |
|------|--------|
| **Root ALSA path** (`AlsaPcmAudioSource.cpp`, `startRootAlsaSession`, etc.) | Not needed for stock Android USB audio. Was a workaround. Keep as optional fallback but don't maintain. |
| **DJM REC Port mode** | Not the primary use case. The main USB connection is what DJs use. |
| **RMX-1000 simulator** (optional) | Not related to USB recording. Remove or move to separate module. |
| **BPM detection** (optional) | Not related to USB recording. |

### B.4 Implementation Steps (In Order)

---

#### Phase 1: Clean Up & Foundation (Day 1)

**Step 1.1: Strip non-recording features**
Remove or `#ifdef` out the RMX-1000 simulator, BPM detector, and DJM REC port code from the main recording path. These add complexity without helping USB audio capture.

Files to touch:
- `MainViewModel.kt` — remove RMX/BPM/DJMREC port code paths
- `RecorderScreen.kt` — remove RMX/BPM tabs
- `UsbAudioEngine.cpp/.h` — remove `openRmxOutput`, BPM, SamplePlayer methods
- `RecordingService.kt` — remove DJMREC port mode intents

**Step 1.2: Remove root ALSA as primary path**
Keep `AlsaPcmAudioSource.cpp` and `RootUsbHostController.kt` but remove them from the default code path. Only invoke as a last-resort diagnostic.

**Step 1.3: Rename for clarity**
- `UsbIsoAudioSource` → keep name (it's good)
- `UsbAudioEngine` → keep name
- The word "Iso" in method names is fine

---

#### Phase 2: Full UAC2 Descriptor Parser (Day 1-2)

**This is the most important enhancement.** UARP parses the FULL UAC topology from raw descriptors. DJMRec currently only parses AudioStreaming interfaces.

**Step 2.1: Rewrite `UsbAudioDescriptorParser.kt`**

The parser needs to walk the ENTIRE configuration descriptor and build a complete UAC2 topology model:

```kotlin
// NEW data classes in UsbAudioDeviceInfo.kt:

data class UacTopology(
    val audioControlInterface: AudioControlInterface?,
    val audioStreamingInterfaces: List<AudioStreamingInterfaceInfo>,
    val clockSources: List<ClockSourceInfo>,
    val clockSelectors: List<ClockSelectorInfo>,
    val featureUnits: List<FeatureUnitInfo>,
    val mixerUnits: List<MixerUnitInfo>,
    val selectorUnits: List<SelectorUnitInfo>,
    val inputTerminals: List<TerminalInfo>,
    val outputTerminals: List<TerminalInfo>,
)

data class ClockSourceInfo(
    val id: Int,
    val supportedSampleRates: List<Int>,  // from tSamFreq array
    val clockType: Int,  // internal/external/etc
)

data class FeatureUnitInfo(
    val id: Int,
    val sourceId: Int,
    val controls: List<Int>,  // bitmap of supported controls
    val channelNames: List<String>,
)

data class AudioControlInterface(
    val interfaceNumber: Int,
    val totalLength: Int,  // wTotalLength from class-specific AC header
)
```

**Descriptor Walk Algorithm:**

```
WALK CONFIGURATION DESCRIPTOR:
─────────────────────────────────
offset = 0
while offset < rawDescriptors.length:
    bLength = rawDescriptors[offset]
    bDescriptorType = rawDescriptors[offset + 1]
    
    IF bDescriptorType == DT_INTERFACE (0x04):
        bInterfaceClass = rawDescriptors[offset + 5]
        bInterfaceSubClass = rawDescriptors[offset + 6]
        
        IF bInterfaceClass == USB_CLASS_AUDIO:
            IF bInterfaceSubClass == SUBCLASS_AUDIOCONTROL (0x01):
                → This is the AudioControl interface
                → Continue walking CS_INTERFACE descriptors
                  inside this interface to find:
                  - HEADER (subtype 0x01): wTotalLength
                  - INPUT_TERMINAL (subtype 0x02)
                  - OUTPUT_TERMINAL (subtype 0x03)
                  - MIXER_UNIT (subtype 0x04)
                  - SELECTOR_UNIT (subtype 0x05)
                  - FEATURE_UNIT (subtype 0x06)
                  - CLOCK_SOURCE (subtype 0x0A)
                  - CLOCK_SELECTOR (subtype 0x0B)
                  
            IF bInterfaceSubClass == SUBCLASS_AUDIOSTREAMING (0x02):
                → Already handled (existing code)
                → ENHANCE: also parse AS ISOCHRONOUS endpoint
                  descriptor subtype for bmAttributes, lock delay
```

**Step 2.2: Add Clock Source Parsing (CRITICAL for sample rate)**

```kotlin
// UAC2 Clock Source descriptor layout:
// bLength, bDescriptorType(0x24), bDescriptorSubtype(0x0A),
// bClockID, bmAttributes, bmControls, bAssocTerminal,
// iClockSource, bNrChannels, bmChannelConfig, iChannelNames

// Followed by one or more sample rate triplets:
// If bmAttributes bit 0 == 0: fixed rate
//   tSamFreq[0..2] = 24-bit little-endian sample rate
//   (e.g., 0x80BB00 = 48000)
// If bmAttributes bit 1 == 1: discrete rates
//   bSamFreqType = number of rates
//   tSamFreq[0..N] = array of 24-bit LE rates
// If bmAttributes bit 2 == 1: continuous range
//   tLowerFreq, tUpperFreq = 24-bit LE bounds

// This gives us the ACTUAL supported sample rates from the hardware,
// not from Android's AudioManager (which may be incomplete)
```

**Step 2.3: Store topology in `UsbAudioDeviceInfo`**

Add `topology: UacTopology?` field. Populated during `inspectAndPublish()`.

---

#### Phase 3: Robust Device Initialization (Day 2)

**Step 3.1: Two-Phase Device Open (like UARP)**

UARP's native `InitUSBDeviceByName` does TWO things with the FD:
1. **Phase 1 (inspection):** Walk descriptors, detect topology, identify audio interfaces
2. **Phase 2 (activation):** Claim the AudioStreaming interface, set alt setting

Split `UsbAudioManager.inspectAndPublish()` accordingly:

```kotlin
private fun inspectAndPublish(device: UsbDevice) {
    // Phase 1: Short-lived connection for inspection only
    val inspectConn = usbManager.openDevice(device) ?: return
    
    val rawDescriptors = inspectConn.rawDescriptors ?: ByteArray(0)
    val topology = UsbAudioDescriptorParser.parseTopology(rawDescriptors)
    val bestInterface = UsbAudioDescriptorParser.selectBestStereoInterface(topology)
    val clockRates = topology?.clockSources
        ?.flatMap { it.supportedSampleRates }
        ?.distinct() ?: emptyList()
    
    inspectConn.close()  // ← CLOSE the inspection connection
    
    // Store everything for later use
    _deviceState.value = UsbAudioDeviceInfo(
        // ... existing fields ...
        rawDescriptors = rawDescriptors,  // ← STORE for native init
        clockRates = clockRates,          // ← From hardware, not AudioManager
        topology = topology,
    )
}
```

**Step 3.2: Native-side Device Init (Phase 2)**

When actually starting capture, `openIsoCaptureHandle()` opens a NEW connection for the session:

```kotlin
fun openIsoCaptureHandle(): UsbIsoCaptureHandle? {
    val info = _deviceState.value ?: return null
    
    // NEW: Include raw descriptors + clock source info in handle
    releaseIsoCaptureConnection()
    val connection = usbManager.openDevice(device) ?: return null
    activeIsoConnection = connection
    
    return UsbIsoCaptureHandle(
        fd = connection.fileDescriptor,
        rawDescriptors = info.rawDescriptors,  // ← Pass to native!
        interfaceNumber = info.streamingInterfaceNumber,
        // ... existing fields ...
        clockSourceValid = info.clockRates.isNotEmpty(),
        availableSampleRates = info.clockRates,
    )
}
```

**Step 3.3: Native `openUsbIso` Enhancement**

Enhance `UsbIsoAudioSource::start()` to:
1. Accept raw descriptor bytes
2. Validate that the endpoint's clock source supports our sample rate
3. If not, pick the nearest supported rate
4. Log mismatches clearly

---

#### Phase 4: Enhanced Isochronous Pipeline (Day 2-3)

**Step 4.1: Add Feedback Endpoint Support**

Some UAC2 async-mode devices need a feedback endpoint. Check for `USAGE_TYPE_FEEDBACK` data endpoint.

```cpp
// In UsbIsoAudioSource::start(), after claiming interface:
// Check for feedback endpoint (bmAttributes = 0x01, direction = OUT, usage = FEEDBACK)
// If found: libusb_fill_iso_transfer() for feedback too
```

This is NOT needed for DJM-A9/DJM-900NXS2 (they're adaptive/synchronous), but adds robustness for other mixers.

**Step 4.2: Add Transfer Error Tracking**

```cpp
struct TransferStats {
    std::atomic<uint64_t> packetsCompleted{0};
    std::atomic<uint64_t> packetsMissed{0};    // status != COMPLETED
    std::atomic<uint64_t> packetsEmpty{0};      // actual_length == 0
    std::atomic<uint64_t> packetsPartial{0};    // actual_length < expected
    std::atomic<uint64_t> bytesReceived{0};
    std::atomic<uint64_t> resubmitFailures{0};
};
```

Add to `UsbIsoAudioSource`, update in `handleCompletedTransfer()`, expose via JNI.

**Step 4.3: Add Sync Loss Detection**

```cpp
// In demuxAndEmit(), track frame timing:
// Expected bytes per second = sampleRate * subframeSize * totalChannels
// Actual bytes per second = running average over last ~2 seconds
// If drift > 1%: log warning, suggest clock source mismatch
```

---

#### Phase 5: Pioneer-Specific Optimizations (Day 3)

**Step 5.1: Hard-Code DJM-A9/DJM-900NXS2 Known Parameters**

```kotlin
// In UsbAudioDeviceInfo.kt:
object PioneerMixerProfiles {
    val DJM_A9 = MixerProfile(
        vendorIds = setOf(0x2B73, 0x08E4),
        productNamePattern = Regex("DJM-A9", RegexOption.IGNORE_CASE),
        masterMixChannelOffset = 8,    // channels 9-10 (0-indexed: 8)
        defaultSampleRate = 48000,
        supportedSampleRates = listOf(44100, 48000, 96000),
        expectedChannelCount = 12,
        expectedBitDepth = 24,
        usesAdaptiveClock = true,      // No feedback endpoint needed
        needsVendorControl = false,    // No special control transfer needed
    )
    
    val DJM_900NXS2 = MixerProfile(
        vendorIds = setOf(0x08E4),
        productNamePattern = Regex("DJM-900NXS2", RegexOption.IGNORE_CASE),
        masterMixChannelOffset = 8,
        defaultSampleRate = 48000,
        supportedSampleRates = listOf(44100, 48000, 96000),
        expectedChannelCount = 12,
        expectedBitDepth = 24,
        usesAdaptiveClock = true,
        needsVendorControl = false,
    )
}
```

When a device matches a profile:
- Skip descriptor guessing, use known-good parameters
- Auto-set channel offset to the profile's master mix position
- Validate that actual descriptors match expectations (log warnings if not)

**Step 5.2: Validate Channel Layout**

After opening the isochronous stream:
1. Log per-pair peak magnitudes for the first 2 seconds
2. Verify the loudest pair matches the expected master mix offset
3. If not, switch to auto-detect mode and log the discrepancy

---

#### Phase 6: Polish & Hardening (Day 3-4)

**Step 6.1: Connection Lifetime Safety**

```kotlin
// In UsbAudioManager.kt:
// Use AtomicBoolean to prevent accidental double-close
private val isoConnectionOpen = AtomicBoolean(false)

fun openIsoCaptureHandle(): UsbIsoCaptureHandle? {
    if (!isoConnectionOpen.compareAndSet(false, true)) {
        Log.w(TAG, "openIsoCaptureHandle: already open!")
        return null
    }
    // ...
}

fun releaseIsoCaptureConnection() {
    if (isoConnectionOpen.compareAndSet(true, false)) {
        activeIsoConnection?.close()
        activeIsoConnection = null
    }
}
```

**Step 6.2: Native Crash Recovery**

```cpp
// In UsbIsoAudioSource:
// Catch all exceptions in the event thread
void eventThreadLoop() {
    try {
        while (mRunning.load() || mOutstandingTransfers.load() > 0) {
            libusb_handle_events_timeout_completed(mContext, &tv, nullptr);
        }
    } catch (const std::exception& e) {
        LOGE("Event thread crashed: %s", e.what());
        mRunning.store(false);
    } catch (...) {
        LOGE("Event thread crashed: unknown exception");
        mRunning.store(false);
    }
}
```

**Step 6.3: USB Disconnect Mid-Session**

```kotlin
// In UsbAudioManager.kt BroadcastReceiver:
UsbManager.ACTION_USB_DEVICE_DETACHED -> {
    // Signal RecordingService to gracefully stop
    context.sendBroadcast(Intent(RecordingService.ACTION_DEVICE_DETACHED))
    _deviceState.value = null
}
```

```kotlin
// In RecordingService.kt:
ACTION_DEVICE_DETACHED -> {
    // Stop encoding (flush file), close engine, release connection
    val durationMs = AudioEngine.stopRecording()
    AudioEngine.close()
    usbAudioManager.releaseIsoCaptureConnection()
    _state.value = RecordingState.Error("USB device disconnected")
    stopForeground(STOP_FOREGROUND_REMOVE)
}
```

**Step 6.4: Audio Verification**

After each recording session, run a quick silence check:
```cpp
// In AudioWriter or UsbAudioEngine:
// Track whether any non-zero samples were written
// Log warning if entire recording was digital silence
// (indicates wrong channel pair, wrong endpoint, or dead device)
```

---

## Part C: Complete File-by-File Changes

### C.1 Files to CREATE

| File | Purpose |
|------|---------|
| `usb/UacTopology.kt` | Full UAC2 topology data classes (ClockSourceInfo, FeatureUnitInfo, etc.) |
| `usb/UacDescriptorParser.kt` | Rewritten full parser (replaces current minimal one) |
| `usb/PioneerMixerProfiles.kt` | Hard-coded parameters for known Pioneer mixers |

### C.2 Files to MODIFY (Major)

| File | Changes |
|------|---------|
| `UsbAudioDeviceInfo.kt` | Add `topology`, `rawDescriptors`, `clockRates`, `UsbIsoCaptureHandle` new fields |
| `UsbAudioManager.kt` | Two-phase open; store raw descriptors; enhanced `openIsoCaptureHandle` |
| `UsbIsoAudioSource.h` | `Config` gets `rawDescriptors` field; add `TransferStats` |
| `UsbIsoAudioSource.cpp` | Feedback endpoint support; transfer error tracking; sync detection |
| `MainViewModel.kt` | Strip RMX/BPM/DJMREC; simplified recording path |
| `RecordingService.kt` | Add `ACTION_DEVICE_DETACHED`; remove DJMREC port code |
| `AudioEngine.kt` | Add `getTransferStats()` JNI method |
| `UsbAudioEngine.cpp/.h` | Remove RMX/BPM; add transfer stats passthrough |
| `CMakeLists.txt` | Remove RMX/BPM sources if stripping |

### C.3 Files to DELETE or ARCHIVE

| File | Action |
|------|--------|
| `AlsaPcmAudioSource.cpp/.h` | Archive (keep in git, don't compile by default) |
| `BpmDetector.cpp/.h` | Archive |
| `BeatClock.cpp/.h` | Archive |
| `EffectChain.cpp/.h` | Archive |
| `SamplePlayer.cpp/.h` | Archive |
| `WavSampleLoader.cpp/.h` | Archive |
| `PioneerVendorControl.kt` | Delete; verified native implementation replaces placeholder |

---

## Part D: Minimum Viable Test Plan

### D.1 Without Hardware

```bash
# Build verification
cd c:\Web\djmrec
.\gradlew.bat assembleDebug
# Should succeed with no errors

# libusb link check
adb shell "run-as com.audiopro.djmrec ls -la lib/arm64/libdjmrec_audio.so"
# Should show the .so exists and linked

# Descriptor parser unit test
# Write a test that feeds a known DJM-A9 descriptor blob
# Verify correct interface/endpoint/channel count/clock rates extracted
```

### D.2 With DJM-A9 Connected

```bash
# 1. Verify device detection
adb logcat -s UsbAudioManager:D | grep -E "Attach|audio class|descriptor|topology"

# 2. Verify descriptor parsing
adb logcat -s UsbAudioDescriptorParser:D
# Should show: interface count, clock sources found, sample rates,
# audio streaming alternates, selected best interface

# 3. Start recording, check isochronous path
adb logcat -s UsbIsoAudioSource:D UsbAudioEngine:D
# Should show: "Claimed iface X alt Y", "USB iso capture open",
# peakSummary showing non-zero values, frame callback active

# 4. Stop recording, verify output
adb shell ls -la /sdcard/Music/DJMRec/
# Check file size > 0, play back on desktop to verify audio content
```

### D.3 Fastest Path to Verification

If you have the mixer NOW, skip all cleanup/refactoring. Just:

1. Build current DJMRec as-is: `.\gradlew.bat assembleDebug`
2. Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
3. Connect DJM-A9, play music through CH1
4. Export diagnostic (Bug Report FAB in settings)
5. Try recording while watching logcat:

```bash
adb logcat -c  # clear
adb logcat -s UsbIsoAudioSource:* UsbAudioEngine:* UsbAudioManager:* MainViewModel:* > capture.log
# Press Record in app, wait 15s, press Stop
# Ctrl+C, analyze capture.log
```

The log will tell you EXACTLY where the chain breaks (if it does):
- `"No audio-class interface"` → device not recognized
- `"openIsoCaptureHandle: INVALID FD"` → Android blocked FD access  
- `"libusb_wrap_sys_device failed"` → libusb can't adopt the FD
- `"libusb_claim_interface failed: LIBUSB_ERROR_BUSY"` → kernel driver owns it
- `"peakSummary ch1-2=0, ch3-4=0, ..."` all zeros → wrong endpoint or no audio
- `"Auto-selected USB channels 9-10"` + non-zero peaks → 🎉 WORKING

---

## Part E: Architecture Decision Record

### Why libusb (not raw ioctl)?

UARP uses libusb (proven by hundreds of `libusb_*` symbols in both .so files). DJMRec already does too. This is the correct choice:
- libusb handles the usbdevfs ioctl complexity (URB submission, reaping, cancellation)
- libusb has `libusb_wrap_sys_device()` which is specifically designed for Android's FD model
- libusb is well-tested on Linux/Android

### Why Kotlin descriptor parser (not native)?

UARP does descriptor parsing in native C++. DJMRec does it in Kotlin. Both work. Kotlin is fine for this — the descriptor blob is at most a few KB and we parse it once on connection. No performance concern. Advantage: easier to debug, modify, and test without NDK rebuilds.

### Why keep AAudio path?

For plain stereo UAC2 devices (like the DJM-900NXS2's DJ REC port, or a simple USB microphone), AAudio works perfectly and is simpler. Keep it as the default for non-Pioneer devices.

---

## Appendix: Key Constants Reference

```kotlin
// USB descriptor types
const val DT_DEVICE = 0x01
const val DT_CONFIG = 0x02
const val DT_INTERFACE = 0x04
const val DT_ENDPOINT = 0x05
const val DT_CS_INTERFACE = 0x24
const val DT_CS_ENDPOINT = 0x25

// USB Audio class codes  
const val USB_CLASS_AUDIO = 0x01
const val SUBCLASS_AUDIOCONTROL = 0x01
const val SUBCLASS_AUDIOSTREAMING = 0x02

// Class-Specific descriptor subtypes (AudioControl)
const val AC_DESCRIPTOR_SUBTYPE_HEADER = 0x01
const val AC_DESCRIPTOR_SUBTYPE_INPUT_TERMINAL = 0x02
const val AC_DESCRIPTOR_SUBTYPE_OUTPUT_TERMINAL = 0x03
const val AC_DESCRIPTOR_SUBTYPE_MIXER_UNIT = 0x04
const val AC_DESCRIPTOR_SUBTYPE_SELECTOR_UNIT = 0x05
const val AC_DESCRIPTOR_SUBTYPE_FEATURE_UNIT = 0x06
const val AC_DESCRIPTOR_SUBTYPE_CLOCK_SOURCE = 0x0A
const val AC_DESCRIPTOR_SUBTYPE_CLOCK_SELECTOR = 0x0B

// Class-Specific descriptor subtypes (AudioStreaming)
const val AS_DESCRIPTOR_SUBTYPE_GENERAL = 0x01
const val AS_DESCRIPTOR_SUBTYPE_FORMAT_TYPE = 0x02

// Format types
const val FORMAT_TYPE_I = 0x01   // PCM
const val FORMAT_TYPE_III = 0x03 // IEC61937 (compressed)

// Pioneer vendor IDs
const val PIONEER_LEGACY_VID = 0x08E4
const val PIONEER_ALPHATHETA_VID = 0x2B73

// DJM-A9 selectable USB pairs (0-indexed offsets).
// Native code routes MIX (REC OUT) to whichever pair is selected.
const val DJM_A9_CH1 = 0       // USB ch 1-2
const val DJM_A9_CH2 = 2       // USB ch 3-4
const val DJM_A9_CH3 = 4       // USB ch 5-6
const val DJM_A9_CH4 = 6       // USB ch 7-8
const val DJM_A9_PAIR_5 = 8    // USB ch 9-10 (app default)
const val DJM_A9_PAIR_6 = 10   // USB ch 11-12
```
