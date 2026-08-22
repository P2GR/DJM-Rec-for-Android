#include "UsbAudioEngine.h"

#include <algorithm>
#include <android/log.h>
#include <cstring>
#include <sstream>

#include "MeterCalculator.h"
#include "AudioGain.h"
#include "writers/WavWriter.h"
#include "writers/FlacWriter.h"

#define TAG "UsbAudioEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace djmrec {

UsbAudioEngine& UsbAudioEngine::instance() {
    static UsbAudioEngine engine;
    return engine;
}

size_t UsbAudioEngine::bytesPerFrameFor(oboe::AudioFormat format, int32_t channelCount) {
    int bytesPerSample;
    switch (format) {
        case oboe::AudioFormat::I16: bytesPerSample = 2; break;
        case oboe::AudioFormat::I24: bytesPerSample = 3; break;
        case oboe::AudioFormat::Float:
        case oboe::AudioFormat::I32:
        default: bytesPerSample = 4; break;
    }
    return static_cast<size_t>(bytesPerSample) * channelCount;
}

int UsbAudioEngine::open(int32_t audioManagerDeviceId, int32_t sampleRateHint, int32_t channelCount,
                          int32_t bitDepthHint) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mStreamOpen.load()) {
        LOGW("open() called while a stream is already open; closing the previous one first");
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    if (mAlsaSource) {
        mAlsaSource->stop();
        mAlsaSource.reset();
    }
    mSourceMode = SourceMode::Oboe;

    mChannelCount = channelCount;
    switch (bitDepthHint) {
        case 16: mOboeFormat = oboe::AudioFormat::I16; break;
        case 24: mOboeFormat = oboe::AudioFormat::I24; break;
        default: mOboeFormat = oboe::AudioFormat::I32; break;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
        ->setAudioApi(oboe::AudioApi::AAudio) // only AAudio exposes exclusive MMAP + device binding
        ->setDeviceId(audioManagerDeviceId)
        ->setInputPreset(oboe::InputPreset::Unprocessed)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive) // bypasses AudioFlinger's mixer entirely
        ->setSampleRate(sampleRateHint)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None) // never silently resample
        ->setChannelCount(channelCount)
        ->setChannelConversionAllowed(false)
        ->setFormat(mOboeFormat)
        ->setFormatConversionAllowed(false) // never silently bit-crush/expand
        ->setDataCallback(this)
        ->setErrorCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder.openStream(stream);

    if (result != oboe::Result::OK && mOboeFormat != oboe::AudioFormat::I32) {
        // Some AAudio HAL implementations only expose exclusive-mode UAC2 endpoints as I32
        // even when the wire format is 24-bit (the 4th byte is just the subslot padding
        // reported in the descriptor) — retry once before giving up.
        LOGW("Exclusive open failed for format %d (%s); retrying with I32",
             static_cast<int>(mOboeFormat), oboe::convertToText(result));
        mOboeFormat = oboe::AudioFormat::I32;
        builder.setFormat(mOboeFormat);
        result = builder.openStream(stream);
    }

    if (result != oboe::Result::OK) {
        LOGW("Exclusive open failed (%s); retrying shared mode with channel conversion allowed",
             oboe::convertToText(result));
        builder.setSharingMode(oboe::SharingMode::Shared)
            ->setInputPreset(oboe::InputPreset::Generic)
            ->setChannelConversionAllowed(true)
            ->setFormatConversionAllowed(true)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        result = builder.openStream(stream);
    }

    if (result != oboe::Result::OK) {
        LOGE("Failed to open exclusive low-latency AAudio input stream: %s", oboe::convertToText(result));
        return -1;
    }

    mStream = stream;
    mFormat.sampleRate = mStream->getSampleRate();
    mFormat.channelCount = mStream->getChannelCount();
    mChannelCount = mFormat.channelCount;
    mOboeFormat = mStream->getFormat();
    // We keep the hardware-reported bit depth (from the USB descriptor) for file headers even
    // though the wire format might be padded into I32 — this is the *true* fidelity of the source.
    mFormat.bitsPerSample = bitDepthHint;

    mAaudioFramesSinceLog = 0;
    mAaudioBytesSinceLog = 0;
    mAaudioNonZeroBytesSinceLog = 0;
    mAaudioLeftPeakSinceLog = -60.0f;
    mAaudioRightPeakSinceLog = -60.0f;

    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, mFormat.channelCount);
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 2; // 2s of headroom
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mLiveRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * 2 * sizeof(int32_t));
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        mRingBuffer.reset();
        return -1;
    }

    mStreamOpen.store(true, std::memory_order_release);
    LOGI("AAudio input open: %d Hz, %d ch, actual format=%d, sharing=%s, perf=%s",
         mFormat.sampleRate, mFormat.channelCount, static_cast<int>(mOboeFormat),
         oboe::convertToText(mStream->getSharingMode()), oboe::convertToText(mStream->getPerformanceMode()));

    return mFormat.sampleRate;
}

