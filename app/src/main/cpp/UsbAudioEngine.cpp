#include "UsbAudioEngine.h"

#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <sstream>

#include "MeterCalculator.h"
#include "AudioGain.h"
#include "writers/WavWriter.h"
#include "writers/FlacWriter.h"
#include "writers/Mp3Writer.h"

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
    mLastUsbSetupFailure.clear();
    if (mStreamOpen.load()) {
        LOGW("open() called while a stream is already open; closing the previous one first");
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
    releaseCaptureProcessing();
    mSourceMode = SourceMode::Oboe;
    mTrackChannels = 0; // multitrack needs the raw USB path's full wire frame

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

    if (audioManagerDeviceId <= 0) {
        LOGE("Refusing default Android input: a specific USB device is required");
        return -1;
    }
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

    if (stream->getDeviceId() != audioManagerDeviceId) {
        LOGE("Android opened device %d instead of requested USB input %d",
             stream->getDeviceId(), audioManagerDeviceId);
        stream->close();
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
    mRingChannels = mFormat.channelCount;

    mAaudioFramesSinceLog = 0;
    mAaudioBytesSinceLog = 0;
    mAaudioNonZeroBytesSinceLog = 0;
    mAaudioLeftPeakSinceLog = -60.0f;
    mAaudioRightPeakSinceLog = -60.0f;

    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, mFormat.channelCount);
    // 4 s of headroom: on Record the encoder first writes up to 15 s of pre-recorded audio.
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 4;
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mLiveRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * 2 * sizeof(int32_t));
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);
    configureCaptureProcessing();

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
    mLastUsbSetupFailure.clear();
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
    releaseCaptureProcessing();
    mSourceMode = SourceMode::UsbIso;

    // The master is always exactly one stereo pair, regardless of how many channels are
    // actually present on the wire (isoConfig.totalChannels). In multitrack mode every wire
    // channel additionally feeds the track bus.
    mTrackChannels = isoConfig.emitAllChannels && isoConfig.totalChannels <= TrackBus::kMaxChannels
        ? isoConfig.totalChannels : 0;
    UsbIsoAudioSource::Config sourceConfig = isoConfig;
    sourceConfig.emitAllChannels = mTrackChannels > 0;
    sourceConfig.disableRouteFallback = isoConfig.disableRouteFallback || mTrackChannels > 0;
    mUsbIsoSource = std::make_unique<UsbIsoAudioSource>();
    const std::string error = mUsbIsoSource->start(
        sourceConfig,
        [this](const int32_t* stereo, const int32_t* allChannels, int wireChannels, size_t count) {
            onUsbIsoFrames(stereo, allChannels, wireChannels, count);
        });

    if (!error.empty()) {
        LOGE("Failed to start USB iso capture: %s", error.c_str());
        mLastUsbSetupFailure = "usb_setup_error=" + error + "\n" + mUsbIsoSource->diagnosticSummary();
        mUsbIsoSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    // UAC2 clock queries are optional and the DJM-A9 rejects GET_RANGE. Measure the active
    // endpoint cadence before creating an output file so its header matches the real stream.
    const int measuredSampleRate = mUsbIsoSource->waitForMeasuredSampleRate(/*timeoutMs=*/1500);
    if (measuredSampleRate <= 0) {
        LOGE("USB iso capture produced no usable sample-rate measurement");
        mUsbIsoSource->stop();
        mLastUsbSetupFailure = "usb_setup_error=No usable sample-rate measurement\n" +
            mUsbIsoSource->diagnosticSummary();
        mUsbIsoSource.reset();
        mSourceMode = SourceMode::None;
        return -1;
    }

    mChannelCount = 2;
    mOboeFormat = oboe::AudioFormat::I32;
    mFormat.sampleRate = measuredSampleRate;
    mFormat.channelCount = 2;
    mFormat.bitsPerSample = isoConfig.bitResolution;
    mRingChannels = mFormat.channelCount + mTrackChannels;
    const size_t canonicalBytesPerFrame = bytesPerFrameFor(oboe::AudioFormat::I32, mRingChannels);
    // 4 s of headroom: on Record the encoder first writes up to 15 s of pre-recorded audio.
    const size_t ringBufferFrames = static_cast<size_t>(mFormat.sampleRate) * 4;
    mRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * canonicalBytesPerFrame);
    mLiveRingBuffer = std::make_unique<RingBuffer>(ringBufferFrames * 2 * sizeof(int32_t));
    mWaveformAnalyzer = std::make_unique<WaveformAnalyzer>(mFormat.sampleRate);
    configureCaptureProcessing();

    mStreamOpen.store(true, std::memory_order_release);
    LOGI("USB iso capture open: %d Hz, 2ch extracted from a %dch wire "
         "format, format=I32 canonical, multitrack channels=%d",
            mFormat.sampleRate, isoConfig.totalChannels, mTrackChannels);

        return mFormat.sampleRate;
}

