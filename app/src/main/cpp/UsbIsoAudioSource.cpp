#include "UsbIsoAudioSource.h"

#include <android/log.h>
#include <libusb.h>

#include <algorithm>
#include <climits>
#include <cstring>

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
        (config.extractChannelOffset >= 0 && config.extractChannelOffset + 2 > config.totalChannels)) {
        return "invalid capture configuration";
    }

    mConfig = config;
    mCallback = std::move(callback);
    mResolvedChannelOffset = config.extractChannelOffset;
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

    rc = libusb_claim_interface(mHandle, config.interfaceNumber);
    if (rc != LIBUSB_SUCCESS) {
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
        libusb_release_interface(mHandle, config.interfaceNumber);
        libusb_close(mHandle);
        libusb_exit(mContext);
        mHandle = nullptr;
        mContext = nullptr;
        return libusbErrorString("libusb_set_interface_alt_setting", rc);
    }

    const std::string channelDescription = config.extractChannelOffset < 0
        ? "auto stereo pair"
        : std::string("ch ") + std::to_string(config.extractChannelOffset + 1) + "-" +
            std::to_string(config.extractChannelOffset + 2);
    LOGI("Claimed iface %d alt %d, endpoint 0x%02x, maxPacketSize=%d, wire=%dch/%dbit (subframe %d "
         "bytes), extracting %s",
         config.interfaceNumber, config.alternateSetting, config.endpointAddress, config.maxPacketSize,
         config.totalChannels, config.bitResolution, config.subframeSize, channelDescription.c_str());

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

    return {};
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
    while (mRunning.load(std::memory_order_acquire) || mOutstandingTransfers.load(std::memory_order_relaxed) > 0) {
        libusb_handle_events_timeout_completed(mContext, &tv, nullptr);
    }
}

void UsbIsoAudioSource::onTransferComplete(libusb_transfer* transfer) {
    static_cast<UsbIsoAudioSource*>(transfer->user_data)->handleCompletedTransfer(transfer);
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
        if (packet.status == LIBUSB_TRANSFER_COMPLETED && packet.actual_length > 0) {
            unsigned char* data = libusb_get_iso_packet_buffer_simple(transfer, i);
            demuxAndEmit(data, packet.actual_length);
        }
    }

    if (!submitTransfer(transfer)) {
        LOGE("Re-submit failed after completed transfer; stopping capture");
        mRunning.store(false, std::memory_order_release);
    }
}

void UsbIsoAudioSource::demuxAndEmit(const uint8_t* data, size_t length) {
    const size_t frameSize = static_cast<size_t>(mConfig.subframeSize) * mConfig.totalChannels;
    if (frameSize == 0) {
        return;
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

        if (mConfig.extractChannelOffset < 0) {
            uint32_t bestMagnitude = 0;
            int bestOffset = 0;
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
                if (pairMagnitude > bestMagnitude) {
                    bestMagnitude = pairMagnitude;
                    bestOffset = offset;
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
    }

    const size_t leftover = mWorking.size() - consumedBytes;
    mCarryover.assign(mWorking.data() + consumedBytes, mWorking.data() + consumedBytes + leftover);
}

void UsbIsoAudioSource::stop() {
    const bool wasRunning = mRunning.exchange(false, std::memory_order_acq_rel);
    if (!wasRunning && mTransfers.empty() && !mHandle) {
        return; // never started, or already fully stopped
    }

    for (auto* transfer : mTransfers) {
        libusb_cancel_transfer(transfer); // no-op (returns an error, harmless) if not in-flight
    }

    if (mEventThread.joinable()) {
        mEventThread.join();
    }

    for (auto* transfer : mTransfers) {
        delete[] transfer->buffer;
        libusb_free_transfer(transfer);
    }
    mTransfers.clear();

    if (mHandle) {
        libusb_release_interface(mHandle, mConfig.interfaceNumber);
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