int UsbAudioEngine::openUsbIso(const UsbIsoAudioSource::Config& isoConfig, int32_t sampleRateHint) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mStreamOpen.load()) {
        LOGW("openUsbIso() called while a stream is already open; closing the previous one first");
    }
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    if (mAlsaSource) {
        mAlsaSource->stop();
        mAlsaSource.reset();
    }
    mSourceMode = SourceMode::UsbIso;

    // The extracted output is always exactly one stereo pair, regardless of how many channels
    // are actually present on the wire (isoConfig.totalChannels) -- that wire channel count is
    // only used internally by UsbIsoAudioSource for its demux math.
    mUsbIsoSource = std::make_unique<UsbIsoAudioSource>();
    const std::string error = mUsbIsoSource->start(
        isoConfig, [this](const int32_t* frames, size_t count) { onUsbIsoFrames(frames, count); });

    if (!error.empty()) {
        LOGE("Failed to start USB iso capture: %s", error.c_str());
        mUsbIsoSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    // UAC2 clock queries are optional and the DJM-A9 rejects GET_RANGE. Measure the active
    // endpoint cadence before creating an output file so its header matches the real stream.
    const int measuredSampleRate = mUsbIsoSource->waitForMeasuredSampleRate(/*timeoutMs=*/750);
    if (measuredSampleRate <= 0) {
        LOGE("USB iso capture produced no usable sample-rate measurement");
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    mChannelCount = 2;
    mOboeFormat = oboe::AudioFormat::I32;
    mFormat.sampleRate = measuredSampleRate;
    mFormat.channelCount = 2;
    mFormat.bitsPerSample = isoConfig.bitResolution;
    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, 2);
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 2;
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mLiveRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * 2 * sizeof(int32_t));
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);

    mStreamOpen.store(true, std::memory_order_release);
    LOGI("USB iso capture open: %d Hz, 2ch extracted from a %dch wire "
         "format, format=I32 canonical",
            mFormat.sampleRate, isoConfig.totalChannels);

        return mFormat.sampleRate;
}

int UsbAudioEngine::openRootAlsa(const AlsaPcmAudioSource::Config& alsaConfig) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mStreamOpen.load()) {
        LOGW("openRootAlsa() called while a stream is already open; closing the previous one first");
    }
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    if (mAlsaSource) {
        mAlsaSource->stop();
        mAlsaSource.reset();
    }
    mSourceMode = SourceMode::RootAlsa;

    mChannelCount = 2;
    mOboeFormat = oboe::AudioFormat::I32;
    mAlsaSource = std::make_unique<AlsaPcmAudioSource>();
    const std::string error = mAlsaSource->start(
        alsaConfig, [this](const int32_t* frames, size_t count) { onUsbIsoFrames(frames, count); });

    if (!error.empty()) {
        LOGE("Failed to start root ALSA capture: %s", error.c_str());
        mAlsaSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    mFormat.sampleRate = mAlsaSource->openedSampleRate();
    mFormat.channelCount = 2;
    mFormat.bitsPerSample = mAlsaSource->openedBitDepth();
    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, 2);
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 2;
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mLiveRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * 2 * sizeof(int32_t));
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);
    mStreamOpen.store(true, std::memory_order_release);

    LOGI("Root ALSA capture open: hw:%d,%d @ %dHz, %dbit, native channels=%d, output stereo I32",
         alsaConfig.card, alsaConfig.device, mFormat.sampleRate, mFormat.bitsPerSample,
         mAlsaSource->openedChannels());
    return mFormat.sampleRate;
}