void UsbAudioEngine::onUsbIsoFrames(const int32_t* interleavedStereo, const int32_t* allChannels,
                                    int wireChannels, size_t frameCount) {
    // --- Invoked on UsbIsoAudioSource's libusb event thread: no blocking I/O below. ---
    // Mirrors the tail of onAudioReady() below -- meter update + optional ring-buffer write --
    // but always against a canonical, already-2-channel buffer (no per-format decode needed
    // here; UsbIsoAudioSource already produced left-justified, sign-extended int32 samples).
    static thread_local std::vector<int32_t> amplified;
    const size_t sampleCount = frameCount * 2;
    if (amplified.size() < sampleCount) amplified.resize(sampleCount);
    std::memcpy(amplified.data(), interleavedStereo, sampleCount * sizeof(int32_t));
    applyGainAndLimit(amplified.data(), frameCount, 2);
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

    TrackBus* bus = mTrackBus.load(std::memory_order_acquire);
    if (!bus) {
        routeCapturedFrames(processedStereo, frameCount, 2);
        return;
    }
    // Multitrack: process every wire channel, then queue [master pair | all channels] frames
    // so the encoder writes the master and each track from the same frames.
    const int channels = bus->channels();
    static thread_local std::vector<int32_t> tracks;
    static thread_local std::vector<int32_t> combined;
    const size_t trackSamples = frameCount * static_cast<size_t>(channels);
    if (tracks.size() < trackSamples) tracks.resize(trackSamples);
    if (allChannels && wireChannels == channels) {
        std::memcpy(tracks.data(), allChannels, trackSamples * sizeof(int32_t));
    } else {
        std::fill(tracks.begin(), tracks.begin() + static_cast<std::ptrdiff_t>(trackSamples), 0);
    }
    float gains[TrackBus::kMaxTracks];
    for (int t = 0; t < bus->trackCount(); ++t) gains[t] = mTrackGainLinear[t].load(std::memory_order_relaxed);
    bus->process(tracks.data(), frameCount, gains, mLimiterEnabled.load(std::memory_order_relaxed),
                 mTrackWaveformsEnabled.load(std::memory_order_relaxed));

    const int ringChannels = 2 + channels;
    const size_t combinedSamples = frameCount * static_cast<size_t>(ringChannels);
    if (combined.size() < combinedSamples) combined.resize(combinedSamples);
    for (size_t f = 0; f < frameCount; ++f) {
        int32_t* dst = combined.data() + f * ringChannels;
        dst[0] = processedStereo[f * 2];
        dst[1] = processedStereo[f * 2 + 1];
        std::memcpy(dst + 2, tracks.data() + f * channels, static_cast<size_t>(channels) * sizeof(int32_t));
    }
    routeCapturedFrames(combined.data(), frameCount, ringChannels);
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

    applyGainAndLimit(canonical.data(), static_cast<size_t>(numFrames), mChannelCount);

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

    routeCapturedFrames(canonical.data(), static_cast<size_t>(numFrames), mChannelCount);

    return oboe::DataCallbackResult::Continue;
}

void UsbAudioEngine::applyGainAndLimit(int32_t* interleaved, size_t frameCount, int32_t channelCount) {
    const float gain = mRecordingGainLinear.load(std::memory_order_relaxed);
    SafetyLimiter* limiter = mLimiter.load(std::memory_order_acquire);
    if (!limiter || limiter->channelCount() != channelCount) {
        // Only during the brief USB sample-rate measurement, before anything can be recorded.
        applyRecordingGain(interleaved, frameCount * static_cast<size_t>(channelCount), gain);
        return;
    }
    limiter->process(interleaved, frameCount, gain, mLimiterEnabled.load(std::memory_order_relaxed));
    const float reduction = limiter->takeReductionDb();
    if (reduction > mLimiterReductionDb.load(std::memory_order_relaxed)) {
        mLimiterReductionDb.store(reduction, std::memory_order_relaxed);
    }
}

