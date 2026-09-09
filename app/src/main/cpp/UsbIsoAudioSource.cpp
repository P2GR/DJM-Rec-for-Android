#include "ProtocolTrace.h"
#include "UsbIsoAudioSource.h"
#include "UsbPcmFormat.h"
#include "UsbSampleRate.h"

#include <android/log.h>
#include <libusb.h>

#include <algorithm>
#include <cmath>
#include <climits>
#include <cstring>
#include <exception>
#include <sstream>

#define TAG "UsbIsoAudioSource"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace djmrec {

namespace {
std::string libusbErrorString(const char* what, int code) {
    return std::string(what) + " failed: " + libusb_error_name(code);
}

uint32_t sampleMagnitude(int32_t sample) {
    if (sample == INT32_MIN) {
        return static_cast<uint32_t>(INT32_MAX) + 1u;
    }
    return static_cast<uint32_t>(sample < 0 ? -sample : sample);
}

std::string peakSummary(const std::vector<uint32_t>& pairPeaks) {
    std::ostringstream out;
    for (size_t pair = 0; pair < pairPeaks.size(); ++pair) {
        if (pair > 0) {
            out << ", ";
        }
        out << "ch" << (pair * 2 + 1) << "-" << (pair * 2 + 2) << "=" << pairPeaks[pair];
    }
    return out.str();
}

constexpr uint8_t kUac2RequestSetCurrent = 0x01;
constexpr uint8_t kUac2RequestGetCurrent = 0x81;
constexpr uint16_t kUac2ClockFrequencyControl = 0x0100;
constexpr uint8_t kUac1RequestSetCurrent = 0x01;
constexpr uint16_t kUac1EndpointSamplingFrequencyControl = 0x0100;

constexpr uint16_t kPioneerRouteIndex = 0x8002;

struct IsoEndpointInfo {
    int address = -1;
    int maxPacketSize = 0;
    int interval = 1;
};

IsoEndpointInfo findIsoOutEndpoint(
    const std::vector<uint8_t>& descriptors,
    int targetInterface,
    int targetAlternateSetting,
    int targetAddress = -1
) {
    int currentInterface = -1;
    int currentAlternateSetting = -1;
    size_t offset = 0;
    while (offset + 1 < descriptors.size()) {
        const int length = descriptors[offset];
        if (length < 2 || offset + static_cast<size_t>(length) > descriptors.size()) break;
        const int descriptorType = descriptors[offset + 1];
        if (descriptorType == LIBUSB_DT_INTERFACE && length >= 9) {
            currentInterface = descriptors[offset + 2];
            currentAlternateSetting = descriptors[offset + 3];
        } else if (descriptorType == LIBUSB_DT_ENDPOINT && length >= 7 &&
                   currentInterface == targetInterface &&
                   currentAlternateSetting == targetAlternateSetting) {
            const int address = descriptors[offset + 2];
            const int attributes = descriptors[offset + 3];
            if ((targetAddress >= 0 ? address == targetAddress : (address & LIBUSB_ENDPOINT_IN) == 0) &&
                (attributes & LIBUSB_TRANSFER_TYPE_MASK) == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS) {
                const int rawMaxPacket = descriptors[offset + 4] | (descriptors[offset + 5] << 8);
                const int transactions = 1 + ((rawMaxPacket >> 11) & 0x03);
                return {
                    address,
                    (rawMaxPacket & 0x07FF) * transactions,
                    std::max(1, static_cast<int>(descriptors[offset + 6]))
                };
            }
        }
        offset += static_cast<size_t>(length);
    }
    return {};
}

bool readPioneerRouteSource(
    libusb_device_handle* handle,
    const PioneerMixerProfile& profile,
    int output,
    int& source
) {
    if (output < 0 || output >= profile.outputCount) return false;
    if (profile.routeReadMode == PioneerRouteReadMode::None) return false;
    uint8_t response[6]{};
    const bool allOutputs = profile.routeReadMode == PioneerRouteReadMode::AllOutputs;
    const uint16_t value = profile.routeReadMode == PioneerRouteReadMode::SingleOutputOneBased
        ? static_cast<uint16_t>(output + 1)
        : static_cast<uint16_t>(allOutputs ? 0 : output);
    const uint16_t length = static_cast<uint16_t>(allOutputs ? profile.outputCount : 2);
    const int rc = djmrec::tracedUsbControl(
        handle, LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_VENDOR | LIBUSB_RECIPIENT_DEVICE,
        0x00, value, kPioneerRouteIndex, response, length, 1000);
    const int expectedOutput = profile.routeReadMode == PioneerRouteReadMode::SingleOutputOneBased
        ? output + 1
        : output;
    if (rc != length || (!allOutputs && response[0] != expectedOutput)) {
        LOGW("%s route GET output %d failed: %s", profile.name, output + 1,
             rc < 0 ? libusb_error_name(rc) : "invalid response");
        return false;
    }
    source = allOutputs ? response[output] : response[1];
    return true;
}

bool writePioneerRouteSource(
    libusb_device_handle* handle,
    const PioneerMixerProfile& profile,
    int output,
    int source
) {
    if (output < 0 || output >= profile.outputCount || source < 0 || source > 0xFF) return false;
    const auto value = static_cast<uint16_t>(((output + 1) << 8) | source);
    const int rc = djmrec::tracedUsbControl(
        handle, LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_VENDOR | LIBUSB_RECIPIENT_DEVICE,
        0x03, value, kPioneerRouteIndex, nullptr, 0, 1000);
    if (rc != 0) {
        LOGW("%s route SET output %d value 0x%04x failed: %s", profile.name,
             output + 1, value, rc < 0 ? libusb_error_name(rc) : "unexpected response");
        return false;
    }
    return true;
}

bool setPioneerCaptureSampleRate(
    libusb_device_handle* handle,
    int endpointAddress,
    int sampleRate,
    const char* profileName
) {
    uint8_t value[3] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF)
    };
    const int rc = djmrec::tracedUsbControl(
        handle,
        LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_ENDPOINT,
        kUac1RequestSetCurrent,
        kUac1EndpointSamplingFrequencyControl,
        static_cast<uint16_t>(endpointAddress),
        value,
        sizeof(value),
        1000);
    if (rc != static_cast<int>(sizeof(value))) {
        LOGW("%s endpoint 0x%02x SET_CUR sampling frequency %d Hz unsupported: %s",
             profileName, endpointAddress, sampleRate,
             rc < 0 ? libusb_error_name(rc) : "short response");
        return false;
    }
    LOGI("%s endpoint 0x%02x initialized at %d Hz using Pioneer driver sequence",
         profileName, endpointAddress, sampleRate);
    return true;
}