void UsbAudioEngine::onUsbIsoFrames(const int32_t* interleavedStereo, size_t frameCount) {
    // --- Invoked on UsbIsoAudioSource's libusb event thread: no blocking I/O below. ---
    // Mirrors the tail of onAudioReady() below -- meter update + optional ring-buffer write --
    // but always against a canonical, already-2-channel buffer (no per-format decode needed
    // here; UsbIsoAudioSource already produced left-justified, sign-extended int32 samples).
    static thread_local std::vector<int32_t> amplified;
    const size_t sampleCount = frameCount * 2;
    if (amplified.size() < sampleCount) amplified.resize(sampleCount);
    std::memcpy(amplified.data(), interleavedStereo, sampleCount * sizeof(int32_t));
    applyRecordingGain(amplified.data(), sampleCount, kRecordingGainLinear);
    const int32_t* processedStereo = amplified.data();

    const StereoMeterReading reading =
        MeterCalculator::analyze(processedStereo, static_cast<int32_t>(frameCount), oboe::AudioFormat::I32);
    mLeftPeakDb.store(reading.leftPeakDb, std::memory_order_relaxed);
    mLeftRmsDb.store(reading.leftRmsDb, std::memory_order_relaxed);
    mRightPeakDb.store(reading.rightPeakDb, std::memory_order_relaxed);
    mRightRmsDb.store(reading.rightRmsDb, std::memory_order_relaxed);
    mClipping.store(reading.clipping, std::memory_order_relaxed);

    if (mWaveformEnabled.load(std::memory_order_relaxed) && mWaveformAnalyzer) {
        mWaveformAnalyzer->pushFrames(processedStereo, frameCount);
    }
    writeLiveFrames(processedStereo, frameCount, 2);

    if (mRecording.load(std::memory_order_relaxed) &&
        !mPaused.load(std::memory_order_relaxed) &&
        mRingBuffer) {
        const size_t bytesToWrite = frameCount * 2 * sizeof(int32_t);
        const size_t written =
            mRingBuffer->write(reinterpret_cast<const uint8_t*>(processedStereo), bytesToWrite);
        if (written < bytesToWrite) {
            mXRunCount.fetch_add(1, std::memory_order_relaxed);
        }
    }
}