void UsbAudioEngine::routeCapturedFrames(const int32_t* interleaved, size_t frameCount, int32_t channelCount) {
    // Read mRecording once so each batch lands in exactly one of recording ring or history.
    if (mRecording.load(std::memory_order_acquire)) {
        if (!mHistoryFrozen.load(std::memory_order_relaxed)) {
            // Every earlier history write happened-before this store: the encoder may read now.
            mHistoryFrozen.store(true, std::memory_order_release);
        }
        if (!mPaused.load(std::memory_order_relaxed) && mRingBuffer) {
            const size_t bytesToWrite = frameCount * static_cast<size_t>(channelCount) * sizeof(int32_t);
            const size_t written =
                mRingBuffer->write(reinterpret_cast<const uint8_t*>(interleaved), bytesToWrite);
            if (written < bytesToWrite) {
                // Encoder thread fell behind (e.g. slow storage): count it, never block the
                // audio thread to catch up.
                mXRunCount.fetch_add(1, std::memory_order_relaxed);
            }
        }
        return;
    }

    PreRecordHistory* history = mHistory.load(std::memory_order_acquire);
    if (!history || history->channelCount() != channelCount ||
        mHistoryFrozen.load(std::memory_order_acquire)) return;
    if (!mPreRecordEnabled.load(std::memory_order_relaxed)) {
        // Drop stale audio so re-enabling never splices an old moment onto the next Record.
        mHistoryClearRequested.store(true, std::memory_order_relaxed);
        return;
    }
    if (mHistoryClearRequested.exchange(false, std::memory_order_acq_rel)) history->clear();
    history->write(interleaved, frameCount);
}

void UsbAudioEngine::configureCaptureProcessing() {
    auto limiter = std::make_unique<SafetyLimiter>();
    limiter->configure(mFormat.sampleRate, mFormat.channelCount);
    mLimiterStorage = std::move(limiter);
    if (mTrackChannels > 0) {
        mTrackBusStorage = std::make_unique<TrackBus>(mFormat.sampleRate, mTrackChannels);
        mTrackLayout = mTrackBusStorage->layout();
        mTrackBus.store(mTrackBusStorage.get(), std::memory_order_release);
    }
    // History frames match recording-ring frames (master plus every track channel).
    mHistoryStorage = std::make_unique<PreRecordHistory>(
        mFormat.sampleRate, mRingChannels, kPreRecordSeconds);
    mHistoryFrozen.store(false, std::memory_order_relaxed);
    mHistoryClearRequested.store(false, std::memory_order_relaxed);
    mLimiterReductionDb.store(0.0f, std::memory_order_relaxed);
    mLimiter.store(mLimiterStorage.get(), std::memory_order_release);
    mHistory.store(mHistoryStorage.get(), std::memory_order_release);
}

void UsbAudioEngine::releaseCaptureProcessing() {
    mLimiter.store(nullptr, std::memory_order_release);
    mHistory.store(nullptr, std::memory_order_release);
    mTrackBus.store(nullptr, std::memory_order_release);
    mLimiterStorage.reset();
    mHistoryStorage.reset();
    mTrackBusStorage.reset();
    mTrackLayout.clear();
    for (auto& writer : mPendingTrackWriters) writer.reset();
    for (auto& writer : mPendingTrackRolls) writer.reset();
}