int readPioneerEndpointSampleRate(
    libusb_device_handle* handle,
    int endpointAddress,
    const char* profileName
) {
    uint8_t value[3]{};
    const int rc = djmrec::tracedUsbControl(
        handle,
        LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_ENDPOINT,
        0x81,
        kUac1EndpointSamplingFrequencyControl,
        static_cast<uint16_t>(endpointAddress),
        value,
        sizeof(value),
        1000);
    if (rc != static_cast<int>(sizeof(value))) {
        LOGW("%s endpoint 0x%02x GET_CUR sampling frequency unsupported: %s",
             profileName, endpointAddress, rc < 0 ? libusb_error_name(rc) : "short response");
        return 0;
    }
    return value[0] | (value[1] << 8) | (value[2] << 16);
}

int readClockFrequency(libusb_device_handle* handle, int interfaceNumber, int clockSourceId) {
    uint8_t value[4]{};
    const int rc = djmrec::tracedUsbControl(
        handle,
        LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
        kUac2RequestGetCurrent,
        kUac2ClockFrequencyControl,
        static_cast<uint16_t>((clockSourceId << 8) | interfaceNumber),
        value,
        sizeof(value),
        1000);
    if (rc != static_cast<int>(sizeof(value))) {
        LOGW("Clock source %d GET_CUR failed: %s", clockSourceId,
             rc < 0 ? libusb_error_name(rc) : "short response");
        return 0;
    }
    return static_cast<int>(value[0]) |
        (static_cast<int>(value[1]) << 8) |
        (static_cast<int>(value[2]) << 16) |
        (static_cast<int>(value[3]) << 24);
}

bool setClockFrequency(libusb_device_handle* handle, int interfaceNumber, int clockSourceId, int sampleRate) {
    uint8_t value[4] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 24) & 0xFF)
    };
    const int rc = djmrec::tracedUsbControl(
        handle,
        LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
        kUac2RequestSetCurrent,
        kUac2ClockFrequencyControl,
        static_cast<uint16_t>((clockSourceId << 8) | interfaceNumber),
        value,
        sizeof(value),
        1000);
    if (rc != static_cast<int>(sizeof(value))) {
        LOGW("Clock source %d SET_CUR %d Hz failed: %s", clockSourceId, sampleRate,
             rc < 0 ? libusb_error_name(rc) : "short response");
        return false;
    }
    return true;
}

} // namespace

UsbIsoAudioSource::~UsbIsoAudioSource() {
    stop();
}

