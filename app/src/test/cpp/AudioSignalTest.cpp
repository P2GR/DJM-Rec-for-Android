#include "WaveformAnalyzer.h"
#include "AudioGain.h"
#include <array>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <vector>

using Snapshot = std::array<float, djmrec::WaveformAnalyzer::kBinCount * 4>;

Snapshot tone(int rate, double hz, bool inverted, bool rightOnly = false) {
    djmrec::WaveformAnalyzer analyzer(rate);
    std::vector<int32_t> samples(rate * 2);
    for (int frame = 0; frame < rate; ++frame) {
        auto sample = static_cast<int32_t>(std::sin(6.283185307179586 * hz * frame / rate) * 1073741824.0);
        samples[frame * 2] = rightOnly ? 0 : sample;
        samples[frame * 2 + 1] = inverted ? -sample : sample;
    }
    analyzer.pushFrames(samples.data(), rate);
    Snapshot bins{};
    analyzer.getBins(bins.data());
    return bins;
}

int main() {
    for (int rate : {44100, 48000, 96000}) {
        for (double hz : {80.0, 800.0, 8000.0}) {
            const auto stereo = tone(rate, hz, false);
            const auto inverted = tone(rate, hz, true);
            const auto right = tone(rate, hz, false, true);
            for (size_t i = 0; i < stereo.size(); ++i) {
                assert(std::isfinite(stereo[i]));
                assert(std::fabs(stereo[i] - inverted[i]) < 0.00001f);
            }
            const size_t last = stereo.size() - 4;
            // At 8 kHz / 48 kHz the sampled sine peaks at sqrt(3)/2 of its
            // continuous amplitude; do not require an unsampled peak of 0.5.
            assert(stereo[last] > 0.4f);
            assert(std::fabs(right[last] - stereo[last]) < 0.00001f);
            const int dominant = hz < 250 ? 1 : hz < 2000 ? 2 : 3;
            for (int band = 1; band <= 3; ++band) {
                if (band != dominant) assert(stereo[last + dominant] > stereo[last + band]);
            }
        }
        djmrec::WaveformAnalyzer analyzer(rate);
        std::vector<int32_t> samples(rate * 8, 1073741824);
        analyzer.pushFrames(samples.data(), samples.size() / 2);
        Snapshot bins{};
        analyzer.getBins(bins.data());
        for (size_t i = 0; i < bins.size(); i += 4) assert(bins[i] == 0.5f);
        analyzer.reset();
        analyzer.getBins(bins.data());
        for (float value : bins) assert(value == 0.0f);
    }
    int32_t samples[] = {0, 100, -100, std::numeric_limits<int32_t>::max(), std::numeric_limits<int32_t>::min()};
    djmrec::applyRecordingGain(samples, 5, 2.0f);
    assert(samples[0] == 0 && samples[1] == 200 && samples[2] == -200);
    assert(samples[3] == std::numeric_limits<int32_t>::max());
    assert(samples[4] == std::numeric_limits<int32_t>::min());
    std::cout << "Audio signal checks passed: phase, stereo, bands, rates, history, reset, gain saturation\n";
}
