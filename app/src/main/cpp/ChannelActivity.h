#pragma once
#include "UsbPcmFormat.h"
#include <array>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <chrono>
#include <sstream>
#include <iomanip>

namespace djmrec {
// Aggregate amplitudes only. Fixed storage, no locks/allocations in observe/publish.
class ChannelActivity {
public:
    void reset() {
        working.fill(0);
        for (auto& value : published) value.store(0, std::memory_order_relaxed);
        publishedAt.store(0, std::memory_order_relaxed);
    }
    void observe(const uint8_t* pcm, size_t frames, int channels, int bytes, bool legacy) {
        if (channels < 1 || channels > 32 || bytes < 2 || bytes > 4) return;
        for (size_t frame = 0; frame < frames; ++frame) {
            for (int ch = 0; ch < channels; ++ch) {
                const int64_t sample = decodeUsbPcm(pcm + (frame * channels + ch) * bytes, bytes, legacy);
                working[ch] = std::max(working[ch], static_cast<uint32_t>(sample < 0 ? -sample : sample));
            }
        }
    }
    uint32_t peak(int channel) const { return channel >= 0 && channel < 32 ? working[channel] : 0; }
    void publish() {
        for (size_t ch = 0; ch < working.size(); ++ch) published[ch].store(working[ch], std::memory_order_relaxed);
        publishedAt.store(now(), std::memory_order_release);
        working.fill(0);
    }
    std::string summary(int channels) const {
        const auto timestamp = publishedAt.load(std::memory_order_acquire);
        if (!timestamp) return "channel_activity=pending first 1-second audio window";
        std::ostringstream out;
        out << "channel_activity=pre-gain 1-second peak window; age_ms=" << now() - timestamp
            << "; active_threshold=-60dBFS; approximate snapshot\n";
        for (int ch = 0; ch < std::min(channels, 32); ++ch) {
            const auto magnitude = published[ch].load(std::memory_order_relaxed);
            const double db = magnitude ? 20.0 * std::log10(magnitude / 2147483648.0) : -120.0;
            out << "USB" << ch + 1 << '=' << std::fixed << std::setprecision(1) << db << "dBFS"
                << (db >= -60.0 ? "(active)" : "(below threshold)") << ' ';
        }
        return out.str();
    }
private:
    static int64_t now() {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    std::array<uint32_t, 32> working{};
    std::array<std::atomic<uint32_t>, 32> published{};
    std::atomic<int64_t> publishedAt{0};
};
}