std::string UsbIsoAudioSource::start(const Config& config, FrameCallback callback) {
    if (mRunning.load(std::memory_order_acquire)) {
        return "already running";
    }
    mMixerProfile = findPioneerMixerProfile(config.vendorId, config.productId);
    if (mMixerProfile && mMixerProfile->captureInChannels > 0 &&
        (config.totalChannels != mMixerProfile->captureInChannels ||
         config.subframeSize != mMixerProfile->captureInSubframeBytes ||
         config.bitResolution != mMixerProfile->captureInBitResolution)) {
        return std::string(mMixerProfile->name) +
            " capture format mismatch between Kotlin and native profiles";
    }
    if (mMixerProfile && mMixerProfile->fixedCaptureInSampleRate > 0 &&
        config.requestedSampleRate != mMixerProfile->fixedCaptureInSampleRate) {
        return std::string(mMixerProfile->name) + " requires " +
            std::to_string(mMixerProfile->fixedCaptureInSampleRate) + " Hz capture";
    }
    if (config.fd < 0 || config.endpointAddress < 0 || config.maxPacketSize <= 0 ||
        config.totalChannels < 1 || config.subframeSize < 1 ||
        config.requestedSampleRate <= 0 ||
        (config.extractChannelOffset >= 0 && config.extractChannelOffset + std::min(2, config.totalChannels) > config.totalChannels)) {
        return "invalid capture configuration";
    }

    mConfig = config;
    mCallback = std::move(callback);
    mClaimedPlaybackInterface = -1;
    mPlaybackTransfers.clear();
    mPlaybackFrameRemainder = 0;
    mPioneerFallbackStage = 0;
    mResolvedChannelOffset = config.extractChannelOffset;
    mFramesSincePeakLog = 0;
    mLoggedPayloadWindow = false;
    mLoggedPayloadSignal = false;
    mChannelActivity.reset();
    mBytesSincePeakLog = 0;
    mNonZeroBytesSincePeakLog = 0;
    mRawPacketDumpsLogged = 0;
    mOpenedSampleRate.store(config.requestedSampleRate, std::memory_order_release);
    mPacketsCompleted.store(0, std::memory_order_relaxed);
    mPacketsMissed.store(0, std::memory_order_relaxed);
    mPacketsEmpty.store(0, std::memory_order_relaxed);
    mPacketsPartial.store(0, std::memory_order_relaxed);
    mBytesReceived.store(0, std::memory_order_relaxed);
    mNonZeroBytesReceived.store(0, std::memory_order_relaxed);
    mResubmitFailures.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(mRateProbeMutex);
        mRateProbeBytes = 0;
        mRateProbePackets = 0;
        mCapturePacketsPerSecond = 0;
        mConfirmedClockRate = mMixerProfile ? mMixerProfile->fixedCaptureInSampleRate : 0;
        mRateProbeStarted = false;
        mRateProbeResolved = false;
    }
    mPairPeaks.assign(static_cast<size_t>((config.totalChannels + 1) / 2), 0);
    mCarryover.clear();
    mCarryover.reserve(static_cast<size_t>(config.subframeSize) * config.totalChannels);

    libusb_init_option options[1]{};
    options[0].option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY;

    int rc = libusb_init_context(&mContext, options, 1);
    if (rc != LIBUSB_SUCCESS) {
        mContext = nullptr;
        return libusbErrorString("libusb_init", rc);
    }

    // This is the entire trick that makes root-free capture possible: Android's UsbManager has
    // already done the permission-prompt + open() dance for us, so libusb just needs to adopt
    // the fd -- it never touches /dev/bus/usb/** itself.
    rc = libusb_wrap_sys_device(mContext, static_cast<intptr_t>(config.fd), &mHandle);
    if (rc != LIBUSB_SUCCESS) {
        libusb_exit(mContext);
        mContext = nullptr;
        return libusbErrorString("libusb_wrap_sys_device", rc);
    }

    const auto captureEndpoint = findIsoOutEndpoint(config.rawDescriptors, config.interfaceNumber,
        config.alternateSetting, config.endpointAddress);
    const int captureSpeed = libusb_get_device_speed(libusb_get_device(mHandle));
    if (captureEndpoint.address >= 0 && captureSpeed >= LIBUSB_SPEED_FULL) {
        const int base = captureSpeed >= LIBUSB_SPEED_HIGH ? 8000 : 1000;
        mCapturePacketsPerSecond = base >> std::clamp(captureEndpoint.interval - 1, 0, 15);
    }

    // Best-effort; harmless if unsupported (Android has no competing kernel audio-class driver
    // holding the interface anyway).
    libusb_set_auto_detach_kernel_driver(mHandle, 1);

    if (mMixerProfile) {
        LOGI("Matched Pioneer mixer profile %s for %04x:%04x", mMixerProfile->name,
             config.vendorId, config.productId);
    }
    configurePioneerRecordingRoute();

    if (mMixerProfile && mMixerProfile->requiresPlaybackTraffic) {
        rc = libusb_claim_interface(mHandle, mMixerProfile->playbackInterface);
        if (rc != LIBUSB_SUCCESS) {
            restorePioneerRecordingRoute();
            libusb_close(mHandle);
            libusb_exit(mContext);
            mHandle = nullptr;
            mContext = nullptr;
            return libusbErrorString("Pioneer playback interface claim", rc);
        }
        mClaimedPlaybackInterface = mMixerProfile->playbackInterface;
        rc = libusb_set_interface_alt_setting(
            mHandle, mMixerProfile->playbackInterface, mMixerProfile->playbackAlternateSetting);
        if (rc != LIBUSB_SUCCESS) {
            restorePioneerRecordingRoute();
            libusb_release_interface(mHandle, mClaimedPlaybackInterface);
            mClaimedPlaybackInterface = -1;
            libusb_close(mHandle);
            libusb_exit(mContext);
            mHandle = nullptr;
            mContext = nullptr;
            return libusbErrorString("Pioneer playback alt setting", rc);
        }
        LOGI("%s duplex session activated playback interface %d alt %d", mMixerProfile->name,
             mMixerProfile->playbackInterface, mMixerProfile->playbackAlternateSetting);
    }

    if (config.clockControlInterfaceNumber >= 0 && config.clockSourceId >= 0) {
        rc = libusb_claim_interface(mHandle, config.clockControlInterfaceNumber);
        if (rc != LIBUSB_SUCCESS) {
            LOGW("Could not claim clock-control interface %d: %s; retaining device clock rate",
                 config.clockControlInterfaceNumber, libusb_error_name(rc));
        } else {
            mClaimedClockControlInterface = config.clockControlInterfaceNumber;
            int activeRate = readClockFrequency(mHandle, config.clockControlInterfaceNumber, config.clockSourceId);
            if (config.clockSupportsFrequencySet && activeRate != config.requestedSampleRate) {
                LOGI("Clock source %d active at %d Hz; requesting %d Hz", config.clockSourceId,
                     activeRate, config.requestedSampleRate);
                setClockFrequency(mHandle, config.clockControlInterfaceNumber, config.clockSourceId,
                                  config.requestedSampleRate);
                activeRate = readClockFrequency(mHandle, config.clockControlInterfaceNumber, config.clockSourceId);
            }
            if (activeRate > 0) {
                mOpenedSampleRate.store(activeRate, std::memory_order_release);
                mConfirmedClockRate = activeRate;
            }
            LOGI("Clock source %d active rate: %d Hz", config.clockSourceId,
                 mOpenedSampleRate.load(std::memory_order_acquire));
        }
    }

    rc = libusb_claim_interface(mHandle, config.interfaceNumber);
    if (rc != LIBUSB_SUCCESS) {
        restorePioneerRecordingRoute();
        if (mClaimedPlaybackInterface >= 0) {
            libusb_set_interface_alt_setting(mHandle, mClaimedPlaybackInterface, 0);
            libusb_release_interface(mHandle, mClaimedPlaybackInterface);
            mClaimedPlaybackInterface = -1;
        }
        if (mClaimedClockControlInterface >= 0) {
            libusb_release_interface(mHandle, mClaimedClockControlInterface);
            mClaimedClockControlInterface = -1;
        }
        libusb_close(mHandle);
        libusb_exit(mContext);
        mHandle = nullptr;
        mContext = nullptr;
        return libusbErrorString("libusb_claim_interface", rc);
    }

    // Standard USB control transfer (SET_INTERFACE) selecting the alt setting whose isochronous
    // endpoint is actually active -- UAC2 devices sit on alt setting 0 (zero-bandwidth, no
    // endpoint) until told otherwise.
    rc = libusb_set_interface_alt_setting(mHandle, config.interfaceNumber, config.alternateSetting);
    if (rc != LIBUSB_SUCCESS) {
        restorePioneerRecordingRoute();
        libusb_release_interface(mHandle, config.interfaceNumber);
        if (mClaimedPlaybackInterface >= 0) {
            libusb_set_interface_alt_setting(mHandle, mClaimedPlaybackInterface, 0);
            libusb_release_interface(mHandle, mClaimedPlaybackInterface);
            mClaimedPlaybackInterface = -1;
        }
        if (mClaimedClockControlInterface >= 0) {
            libusb_release_interface(mHandle, mClaimedClockControlInterface);
            mClaimedClockControlInterface = -1;
        }
        libusb_close(mHandle);
        libusb_exit(mContext);
        mHandle = nullptr;
        mContext = nullptr;
        return libusbErrorString("libusb_set_interface_alt_setting", rc);
    }

    // The route configuration attempted above (before any interface was claimed) works on
    // models whose vendor control pipe accepts requests pre-claim (confirmed: DJM-A9). On the
    // DJM-900NXS2, that same route GET instead fails outright at that point on real hardware --
    // every output stays unreadable, so no SET is ever attempted and the mixer's MIX/REC routing
    // is left wherever it happened to be (observed: digital silence on the USB pair despite a
    // perfectly healthy isochronous transport). Retry once now that an interface is actually
    // claimed, in case that ordering is what the vendor control pipe needs. Guarded so it's a
    // no-op when the earlier attempt already succeeded, to avoid any risk of regressing the
    // already-validated DJM-A9 path (configurePioneerRecordingRoute() resets its bookkeeping on
    // entry, which would otherwise wipe a route restorePioneerRecordingRoute() needs on stop()).
    if (mMixerProfile) {
        bool routeAlreadyEstablished;
        {
            std::lock_guard<std::mutex> lock(mDiagnosticMutex);
            routeAlreadyEstablished =
                std::any_of(mPioneerRoutesChanged.begin(), mPioneerRoutesChanged.end(),
                            [](bool changed) { return changed; }) ||
                std::any_of(mPioneerAppliedSources.begin(), mPioneerAppliedSources.end(),
                            [](int applied) { return applied >= 0; });
        }
        if (!routeAlreadyEstablished) {
            LOGI("%s: pre-claim route configuration did not take; retrying now that if%d is claimed",
                 mMixerProfile->name, config.interfaceNumber);
            configurePioneerRecordingRoute();
        }
    }

    if (!mMixerProfile && hasEndpointFrequencyControl(config.rawDescriptors, config.interfaceNumber,
            config.alternateSetting, config.endpointAddress)) {
        setPioneerCaptureSampleRate(mHandle, config.endpointAddress, config.requestedSampleRate, "USB Audio Class 1");
        const int endpointRate = readPioneerEndpointSampleRate(mHandle, config.endpointAddress, "USB Audio Class 1");
        if (endpointRate > 0) {
            mOpenedSampleRate.store(endpointRate, std::memory_order_release);
            mConfirmedClockRate = endpointRate;
        }
    }

    if (mMixerProfile && mMixerProfile->usesEndpointSampleRate) {
        // A USBPcap capture of Pioneer's own driver actually recording real audio (2026-07-20,
        // whit_sound_on.pcapng) showed it never sends a GET_CUR probe here at all: right after
        // SET_INTERFACE it unconditionally sends SET_CUR sampling frequency (bmRequestType=0x22,
        // bRequest=0x01, wValue=0x0100, wIndex=0x0082, 3-byte LE rate), OUT traffic starts within
        // ~150us of that SET completing, and the first real (nonzero) IN packet doesn't appear
        // until ~13ms after that. This code used to GET first and skip the SET whenever the GET
        // returned a plausible-looking rate -- but exactly like the MIX-route GET (separately
        // proven via pcap to return a static, non-informative value regardless of real state),
        // that GET result may not reflect whether the endpoint is actually armed, so gating the
        // SET on it could have been silently skipping the one command that arms real streaming.
        // Always SET now, unconditionally, matching the proven-working driver sequence.
        setPioneerCaptureSampleRate(
            mHandle, config.endpointAddress, config.requestedSampleRate, mMixerProfile->name);
        const int endpointRate = readPioneerEndpointSampleRate(
            mHandle, config.endpointAddress, mMixerProfile->name);
        if (endpointRate > 0) {
            mOpenedSampleRate.store(endpointRate, std::memory_order_release);
            // Vendor endpoint GET may echo a requested rate; packet timing verifies the wire.
            LOGI("%s capture endpoint reports active rate %d Hz", mMixerProfile->name, endpointRate);
        }
    }

    // Some UAC2 Pioneer models (notably DJM-S11) derive the capture clock from the active
    // playback stream but do not use the UAC1 endpoint-rate request above. Keep their OUT
    // traffic alive independently of the endpoint-rate initialization path.
    if (mMixerProfile && mMixerProfile->requiresPlaybackTraffic && mPlaybackTransfers.empty() &&
        !startPioneerPlaybackSilence(mOpenedSampleRate.load(std::memory_order_acquire))) {
        LOGW("%s could not start playback traffic; continuing capture-only", mMixerProfile->name);
    }

    const std::string channelDescription = config.extractChannelOffset < 0
        ? "auto stereo pair"
        : std::string("ch ") + std::to_string(config.extractChannelOffset + 1) + "-" +
            std::to_string(config.extractChannelOffset + 2);
    LOGI("Claimed iface %d alt %d, endpoint 0x%02x, maxPacketSize=%d, wire=%dch/%dbit (subframe %d "
         "bytes), clock=%dHz, extracting %s",
         config.interfaceNumber, config.alternateSetting, config.endpointAddress, config.maxPacketSize,
         config.totalChannels, config.bitResolution, config.subframeSize,
         mOpenedSampleRate.load(std::memory_order_acquire),
         channelDescription.c_str());
    if (!config.rawDescriptors.empty()) {
        LOGI("Received %zu raw USB descriptor bytes for native session", config.rawDescriptors.size());
    }
    if (config.feedbackEndpointAddress >= 0) {
        LOGI("Detected isochronous feedback endpoint 0x%02x maxPacketSize=%d; capture endpoint is adaptive",
             config.feedbackEndpointAddress, config.feedbackMaxPacketSize);
    }

    mTransfers.reserve(kNumTransfers);
    for (int i = 0; i < kNumTransfers; ++i) {
        libusb_transfer* transfer = libusb_alloc_transfer(kPacketsPerTransfer);
        if (!transfer) {
            stop();
            return "libusb_alloc_transfer failed";
        }
        const int bufferSize = config.maxPacketSize * kPacketsPerTransfer;
        auto* buffer = new uint8_t[bufferSize];
        libusb_fill_iso_transfer(
            transfer, mHandle, static_cast<unsigned char>(config.endpointAddress), buffer, bufferSize,
            kPacketsPerTransfer, &UsbIsoAudioSource::onTransferComplete, this, /*timeout=*/1000);
        libusb_set_iso_packet_lengths(transfer, static_cast<unsigned int>(config.maxPacketSize));
        mTransfers.push_back(transfer);
    }

    // Flip mRunning + start the event thread *before* submitting anything, so that if a later
    // submit fails partway through the loop below, the transfers that DID submit successfully
    // still have something pumping libusb_handle_events() to deliver their cancellation
    // completions during the stop() this function calls on the way out. Starting the thread
    // only after every submit succeeds would leave those in-flight transfers with no one
    // servicing them if a later submit failed.
    mRunning.store(true, std::memory_order_release);
    mEventThread = std::thread(&UsbIsoAudioSource::eventThreadLoop, this);

    for (auto* transfer : mTransfers) {
        if (!submitTransfer(transfer)) {
            stop();
            return "libusb_submit_transfer failed";
        }
    }
    for (auto* transfer : mPlaybackTransfers) {
        if (!submitPlaybackTransfer(transfer)) {
            stop();
            return "Pioneer playback transfer submission failed";
        }
    }

    return {};
}