oboe::DataCallbackResult UsbAudioEngine::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
                                                       int32_t numFrames) {
    // --- REALTIME THREAD: no allocation after warmup, no locks, no blocking I/O below. ---
    static thread_local std::vector<int32_t> canonical;
    const size_t sampleCount = static_cast<size_t>(numFrames) * mChannelCount;
    if (canonical.size() < sampleCount) canonical.resize(sampleCount);

    const size_t inputBytes = bytesPerFrameFor(mOboeFormat, mChannelCount) * static_cast<size_t>(numFrames);
    const auto* inputBytesPtr = static_cast<const uint8_t*>(audioData);
    for (size_t i = 0; i < inputBytes; ++i) {
        if (inputBytesPtr[i] != 0) {
            ++mAaudioNonZeroBytesSinceLog;
        }
    }
    mAaudioBytesSinceLog += inputBytes;

    switch (mOboeFormat) {
        case oboe::AudioFormat::I16: {
            const auto* src = static_cast<const int16_t*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                canonical[i] = static_cast<int32_t>(src[i]) << 16;
            }
            break;
        }
        case oboe::AudioFormat::I24: {
            // Packed 3-byte little-endian PCM: sign-extend to 32 bits, then left-justify.
            const auto* src = static_cast<const uint8_t*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                const size_t o = i * 3;
                int32_t v = src[o] | (src[o + 1] << 8) | (src[o + 2] << 16);
                if (v & 0x00800000) v |= static_cast<int32_t>(0xFF000000);
                canonical[i] = v << 8;
            }
            break;
        }
        case oboe::AudioFormat::Float: {
            const auto* src = static_cast<const float*>(audioData);
            for (size_t i = 0; i < sampleCount; ++i) {
                const float clamped = std::max(-1.0f, std::min(1.0f, src[i]));
                canonical[i] = static_cast<int32_t>(clamped * 2147483647.0f);
            }
            break;
        }
        case oboe::AudioFormat::I32:
        default:
            std::memcpy(canonical.data(), audioData, sampleCount * sizeof(int32_t));
            break;
    }

    applyRecordingGain(canonical.data(), sampleCount, kRecordingGainLinear);

    writeLiveFrames(canonical.data(), static_cast<size_t>(numFrames), mChannelCount);

    // Live stereo metering — always computed, even while paused/stopped, so the UI VU meter
    // reflects the signal actually present at the mixer's output at all times.
    const StereoMeterReading reading =
        MeterCalculator::analyze(canonical.data(), numFrames, oboe::AudioFormat::I32);
    mLeftPeakDb.store(reading.leftPeakDb, std::memory_order_relaxed);
    mLeftRmsDb.store(reading.leftRmsDb, std::memory_order_relaxed);
    mRightPeakDb.store(reading.rightPeakDb, std::memory_order_relaxed);
    mRightRmsDb.store(reading.rightRmsDb, std::memory_order_relaxed);
    mClipping.store(reading.clipping, std::memory_order_relaxed);

    mAaudioLeftPeakSinceLog = std::max(mAaudioLeftPeakSinceLog, reading.leftPeakDb);
    mAaudioRightPeakSinceLog = std::max(mAaudioRightPeakSinceLog, reading.rightPeakDb);
    mAaudioFramesSinceLog += static_cast<uint64_t>(numFrames);
    if (mAaudioFramesSinceLog >= static_cast<uint64_t>(std::max(1, mFormat.sampleRate))) {
        LOGI("AAudio payload nonzero bytes=%llu/%llu; decoded peaks L=%.1f dBFS R=%.1f dBFS; "
             "format=%d ch=%d rate=%d",
             static_cast<unsigned long long>(mAaudioNonZeroBytesSinceLog),
             static_cast<unsigned long long>(mAaudioBytesSinceLog),
             mAaudioLeftPeakSinceLog, mAaudioRightPeakSinceLog,
             static_cast<int>(mOboeFormat), mChannelCount, mFormat.sampleRate);
        mAaudioFramesSinceLog = 0;
        mAaudioBytesSinceLog = 0;
        mAaudioNonZeroBytesSinceLog = 0;
        mAaudioLeftPeakSinceLog = -60.0f;
        mAaudioRightPeakSinceLog = -60.0f;
    }

    if (mWaveformEnabled.load(std::memory_order_relaxed) && mWaveformAnalyzer) {
        mWaveformAnalyzer->pushFrames(canonical.data(), numFrames);
    }

    if (mRecording.load(std::memory_order_relaxed) &&
        !mPaused.load(std::memory_order_relaxed) &&
        mRingBuffer) {
        const size_t bytesToWrite = sampleCount * sizeof(int32_t);
        const size_t written =
            mRingBuffer->write(reinterpret_cast<const uint8_t*>(canonical.data()), bytesToWrite);
        if (written < bytesToWrite) {
            // Encoder thread fell behind (e.g. slow storage): count it, never block the
            // audio thread to catch up.
            mXRunCount.fetch_add(1, std::memory_order_relaxed);
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void UsbAudioEngine::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // Typically fired when the USB mixer is unplugged mid-session. We can't safely reopen
    // from this callback thread; flag state so the Kotlin layer can react (stop cleanly,
    // show "device disconnected") on its next status check.
    LOGE("Stream closed unexpectedly: %s", oboe::convertToText(error));
    mStreamOpen.store(false, std::memory_order_release);
}

bool UsbAudioEngine::startRecording(const std::string& path, ContainerFormat format) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mStreamOpen.load() || mRecording.load()) return false;

    switch (format) {
        case ContainerFormat::Wav: mWriter = std::make_unique<WavWriter>(); break;
        case ContainerFormat::Flac: mWriter = std::make_unique<FlacWriter>(); break;
    }

    if (!mWriter->open(path, mFormat)) {
        LOGE("Writer failed to open output file: %s", path.c_str());
        mWriter.reset();
        return false;
    }

    mXRunCount.store(0, std::memory_order_relaxed);
    mRecordingErrorCode.store(0, std::memory_order_relaxed);
    mElapsedMillis.store(0, std::memory_order_relaxed);
    mStopRequested.store(false, std::memory_order_relaxed);
    mPaused.store(false, std::memory_order_relaxed);
    mRingBuffer->reset();
    if (mWaveformAnalyzer) mWaveformAnalyzer->reset();
    mRecording.store(true, std::memory_order_release);

    mEncoderThread = std::thread(&UsbAudioEngine::encoderThreadLoop, this);
    return true;
}

