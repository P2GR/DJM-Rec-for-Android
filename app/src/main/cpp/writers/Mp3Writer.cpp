#include "Mp3Writer.h"

#include <algorithm>
#include <android/log.h>

#define TAG "Mp3Writer"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace djmrec {

#if HAVE_LAME

bool Mp3Writer::open(const std::string& path, const AudioFormatInfo& format) {
    mFile = fopen(path.c_str(), "wb");
    if (!mFile) {
        LOGE("Failed to open %s for writing", path.c_str());
        return false;
    }

    mLame = lame_init();
    if (!mLame) {
        LOGE("lame_init failed");
        fclose(mFile);
        mFile = nullptr;
        return false;
    }

    lame_set_in_samplerate(mLame, format.sampleRate);
    mChannelCount = format.channelCount;
    lame_set_num_channels(mLame, mChannelCount);
    lame_set_quality(mLame, 2); // 0 = best/slowest, 2 is the standard "near-best" realtime setting

    if (mUseVbr) {
        lame_set_VBR(mLame, vbr_default);
        lame_set_VBR_quality(mLame, 0); // 0 = highest VBR quality
    } else {
        lame_set_VBR(mLame, vbr_off);
        lame_set_brate(mLame, mBitrateKbps); // 320 kbps CBR by default
    }

    if (lame_init_params(mLame) < 0) {
        LOGE("lame_init_params failed");
        lame_close(mLame);
        mLame = nullptr;
        fclose(mFile);
        mFile = nullptr;
        return false;
    }

    LOGI("Opened MP3 %s @ %dHz %s", path.c_str(), format.sampleRate,
         mUseVbr ? "VBR-Q0" : (std::to_string(mBitrateKbps) + "kbps CBR").c_str());
    return true;
}

bool Mp3Writer::writeFrames(const int32_t* interleaved, size_t frameCount) {
    if (!mLame || !mFile) return false;

    const size_t sampleCount = frameCount * static_cast<size_t>(mChannelCount);
    if (mInt16Scratch.size() < sampleCount) mInt16Scratch.resize(sampleCount);

    // LAME's interleaved API expects classic 16-bit PCM; downshifting from our full-scale
    // int32 is lossless enough here since MP3 itself is a lossy codec at this bitrate — the
    // quantization noise introduced by dropping to 16-bit is far below the codec's own noise floor.
    for (size_t i = 0; i < sampleCount; ++i) {
        mInt16Scratch[i] = static_cast<int16_t>(interleaved[i] >> 16);
    }

    const int mp3BufSize = static_cast<int>(1.25 * frameCount) + 7200;
    if (mMp3Scratch.size() < static_cast<size_t>(mp3BufSize)) mMp3Scratch.resize(mp3BufSize);

    const int bytesWritten = lame_encode_buffer_interleaved(
        mLame, mInt16Scratch.data(), static_cast<int>(frameCount),
        mMp3Scratch.data(), mp3BufSize);

    if (bytesWritten < 0) {
        LOGE("lame_encode_buffer_interleaved error %d", bytesWritten);
        return false;
    }
    if (bytesWritten == 0) return true;

    return fwrite(mMp3Scratch.data(), 1, bytesWritten, mFile) == static_cast<size_t>(bytesWritten);
}

bool Mp3Writer::close() {
    if (!mLame && !mFile) return true;

    bool ok = true;
    if (mLame) {
        std::vector<unsigned char> flushBuf(7200);
        const int flushed = lame_encode_flush(mLame, flushBuf.data(), static_cast<int>(flushBuf.size()));
        if (flushed > 0 && mFile) {
            ok = fwrite(flushBuf.data(), 1, flushed, mFile) == static_cast<size_t>(flushed);
        }
        lame_close(mLame);
        mLame = nullptr;
    }
    if (mFile) {
        ok = fclose(mFile) == 0 && ok;
        mFile = nullptr;
    }
    LOGI("Closed MP3 encoder (ok=%d)", ok);
    return ok;
}

#else // !HAVE_LAME

bool Mp3Writer::open(const std::string&, const AudioFormatInfo&) {
    LOGW("MP3 export unavailable: this build was compiled without LAME sources. "
         "See CMakeLists.txt third_party/lame instructions.");
    return false;
}

bool Mp3Writer::writeFrames(const int32_t*, size_t) { return false; }

bool Mp3Writer::close() { return true; }

#endif

} // namespace djmrec
