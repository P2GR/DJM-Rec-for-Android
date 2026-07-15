#include "UsbIsoAudioSource.h"

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

int32_t decodeCanonicalSample(const uint8_t* sample, int subframeSize, int bitResolution) {
    switch (subframeSize) {
        case 1:
            return static_cast<int32_t>(static_cast<int8_t>(sample[0])) << 24;
        case 2: {
            auto v = static_cast<int16_t>(sample[0] | (sample[1] << 8));
            return static_cast<int32_t>(v) << 16;
        }
        case 3: {
            int32_t v = sample[0] | (sample[1] << 8) | (sample[2] << 16);
            if (v & 0x00800000) {
                v |= static_cast<int32_t>(0xFF000000);
            }
            return v << 8;
        }
        case 4:
        default: {
            auto v = static_cast<int32_t>(
                static_cast<uint32_t>(sample[0]) | (static_cast<uint32_t>(sample[1]) << 8) |
                (static_cast<uint32_t>(sample[2]) << 16) | (static_cast<uint32_t>(sample[3]) << 24));
            return (bitResolution > 0 && bitResolution <= 24) ? (v << 8) : v;
        }
    }
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

constexpr int kDjmA9VendorId = 0x2B73;
constexpr int kDjmA9ProductId = 0x003C;
constexpr uint16_t kDjmA9RouteIndex = 0x8002;
constexpr int kDjmA9PlaybackInterface = 1;
constexpr int kDjmA9PlaybackAlternateSetting = 1;
constexpr int kDjmA9PlaybackChannels = 10;
constexpr int kDjmA9PlaybackSubframeBytes = 3;

struct IsoEndpointInfo {
    int address = -1;
    int maxPacketSize = 0;
    int interval = 1;
};

IsoEndpointInfo findIsoOutEndpoint(
    const std::vector<uint8_t>& descriptors,
    int targetInterface,
    int targetAlternateSetting
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
            if ((address & LIBUSB_ENDPOINT_IN) == 0 &&
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

// Exact route values from Pioneer DJM-A9_Setup.dll 1.100.002.0.
constexpr uint16_t kDjmA9RouteValues[5][13] = {
    {0x010A, 0x0131, 0x0132, 0x0133, 0x0134, 0x0111, 0x0112,
     0x0113, 0x0114, 0x0107, 0x0108, 0x0109, 0x010E},
    {0x0203, 0x0201, 0x0202, 0x0205, 0x0206, 0x0207, 0x0208,
     0x0209, 0x020A, 0x020E, 0, 0, 0},
    {0x0303, 0x0301, 0x0302, 0x0305, 0x0306, 0x0307, 0x0308,
     0x0309, 0x030A, 0x030E, 0, 0, 0},
    {0x0403, 0x0401, 0x0402, 0x0405, 0x0406, 0x0407, 0x0408,
     0x0409, 0x040A, 0x040E, 0, 0, 0},
    {0x0503, 0x0501, 0x0502, 0x0505, 0x0506, 0x0507, 0x0508,
     0x0509, 0x050A, 0x050E, 0, 0, 0},
};

bool readDjmA9RouteSource(libusb_device_handle* handle, int output, int& source) {
    uint8_t response[2]{};
    const int rc = libusb_control_transfer(
        handle, LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_VENDOR | LIBUSB_RECIPIENT_DEVICE,
        0x00, static_cast<uint16_t>(output), kDjmA9RouteIndex,
        response, sizeof(response), 1000);
    if (rc != static_cast<int>(sizeof(response)) || response[0] != output) {
        LOGW("DJM-A9 route GET output %d failed: %s", output + 1,
             rc < 0 ? libusb_error_name(rc) : "invalid response");
        return false;
    }
    // Firmware returns its source code (low byte of the SET value), not the
    // zero-based UI index used to address kDjmA9RouteValues.
    source = response[1];
    return true;
}

bool writeDjmA9RouteValue(libusb_device_handle* handle, int output, uint16_t value) {
    if (output < 0 || output >= 5 || (value >> 8) != output + 1) return false;
    if (value == 0) return false;
    const int rc = libusb_control_transfer(
        handle, LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_VENDOR | LIBUSB_RECIPIENT_DEVICE,
        0x03, value, kDjmA9RouteIndex, nullptr, 0, 1000);
    if (rc != 0) {
        LOGW("DJM-A9 route SET output %d value 0x%04x failed: %s",
             output + 1, value, rc < 0 ? libusb_error_name(rc) : "unexpected response");
        return false;
    }
    return true;
}

bool writeDjmA9Route(libusb_device_handle* handle, int output, int route) {
    if (output < 0 || output >= 5 || route < 0 || route >= 13) return false;
    return writeDjmA9RouteValue(handle, output, kDjmA9RouteValues[output][route]);
}

bool writeDjmA9RouteSource(libusb_device_handle* handle, int output, int source) {
    if (source < 0 || source > 0xFF) return false;
    const auto value = static_cast<uint16_t>(((output + 1) << 8) | source);
    return writeDjmA9RouteValue(handle, output, value);
}

bool setDjmA9CaptureSampleRate(
    libusb_device_handle* handle,
    int endpointAddress,
    int sampleRate
) {
    uint8_t value[3] = {
        static_cast<uint8_t>(sampleRate & 0xFF),
        static_cast<uint8_t>((sampleRate >> 8) & 0xFF),
        static_cast<uint8_t>((sampleRate >> 16) & 0xFF)
    };
    const int rc = libusb_control_transfer(
        handle,
        LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_ENDPOINT,
        kUac1RequestSetCurrent,
        kUac1EndpointSamplingFrequencyControl,
        static_cast<uint16_t>(endpointAddress),
        value,
        sizeof(value),
        1000);
    if (rc != static_cast<int>(sizeof(value))) {
        LOGW("DJM-A9 endpoint 0x%02x SET_CUR sampling frequency %d Hz unsupported: %s",
             endpointAddress, sampleRate,
             rc < 0 ? libusb_error_name(rc) : "short response");
        return false;
    }
    LOGI("DJM-A9 endpoint 0x%02x initialized at %d Hz using Pioneer driver sequence",
         endpointAddress, sampleRate);
    return true;
}

int readDjmA9EndpointSampleRate(libusb_device_handle* handle, int endpointAddress) {
    uint8_t value[3]{};
    const int rc = libusb_control_transfer(
        handle,
        LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_ENDPOINT,
        0x81,
        kUac1EndpointSamplingFrequencyControl,
        static_cast<uint16_t>(endpointAddress),
        value,
        sizeof(value),
        1000);
    if (rc != static_cast<int>(sizeof(value))) {
        LOGW("DJM-A9 endpoint 0x%02x GET_CUR sampling frequency unsupported: %s",
             endpointAddress, rc < 0 ? libusb_error_name(rc) : "short response");
        return 0;
    }
    return value[0] | (value[1] << 8) | (value[2] << 16);
}

int readClockFrequency(libusb_device_handle* handle, int interfaceNumber, int clockSourceId) {
    uint8_t value[4]{};
    const int rc = libusb_control_transfer(
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
    const int rc = libusb_control_transfer(
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

int normalizeSampleRate(int measuredRate) {
    constexpr int kCommonRates[] = {8000, 11025, 12000, 16000, 22050, 24000, 32000,
                                    44100, 48000, 88200, 96000, 176400, 192000};
    int closest = measuredRate;
    int closestDistance = INT_MAX;
    for (const int candidate : kCommonRates) {
        const int distance = std::abs(measuredRate - candidate);
        if (distance < closestDistance) {
            closest = candidate;
            closestDistance = distance;
        }
    }
    // Wall-clock probing is deliberately approximate. Snap only when a standard USB rate is close.
    return closestDistance * 100 <= closest * 3 ? closest : measuredRate;
}
} // namespace

UsbIsoAudioSource::~UsbIsoAudioSource() {
    stop();
}

std::string UsbIsoAudioSource::start(const Config& config, FrameCallback callback) {
    if (mRunning.load(std::memory_order_acquire)) {
        return "already running";
    }
    if (config.fd < 0 || config.endpointAddress < 0 || config.maxPacketSize <= 0 ||
        config.totalChannels < 1 || config.subframeSize < 1 ||
        config.requestedSampleRate <= 0 ||
        (config.extractChannelOffset >= 0 && config.extractChannelOffset + 2 > config.totalChannels)) {
        return "invalid capture configuration";
    }

    mConfig = config;
    mCallback = std::move(callback);
    mClaimedPlaybackInterface = -1;
    mPlaybackTransfers.clear();
    mPlaybackFrameRemainder = 0;
    mDjmA9FallbackStage = 0;
    mResolvedChannelOffset = config.extractChannelOffset;
    mFramesSincePeakLog = 0;
    mBytesSincePeakLog = 0;
    mNonZeroBytesSincePeakLog = 0;
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
        mRateProbeFrames = 0;
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

    // Best-effort; harmless if unsupported (Android has no competing kernel audio-class driver
    // holding the interface anyway).
    libusb_set_auto_detach_kernel_driver(mHandle, 1);

    configureDjmA9RecordingRoute();

    if (config.vendorId == kDjmA9VendorId && config.productId == kDjmA9ProductId) {
        rc = libusb_claim_interface(mHandle, kDjmA9PlaybackInterface);
        if (rc != LIBUSB_SUCCESS) {
            restoreDjmA9RecordingRoute();
            libusb_close(mHandle);
            libusb_exit(mContext);
            mHandle = nullptr;
            mContext = nullptr;
            return libusbErrorString("DJM-A9 playback interface claim", rc);
        }
        mClaimedPlaybackInterface = kDjmA9PlaybackInterface;
        rc = libusb_set_interface_alt_setting(
            mHandle, kDjmA9PlaybackInterface, kDjmA9PlaybackAlternateSetting);
        if (rc != LIBUSB_SUCCESS) {
            restoreDjmA9RecordingRoute();
            libusb_release_interface(mHandle, mClaimedPlaybackInterface);
            mClaimedPlaybackInterface = -1;
            libusb_close(mHandle);
            libusb_exit(mContext);
            mHandle = nullptr;
            mContext = nullptr;
            return libusbErrorString("DJM-A9 playback alt setting", rc);
        }
        LOGI("DJM-A9 duplex session activated playback interface %d alt %d",
             kDjmA9PlaybackInterface, kDjmA9PlaybackAlternateSetting);
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
            }
            LOGI("Clock source %d active rate: %d Hz", config.clockSourceId,
                 mOpenedSampleRate.load(std::memory_order_acquire));
        }
    }

    rc = libusb_claim_interface(mHandle, config.interfaceNumber);
    if (rc != LIBUSB_SUCCESS) {
        restoreDjmA9RecordingRoute();
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
        restoreDjmA9RecordingRoute();
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

    if (config.vendorId == kDjmA9VendorId && config.productId == kDjmA9ProductId) {
        const int endpointRate = readDjmA9EndpointSampleRate(mHandle, config.endpointAddress);
        if (endpointRate > 0) {
            mOpenedSampleRate.store(endpointRate, std::memory_order_release);
            LOGI("DJM-A9 capture endpoint reports active rate %d Hz", endpointRate);
        } else {
            // This request is conditional in the Pioneer driver and stalls on some firmware.
            setDjmA9CaptureSampleRate(mHandle, config.endpointAddress, config.requestedSampleRate);
        }
        if (!startDjmA9PlaybackSilence(mOpenedSampleRate.load(std::memory_order_acquire))) {
            LOGW("DJM-A9 fallback strategy could not start playback traffic; continuing capture-only");
        }
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
            return "DJM-A9 playback transfer submission failed";
        }
    }

    return {};
}

bool UsbIsoAudioSource::startDjmA9PlaybackSilence(int sampleRate) {
    const IsoEndpointInfo endpoint = findIsoOutEndpoint(
        mConfig.rawDescriptors, kDjmA9PlaybackInterface, kDjmA9PlaybackAlternateSetting);
    if (endpoint.address < 0 || endpoint.maxPacketSize <= 0 || sampleRate <= 0) {
        LOGW("DJM-A9 playback OUT endpoint unavailable in raw descriptors");
        return false;
    }

    const int speed = libusb_get_device_speed(libusb_get_device(mHandle));
    const int basePacketsPerSecond =
        (speed == LIBUSB_SPEED_HIGH || speed == LIBUSB_SPEED_SUPER) ? 8000 : 1000;
    const int intervalShift = std::clamp(endpoint.interval - 1, 0, 10);
    mPlaybackPacketsPerSecond = std::max(1, basePacketsPerSecond >> intervalShift);
    mPlaybackFrameBytes = kDjmA9PlaybackChannels * kDjmA9PlaybackSubframeBytes;
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
    mDjmA9FallbackStage = 1;
    LOGI("DJM-A9 fallback strategy 1: streaming silence to endpoint 0x%02x at %d Hz "
         "(%dch packed 24-bit, %d packets/sec, maxPacket=%d)",
         endpoint.address, sampleRate, kDjmA9PlaybackChannels,
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
            LOGE("DJM-A9 playback packet %d exceeds endpoint capacity %d", packetLength,
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
        LOGE("DJM-A9 playback submit failed: %s", libusb_error_name(rc));
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
        LOGW("DJM-A9 playback transfer completed with status %d", transfer->status);
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

void UsbIsoAudioSource::configureDjmA9RecordingRoute() {
    mDjmA9OriginalSources.fill(-1);
    mDjmA9AppliedSources.fill(-1);
    mDjmA9RoutesChanged.fill(false);
    if (!mHandle || mConfig.vendorId != kDjmA9VendorId || mConfig.productId != kDjmA9ProductId) return;

    const int output = mConfig.extractChannelOffset >= 0
        ? std::clamp(mConfig.extractChannelOffset / 2, 0, 4)
        : 4;
    routeDjmA9OutputToMix(output);
}

void UsbIsoAudioSource::routeDjmA9OutputToMix(int output) {
    if (!mHandle || output < 0 || output >= 5) return;
    int currentSource = -1;
    const bool readCurrent = readDjmA9RouteSource(mHandle, output, currentSource);
    const int mixWithMicRoute = output == 0 ? 11 : 7;
    const int mixWithoutMicRoute = output == 0 ? 0 : 8;
    const int mixWithMicSource = kDjmA9RouteValues[output][mixWithMicRoute] & 0xFF;
    const int mixWithoutMicSource = kDjmA9RouteValues[output][mixWithoutMicRoute] & 0xFF;
    if (readCurrent &&
        (currentSource == mixWithMicSource || currentSource == mixWithoutMicSource)) {
        mDjmA9AppliedSources[output] = currentSource;
        LOGI("DJM-A9 USB output %d already routed to MIX (source 0x%02x)",
             output + 1, currentSource);
        return;
    }

    if (!writeDjmA9Route(mHandle, output, mixWithoutMicRoute)) return;
    int verifiedSource = -1;
    if (!readDjmA9RouteSource(mHandle, output, verifiedSource) ||
        verifiedSource != mixWithoutMicSource) {
        LOGW("DJM-A9 USB output %d MIX source did not verify: expected=0x%02x actual=0x%02x",
             output + 1, mixWithoutMicSource, verifiedSource);
        if (readCurrent) {
            writeDjmA9RouteSource(mHandle, output, currentSource);
        }
        return;
    }
    mDjmA9OriginalSources[output] = readCurrent ? currentSource : -1;
    mDjmA9AppliedSources[output] = mixWithoutMicSource;
    mDjmA9RoutesChanged[output] = readCurrent;
    LOGI("DJM-A9 USB output %d routed to MIX (REC OUT without MIC), source=0x%02x previous=0x%02x",
         output + 1, mixWithoutMicSource, currentSource);
}

void UsbIsoAudioSource::routeAllDjmA9OutputsToMix() {
    LOGI("DJM-A9 fallback strategy: route MIX to all five configurable USB output pairs");
    for (int output = 0; output < 5; ++output) {
        routeDjmA9OutputToMix(output);
    }
}

void UsbIsoAudioSource::restoreDjmA9RecordingRoute() {
    if (!mHandle) return;
    for (int output = 0; output < 5; ++output) {
        if (!mDjmA9RoutesChanged[output] || mDjmA9OriginalSources[output] < 0) continue;
        int currentSource = -1;
        if (!readDjmA9RouteSource(mHandle, output, currentSource) ||
            currentSource != mDjmA9AppliedSources[output]) {
            LOGW("DJM-A9 USB output %d changed externally; not restoring previous route", output + 1);
            continue;
        }
        if (writeDjmA9RouteSource(mHandle, output, mDjmA9OriginalSources[output])) {
            LOGI("DJM-A9 USB output %d restored to source 0x%02x",
                 output + 1, mDjmA9OriginalSources[output]);
        }
        mDjmA9RoutesChanged[output] = false;
    }
}

int UsbIsoAudioSource::waitForMeasuredSampleRate(int timeoutMs) {
    std::unique_lock<std::mutex> lock(mRateProbeMutex);
    mRateProbeReady.wait_for(lock, std::chrono::milliseconds(std::max(0, timeoutMs)), [this] {
        return mRateProbeResolved || !mRunning.load(std::memory_order_acquire);
    });
    return mOpenedSampleRate.load(std::memory_order_acquire);
}

void UsbIsoAudioSource::updateMeasuredSampleRate(size_t frameCount) {
    std::lock_guard<std::mutex> lock(mRateProbeMutex);
    if (mRateProbeResolved) {
        return;
    }

    const auto now = std::chrono::steady_clock::now();
    if (!mRateProbeStarted) {
        mRateProbeStart = now;
        mRateProbeStarted = true;
    }
    mRateProbeFrames += frameCount;

    const auto elapsed = std::chrono::duration<double>(now - mRateProbeStart).count();
    if (elapsed < 0.25) {
        return;
    }

    const int measuredRate = static_cast<int>(std::lround(mRateProbeFrames / elapsed));
    if (measuredRate > 0) {
        const int normalizedRate = normalizeSampleRate(measuredRate);
        mOpenedSampleRate.store(normalizedRate, std::memory_order_release);
        LOGI("Measured USB wire rate: %d Hz (raw=%d Hz, requested=%d Hz)",
             normalizedRate, measuredRate, mConfig.requestedSampleRate);
    }
    mRateProbeResolved = true;
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
        updateMeasuredSampleRate(completeFrames);
        if (mScratch.size() < completeFrames * 2) {
            mScratch.resize(completeFrames * 2);
        }

        const int subframe = mConfig.subframeSize;
        for (int offset = 0; offset + 1 < mConfig.totalChannels; offset += 2) {
            uint32_t pairMagnitude = 0;
            const int offsetBytes = offset * subframe;
            for (size_t f = 0; f < completeFrames; ++f) {
                const uint8_t* frameBase = mWorking.data() + f * frameSize + offsetBytes;
                const int32_t left = decodeCanonicalSample(frameBase, subframe, mConfig.bitResolution);
                const int32_t right = decodeCanonicalSample(frameBase + subframe, subframe, mConfig.bitResolution);
                pairMagnitude = std::max(pairMagnitude, sampleMagnitude(left));
                pairMagnitude = std::max(pairMagnitude, sampleMagnitude(right));
            }
            const size_t pairIndex = static_cast<size_t>(offset / 2);
            if (pairIndex < mPairPeaks.size()) {
                mPairPeaks[pairIndex] = std::max(mPairPeaks[pairIndex], pairMagnitude);
            }
        }

        if (mConfig.extractChannelOffset < 0) {
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
            if (bestMagnitude >= kAudibleThreshold && bestOffset != mResolvedChannelOffset) {
                mResolvedChannelOffset = bestOffset;
                LOGI("Auto-selected USB channels %d-%d for active audio", bestOffset + 1, bestOffset + 2);
            } else if (mResolvedChannelOffset < 0) {
                mResolvedChannelOffset = bestOffset;
            }
        }

        const int selectedOffset = std::max(0, mResolvedChannelOffset);
        const int offsetBytes = selectedOffset * subframe;

        for (size_t f = 0; f < completeFrames; ++f) {
            const uint8_t* frameBase = mWorking.data() + f * frameSize + offsetBytes;
            for (int ch = 0; ch < 2; ++ch) {
                const uint8_t* s = frameBase + static_cast<size_t>(ch) * subframe;
                mScratch[f * 2 + ch] = decodeCanonicalSample(s, subframe, mConfig.bitResolution);
            }
        }

        if (mCallback) {
            mCallback(mScratch.data(), completeFrames);
        }

        mFramesSincePeakLog += completeFrames;
        if (mFramesSincePeakLog >= static_cast<size_t>(
                std::max(1, mOpenedSampleRate.load(std::memory_order_acquire)))) {
            LOGI("USB raw payload nonzero bytes=%llu/%llu; decoded pair peaks: %s; selected ch %d-%d",
                 static_cast<unsigned long long>(mNonZeroBytesSincePeakLog),
                 static_cast<unsigned long long>(mBytesSincePeakLog),
                 peakSummary(mPairPeaks).c_str(), selectedOffset + 1, selectedOffset + 2);
            if (mConfig.vendorId == kDjmA9VendorId && mConfig.productId == kDjmA9ProductId) {
                if (mNonZeroBytesReceived.load(std::memory_order_relaxed) > 0 &&
                    mDjmA9FallbackStage > 0 && mDjmA9FallbackStage < 3) {
                    LOGI("DJM-A9 fallback strategy %d succeeded: capture payload is non-zero",
                         mDjmA9FallbackStage);
                    mDjmA9FallbackStage = 3;
                } else if (mNonZeroBytesSincePeakLog == 0 && mDjmA9FallbackStage == 1) {
                    mDjmA9FallbackStage = 2;
                    routeAllDjmA9OutputsToMix();
                } else if (mNonZeroBytesSincePeakLog == 0 && mDjmA9FallbackStage == 2) {
                    LOGW("DJM-A9 fallback strategies exhausted: duplex playback and all MIX routes "
                         "still produce an all-zero capture payload");
                    mDjmA9FallbackStage = 4;
                }
            }
            std::fill(mPairPeaks.begin(), mPairPeaks.end(), 0);
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
        restoreDjmA9RecordingRoute();
        libusb_set_interface_alt_setting(mHandle, mConfig.interfaceNumber, 0);
        libusb_release_interface(mHandle, mConfig.interfaceNumber);
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