bool UsbAudioEngine::startRecordingFd(int fd, ContainerFormat format) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mStreamOpen.load() || mRecording.load() || fd < 0) return false;

    switch (format) {
        case ContainerFormat::Wav: mWriter = std::make_unique<WavWriter>(); break;
        case ContainerFormat::Flac: mWriter = std::make_unique<FlacWriter>(); break;
    }
    if (!mWriter->openFd(fd, mFormat)) {
        LOGE("Writer failed to open MediaStore fd");
        mWriter.reset();
        return false;
    }

    mXRunCount.store(0, std::memory_order_relaxed);
    mRecordingErrorCode.store(0, std::memory_order_relaxed);
    mElapsedMillis.store(0, std::memory_order_relaxed);
    mStopRequested.store(false, std::memory_order_relaxed);
    mPaused.store(false, std::memory_order_relaxed);
    mRingBuffer->reset();
    if (mWaveformAnalyzer) mWaveformAnalyzer->reset();
    mRecording.store(true, std::memory_order_release);
    mEncoderThread = std::thread(&UsbAudioEngine::encoderThreadLoop, this);
    return true;
}

bool UsbAudioEngine::rollRecordingFd(int fd, ContainerFormat format) {
    std::lock_guard<std::mutex> controlLock(mControlMutex);
    if (!mRecording.load() || fd < 0) return false;

    std::unique_ptr<AudioWriter> next;
    switch (format) {
        case ContainerFormat::Wav: next = std::make_unique<WavWriter>(); break;
        case ContainerFormat::Flac: next = std::make_unique<FlacWriter>(); break;
    }
    if (!next->openFd(fd, mFormat)) return false;

    std::unique_ptr<AudioWriter> previous;
    {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        previous = std::move(mWriter);
        mWriter = std::move(next);
    }
    const bool finalized = !previous || previous->close();
    if (!finalized) mRecordingErrorCode.store(3, std::memory_order_release);
    LOGI("Recording rolled to next MediaStore part (previous finalized=%d)", finalized);
    // Swap succeeded and next writer is live. Finalization failure is exposed separately via
    // getRecordingErrorCode(); reporting roll failure here could make caller delete active part.
    return true;
}

