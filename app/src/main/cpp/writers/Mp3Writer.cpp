#include "writers/Mp3Writer.h"

#include <android/log.h>
#include <unistd.h>

#include <cerrno>
#include <cmath>
#include <cstring>

#define TAG "Mp3Writer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// shine is plain C without extern "C" guards in its headers -- without this the symbols
// get C++-mangled and the link fails with "undefined symbol: shine_...".
extern "C" {
#include "lib/layer3.h"
}

namespace djmrec {

// 15-tap Blackman-windowed sinc half-band low-pass (cutoff = fs/4 of the input rate).
// Zero coefficients at even offsets (except centre) make it a true half-band filter:
// cheap and exactly linear-phase before dropping every other sample.
const float Mp3Writer::Decimator::kCoeffs[15] = {
    0.f, 0.f, 0.0057647f, 0.f, -0.0487182f, 0.f, 0.2929677f, 0.499971f,
    0.2929677f, 0.f, -0.0487182f, 0.f, 0.0057647f, 0.f, 0.f
};

bool Mp3Writer::Decimator::process(float sample, float* out) {
    history[cursor] = sample;
    float dot = 0.f;
    for (int i = 0; i < 15; ++i) dot += kCoeffs[i] * history[(cursor - i + 15) % 15];
    cursor = (cursor + 1) % 15;
    phase ^= 1;
    if (phase != 0) return false;
    *out = dot;
    return true;
}

namespace {

/** Maps capture rates above 48 kHz down to a shine-legal rate (mirrors StreamAudioFormat). */
int encoderRateFor(int captureRate, int* decimatorStages) {
    switch (captureRate) {
        case 88200:  *decimatorStages = 1; return 44100;
        case 96000:  *decimatorStages = 1; return 48000;
        case 176400: *decimatorStages = 2; return 44100;
        case 192000: *decimatorStages = 2; return 48000;
        default:     *decimatorStages = 0; return captureRate;
    }
}

inline int16_t clampToPcm16(float sample) {
    const float clamped = std::fmaxf(-32768.f, std::fminf(32767.f, sample));
    return static_cast<int16_t>(std::lroundf(clamped));
}

} // namespace

Mp3Writer::~Mp3Writer() {
    close();
}

bool Mp3Writer::open(const std::string& path, const AudioFormatInfo& format) {
    FILE* file = std::fopen(path.c_str(), "wb");
    if (file == nullptr) return false;
    return begin(format, file);
}

bool Mp3Writer::openFd(int fd, const AudioFormatInfo& format) {
    // The engine's fd owner closes its own copy right after handing it over, so keep ours.
    const int owned = ::dup(fd);
    if (owned < 0) return false;
    FILE* file = ::fdopen(owned, "wb");
    if (file == nullptr) {
        ::close(owned);
        return false;
    }
    return begin(format, file);
}

bool Mp3Writer::begin(const AudioFormatInfo& format, FILE* file) {
    int stages = 0;
    const int rate = encoderRateFor(format.sampleRate, &stages);
    // Spec is 320 kbps; fall back to the highest rate-legal bitrate for exotic
    // sub-32 kHz capture rates where 320 is not representable in the MPEG tables.
    int bitrate = 0;
    for (int candidate : { 320, 160, 128, 64 }) {
        if (shine_check_config(rate, candidate) >= 0) {
            bitrate = candidate;
            break;
        }
    }
    shine_t shine = nullptr;
    if (bitrate > 0) {
        shine_config_t config;
        std::memset(&config, 0, sizeof(config));
        shine_set_config_mpeg_defaults(&config.mpeg);
        config.wave.channels = PCM_STEREO;
        config.wave.samplerate = rate;
        config.mpeg.mode = STEREO;
        config.mpeg.bitr = bitrate;
        shine = shine_initialise(&config);
    }
    if (shine == nullptr) {
        LOGE("MP3: unsupported capture rate %d", format.sampleRate);
        std::fclose(file);
        return false;
    }
    mFile = file;
    mShine = shine;
    mDecimatorStages = stages;
    mPassSamples = shine_samples_per_pass(shine);
    mStage.assign(static_cast<size_t>(mPassSamples) * 2, 0);
    mStagedFrames = 0;
    mBytesWritten = 0;
    return true;
}

bool Mp3Writer::writeFrames(const int32_t* interleaved, size_t frameCount) {
    if (mFile == nullptr || mShine == nullptr || interleaved == nullptr) return false;
    for (size_t frame = 0; frame < frameCount; ++frame) {
        // int32 payload is left-justified 24-bit capture; the top 16 bits are the MP3 sample.
        const int16_t left = static_cast<int16_t>(interleaved[frame * 2] >> 16);
        const int16_t right = static_cast<int16_t>(interleaved[frame * 2 + 1] >> 16);
        pushDecimated(left, right);
    }
    return true;
}

void Mp3Writer::pushDecimated(int16_t left, int16_t right) {
    float curLeft = static_cast<float>(left);
    float curRight = static_cast<float>(right);
    for (int stage = 0; stage < mDecimatorStages; ++stage) {
        float outLeft = 0.f;
        float outRight = 0.f;
        const bool emitLeft = mDecL[stage].process(curLeft, &outLeft);
        mDecR[stage].process(curRight, &outRight);
        if (!emitLeft) return;
        curLeft = outLeft;
        curRight = outRight;
    }
    mStage[static_cast<size_t>(mStagedFrames) * 2] = clampToPcm16(curLeft);
    mStage[static_cast<size_t>(mStagedFrames) * 2 + 1] = clampToPcm16(curRight);
    if (++mStagedFrames >= mPassSamples) encodeStagedPass();
}

void Mp3Writer::encodeStagedPass() {
    int written = 0;
    unsigned char* data = shine_encode_buffer_interleaved(
        mShine, reinterpret_cast<int16_t*>(mStage.data()), &written);
    if (data != nullptr && written > 0) {
        std::fwrite(data, 1, static_cast<size_t>(written), mFile);
        mBytesWritten += static_cast<uint64_t>(written);
    }
    mStagedFrames = 0;
}

bool Mp3Writer::close() {
    if (mFile == nullptr) return true; // never opened or already closed
    if (mShine != nullptr) {
        if (mStagedFrames > 0) {
            // shine requires full passes; pad the final partial frame block with silence.
            for (size_t i = static_cast<size_t>(mStagedFrames) * 2; i < mStage.size(); ++i) {
                mStage[i] = 0;
            }
            encodeStagedPass();
        }
        int written = 0;
        unsigned char* tail = shine_flush(mShine, &written);
        if (tail != nullptr && written > 0) {
            std::fwrite(tail, 1, static_cast<size_t>(written), mFile);
            mBytesWritten += static_cast<uint64_t>(written);
        }
        shine_close(mShine);
        mShine = nullptr;
    }
    const bool ok = std::fflush(mFile) == 0 && std::fclose(mFile) == 0;
    mFile = nullptr;
    return ok;
}

bool Mp3Writer::checkpoint() {
    if (mFile == nullptr) return false;
    if (std::fflush(mFile) != 0) return false;
    // fsync keeps crash recovery meaningful; tolerate fds where the provider refuses it.
    return ::fsync(::fileno(mFile)) == 0 || errno == EINVAL;
}

} // namespace djmrec