bool UsbIsoAudioSource::startPioneerPlaybackSilence(int sampleRate) {
    if (!mMixerProfile || !mMixerProfile->requiresPlaybackTraffic) return false;
    const IsoEndpointInfo endpoint = findIsoOutEndpoint(
        mConfig.rawDescriptors, mMixerProfile->playbackInterface,
        mMixerProfile->playbackAlternateSetting);
    if (endpoint.address < 0 || endpoint.maxPacketSize <= 0 || sampleRate <= 0) {
        LOGW("%s playback OUT endpoint unavailable in raw descriptors", mMixerProfile->name);
        return false;
    }

    const int speed = libusb_get_device_speed(libusb_get_device(mHandle));
    const int basePacketsPerSecond =
        (speed == LIBUSB_SPEED_HIGH || speed == LIBUSB_SPEED_SUPER) ? 8000 : 1000;
    const int intervalShift = std::clamp(endpoint.interval - 1, 0, 10);
    mPlaybackPacketsPerSecond = std::max(1, basePacketsPerSecond >> intervalShift);
    mPlaybackFrameBytes =
        mMixerProfile->playbackOutChannels * mMixerProfile->playbackOutSubframeBytes;
    mPlaybackMaxPacketSize = endpoint.maxPacketSize;
    mPlaybackFrameRemainder = 0;

    mPlaybackTransfers.reserve(kNumTransfers);
    for (int index = 0; index < kNumTransfers; ++index) {
        libusb_transfer* transfer = libusb_alloc_transfer(kPacketsPerTransfer);
        if (!transfer) return false;
        const int bufferSize = mPlaybackMaxPacketSize * kPacketsPerTransfer;
        auto* buffer = new uint8_t[bufferSize]{};
        libusb_fill_iso_transfer(
            transfer, mHandle, static_cast<unsigned char>(endpoint.address), buffer, bufferSize,
            kPacketsPerTransfer, &UsbIsoAudioSource::onPlaybackTransferComplete, this, 1000);
        mPlaybackTransfers.push_back(transfer);
    }
    mPioneerFallbackStage = 1;
    LOGI("%s fallback strategy 1: streaming silence to endpoint 0x%02x at %d Hz "
         "(%dch packed 24-bit, %d packets/sec, maxPacket=%d)",
         mMixerProfile->name, endpoint.address, sampleRate, mMixerProfile->playbackOutChannels,
         mPlaybackPacketsPerSecond, mPlaybackMaxPacketSize);
    return true;
}

