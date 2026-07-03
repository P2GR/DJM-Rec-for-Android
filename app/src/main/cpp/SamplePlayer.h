#pragma once

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

namespace djmrec {

/**
 * RMX-1000 drum sound identifiers. Order matches the sample pad grid in the UI.
 */
enum class RmxSound : int {
    Kick = 0,
    Snare = 1,
    HihatClosed = 2,
    HihatOpen = 3,
    Clap = 4,
    Rim = 5,
    TomLow = 6,
    TomHigh = 7,
    Crash = 8,
    Percussion = 9,
    Count = 10
};

/**
 * Polyphonic sample player with 16 voices and beat-synced looping.
 * All render methods are realtime-safe (no allocations, no locks).
 */
class SamplePlayer {
public:
    static constexpr int kVoiceCount = 16;
    static constexpr int kSampleRate = 44100;

    struct VoiceState {
        const float* sampleData = nullptr;
        size_t sampleLength = 0;
        size_t readPos = 0;
        float gain = 1.0f;
        float pitchRatio = 1.0f;
        bool active = false;
        RmxSound sound = RmxSound::Kick;
    };

    SamplePlayer();

    /** Load a sample into the bank. Samples are mono float at 44100 Hz. */
    void loadSample(RmxSound sound, const float* data, size_t length);

    /**
     * Trigger a one-shot sample. If looping, the sample restarts at loopStart when it
     * reaches loopEnd. Set loopLengthSamples to 0 for one-shot (no loop).
     */
    void trigger(RmxSound sound, float gain = 1.0f, float pitchRatio = 1.0f,
                 size_t loopStartSamples = 0, size_t loopEndSamples = 0);

    /** Stop all voices for a given sound. */
    void stopSound(RmxSound sound);

    /** Stop all voices immediately. */
    void stopAll();

    /**
     * Render mixed stereo output. Called from the realtime audio callback.
     * @param stereoOut interleaved stereo float buffer, [-1, 1].
     * @param frameCount number of stereo frames to render.
     */
    void render(float* stereoOut, int frameCount);

    /** Check if any voice of a given sound is currently playing. */
    bool isSoundPlaying(RmxSound sound) const;

    /** Get the number of currently active voices. */
    int activeVoiceCount() const;

private:
    int findFreeVoice() const;
    int findQuietestVoice() const;

    std::vector<float> mSampleBank[static_cast<int>(RmxSound::Count)];
    VoiceState mVoices[kVoiceCount];
};

} // namespace djmrec
