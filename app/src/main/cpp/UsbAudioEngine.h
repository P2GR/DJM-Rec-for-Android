#pragma once

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include <oboe/Oboe.h>

#include "AlsaPcmAudioSource.h"
#include "RingBuffer.h"
#include "UsbIsoAudioSource.h"
#include "WaveformAnalyzer.h"
#include "writers/AudioWriter.h"

namespace djmrec {

enum class ContainerFormat : int {
    Wav = 0,
    Flac = 1
};

/**
 * Which producer is currently feeding mRingBuffer. Oboe is the default AAudio/AudioRecord
 * path (used for plain stereo UAC2 devices); UsbIso is the libusb raw-isochronous path used
 * to reach into a multichannel interface for a specific channel pair that AAudio itself has
 * no way to select.
 */
enum class SourceMode { None, Oboe, UsbIso, RootAlsa };

/**
 * The whole native audio pipeline in one place:
 *
 *   AAudio exclusive MMAP callback (producer, realtime)
 *        -> RingBuffer (lock-free hand-off)
 *        -> encoder thread (consumer, THREAD_PRIORITY_URGENT_AUDIO on the Kotlin side owns
 *           the *service* thread priority; this native thread inherits pthread defaults and
 *           is deliberately NOT realtime since file I/O/encoding must be free to block)
 *        -> AudioWriter (WAV/FLAC)
 *
 * Exactly one recording session is supported at a time, matching the app's single-mixer,
 * single-session use case.
 */
class UsbAudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static UsbAudioEngine& instance();

    /** Opens the exclusive AAudio input stream. Returns the negotiated sample rate, or -1. */
    int open(int32_t audioManagerDeviceId, int32_t sampleRateHint, int32_t channelCount, int32_t bitDepthHint);

    /**
     * Opens the raw libusb isochronous capture path instead of AAudio, extracting a stereo
     * pair out of a wider multichannel USB Audio interface. The source briefly measures actual frame cadence
     * before returning, which covers UAC2 devices that do not answer clock-frequency queries.
     * Returns the measured sample rate on success, or -1 on failure.
     */
    int openUsbIso(const UsbIsoAudioSource::Config& isoConfig, int32_t sampleRateHint);
    int openRootAlsa(const AlsaPcmAudioSource::Config& alsaConfig);

    bool startRecording(const std::string& path, ContainerFormat format);
    bool startRecordingFd(int fd, ContainerFormat format);
    bool rollRecordingFd(int fd, ContainerFormat format);
    int64_t checkpointRecording();
    int32_t getRecordingErrorCode() const;
    bool isStreamOpen() const;
    bool startLivePcm();
    void stopLivePcm();
    size_t readLivePcm16(uint8_t* output, size_t maxBytes);
    void pauseRecording();
    void resumeRecording();
    /** Stops encoding, finalizes the file, and returns total recorded duration in ms. */
    int64_t stopRecording();
    void closeEngine();

    /** [leftPeakDb, leftRmsDb, rightPeakDb, rightRmsDb] — safe to call from any thread. */
    void getLevels(float outLevels[4]) const;
    bool isClipping() const;
    int64_t getElapsedMillis() const;
    int32_t getXRunCount() const;
    void getUsbIsoTransferStats(uint64_t outStats[7]) const;
    std::string getDiagnosticSummary();

    /** Copies the RGB waveform snapshot into @p outBins (kBinCount * 4 floats).
     *  Safe to call from any thread. */
    void getWaveformBins(float* outBins) const;
    void setWaveformEnabled(bool enabled);
    static constexpr int kWaveformBinCount = WaveformAnalyzer::kBinCount;

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    UsbAudioEngine() = default;

    void encoderThreadLoop();
    static size_t bytesPerFrameFor(oboe::AudioFormat format, int32_t channelCount);

    /** Shared tail of both capture paths once a canonical stereo I32 frame batch is in hand:
     *  updates the VU meter atomics and (if recording) writes into mRingBuffer. Called from
     *  the libusb event thread by UsbIsoAudioSource's callback. */
    void onUsbIsoFrames(const int32_t* interleavedStereo, size_t frameCount);
    void writeLiveFrames(const int32_t* interleaved, size_t frameCount, int32_t channelCount);

    std::shared_ptr<oboe::AudioStream> mStream;
    std::unique_ptr<UsbIsoAudioSource> mUsbIsoSource;
    std::unique_ptr<AlsaPcmAudioSource> mAlsaSource;
    SourceMode mSourceMode = SourceMode::None;
    std::unique_ptr<RingBuffer> mRingBuffer;
    std::unique_ptr<RingBuffer> mLiveRingBuffer;
    std::unique_ptr<AudioWriter> mWriter;
    std::unique_ptr<WaveformAnalyzer> mWaveformAnalyzer;
    std::thread mEncoderThread;

    std::mutex mControlMutex; // guards start/stop/pause transitions (not the realtime path)
    mutable std::mutex mWriterMutex;
    std::atomic<bool> mStreamOpen{false};
    std::atomic<bool> mRecording{false};
    std::atomic<bool> mPaused{false};
    std::atomic<bool> mStopRequested{false};
    std::atomic<bool> mWaveformEnabled{true};
    std::atomic<bool> mLivePcmActive{false};
    std::atomic<uint64_t> mLiveDroppedFrames{0};
    std::atomic<uint64_t> mLivePcmFramesRead{0};
    std::atomic<uint64_t> mLivePcmNonZeroSamples{0};
    std::atomic<int32_t> mRecordingErrorCode{0};

    AudioFormatInfo mFormat;
    oboe::AudioFormat mOboeFormat = oboe::AudioFormat::I32;
    int32_t mChannelCount = 2;

    uint64_t mAaudioFramesSinceLog = 0;
    uint64_t mAaudioBytesSinceLog = 0;
    uint64_t mAaudioNonZeroBytesSinceLog = 0;
    float mAaudioLeftPeakSinceLog = -60.0f;
    float mAaudioRightPeakSinceLog = -60.0f;

    std::atomic<int32_t> mXRunCount{0};
    std::atomic<int64_t> mElapsedMillis{0};

    // Meter state, updated every realtime callback, read by the UI's polling loop.
    std::atomic<float> mLeftPeakDb{-60.0f};
    std::atomic<float> mLeftRmsDb{-60.0f};
    std::atomic<float> mRightPeakDb{-60.0f};
    std::atomic<float> mRightRmsDb{-60.0f};
    std::atomic<bool> mClipping{false};

};

} // namespace djmrec
