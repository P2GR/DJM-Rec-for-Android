#pragma once

#include <atomic>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "PioneerMixerProfiles.h"

struct libusb_context;
struct libusb_device_handle;
struct libusb_transfer;

namespace djmrec {

/**
 * Captures the raw isochronous USB audio stream directly via libusb, bypassing AAudio /
 * AudioRecord entirely.
 *
 * Why: Android's audio HAL only ever exposes channels 1/2 (or, at best, the full interleaved
 * N-channel block starting at channel 1) of a UAC2 interface -- there is no public API to
 * select an arbitrary channel *offset*. DJ mixers such as the Pioneer DJM-A9 expose one
 * combined multichannel interface, which AAudio cannot target pair-by-pair. This class
 * configures the selected pair when needed, then demuxes it from the isochronous IN endpoint.
 *
 * How: libusb_wrap_sys_device() wraps an already-open, already-permission-granted fd (from
 * Kotlin's `UsbDeviceConnection.getFileDescriptor()`) -- libusb never calls open() on the
 * device node itself, which is exactly why this works without root on stock Android.
 *
 * Threading: start()/stop() are expected to be called from a single control thread (the
 * caller is responsible for not calling them concurrently). Internally, a dedicated thread
 * pumps libusb_handle_events_timeout_completed(); the FrameCallback supplied to start() is
 * invoked synchronously from that thread and must not block or perform blocking I/O.
 *
 * Lifetime contract: the Kotlin-side UsbDeviceConnection that produced Config::fd MUST remain
 * open (not .close()'d) for the entire time this object is running -- closing it invalidates
 * the fd out from under libusb mid-capture.
 */
class UsbIsoAudioSource {
public:
    struct Config {
        int fd = -1;                  // UsbDeviceConnection.getFileDescriptor()
        int interfaceNumber = -1;      // AudioStreaming interface number
        int alternateSetting = -1;     // alt setting that activates the isochronous endpoint
        int endpointAddress = -1;      // e.g. 0x81 (bit 7 set = IN)
        int maxPacketSize = 0;         // wMaxPacketSize from the endpoint descriptor
        int totalChannels = 2;         // channels in the *wire* format (e.g. 12 for the DJM-A9)
        int subframeSize = 4;          // bytes per sample container (1/2/3/4)
        int bitResolution = 24;        // significant bits per sample within the container
        int extractChannelOffset = 0;  // 0-indexed first channel of the stereo pair; -1 = auto-pick loudest pair
        int clockControlInterfaceNumber = -1;
        int clockSourceId = -1;
        bool clockSupportsFrequencySet = false;
        int requestedSampleRate = 48000;
        int feedbackEndpointAddress = -1;
        int feedbackMaxPacketSize = 0;
        int vendorId = -1;
        int productId = -1;
        std::vector<uint8_t> rawDescriptors;
    };

    struct TransferStatsSnapshot {
        uint64_t packetsCompleted = 0;
        uint64_t packetsMissed = 0;
        uint64_t packetsEmpty = 0;
        uint64_t packetsPartial = 0;
        uint64_t bytesReceived = 0;
        uint64_t nonZeroBytesReceived = 0;
        uint64_t resubmitFailures = 0;
    };

    /** Canonical (left-justified, sign-extended) int32 interleaved STEREO frames.
     *  Invoked on this object's internal libusb event-handling thread -- must not block. */
    using FrameCallback = std::function<void(const int32_t* interleavedStereo, size_t frameCount)>;

    UsbIsoAudioSource() = default;
    ~UsbIsoAudioSource();

    UsbIsoAudioSource(const UsbIsoAudioSource&) = delete;
    UsbIsoAudioSource& operator=(const UsbIsoAudioSource&) = delete;

    /** Returns an empty string on success, or a human-readable error otherwise. */
    std::string start(const Config& config, FrameCallback callback);

    /** Idempotent; safe to call even if start() failed partway through or was never called. */
    void stop();