bool UsbIsoAudioSource::submitPlaybackTransfer(libusb_transfer* transfer) {
    const int sampleRate = std::max(1, mOpenedSampleRate.load(std::memory_order_acquire));
    int totalLength = 0;
    for (int packetIndex = 0; packetIndex < transfer->num_iso_packets; ++packetIndex) {
        mPlaybackFrameRemainder += static_cast<uint64_t>(sampleRate);
        const int frames = static_cast<int>(mPlaybackFrameRemainder / mPlaybackPacketsPerSecond);
        mPlaybackFrameRemainder %= static_cast<uint64_t>(mPlaybackPacketsPerSecond);
        const int packetLength = frames * mPlaybackFrameBytes;
        if (packetLength <= 0 || packetLength > mPlaybackMaxPacketSize) {
            LOGE("Pioneer playback packet %d exceeds endpoint capacity %d", packetLength,
                 mPlaybackMaxPacketSize);
            return false;
        }
        transfer->iso_packet_desc[packetIndex].length = static_cast<unsigned int>(packetLength);
        totalLength += packetLength;
    }
    transfer->length = totalLength;
    mOutstandingTransfers.fetch_add(1, std::memory_order_relaxed);
    const int rc = libusb_submit_transfer(transfer);
    if (rc != LIBUSB_SUCCESS) {
        mOutstandingTransfers.fetch_sub(1, std::memory_order_relaxed);
        LOGE("Pioneer playback submit failed: %s", libusb_error_name(rc));
        return false;
    }
    return true;
}

bool UsbIsoAudioSource::submitTransfer(libusb_transfer* transfer) {
    mOutstandingTransfers.fetch_add(1, std::memory_order_relaxed);
    const int rc = libusb_submit_transfer(transfer);
    if (rc != LIBUSB_SUCCESS) {
        mOutstandingTransfers.fetch_sub(1, std::memory_order_relaxed);
        LOGE("libusb_submit_transfer failed: %s", libusb_error_name(rc));
        return false;
    }
    return true;
}

void UsbIsoAudioSource::eventThreadLoop() {
    struct timeval tv {};
    tv.tv_usec = 100 * 1000; // 100ms -- just needs to be short enough to notice mRunning flip
    try {
        while (mRunning.load(std::memory_order_acquire) ||
               mOutstandingTransfers.load(std::memory_order_relaxed) > 0) {
            const int rc = libusb_handle_events_timeout_completed(mContext, &tv, nullptr);
            if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_INTERRUPTED) {
                LOGE("libusb_handle_events failed: %s", libusb_error_name(rc));
                mRunning.store(false, std::memory_order_release);
                break;
            }
        }
    } catch (const std::exception& error) {
        LOGE("USB event thread crashed: %s", error.what());
        mRunning.store(false, std::memory_order_release);
    } catch (...) {
        LOGE("USB event thread crashed: unknown exception");
        mRunning.store(false, std::memory_order_release);
    }
}

void UsbIsoAudioSource::onTransferComplete(libusb_transfer* transfer) {
    static_cast<UsbIsoAudioSource*>(transfer->user_data)->handleCompletedTransfer(transfer);
}

void UsbIsoAudioSource::onPlaybackTransferComplete(libusb_transfer* transfer) {
    static_cast<UsbIsoAudioSource*>(transfer->user_data)->handlePlaybackTransfer(transfer);
}

void UsbIsoAudioSource::handlePlaybackTransfer(libusb_transfer* transfer) {
    mOutstandingTransfers.fetch_sub(1, std::memory_order_relaxed);
    if (!mRunning.load(std::memory_order_acquire)) return;

    if (transfer->status != LIBUSB_TRANSFER_COMPLETED) {
        LOGW("Pioneer playback transfer completed with status %d", transfer->status);
    }
    if (!submitPlaybackTransfer(transfer)) {
        mResubmitFailures.fetch_add(1, std::memory_order_relaxed);
        mRunning.store(false, std::memory_order_release);
    }
}

void UsbIsoAudioSource::handleCompletedTransfer(libusb_transfer* transfer) {
    mOutstandingTransfers.fetch_sub(1, std::memory_order_relaxed);

    if (!mRunning.load(std::memory_order_acquire)) {
        // Shutting down -- this transfer is done for good; stop() frees it once every
        // outstanding transfer has drained back through here.
        return;
    }

    for (int i = 0; i < transfer->num_iso_packets; ++i) {
        const libusb_iso_packet_descriptor& packet = transfer->iso_packet_desc[i];
        if (packet.status != LIBUSB_TRANSFER_COMPLETED) {
            mPacketsMissed.fetch_add(1, std::memory_order_relaxed);
            continue;
        }
        mPacketsCompleted.fetch_add(1, std::memory_order_relaxed);
        updateMeasuredSampleRate(packet.actual_length);
        if (packet.actual_length == 0) {
            mPacketsEmpty.fetch_add(1, std::memory_order_relaxed);
            continue;
        }
        if (packet.actual_length < packet.length) {
            mPacketsPartial.fetch_add(1, std::memory_order_relaxed);
        }
        mBytesReceived.fetch_add(packet.actual_length, std::memory_order_relaxed);
        {
            unsigned char* data = libusb_get_iso_packet_buffer_simple(transfer, i);
            demuxAndEmit(data, packet.actual_length);
        }
    }

    if (!submitTransfer(transfer)) {
        mResubmitFailures.fetch_add(1, std::memory_order_relaxed);
        LOGE("Re-submit failed after completed transfer; stopping capture");
        mRunning.store(false, std::memory_order_release);
    }
}

UsbIsoAudioSource::TransferStatsSnapshot UsbIsoAudioSource::getTransferStats() const {
    return {
        mPacketsCompleted.load(std::memory_order_relaxed),
        mPacketsMissed.load(std::memory_order_relaxed),
        mPacketsEmpty.load(std::memory_order_relaxed),
        mPacketsPartial.load(std::memory_order_relaxed),
        mBytesReceived.load(std::memory_order_relaxed),
        mNonZeroBytesReceived.load(std::memory_order_relaxed),
        mResubmitFailures.load(std::memory_order_relaxed)
    };
}