int64_t UsbAudioEngine::checkpointRecording() {
    std::lock_guard<std::mutex> controlLock(mControlMutex);
    if (!mRecording.load()) return -1;
    std::lock_guard<std::mutex> writerLock(mWriterMutex);
    if (!mWriter || !mWriter->checkpoint()) {
        mRecordingErrorCode.store(2, std::memory_order_release);
        return -1;
    }
    return static_cast<int64_t>(mWriter->bytesWritten());
}

int32_t UsbAudioEngine::getRecordingErrorCode() const {
    return mRecordingErrorCode.load(std::memory_order_acquire);
}

bool UsbAudioEngine::isStreamOpen() const {
    return mStreamOpen.load(std::memory_order_acquire);
}

void UsbAudioEngine::writeLiveFrames(
    const int32_t* interleaved, size_t frameCount, int32_t channelCount) {
    if (!mLivePcmActive.load(std::memory_order_relaxed) || !mLiveRingBuffer ||
        !interleaved || frameCount == 0 || channelCount < 1) return;

    const int32_t* stereo = interleaved;
    static thread_local std::vector<int32_t> stereoScratch;
    if (channelCount != 2) {
        const size_t samples = frameCount * 2;
        if (stereoScratch.size() < samples) stereoScratch.resize(samples);
        for (size_t frame = 0; frame < frameCount; ++frame) {
            stereoScratch[frame * 2] = interleaved[frame * channelCount];
            stereoScratch[frame * 2 + 1] = channelCount > 1
                ? interleaved[frame * channelCount + 1]
                : interleaved[frame * channelCount];
        }
        stereo = stereoScratch.data();
    }

    const size_t bytes = frameCount * 2 * sizeof(int32_t);
    const size_t written = mLiveRingBuffer->write(
        reinterpret_cast<const uint8_t*>(stereo), bytes);
    if (written < bytes) {
        mLiveDroppedFrames.fetch_add((bytes - written) / (2 * sizeof(int32_t)),
                                     std::memory_order_relaxed);
    }
}

bool UsbAudioEngine::startLivePcm() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mStreamOpen.load() || !mLiveRingBuffer || mFormat.sampleRate <= 0) return false;
    mLiveDroppedFrames.store(0, std::memory_order_relaxed);
    mLivePcmFramesRead.store(0, std::memory_order_relaxed);
    mLivePcmNonZeroSamples.store(0, std::memory_order_relaxed);
    mLivePcmActive.store(true, std::memory_order_release);
    return true;
}

void UsbAudioEngine::stopLivePcm() {
    mLivePcmActive.store(false, std::memory_order_release);
}

size_t UsbAudioEngine::readLivePcm16(uint8_t* output, size_t maxBytes) {
    if (!mLivePcmActive.load(std::memory_order_acquire) || !mLiveRingBuffer || !output) return 0;
    const size_t maxFrames = maxBytes / (2 * sizeof(int16_t));
    const size_t availableFrames = mLiveRingBuffer->availableToRead() / (2 * sizeof(int32_t));
    // MediaCodec AAC is most reliable with full, stable PCM blocks. Waiting for the requested
    // block also avoids submitting hundreds of tiny USB-packet-sized frames each second.
    if (maxFrames == 0 || availableFrames < maxFrames) return 0;
    const size_t frames = maxFrames;

    const size_t inputSamples = frames * 2;
    static thread_local std::vector<int32_t> input;
    if (input.size() < inputSamples) input.resize(inputSamples);
    const size_t inputBytes = inputSamples * sizeof(int32_t);
    const size_t read = mLiveRingBuffer->read(reinterpret_cast<uint8_t*>(input.data()), inputBytes);
    const size_t samplesRead = read / sizeof(int32_t);
    uint64_t nonZeroSamples = 0;
    for (size_t index = 0; index < samplesRead; ++index) {
        const int16_t sample = static_cast<int16_t>(input[index] >> 16);
        if (sample != 0) ++nonZeroSamples;
        output[index * 2] = static_cast<uint8_t>(sample & 0xFF);
        output[index * 2 + 1] = static_cast<uint8_t>((sample >> 8) & 0xFF);
    }
    mLivePcmFramesRead.fetch_add(samplesRead / 2, std::memory_order_relaxed);
    mLivePcmNonZeroSamples.fetch_add(nonZeroSamples, std::memory_order_relaxed);
    return samplesRead * sizeof(int16_t);
}