    bool isRunning() const { return mRunning.load(std::memory_order_acquire); }
    int openedSampleRate() const { return mOpenedSampleRate.load(std::memory_order_acquire); }

    /**
     * Waits briefly for the active endpoint's frame cadence, then returns the measured rate.
     * This is the authoritative fallback when UAC2 clock controls are read-only or unavailable.
     */
    int waitForMeasuredSampleRate(int timeoutMs);

    TransferStatsSnapshot getTransferStats() const;

    /** Release-safe, read-only snapshot used by exported support reports. */
    std::string diagnosticSummary() const;

private:
    void eventThreadLoop();
    void handleCompletedTransfer(libusb_transfer* transfer);
    void handlePlaybackTransfer(libusb_transfer* transfer);
    void demuxAndEmit(const uint8_t* data, size_t length);
    void updateMeasuredSampleRate(size_t frameCount);
    bool submitTransfer(libusb_transfer* transfer);
    bool submitPlaybackTransfer(libusb_transfer* transfer);
    bool startPioneerPlaybackSilence(int sampleRate);
    void configurePioneerRecordingRoute();
    void routeAllPioneerOutputsToMix();
    void routePioneerOutputToMix(int output);
    void restorePioneerRecordingRoute();

    static void onTransferComplete(libusb_transfer* transfer);
    static void onPlaybackTransferComplete(libusb_transfer* transfer);

    static constexpr int kNumTransfers = 8;
    static constexpr int kPacketsPerTransfer = 16;

    Config mConfig{};
    FrameCallback mCallback;

    libusb_context* mContext = nullptr;
    libusb_device_handle* mHandle = nullptr;
    int mClaimedClockControlInterface = -1;
    int mClaimedPlaybackInterface = -1;
    const PioneerMixerProfile* mMixerProfile = nullptr;
    std::array<int, 5> mPioneerOriginalSources{{-1, -1, -1, -1, -1}};
    std::array<int, 5> mPioneerAppliedSources{{-1, -1, -1, -1, -1}};
    std::array<bool, 5> mPioneerRoutesChanged{{false, false, false, false, false}};
    mutable std::mutex mDiagnosticMutex;
    std::vector<libusb_transfer*> mTransfers;
    std::vector<libusb_transfer*> mPlaybackTransfers;
    int mPlaybackPacketsPerSecond = 0;
    int mPlaybackFrameBytes = 0;
    int mPlaybackMaxPacketSize = 0;
    uint64_t mPlaybackFrameRemainder = 0;
    std::atomic<int> mPioneerFallbackStage{0};

    std::atomic<bool> mRunning{false};
    std::atomic<int> mOutstandingTransfers{0};
    std::atomic<uint64_t> mPacketsCompleted{0};
    std::atomic<uint64_t> mPacketsMissed{0};
    std::atomic<uint64_t> mPacketsEmpty{0};
    std::atomic<uint64_t> mPacketsPartial{0};
    std::atomic<uint64_t> mBytesReceived{0};
    std::atomic<uint64_t> mNonZeroBytesReceived{0};
    std::atomic<uint64_t> mResubmitFailures{0};
    std::thread mEventThread;

    std::vector<uint8_t> mCarryover; // partial-frame bytes carried over between packets
    std::vector<uint8_t> mWorking;   // scratch: carryover + newest packet, reused per call
    std::vector<int32_t> mScratch;   // reusable decode buffer, grown as needed
    std::vector<uint32_t> mPairPeaks;
    std::atomic<int> mResolvedChannelOffset{-1};
    size_t mFramesSincePeakLog = 0;
    uint64_t mBytesSincePeakLog = 0;
    uint64_t mNonZeroBytesSincePeakLog = 0;
    std::atomic<int> mOpenedSampleRate{0};

    std::mutex mRateProbeMutex;
    std::condition_variable mRateProbeReady;
    std::chrono::steady_clock::time_point mRateProbeStart{};
    uint64_t mRateProbeFrames = 0;
    bool mRateProbeStarted = false;
    bool mRateProbeResolved = false;
};

} // namespace djmrec