bool UsbAudioEngine::takePreRecordedAudio(std::vector<int32_t>& out) {
    out.clear();
    PreRecordHistory* history = mHistory.load(std::memory_order_acquire);
    if (!history) return false;
    // The capture thread freezes the history on its first callback after Record; if the
    // stream stalls instead, skip the pre-roll rather than race a late history write.
    for (int waited = 0; !mHistoryFrozen.load(std::memory_order_acquire); waited += 2) {
        if (waited >= 1000 || mStopRequested.load(std::memory_order_acquire)) {
            LOGW("Pre-record buffer skipped: capture did not confirm the Record start");
            return false;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    history->copyOldestFirst(out);
    return !out.empty();
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
        case ContainerFormat::Mp3: mWriter = std::make_unique<Mp3Writer>(); break;
    }

    if (!mWriter->open(path, mFormat)) {
        LOGE("Writer failed to open output file: %s", path.c_str());
        mWriter.reset();
        mPendingCompanion.reset();
        for (auto& writer : mPendingTrackWriters) writer.reset();
        return false;
    }

    mXRunCount.store(0, std::memory_order_relaxed);
    mRecordingErrorCode.store(0, std::memory_order_relaxed);
    mElapsedMillis.store(0, std::memory_order_relaxed);
    mStopRequested.store(false, std::memory_order_relaxed);
    mPaused.store(false, std::memory_order_relaxed);
    mRingBuffer->reset();
    resetSilenceGate();
    mPreRecordAtStart = mPreRecordEnabled.load(std::memory_order_acquire);
    mPreRecordedMillis.store(0, std::memory_order_relaxed);
    mTrailingSilenceFrames.store(0, std::memory_order_relaxed);
    mCompanionErrorCode.store(0, std::memory_order_relaxed);
    mTrackErrorMask.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        mCompanionWriter = std::move(mPendingCompanion);
        for (size_t t = 0; t < mTrackWriters.size(); ++t) {
            mTrackWriters[t] = std::move(mPendingTrackWriters[t]);
        }
    }
    // Keep live history: monitoring is already writing the analyzer on the audio thread.
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
        case ContainerFormat::Mp3: mWriter = std::make_unique<Mp3Writer>(); break;
    }
    if (!mWriter->openFd(fd, mFormat)) {
        LOGE("Writer failed to open MediaStore fd");
        mWriter.reset();
        mPendingCompanion.reset();
        for (auto& writer : mPendingTrackWriters) writer.reset();
        return false;
    }

    mXRunCount.store(0, std::memory_order_relaxed);
    mRecordingErrorCode.store(0, std::memory_order_relaxed);
    mElapsedMillis.store(0, std::memory_order_relaxed);
    mStopRequested.store(false, std::memory_order_relaxed);
    mPaused.store(false, std::memory_order_relaxed);
    mRingBuffer->reset();
    resetSilenceGate();
    mPreRecordAtStart = mPreRecordEnabled.load(std::memory_order_acquire);
    mPreRecordedMillis.store(0, std::memory_order_relaxed);
    mTrailingSilenceFrames.store(0, std::memory_order_relaxed);
    mCompanionErrorCode.store(0, std::memory_order_relaxed);
    mTrackErrorMask.store(0, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        mCompanionWriter = std::move(mPendingCompanion);
        for (size_t t = 0; t < mTrackWriters.size(); ++t) {
            mTrackWriters[t] = std::move(mPendingTrackWriters[t]);
        }
    }
    // Keep live history: monitoring is already writing the analyzer on the audio thread.
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
        case ContainerFormat::Mp3: next = std::make_unique<Mp3Writer>(); break;
    }
    if (!next->openFd(fd, mFormat)) return false;

    std::unique_ptr<AudioWriter> previous;
    std::array<std::unique_ptr<AudioWriter>, TrackBus::kMaxTracks> previousTracks;
    {
        // One lock for master and tracks: every file's part boundary lands on the same frame.
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        previous = std::move(mWriter);
        mWriter = std::move(next);
        for (size_t t = 0; t < mTrackWriters.size(); ++t) {
            if (!mPendingTrackRolls[t]) continue;
            previousTracks[t] = std::move(mTrackWriters[t]);
            mTrackWriters[t] = std::move(mPendingTrackRolls[t]);
        }
    }
    const bool finalized = !previous || previous->close();
    if (!finalized) mRecordingErrorCode.store(3, std::memory_order_release);
    for (size_t t = 0; t < previousTracks.size(); ++t) {
        if (previousTracks[t] && !previousTracks[t]->close()) {
            LOGE("Track %zu part failed to finalize; the master recording continues", t + 1);
            mTrackErrorMask.fetch_or(1u << t, std::memory_order_release);
        }
    }
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
    if (mCompanionWriter && mCompanionErrorCode.load(std::memory_order_relaxed) == 0 &&
        !mCompanionWriter->checkpoint()) {
        LOGW("Companion writer checkpoint failed; the master recording continues");
        mCompanionErrorCode.store(2, std::memory_order_release);
    }
    for (size_t t = 0; t < mTrackWriters.size(); ++t) {
        const uint32_t bit = 1u << t;
        if (mTrackWriters[t] && (mTrackErrorMask.load(std::memory_order_relaxed) & bit) == 0 &&
            !mTrackWriters[t]->checkpoint()) {
            LOGW("Track %zu checkpoint failed; the master recording continues", t + 1);
            mTrackErrorMask.fetch_or(bit, std::memory_order_release);
        }
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
        if (mCompanionWriter) {
            if (!mCompanionWriter->close()) {
                LOGE("Companion writer failed while finalizing output file");
                mCompanionErrorCode.store(3, std::memory_order_release);
            }
            mCompanionWriter.reset();
        }
        for (size_t t = 0; t < mTrackWriters.size(); ++t) {
            if (!mTrackWriters[t]) continue;
            if (!mTrackWriters[t]->close()) {
                LOGE("Track %zu writer failed while finalizing", t + 1);
                mTrackErrorMask.fetch_or(1u << t, std::memory_order_release);
            }
            mTrackWriters[t].reset();
        }
    }
    for (auto& writer : mPendingTrackRolls) writer.reset();
    // Monitoring resumes filling the pre-record history from empty; the capture thread owns
    // the clear so it never races a write.
    mHistoryClearRequested.store(true, std::memory_order_relaxed);
    mHistoryFrozen.store(false, std::memory_order_release);
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
    releaseCaptureProcessing();
    mPendingCompanion.reset();
    mRingBuffer.reset();
    mTrackChannels = 0;
    mSourceMode = SourceMode::None;
    mStreamOpen.store(false, std::memory_order_release);
}