void UsbAudioEngine::pauseRecording() {
    mPaused.store(true, std::memory_order_release);
}

void UsbAudioEngine::resumeRecording() {
    mPaused.store(false, std::memory_order_release);
}

int64_t UsbAudioEngine::stopRecording() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mRecording.load()) return 0;

    // Stop producer first, then unpause encoder so it can drain finite buffered audio.
    // Leaving mRecording true lets live capture refill ring forever; leaving mPaused true
    // deadlocks stop when user presses Stop while paused.
    mRecording.store(false, std::memory_order_release);
    mPaused.store(false, std::memory_order_release);
    mStopRequested.store(true, std::memory_order_release);
    if (mEncoderThread.joinable()) mEncoderThread.join();

    const int64_t duration = mElapsedMillis.load(std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        if (mWriter) {
            if (!mWriter->close()) {
                LOGE("Writer failed while finalizing output file");
                mRecordingErrorCode.store(3, std::memory_order_release);
            }
            mWriter.reset();
        }
    }
    return duration;
}

void UsbAudioEngine::closeEngine() {
    stopLivePcm();
    if (mRecording.load()) {
        stopRecording();
    }

    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
    if (mUsbIsoSource) {
        mUsbIsoSource->stop();
        mUsbIsoSource.reset();
    }
    if (mAlsaSource) {
        mAlsaSource->stop();
        mAlsaSource.reset();
    }
    mRingBuffer.reset();
    mSourceMode = SourceMode::None;
    mStreamOpen.store(false, std::memory_order_release);
}

void UsbAudioEngine::encoderThreadLoop() {
    constexpr size_t kChunkFrames = 960; // ~20ms chunks @48kHz; small enough for low file-write latency
    std::vector<int32_t> chunk(kChunkFrames * mFormat.channelCount);
    uint64_t framesEncoded = 0;
    const size_t bytesPerFrame = sizeof(int32_t) * mFormat.channelCount;

    while (true) {
        if (mStopRequested.load(std::memory_order_acquire) && mRingBuffer->availableToRead() == 0) {
            break;
        }
        if (mPaused.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
            continue;
        }

        const size_t bytesAvailable = mRingBuffer->availableToRead();
        if (bytesAvailable < bytesPerFrame) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        const size_t framesToRead = std::min(kChunkFrames, bytesAvailable / bytesPerFrame);
        const size_t bytesToRead = framesToRead * bytesPerFrame;
        const size_t bytesRead =
            mRingBuffer->read(reinterpret_cast<uint8_t*>(chunk.data()), bytesToRead);
        const size_t framesRead = bytesRead / bytesPerFrame;

        if (framesRead > 0) {
            std::lock_guard<std::mutex> writerLock(mWriterMutex);
            if (!mWriter || !mWriter->writeFrames(chunk.data(), framesRead)) {
                LOGE("Encoder write failed after %llu frames",
                     static_cast<unsigned long long>(framesEncoded));
                mRecordingErrorCode.store(1, std::memory_order_release);
                break;
            }
            framesEncoded += framesRead;
            mElapsedMillis.store(
                static_cast<int64_t>(framesEncoded * 1000 / mFormat.sampleRate),
                std::memory_order_relaxed);
        }
    }
}

void UsbAudioEngine::getLevels(float outLevels[4]) const {
    outLevels[0] = mLeftPeakDb.load(std::memory_order_relaxed);
    outLevels[1] = mLeftRmsDb.load(std::memory_order_relaxed);
    outLevels[2] = mRightPeakDb.load(std::memory_order_relaxed);
    outLevels[3] = mRightRmsDb.load(std::memory_order_relaxed);
}