std::string UsbIsoAudioSource::diagnosticSummary() const {
    const auto stats = getTransferStats();
    std::array<int, 6> original{};
    std::array<int, 6> applied{};
    std::array<bool, 6> changed{};
    {
        std::lock_guard<std::mutex> lock(mDiagnosticMutex);
        original = mPioneerOriginalSources;
        applied = mPioneerAppliedSources;
        changed = mPioneerRoutesChanged;
    }

    std::ostringstream out;
    out << "running=" << (isRunning() ? "true" : "false") << '\n'
        << "profile=" << (mMixerProfile ? mMixerProfile->name : "none") << '\n'
        << "usb_id=" << std::hex << mConfig.vendorId << ':' << mConfig.productId << std::dec << '\n'
        << "capture=if" << mConfig.interfaceNumber << "/alt" << mConfig.alternateSetting
        << " ep=0x" << std::hex << mConfig.endpointAddress << std::dec
        << " max_packet=" << mConfig.maxPacketSize << '\n'
        << "wire=" << mConfig.totalChannels << "ch/" << mConfig.bitResolution
        << "bit/subframe" << mConfig.subframeSize << '\n'
        << "sample_rate=requested:" << mConfig.requestedSampleRate
        << " opened:" << openedSampleRate() << '\n'
        << "channel_offset=requested:" << mConfig.extractChannelOffset
        << " resolved:" << mResolvedChannelOffset.load(std::memory_order_relaxed) << '\n'
        << "clock=control_if:" << mConfig.clockControlInterfaceNumber
        << " source:" << mConfig.clockSourceId
        << " settable:" << (mConfig.clockSupportsFrequencySet ? "true" : "false") << '\n'
        << "feedback=ep:" << mConfig.feedbackEndpointAddress
        << " max_packet:" << mConfig.feedbackMaxPacketSize << '\n'
        << "playback_keepalive=required:"
        << (mMixerProfile && mMixerProfile->requiresPlaybackTraffic ? "true" : "false")
        << " claimed_if:" << mClaimedPlaybackInterface
        << " transfers:" << mPlaybackTransfers.size() << '\n'
        << "route_fallback_stage=" << mPioneerFallbackStage.load(std::memory_order_relaxed) << '\n';

    if (mMixerProfile) {
        for (int output = 0; output < mMixerProfile->outputCount; ++output) {
            out << "route_output_" << (output + 1)
                << "=original:" << original[output]
                << " applied:" << applied[output]
                << " changed:" << (changed[output] ? "true" : "false") << '\n';
        }
    }
    out << "transfers=completed:" << stats.packetsCompleted
        << " missed:" << stats.packetsMissed
        << " empty:" << stats.packetsEmpty
        << " partial:" << stats.packetsPartial
        << " bytes:" << stats.bytesReceived
        << " nonzero_bytes:" << stats.nonZeroBytesReceived
        << " resubmit_failures:" << stats.resubmitFailures;
    out << '\n' << mChannelActivity.summary(mConfig.totalChannels);
    return out.str();
}

void UsbIsoAudioSource::configurePioneerRecordingRoute() {
    {
        std::lock_guard<std::mutex> lock(mDiagnosticMutex);
        mPioneerOriginalSources.fill(-1);
        mPioneerAppliedSources.fill(-1);
        mPioneerRoutesChanged.fill(false);
    }
    if (!mHandle || !mMixerProfile) return;

    int output = mMixerProfile->defaultOutput;
    if (mConfig.extractChannelOffset >= 0) {
        const int requestedOutput = mConfig.extractChannelOffset / 2;
        if (requestedOutput < mMixerProfile->outputCount) {
            output = requestedOutput;
        } else {
            // A valid wire pair may be fixed rather than vendor-configurable.
            // Honor explicit selection instead of silently recording another pair.
            LOGI("%s USB output %d uses its existing fixed route",
                 mMixerProfile->name, requestedOutput + 1);
            return;
        }
    } else if (mMixerProfile == &kDjmS11Profile) {
        // Never auto-select a deck input instead of S11's dedicated REC OUT pair.
        mResolvedChannelOffset.store(mMixerProfile->defaultOutput * 2, std::memory_order_relaxed);
    }
    routePioneerOutputToMix(output);
    mPioneerFallbackStage = 1;
}

void UsbIsoAudioSource::routePioneerOutputToMix(int output) {
    if (!mHandle || !mMixerProfile || output < 0 || output >= mMixerProfile->outputCount) return;
    int currentSource = -1;
    const bool readCurrent =
        readPioneerRouteSource(mHandle, *mMixerProfile, output, currentSource);
    if (!readCurrent) {
        LOGW("%s USB output %d route is unreadable; refusing an unrestorable change",
             mMixerProfile->name, output + 1);
        return;
    }
    const int mixWithMicSource = mMixerProfile->mixWithMicSources[output];
    const int mixWithoutMicSource = mMixerProfile->mixWithoutMicSources[output];
    if (mixWithoutMicSource < 0) return;
    if (currentSource == mixWithMicSource || currentSource == mixWithoutMicSource) {
        std::lock_guard<std::mutex> lock(mDiagnosticMutex);
        mPioneerAppliedSources[output] = currentSource;
        LOGI("%s USB output %d already routed to MIX (source 0x%02x)",
             mMixerProfile->name, output + 1, currentSource);
        return;
    }

    if (!writePioneerRouteSource(
            mHandle, *mMixerProfile, output, mixWithoutMicSource)) return;
    int verifiedSource = -1;
    if (!readPioneerRouteSource(mHandle, *mMixerProfile, output, verifiedSource) ||
        verifiedSource != mixWithoutMicSource) {
        LOGW("%s USB output %d MIX source did not verify: expected=0x%02x actual=0x%02x",
             mMixerProfile->name, output + 1, mixWithoutMicSource, verifiedSource);
        writePioneerRouteSource(mHandle, *mMixerProfile, output, currentSource);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(mDiagnosticMutex);
        mPioneerOriginalSources[output] = currentSource;
        mPioneerAppliedSources[output] = mixWithoutMicSource;
        mPioneerRoutesChanged[output] = true;
    }
    LOGI("%s USB output %d routed to MIX/REC OUT, source=0x%02x previous=0x%02x",
         mMixerProfile->name, output + 1, mixWithoutMicSource, currentSource);
}

void UsbIsoAudioSource::routeAllPioneerOutputsToMix() {
    if (!mMixerProfile) return;
    LOGI("%s fallback: route MIX to all %d configurable USB output pairs",
         mMixerProfile->name, mMixerProfile->outputCount);
    for (int output = 0; output < mMixerProfile->outputCount; ++output) {
        routePioneerOutputToMix(output);
    }
}