void UsbAudioEngine::encoderThreadLoop() {
    constexpr size_t kChunkFrames = 960; // ~20ms chunks @48kHz; small enough for low file-write latency
    // Ring frames hold the master channels, then (multitrack only) every track channel.
    const int masterChannels = mFormat.channelCount;
    const int ringChannels = mRingChannels;
    std::vector<int32_t> chunk(kChunkFrames * ringChannels);
    std::vector<int32_t> masterScratch;
    std::vector<int32_t> trackScratch;
    uint64_t framesEncoded = 0;
    const size_t bytesPerFrame = sizeof(int32_t) * ringChannels;
    // Writes frames that passed the leading-silence gate; elapsed time counts only audio
    // that actually lands in the file.
    auto writeEncoded = [&](const int32_t* frames, size_t frameCount) {
        std::lock_guard<std::mutex> writerLock(mWriterMutex);
        const int32_t* master = frames;
        if (ringChannels != masterChannels) {
            masterScratch.resize(frameCount * masterChannels);
            for (size_t f = 0; f < frameCount; ++f) {
                std::memcpy(masterScratch.data() + f * masterChannels, frames + f * ringChannels,
                            static_cast<size_t>(masterChannels) * sizeof(int32_t));
            }
            master = masterScratch.data();
        }
        if (!mWriter || !mWriter->writeFrames(master, frameCount)) return false;
        if (mCompanionWriter && mCompanionErrorCode.load(std::memory_order_relaxed) == 0 &&
            !mCompanionWriter->writeFrames(master, frameCount)) {
            LOGE("Companion writer failed; the master recording continues");
            mCompanionErrorCode.store(1, std::memory_order_release);
        }
        if (ringChannels != masterChannels) writeTrackFrames(frames, frameCount, trackScratch);
        framesEncoded += frameCount;
        mElapsedMillis.store(
            static_cast<int64_t>(framesEncoded * 1000 / mFormat.sampleRate),
            std::memory_order_relaxed);
        const size_t lastAudible =
            LeadingSilenceGate::lastAudibleFrame(master, frameCount, masterChannels);
        if (lastAudible == frameCount) {
            mTrailingSilenceFrames.fetch_add(frameCount, std::memory_order_relaxed);
        } else {
            mTrailingSilenceFrames.store(frameCount - 1 - lastAudible, std::memory_order_relaxed);
        }
        return true;
    };

    // Pre-roll: the last seconds before Record, through the same silence gate as live audio.
    if (mPreRecordAtStart) {
        std::vector<int32_t> preRecorded;
        if (takePreRecordedAudio(preRecorded)) {
            const size_t totalFrames = preRecorded.size() / ringChannels;
            for (size_t offset = 0; offset < totalFrames; offset += kChunkFrames) {
                const size_t count = std::min(kChunkFrames, totalFrames - offset);
                const bool wasAwaiting = mSilenceGate.awaiting();
                if (!mSilenceGate.process(preRecorded.data() + offset * ringChannels,
                                          count, writeEncoded)) {
                    LOGE("Encoder write failed while writing the pre-record buffer");
                    mRecordingErrorCode.store(1, std::memory_order_release);
                    return;
                }
                if (wasAwaiting) publishSilenceGateState();
            }
            mPreRecordedMillis.store(
                static_cast<int64_t>(totalFrames * 1000 / mFormat.sampleRate),
                std::memory_order_relaxed);
            LOGI("Pre-record buffer: %zu frames prepended", totalFrames);
        }
    }

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
            const bool wasAwaiting = mSilenceGate.awaiting();
            if (!mSilenceGate.process(chunk.data(), framesRead, writeEncoded)) {
                LOGE("Encoder write failed after %llu frames",
                     static_cast<unsigned long long>(framesEncoded));
                mRecordingErrorCode.store(1, std::memory_order_release);
                return;
            }
            if (wasAwaiting) publishSilenceGateState();
        }
    }

    // Stopped before any audio arrived: keep the short pre-roll so the file stays playable.
    const bool wasAwaiting = mSilenceGate.awaiting();
    if (!mSilenceGate.flush(writeEncoded)) {
        LOGE("Encoder write failed while flushing leading-silence pre-roll");
        mRecordingErrorCode.store(1, std::memory_order_release);
    }
    if (wasAwaiting) publishSilenceGateState();
}

