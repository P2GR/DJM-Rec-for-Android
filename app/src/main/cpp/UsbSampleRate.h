#pragma once
#include <cmath>
#include <cstdint>
#include <climits>

namespace djmrec {
// Measurements are evidence for a nominal clock, never a new PCM format.
inline int nominalUsbSampleRate(int measured) {
    constexpr int rates[] = {8000, 11025, 12000, 16000, 22050, 24000, 32000,
                             44100, 48000, 88200, 96000, 176400, 192000};
    int closest = 0, distance = INT_MAX;
    for (int rate : rates) {
        const int delta = std::abs(measured - rate);
        if (delta < distance) { closest = rate; distance = delta; }
    }
    return static_cast<int64_t>(distance) * 100 <= closest * 3 ? closest : 0;
}

inline int usbPacketSampleRate(uint64_t frames, uint64_t packets, int packetsPerSecond) {
    if (!packets || packetsPerSecond <= 0) return 0;
    return nominalUsbSampleRate(static_cast<int>(std::llround(
        static_cast<double>(frames) * packetsPerSecond / packets)));
}
}