void UsbIsoAudioSource::restorePioneerRecordingRoute() {
    if (!mHandle || !mMixerProfile) return;
    for (int output = 0; output < mMixerProfile->outputCount; ++output) {
        if (!mPioneerRoutesChanged[output] || mPioneerOriginalSources[output] < 0) continue;
        int currentSource = -1;
        if (!readPioneerRouteSource(mHandle, *mMixerProfile, output, currentSource) ||
            currentSource != mPioneerAppliedSources[output]) {
            LOGW("%s USB output %d changed externally; not restoring previous route",
                 mMixerProfile->name, output + 1);
            continue;
        }
        if (writePioneerRouteSource(
                mHandle, *mMixerProfile, output, mPioneerOriginalSources[output])) {
            LOGI("%s USB output %d restored to source 0x%02x", mMixerProfile->name,
                 output + 1, mPioneerOriginalSources[output]);
        }
        {
            std::lock_guard<std::mutex> lock(mDiagnosticMutex);
            mPioneerRoutesChanged[output] = false;
        }
    }
}

int UsbIsoAudioSource::waitForMeasuredSampleRate(int timeoutMs) {
    std::unique_lock<std::mutex> lock(mRateProbeMutex);
    mRateProbeReady.wait_for(lock, std::chrono::milliseconds(std::max(0, timeoutMs)), [this] {
        return mRateProbeResolved || !mRunning.load(std::memory_order_acquire);
    });
    if (!mRunning.load(std::memory_order_acquire)) return 0;
    if (!mRateProbeResolved) {
        mOpenedSampleRate.store(mConfirmedClockRate, std::memory_order_release);
        mRateProbeResolved = true;
    }
    return mOpenedSampleRate.load(std::memory_order_acquire);
}