void UsbAudioEngine::resetSilenceGate() {
    // Only the master decides when audio starts; tracks are trimmed on the same frame.
    mSilenceGate.reset(mTrimLeadingSilence.load(std::memory_order_acquire),
                       mRingChannels, mFormat.sampleRate, mFormat.channelCount);
    mTrimmedLeadingMillis.store(0, std::memory_order_relaxed);
    mAwaitingAudio.store(mSilenceGate.awaiting(), std::memory_order_release);
}

void UsbAudioEngine::publishSilenceGateState() {
    const int64_t trimmedMillis = mFormat.sampleRate > 0
        ? static_cast<int64_t>(mSilenceGate.discardedFrames() * 1000 / mFormat.sampleRate)
        : 0;
    mTrimmedLeadingMillis.store(trimmedMillis, std::memory_order_relaxed);
    if (!mSilenceGate.awaiting()) {
        mAwaitingAudio.store(false, std::memory_order_release);
        LOGI("Leading silence trimmed: %lld ms", static_cast<long long>(trimmedMillis));
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
        << "trim_leading_silence=" << (mTrimLeadingSilence.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "awaiting_audio=" << (mAwaitingAudio.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "trimmed_leading_ms=" << mTrimmedLeadingMillis.load(std::memory_order_relaxed) << '\n'
        << "limiter_enabled=" << (mLimiterEnabled.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "pre_record_enabled=" << (mPreRecordEnabled.load(std::memory_order_relaxed) ? "true" : "false") << '\n'
        << "pre_recorded_ms=" << mPreRecordedMillis.load(std::memory_order_relaxed) << '\n'
        << "trailing_silence_ms=" << getTrailingSilenceMillis() << '\n'
        << "companion_error_code=" << mCompanionErrorCode.load(std::memory_order_relaxed) << '\n'
        << "multitrack_channels=" << mTrackChannels << " ring_channels=" << mRingChannels
        << " track_error_mask=" << mTrackErrorMask.load(std::memory_order_relaxed) << '\n'
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
    } else if (!mLastUsbSetupFailure.empty()) {
        out << "\n--- failed_usb_setup ---\n" << mLastUsbSetupFailure;
    }
    return out.str();
}

void UsbAudioEngine::getWaveformBins(float* outBins) const {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mWaveformAnalyzer) {
        uint32_t sequence = 0;
        mWaveformAnalyzer->getBins(outBins, &sequence);
        outBins[kWaveformBinCount * 4] = static_cast<float>(sequence % 1048576);
        outBins[kWaveformBinCount * 4 + 1] = mWaveformAnalyzer->binDurationMillis();
    } else {
        std::memset(outBins, 0, (kWaveformBinCount * 4 + 2) * sizeof(float));
    }
}

void UsbAudioEngine::setWaveformEnabled(bool enabled) {
    mWaveformEnabled.store(enabled, std::memory_order_release);
}

void UsbAudioEngine::setTrimLeadingSilence(bool enabled) {
    mTrimLeadingSilence.store(enabled, std::memory_order_release);
}

void UsbAudioEngine::setLimiterEnabled(bool enabled) {
    mLimiterEnabled.store(enabled, std::memory_order_relaxed);
}

float UsbAudioEngine::takeLimiterReductionDb() {
    return mLimiterReductionDb.exchange(0.0f, std::memory_order_relaxed);
}

void UsbAudioEngine::setPreRecordEnabled(bool enabled) {
    mPreRecordEnabled.store(enabled, std::memory_order_release);
}

int64_t UsbAudioEngine::getTrailingSilenceMillis() const {
    const int rate = mFormat.sampleRate;
    if (rate <= 0) return 0;
    return static_cast<int64_t>(mTrailingSilenceFrames.load(std::memory_order_relaxed) * 1000 / rate);
}

bool UsbAudioEngine::prepareCompanionFd(int fd, ContainerFormat format) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mPendingCompanion.reset();
    if (!mStreamOpen.load() || mRecording.load() || fd < 0) return false;
    std::unique_ptr<AudioWriter> writer;
    switch (format) {
        case ContainerFormat::Wav: writer = std::make_unique<WavWriter>(); break;
        case ContainerFormat::Flac: writer = std::make_unique<FlacWriter>(); break;
        case ContainerFormat::Mp3: writer = std::make_unique<Mp3Writer>(); break;
    }
    if (!writer->openFd(fd, mFormat)) {
        LOGE("Companion writer failed to open MediaStore fd");
        return false;
    }
    mPendingCompanion = std::move(writer);
    return true;
}

void UsbAudioEngine::clearPendingCompanion() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    mPendingCompanion.reset();
}