bool UsbAudioEngine::isClipping() const {
    return mClipping.load(std::memory_order_relaxed);
}

int64_t UsbAudioEngine::getElapsedMillis() const {
    return mElapsedMillis.load(std::memory_order_relaxed);
}

int32_t UsbAudioEngine::getXRunCount() const {
    return mXRunCount.load(std::memory_order_relaxed);
}

void UsbAudioEngine::getUsbIsoTransferStats(uint64_t outStats[7]) const {
    std::fill(outStats, outStats + 7, 0);
    if (!mUsbIsoSource) {
        return;
    }
    const auto stats = mUsbIsoSource->getTransferStats();
    outStats[0] = stats.packetsCompleted;
    outStats[1] = stats.packetsMissed;
    outStats[2] = stats.packetsEmpty;
    outStats[3] = stats.packetsPartial;
    outStats[4] = stats.bytesReceived;
    outStats[5] = stats.nonZeroBytesReceived;
    outStats[6] = stats.resubmitFailures;
}

std::string UsbAudioEngine::getDiagnosticSummary() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    const char* sourceMode = "none";
    switch (mSourceMode) {
        case SourceMode::Oboe: sourceMode = "aaudio"; break;
        case SourceMode::UsbIso: sourceMode = "usb_iso"; break;
        case SourceMode::RootAlsa: sourceMode = "root_alsa"; break;
        case SourceMode::None: break;
    }

    std::ostringstream out;
    out << "source_mode=" << sourceMode << '\n'
        << "stream_open=" << (mStreamOpen.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "recording=" << (mRecording.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "paused=" << (mPaused.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "format=" << mFormat.sampleRate << "Hz/" << mFormat.channelCount
        << "ch/" << mFormat.bitsPerSample << "bit\n"
        << "xrun_count=" << mXRunCount.load(std::memory_order_relaxed) << '\n'
        << "recording_error_code=" << mRecordingErrorCode.load(std::memory_order_relaxed) << '\n'
        << "live_pcm_active=" << (mLivePcmActive.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "live_pcm_dropped_frames=" << mLiveDroppedFrames.load(std::memory_order_relaxed) << '\n'
        << "live_pcm_frames_read=" << mLivePcmFramesRead.load(std::memory_order_relaxed) << '\n'
        << "live_pcm_nonzero_samples=" << mLivePcmNonZeroSamples.load(std::memory_order_relaxed) << '\n'
        << "elapsed_ms=" << mElapsedMillis.load(std::memory_order_relaxed) << '\n'
        << "levels_db=peak_l:" << mLeftPeakDb.load(std::memory_order_relaxed)
        << " rms_l:" << mLeftRmsDb.load(std::memory_order_relaxed)
        << " peak_r:" << mRightPeakDb.load(std::memory_order_relaxed)
        << " rms_r:" << mRightRmsDb.load(std::memory_order_relaxed)
        << " clipping:" << (mClipping.load(std::memory_order_relaxed) ? "true" : "false");
    {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        out << "\nwriter_bytes=" << (mWriter ? mWriter->bytesWritten() : 0);
    }
    if (mUsbIsoSource) {
        out << "\n--- usb_iso_source ---\n" << mUsbIsoSource->diagnosticSummary();
    }
    return out.str();
}

void UsbAudioEngine::getWaveformBins(float* outBins) const {
    if (mWaveformAnalyzer) {
        mWaveformAnalyzer->getBins(outBins);
    } else {
        std::memset(outBins, 0, kWaveformBinCount * 4 * sizeof(float));
    }
}

void UsbAudioEngine::setWaveformEnabled(bool enabled) {
    mWaveformEnabled.store(enabled, std::memory_order_release);
}

} // namespace djmrec
