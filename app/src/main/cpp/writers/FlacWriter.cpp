#include "FlacWriter.h"

#include <algorithm>
#include <android/log.h>
#include <vector>

#define TAG "FlacWriter"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace djmrec {

bool FlacWriter::open(const std::string& path, const AudioFormatInfo& format) {
    if (format.sampleRate <= 0 || format.channelCount <= 0 || format.channelCount > 8 ||
        format.bitsPerSample < 8 || format.bitsPerSample > 32) {
        LOGE("Invalid FLAC format: %dHz / %d-bit / %dch", format.sampleRate,
             format.bitsPerSample, format.channelCount);
        return false;
    }
    mFormat = format;

    // The FLAC "streamable subset" (required for broad decoder/player compatibility) caps
    // bit depth at 24; a 32-bit-container source is still only ever 24 effective bits from
    // a real UAC2 mixer, so clamping here loses nothing in practice while maximizing
    // compatibility with downstream players.
    const int flacBitsPerSample = std::min(format.bitsPerSample, 24);
    mShiftAmount = 32 - flacBitsPerSample;

    mEncoder = FLAC__stream_encoder_new();
    if (!mEncoder) {
        LOGE("FLAC__stream_encoder_new failed");
        return false;
    }

    const bool configured =
        FLAC__stream_encoder_set_channels(mEncoder, format.channelCount) &&
        FLAC__stream_encoder_set_bits_per_sample(mEncoder, flacBitsPerSample) &&
        FLAC__stream_encoder_set_sample_rate(mEncoder, format.sampleRate) &&
        FLAC__stream_encoder_set_compression_level(mEncoder, 5) &&
        FLAC__stream_encoder_set_streamable_subset(mEncoder, true);
    if (!configured) {
        LOGE("FLAC encoder rejected %dHz / %d-bit / %dch", format.sampleRate,
             flacBitsPerSample, format.channelCount);
        FLAC__stream_encoder_delete(mEncoder);
        mEncoder = nullptr;
        return false;
    }

    const FLAC__StreamEncoderInitStatus status =
        FLAC__stream_encoder_init_file(mEncoder, path.c_str(), nullptr, nullptr);
    if (status != FLAC__STREAM_ENCODER_INIT_STATUS_OK) {
        LOGE("FLAC__stream_encoder_init_file failed: %s",
             FLAC__StreamEncoderInitStatusString[status]);
        FLAC__stream_encoder_delete(mEncoder);
        mEncoder = nullptr;
        return false;
    }

    LOGI("Opened FLAC %s @ %dHz / %d-bit / %dch", path.c_str(), format.sampleRate,
         flacBitsPerSample, format.channelCount);
    return true;
}

bool FlacWriter::writeFrames(const int32_t* interleaved, size_t frameCount) {
    if (!mEncoder) return false;

    // libFLAC's process_interleaved() wants FLAC__int32 samples already scaled to the target
    // bit depth (e.g. -8388608..8388607 for 24-bit), not full 32-bit range, hence the shift.
    static thread_local std::vector<FLAC__int32> scratch;
    const size_t sampleCount = frameCount * mFormat.channelCount;
    if (scratch.size() < sampleCount) scratch.resize(sampleCount);

    for (size_t i = 0; i < sampleCount; ++i) {
        scratch[i] = interleaved[i] >> mShiftAmount;
    }

    const bool ok = FLAC__stream_encoder_process_interleaved(
        mEncoder, scratch.data(), static_cast<uint32_t>(frameCount));
    if (!ok) {
        LOGE("FLAC encode failed: %s",
             FLAC__StreamEncoderStateString[FLAC__stream_encoder_get_state(mEncoder)]);
    }
    return ok;
}

bool FlacWriter::close() {
    if (!mEncoder) return true;
    const bool ok = FLAC__stream_encoder_finish(mEncoder);
    FLAC__stream_encoder_delete(mEncoder);
    mEncoder = nullptr;
    LOGI("Closed FLAC encoder (finish=%d)", ok);
    return ok;
}

} // namespace djmrec