void UsbIsoAudioSource::updateMeasuredSampleRate(size_t payloadBytes) {
    if (mRateProbeResolved.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> lock(mRateProbeMutex);
    if (mRateProbeResolved) return;
    const auto now = std::chrono::steady_clock::now();
    if (!mRateProbeStarted) {
        mRateProbeStart = now;
        mRateProbeStarted = true;
        return; // The first packet's time interval started before the observation.
    }
    mRateProbeBytes += payloadBytes;
    ++mRateProbePackets;
    const double elapsed = std::chrono::duration<double>(now - mRateProbeStart).count();
    if (mCapturePacketsPerSecond > 0 ? mRateProbePackets < mCapturePacketsPerSecond / 2 : elapsed < 1.0) return;
    const uint64_t frames = mRateProbeBytes / (mConfig.subframeSize * mConfig.totalChannels);
    const int rawWallRate = elapsed > 0 ? static_cast<int>(std::lround(frames / elapsed)) : 0;
    const int packetRate = usbPacketSampleRate(frames, mRateProbePackets, mCapturePacketsPerSecond);
    const int resolved = mConfirmedClockRate > 0 ? mConfirmedClockRate :
        mCapturePacketsPerSecond > 0 ? packetRate : nominalUsbSampleRate(rawWallRate);
    mOpenedSampleRate.store(resolved, std::memory_order_release);
    LOGI("USB rate resolved=%d Hz; packet_rate=%d Hz; wall_estimate=%d Hz; confirmed_clock=%d Hz; packets_per_second=%d",
        resolved, packetRate, rawWallRate, mConfirmedClockRate, mCapturePacketsPerSecond);
    mRateProbeResolved.store(true, std::memory_order_release);
    mRateProbeReady.notify_all();
}

void UsbIsoAudioSource::demuxAndEmit(const uint8_t* data, size_t length) {
    const size_t frameSize = static_cast<size_t>(mConfig.subframeSize) * mConfig.totalChannels;
    if (frameSize == 0) {
        return;
    }

    mBytesSincePeakLog += length;
    uint64_t nonZeroBytes = 0;
    for (size_t i = 0; i < length; ++i) {
        if (data[i] != 0) {
            ++mNonZeroBytesSincePeakLog;
            ++nonZeroBytes;
        }
    }
    if (nonZeroBytes > 0) {
        mNonZeroBytesReceived.fetch_add(nonZeroBytes, std::memory_order_relaxed);
    }

    // One-time raw hex dump of the first few completed packets per capture session, taken
    // directly from the untouched wire bytes (same `data`/`length` the nonZeroBytes scan above
    // just walked) -- lets a human eyeball whether the endpoint is truly emitting all-zero
    // payload versus the demux/format math downstream misinterpreting real content.
    if (mRawPacketDumpsLogged < 5) {
        ++mRawPacketDumpsLogged;
        const size_t dumpLen = std::min<size_t>(length, 64);
        char hex[64 * 3 + 1];
        char* p = hex;
        for (size_t i = 0; i < dumpLen; ++i) {
            p += std::snprintf(p, 4, "%02x ", data[i]);
        }
        *p = '\0';
        LOGI("raw iso packet #%d dump (len=%zu, nonzero_in_packet=%llu): %s",
             mRawPacketDumpsLogged, length,
             static_cast<unsigned long long>(nonZeroBytes), hex);
    }

    // Frames from a UAC2 isochronous endpoint are not guaranteed to align to packet
    // boundaries, so leftover bytes from the previous packet are stitched onto the front of
    // this one before we start slicing out whole frames.
    mWorking.resize(mCarryover.size() + length);
    if (!mCarryover.empty()) {
        std::memcpy(mWorking.data(), mCarryover.data(), mCarryover.size());
    }
    std::memcpy(mWorking.data() + mCarryover.size(), data, length);

    const size_t completeFrames = mWorking.size() / frameSize;
    const size_t consumedBytes = completeFrames * frameSize;

    if (completeFrames > 0) {
        if (mScratch.size() < completeFrames * 2) {
            mScratch.resize(completeFrames * 2);
        }

        const int subframe = mConfig.subframeSize;
        mChannelActivity.observe(mWorking.data(), completeFrames, mConfig.totalChannels, subframe,
            mMixerProfile && mConfig.bitResolution <= 24);
        for (int offset = 0; offset + 1 < mConfig.totalChannels; offset += 2) {
            const auto pairMagnitude = std::max(mChannelActivity.peak(offset), mChannelActivity.peak(offset + 1));
            const size_t pairIndex = static_cast<size_t>(offset / 2);
            if (pairIndex < mPairPeaks.size()) {
                mPairPeaks[pairIndex] = std::max(mPairPeaks[pairIndex], pairMagnitude);
            }
        }

        const int selectedOffset = std::max(
            0, mResolvedChannelOffset.load(std::memory_order_relaxed));
        const int offsetBytes = selectedOffset * subframe;

        for (size_t f = 0; f < completeFrames; ++f) {
            const uint8_t* frameBase = mWorking.data() + f * frameSize + offsetBytes;
            for (int ch = 0; ch < 2; ++ch) {
                const uint8_t* s = frameBase + static_cast<size_t>(mConfig.totalChannels == 1 ? 0 : ch) * subframe;
                mScratch[f * 2 + ch] = decodeUsbPcm(s, subframe, mMixerProfile && mConfig.bitResolution <= 24);
            }
        }

        if (mCallback) {
            mCallback(mScratch.data(), completeFrames);
        }

        mFramesSincePeakLog += completeFrames;
        if (mFramesSincePeakLog >= static_cast<size_t>(
                std::max(1, mOpenedSampleRate.load(std::memory_order_acquire)))) {
            // Inventory once, then first signal if capture initially started silent.
            // Peak windows still update every second for routing and diagnostics.
            if (!mLoggedPayloadWindow || (!mLoggedPayloadSignal && mNonZeroBytesSincePeakLog > 0)) {
                LOGI("USB raw payload nonzero bytes=%llu/%llu; decoded pair peaks: %s; selected ch %d-%d",
                     static_cast<unsigned long long>(mNonZeroBytesSincePeakLog),
                     static_cast<unsigned long long>(mBytesSincePeakLog),
                     peakSummary(mPairPeaks).c_str(), selectedOffset + 1, selectedOffset + 2);
                mLoggedPayloadWindow = true;
                mLoggedPayloadSignal = mNonZeroBytesSincePeakLog > 0;
            }
            if (mMixerProfile) {
                const int fallbackStage = mPioneerFallbackStage.load(std::memory_order_relaxed);
                // mNonZeroBytesReceived is cumulative for the whole session -- a single stray
                // nonzero byte anywhere in the stream's history (isochronous framing glitch at
                // startup, USB electrical noise) would satisfy ">0" forever and latch this
                // fallback into "succeeded", permanently skipping routeAllPioneerOutputsToMix()
                // even if every window since has been 100% silent. Use the per-window counter
                // instead so "succeeded" means *this* window actually carried signal.
                if (mNonZeroBytesSincePeakLog > 0 &&
                    fallbackStage > 0 && fallbackStage < 3) {
                    LOGI("%s fallback strategy %d succeeded: capture payload is non-zero",
                         mMixerProfile->name, fallbackStage);
                    mPioneerFallbackStage.store(3, std::memory_order_relaxed);
                } else if (mNonZeroBytesSincePeakLog == 0 && fallbackStage == 1) {
                    mPioneerFallbackStage.store(2, std::memory_order_relaxed);
                    routeAllPioneerOutputsToMix();
                } else if (mNonZeroBytesSincePeakLog == 0 && fallbackStage == 2) {
                    LOGW("%s fallback strategies exhausted: all MIX routes still produce an "
                         "all-zero capture payload", mMixerProfile->name);
                    mPioneerFallbackStage.store(4, std::memory_order_relaxed);
                }
            }
            if (mConfig.extractChannelOffset < 0) {
                // Auto-pick decisions used to be re-evaluated on every incoming packet against
                // mPairPeaks *while it was still accumulating* for the current window -- multiple
                // output pairs carrying genuinely comparable real-audio amplitude (confirmed on
                // DJM-900NXS2 once real signal started flowing, 2026-07-20: all 5 pairs within
                // ~20% of each other) meant whichever pair's running max happened to be highest at
                // that exact instant kept leapfrogging, flipping the selected pair dozens of times
                // per second. That mid-recording channel-switching is what produced the reported
                // quiet/distorted output: FLAC frames interleaved from different pairs mid-stream.
                // Wait for one finished window containing audible signal, then lock that pair for
                // the lifetime of this capture. Re-selecting later can splice unrelated routed
                // outputs into one recording even when the comparison itself is race-free.
                uint32_t bestMagnitude = 0;
                int bestOffset = 0;
                for (size_t pairIndex = 0; pairIndex < mPairPeaks.size(); ++pairIndex) {
                    const uint32_t pairMagnitude = mPairPeaks[pairIndex];
                    if (pairMagnitude > bestMagnitude) {
                        bestMagnitude = pairMagnitude;
                        bestOffset = static_cast<int>(pairIndex * 2);
                    }
                }

                constexpr uint32_t kAudibleThreshold = 1u << 20;
                const int currentOffset = mResolvedChannelOffset.load(std::memory_order_relaxed);
                if (currentOffset < 0 && bestMagnitude >= kAudibleThreshold) {
                    mResolvedChannelOffset.store(bestOffset, std::memory_order_relaxed);
                    LOGI("Locked AUTO capture to USB channels %d-%d for this session",
                         bestOffset + 1, bestOffset + 2);
                }
            }
            std::fill(mPairPeaks.begin(), mPairPeaks.end(), 0);
            mChannelActivity.publish();
            mFramesSincePeakLog = 0;
            mBytesSincePeakLog = 0;
            mNonZeroBytesSincePeakLog = 0;
        }
    }

    const size_t leftover = mWorking.size() - consumedBytes;
    mCarryover.assign(mWorking.data() + consumedBytes, mWorking.data() + consumedBytes + leftover);
}

void UsbIsoAudioSource::stop() {
    const bool wasRunning = mRunning.exchange(false, std::memory_order_acq_rel);
    mRateProbeReady.notify_all();
    if (!wasRunning && mTransfers.empty() && !mHandle) {
        return; // never started, or already fully stopped
    }

    for (auto* transfer : mTransfers) {
        libusb_cancel_transfer(transfer); // no-op (returns an error, harmless) if not in-flight
    }
    for (auto* transfer : mPlaybackTransfers) {
        libusb_cancel_transfer(transfer);
    }

    if (mEventThread.joinable()) {
        mEventThread.join();
    }

    for (auto* transfer : mTransfers) {
        delete[] transfer->buffer;
        libusb_free_transfer(transfer);
    }
    mTransfers.clear();
    for (auto* transfer : mPlaybackTransfers) {
        delete[] transfer->buffer;
        libusb_free_transfer(transfer);
    }
    mPlaybackTransfers.clear();

    if (mHandle) {
        restorePioneerRecordingRoute();
        libusb_set_interface_alt_setting(mHandle, mConfig.interfaceNumber, 0);
        libusb_release_interface(mHandle, mConfig.interfaceNumber);
        // DJM-900NXS2's playback (OUT) endpoint lives on the same interface+alt-setting as
        // capture (both playbackInterface and config.interfaceNumber are 0), so the release
        // immediately above already released it -- a second release/alt-setting call on the same
        // interface number is redundant. libusb's own claimed_interfaces bitmap makes the second
        // call a harmless no-op at the core.c level, but skip it outright rather than lean on
        // that: a crash traced to a destroyed mutex inside libusb_close() surfaced right after
        // this dual-claim path was introduced, and removing the redundant call is a safe
        // simplification regardless of whether it was the actual cause.
        if (mClaimedPlaybackInterface >= 0 && mClaimedPlaybackInterface != mConfig.interfaceNumber) {
            libusb_set_interface_alt_setting(mHandle, mClaimedPlaybackInterface, 0);
            libusb_release_interface(mHandle, mClaimedPlaybackInterface);
        }
        mClaimedPlaybackInterface = -1;
        if (mClaimedClockControlInterface >= 0) {
            libusb_release_interface(mHandle, mClaimedClockControlInterface);
            mClaimedClockControlInterface = -1;
        }
        libusb_close(mHandle);
        mHandle = nullptr;
    }
    if (mContext) {
        libusb_exit(mContext);
        mContext = nullptr;
    }

    mCallback = nullptr;
    LOGI("USB iso capture stopped");
}

} // namespace djmrec
