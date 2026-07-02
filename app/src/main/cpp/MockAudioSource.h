#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace djmrec {

/**
 * Synthetic 12-channel PCM source that simulates a Pioneer DJM-A9's USB output
 * for testing without physical hardware. Each channel carries a distinct sine
 * tone so you can audibly/visually verify that channel extraction is correct:
 *
 *   Ch  1–8 : 220, 330, 440, 550, 660, 770, 880, 990 Hz  (deck channels)
 *   Ch  9   : 1000 Hz  (master left  — what gets recorded)
 *   Ch 10   : 1200 Hz  (master right — what gets recorded)
 *   Ch 11–12: 1500, 1800 Hz  (unused)
 *
 * Runs on its own thread, calling the provided callback with interleaved
 * stereo int32 PCM at a steady rate. The callback is the same onUsbIsoFrames()
 * used by the real USB path, so the entire downstream pipeline (meter,
 * waveform, encoder) works identically.
 */
class MockAudioSource {
public:
    using FrameCallback = std::function<void(const int32_t* interleavedStereo, size_t frameCount)>;

    struct Config {
        int32_t totalChannels = 12;
        int32_t extractChannelOffset = 8; // channels 9/10 (0-indexed)
        int32_t sampleRate = 48000;
    };

    MockAudioSource() = default;
    ~MockAudioSource();

    MockAudioSource(const MockAudioSource&) = delete;
    MockAudioSource& operator=(const MockAudioSource&) = delete;

    /**
     * Starts generating synthetic audio on a background thread. @p callback
     * receives 2-channel interleaved int32 PCM at ~20ms chunk intervals.
     * Returns an empty string on success, or an error description on failure.
     */
    std::string start(const Config& config, FrameCallback callback);

    /** Signals the generator thread to stop and joins it. */
    void stop();

private:
    void generatorLoop();
    void fillFrame(int32_t* frame12ch, uint64_t frameIndex) const;

    Config mConfig;
    FrameCallback mCallback;
    std::unique_ptr<std::thread> mThread;
    std::atomic<bool> mRunning{false};

    // Pre-computed sine table for 48kHz — one full cycle per frequency.
    struct SineOsc {
        float phase = 0.0f;
        float step = 0.0f;
        explicit SineOsc(float hz, float sr = 48000.0f) : step(hz * 2.0f * 3.14159265f / sr) {}
        float next() { float v = std::sin(phase); phase += step; return v; }
    };
};

} // namespace djmrec
