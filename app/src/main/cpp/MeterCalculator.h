#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <oboe/Oboe.h>

namespace djmrec {

/** Floor of the UI's dBFS scale; matches the VU meter's [-60, 0] dB requirement. */
constexpr float kMeterFloorDb = -60.0f;
/** Samples at or above this normalized magnitude are flagged as clipping (~-0.3 dBFS headroom). */
constexpr float kClipThreshold = 0.966f;

struct StereoMeterReading {
    float leftPeakDb = kMeterFloorDb;
    float leftRmsDb = kMeterFloorDb;
    float rightPeakDb = kMeterFloorDb;
    float rightRmsDb = kMeterFloorDb;
    bool clipping = false;
};

/**
 * Converts a linear amplitude ratio (0..1) into a clamped dBFS value for the VU meter.
 * 0 amplitude maps to the floor rather than -inf so the UI never has to special-case silence.
 */
inline float amplitudeToDb(float amplitude) {
    if (amplitude <= 0.0f) return kMeterFloorDb;
    const float db = 20.0f * log10f(amplitude);
    return std::max(kMeterFloorDb, std::min(0.0f, db));
}

/**
 * Computes peak + RMS levels per stereo channel directly from the raw interleaved buffer
 * pulled off the ring buffer, without any allocation — called every audio callback so it
 * must stay allocation-free and branch-predictable.
 */
class MeterCalculator {
public:
    static StereoMeterReading analyze(const void* interleaved, int32_t frameCount, oboe::AudioFormat format) {
        StereoMeterReading reading;
        float leftPeak = 0.0f, rightPeak = 0.0f;
        double leftSumSq = 0.0, rightSumSq = 0.0;

        switch (format) {
            case oboe::AudioFormat::I16: {
                const auto* samples = static_cast<const int16_t*>(interleaved);
                constexpr float scale = 1.0f / 32768.0f;
                for (int32_t i = 0; i < frameCount; ++i) {
                    const float l = samples[i * 2] * scale;
                    const float r = samples[i * 2 + 1] * scale;
                    leftPeak = std::max(leftPeak, std::fabs(l));
                    rightPeak = std::max(rightPeak, std::fabs(r));
                    leftSumSq += static_cast<double>(l) * l;
                    rightSumSq += static_cast<double>(r) * r;
                }
                break;
            }
            case oboe::AudioFormat::I32: {
                const auto* samples = static_cast<const int32_t*>(interleaved);
                constexpr float scale = 1.0f / 2147483648.0f;
                for (int32_t i = 0; i < frameCount; ++i) {
                    const float l = samples[i * 2] * scale;
                    const float r = samples[i * 2 + 1] * scale;
                    leftPeak = std::max(leftPeak, std::fabs(l));
                    rightPeak = std::max(rightPeak, std::fabs(r));
                    leftSumSq += static_cast<double>(l) * l;
                    rightSumSq += static_cast<double>(r) * r;
                }
                break;
            }
            case oboe::AudioFormat::Float: {
                const auto* samples = static_cast<const float*>(interleaved);
                for (int32_t i = 0; i < frameCount; ++i) {
                    const float l = samples[i * 2];
                    const float r = samples[i * 2 + 1];
                    leftPeak = std::max(leftPeak, std::fabs(l));
                    rightPeak = std::max(rightPeak, std::fabs(r));
                    leftSumSq += static_cast<double>(l) * l;
                    rightSumSq += static_cast<double>(r) * r;
                }
                break;
            }
            default:
                // Unsupported format for direct metering (e.g. I24_PACKED handled by caller
                // via pre-expansion to I32 before reaching here).
                break;
        }

        if (frameCount > 0) {
            reading.leftRmsDb = amplitudeToDb(static_cast<float>(std::sqrt(leftSumSq / frameCount)));
            reading.rightRmsDb = amplitudeToDb(static_cast<float>(std::sqrt(rightSumSq / frameCount)));
        }
        reading.leftPeakDb = amplitudeToDb(leftPeak);
        reading.rightPeakDb = amplitudeToDb(rightPeak);
        reading.clipping = leftPeak >= kClipThreshold || rightPeak >= kClipThreshold;
        return reading;
    }
};

} // namespace djmrec