int32_t UsbAudioEngine::getCompanionErrorCode() const {
    return mCompanionErrorCode.load(std::memory_order_acquire);
}

bool UsbAudioEngine::isAwaitingAudio() const {
    return mRecording.load(std::memory_order_acquire) &&
           mAwaitingAudio.load(std::memory_order_acquire);
}

// --- Multitrack -------------------------------------------------------------------------

void UsbAudioEngine::writeTrackFrames(const int32_t* combined, size_t frameCount,
                                      std::vector<int32_t>& scratch) {
    const int masterChannels = mFormat.channelCount;
    const int ringChannels = mRingChannels;
    const uint32_t failed = mTrackErrorMask.load(std::memory_order_relaxed);
    for (size_t t = 0; t < mTrackLayout.size() && t < mTrackWriters.size(); ++t) {
        AudioWriter* writer = mTrackWriters[t].get();
        const uint32_t bit = 1u << t;
        if (!writer || (failed & bit) != 0) continue;
        const TrackLayout& track = mTrackLayout[t];
        scratch.resize(frameCount * static_cast<size_t>(track.width));
        for (size_t f = 0; f < frameCount; ++f) {
            const int32_t* src = combined + f * ringChannels + masterChannels + track.first;
            for (int ch = 0; ch < track.width; ++ch) scratch[f * track.width + ch] = src[ch];
        }
        if (!writer->writeFrames(scratch.data(), frameCount)) {
            // A full or failing track file never stops the master or the other tracks.
            LOGE("Track %zu writer failed; the master recording continues", t + 1);
            mTrackErrorMask.fetch_or(bit, std::memory_order_release);
        }
    }
}

std::unique_ptr<AudioWriter> UsbAudioEngine::makeTrackWriter(int track, int fd, ContainerFormat format) {
    if (fd < 0 || track < 0 || track >= static_cast<int>(mTrackLayout.size())) return nullptr;
    std::unique_ptr<AudioWriter> writer;
    switch (format) {
        case ContainerFormat::Wav: writer = std::make_unique<WavWriter>(); break;
        case ContainerFormat::Flac: writer = std::make_unique<FlacWriter>(); break;
        case ContainerFormat::Mp3: writer = std::make_unique<Mp3Writer>(); break;
    }
    AudioFormatInfo trackFormat = mFormat;
    trackFormat.channelCount = mTrackLayout[track].width;
    if (!writer->openFd(fd, trackFormat)) {
        LOGE("Track %d writer failed to open MediaStore fd", track + 1);
        return nullptr;
    }
    return writer;
}

