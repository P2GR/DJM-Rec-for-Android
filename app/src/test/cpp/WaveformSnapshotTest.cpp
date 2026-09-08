#include "WaveformAnalyzer.h"
#include <array>
#include <atomic>
#include <cassert>
#include <cmath>
#include <thread>
#include <vector>
#include <iostream>

int main() {
    djmrec::WaveformAnalyzer analyzer(48000);
    std::atomic<bool> done{false};
    std::thread producer([&] {
        std::vector<int32_t> frames(294 * 2);
        for (int bin = 1; bin <= 4096; ++bin) {
            std::fill(frames.begin(), frames.end(), (bin % 1000) * 1000000);
            analyzer.pushFrames(frames.data(), 294);
        }
        done.store(true);
    });
    do {
        std::array<float, 2048> snapshot{};
        uint32_t end = 0;
        analyzer.getBins(snapshot.data(), &end);
        for (int i = 0; i < 512; ++i) {
            const int bin = static_cast<int>(end) - 511 + i;
            const float expected = bin <= 0 ? 0.0f : (bin % 1000) * 1000000 / 2147483648.0f;
            assert(std::fabs(snapshot[i * 4] - expected) < 0.000001f);
        }
    } while (!done.load());
    producer.join();
    assert(std::fabs(analyzer.binDurationMillis() - 6.125f) < 0.001f);
    std::cout << "Concurrent waveform history and cursor checks passed\n";
}
