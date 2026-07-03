#include "SamplePlayer.h"

#include <android/log.h>
#include <cmath>
#include <cstring>

#define TAG "SamplePlayer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace djmrec {

SamplePlayer::SamplePlayer() {
    std::memset(mVoices, 0, sizeof(mVoices));
}

void SamplePlayer::loadSample(RmxSound sound, const float* data, size_t length) {
    const int idx = static_cast<int>(sound);
    if (idx < 0 || idx >= static_cast<int>(RmxSound::Count)) return;
    mSampleBank[idx].assign(data, data + length);
    LOGI("Loaded sample %d: %zu samples", idx, length);
}

void SamplePlayer::trigger(RmxSound sound, float gain, float pitchRatio,
                           size_t loopEndSamples) {
    const int idx = static_cast<int>(sound);
    if (idx < 0 || idx >= static_cast<int>(RmxSound::Count)) return;
    if (mSampleBank[idx].empty()) return;

    int voiceIdx = findFreeVoice();
    if (voiceIdx < 0) voiceIdx = findQuietestVoice();
    if (voiceIdx < 0) return;

    VoiceState& v = mVoices[voiceIdx];
    v.sampleData = mSampleBank[idx].data();
    v.sampleLength = mSampleBank[idx].size();
    v.readPos = 0;
    v.loopEnd = loopEndSamples > 0 ? std::min(loopEndSamples, v.sampleLength) : 0;
    v.gain = gain;
    v.pitchRatio = pitchRatio;
    v.active = true;
    v.sound = sound;
}

void SamplePlayer::updateVoiceLoop(RmxSound sound, size_t loopEndSamples) {
    for (int i = 0; i < kVoiceCount; ++i) {
        if (mVoices[i].active && mVoices[i].sound == sound) {
            mVoices[i].loopEnd = loopEndSamples > 0 ? std::min(loopEndSamples, mVoices[i].sampleLength) : 0;
        }
    }
}

void SamplePlayer::stopSound(RmxSound sound) {
    for (int i = 0; i < kVoiceCount; ++i) {
        if (mVoices[i].sound == sound) {
            mVoices[i].active = false;
        }
    }
}

void SamplePlayer::stopAll() {
    for (int i = 0; i < kVoiceCount; ++i) {
        mVoices[i].active = false;
    }
}

void SamplePlayer::render(float* stereoOut, int frameCount) {
    // Zero output buffer.
    std::memset(stereoOut, 0, frameCount * 2 * sizeof(float));

    for (int v = 0; v < kVoiceCount; ++v) {
        VoiceState& voice = mVoices[v];
        if (!voice.active || !voice.sampleData) continue;

        for (int f = 0; f < frameCount; ++f) {
            const size_t effectiveEnd = voice.loopEnd > 0 ? voice.loopEnd : voice.sampleLength;
            if (voice.readPos >= effectiveEnd) {
                if (voice.loopEnd > 0) {
                    voice.readPos = 0; // loop back to start
                } else {
                    voice.active = false;
                    break;
                }
            }

            const size_t idx = static_cast<size_t>(voice.readPos);
            const float sample = voice.sampleData[idx] * voice.gain;

            stereoOut[f * 2] += sample;
            stereoOut[f * 2 + 1] += sample;

            voice.readPos += voice.pitchRatio;
        }
    }
}

bool SamplePlayer::isSoundPlaying(RmxSound sound) const {
    for (int i = 0; i < kVoiceCount; ++i) {
        if (mVoices[i].active && mVoices[i].sound == sound) return true;
    }
    return false;
}

int SamplePlayer::activeVoiceCount() const {
    int count = 0;
    for (int i = 0; i < kVoiceCount; ++i) {
        if (mVoices[i].active) ++count;
    }
    return count;
}

int SamplePlayer::findFreeVoice() const {
    for (int i = 0; i < kVoiceCount; ++i) {
        if (!mVoices[i].active) return i;
    }
    return -1;
}

int SamplePlayer::findQuietestVoice() const {
    int bestIdx = -1;
    float lowestGain = 999.0f;
    for (int i = 0; i < kVoiceCount; ++i) {
        if (mVoices[i].gain < lowestGain) {
            lowestGain = mVoices[i].gain;
            bestIdx = i;
        }
    }
    return bestIdx;
}

} // namespace djmrec