int UsbAudioEngine::getTrackChannelCount() const {
    const TrackBus* bus = mTrackBus.load(std::memory_order_acquire);
    return bus ? bus->channels() : 0;
}

bool UsbAudioEngine::prepareTrackFd(int track, int fd, ContainerFormat format) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mStreamOpen.load() || mRecording.load() || track < 0 ||
        track >= static_cast<int>(mPendingTrackWriters.size())) return false;
    mPendingTrackWriters[track] = makeTrackWriter(track, fd, format);
    return mPendingTrackWriters[track] != nullptr;
}

void UsbAudioEngine::clearPendingTracks() {
    std::lock_guard<std::mutex> lock(mControlMutex);
    for (auto& writer : mPendingTrackWriters) writer.reset();
    for (auto& writer : mPendingTrackRolls) writer.reset();
}

bool UsbAudioEngine::prepareTrackRollFd(int track, int fd, ContainerFormat format) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (!mRecording.load() || track < 0 || track >= static_cast<int>(mPendingTrackRolls.size())) return false;
    mPendingTrackRolls[track] = makeTrackWriter(track, fd, format);
    return mPendingTrackRolls[track] != nullptr;
}

uint32_t UsbAudioEngine::getTrackErrorMask() const {
    return mTrackErrorMask.load(std::memory_order_acquire);
}

void UsbAudioEngine::setTrackGainDb(int track, float gainDb) {
    if (track < 0 || track >= TrackBus::kMaxTracks || !std::isfinite(gainDb)) return;
    mTrackGainLinear[track].store(std::pow(10.0f, std::clamp(gainDb, -24.0f, 12.0f) / 20.0f),
                                  std::memory_order_relaxed);
}

void UsbAudioEngine::setTrackWaveformsEnabled(bool enabled) {
    mTrackWaveformsEnabled.store(enabled, std::memory_order_release);
}

int UsbAudioEngine::getTrackLevels(float* out, int maxChannels) {
    // Polled by the UI: never wait behind a slow open or a mixer routing change.
    std::unique_lock<std::mutex> lock(mControlMutex, std::try_to_lock);
    if (!lock.owns_lock()) return -1;
    TrackBus* bus = mTrackBus.load(std::memory_order_acquire);
    return bus ? bus->readLevels(out, maxChannels) : 0;
}

void UsbAudioEngine::getTrackWaveformBins(int track, float* outBins) const {
    std::unique_lock<std::mutex> lock(mControlMutex, std::try_to_lock);
    const TrackBus* bus = lock.owns_lock() ? mTrackBus.load(std::memory_order_acquire) : nullptr;
    if (bus) {
        bus->readWaveform(track, outBins);
    } else {
        std::memset(outBins, 0, (kWaveformBinCount * 4 + 2) * sizeof(float));
    }
}

int UsbAudioEngine::getMasterChannelOffset() const {
    std::unique_lock<std::mutex> lock(mControlMutex, std::try_to_lock);
    if (!lock.owns_lock()) return -1;
    if (mSourceMode == SourceMode::UsbIso && mUsbIsoSource) return mUsbIsoSource->resolvedChannelOffset();
    return mSourceMode == SourceMode::Oboe ? 0 : -1;
}

int UsbAudioEngine::setPioneerTrackSource(int output, int source) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mSourceMode != SourceMode::UsbIso || !mUsbIsoSource || mTrackChannels == 0) {
        return UsbIsoAudioSource::kRouteUnsupported;
    }
    return mUsbIsoSource->setPioneerOutputSource(output, source);
}

int UsbAudioEngine::getPioneerTrackSource(int output) {
    std::lock_guard<std::mutex> lock(mControlMutex);
    if (mSourceMode != SourceMode::UsbIso || !mUsbIsoSource) return -1;
    return mUsbIsoSource->readPioneerOutputSource(output);
}

} // namespace djmrec

void djmrec::UsbAudioEngine::setRecordingGainDb(int gainDb) {
    mRecordingGainLinear.store(std::pow(10.0f, std::clamp(gainDb, -12, 24) / 20.0f),
                               std::memory_order_relaxed);
}
