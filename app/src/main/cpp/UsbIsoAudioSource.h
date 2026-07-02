#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <thread>
#include <vector>

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
 * combined interface with a stereo pair per input channel followed by the Master Mix (USB
 * channels 9/10), which AAudio simply cannot target. This class talks to the interface's
 * isochronous IN endpoint directly and demuxes out whichever channel pair the caller asks for.
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

private:
    void eventThreadLoop();
    void handleCompletedTransfer(libusb_transfer* transfer);
    void demuxAndEmit(const uint8_t* data, size_t length);
    bool submitTransfer(libusb_transfer* transfer);

    static void onTransferComplete(libusb_transfer* transfer);

    static constexpr int kNumTransfers = 8;
    static constexpr int kPacketsPerTransfer = 16;

    Config mConfig{};
    FrameCallback mCallback;

    libusb_context* mContext = nullptr;
    libusb_device_handle* mHandle = nullptr;
    std::vector<libusb_transfer*> mTransfers;

    std::atomic<bool> mRunning{false};
    std::atomic<int> mOutstandingTransfers{0};
    std::thread mEventThread;

    std::vector<uint8_t> mCarryover; // partial-frame bytes carried over between packets
    std::vector<uint8_t> mWorking;   // scratch: carryover + newest packet, reused per call
    std::vector<int32_t> mScratch;   // reusable decode buffer, grown as needed
    int mResolvedChannelOffset = -1;
};

} // namespace djmrec
