#pragma once

#include <array>

namespace djmrec {

enum class PioneerRouteReadMode {
    SingleOutputZeroBased,
    SingleOutputOneBased,
    AllOutputs
};

struct PioneerMixerProfile {
    const char* name;
    int productIdFirst;
    int productIdLast;
    int outputCount;
    int defaultOutput;
    std::array<int, 5> mixWithMicSources;
    std::array<int, 5> mixWithoutMicSources;
    PioneerRouteReadMode routeReadMode;
    bool usesEndpointSampleRate;
    bool requiresPlaybackTraffic;
    int playbackInterface;
    int playbackAlternateSetting;
    int playbackChannels;
    int playbackSubframeBytes;
};

constexpr int kAlphaThetaVendorId = 0x2B73;

// Values derived from installed Pioneer/AlphaTheta setup DLLs and kernel drivers.
constexpr PioneerMixerProfile kDjmA9Profile{
    "DJM-A9", 0x003C, 0x003C, 5, 4,
    {0x09, 0x09, 0x09, 0x09, 0x09},
    {0x0A, 0x0A, 0x0A, 0x0A, 0x0A},
    PioneerRouteReadMode::SingleOutputZeroBased, true, true, 1, 1, 10, 3
};

constexpr PioneerMixerProfile kDjmV5Profile{
    "DJM-V5", 0x0058, 0x005B, 4, 0,
    {0x0A, 0x0A, 0x0A, 0x0A, -1},
    {0x0E, 0x0E, 0x0E, 0x0E, -1},
    PioneerRouteReadMode::SingleOutputOneBased, true, false, -1, -1, 0, 0
};

constexpr PioneerMixerProfile kDjm900Nxs2Profile{
    "DJM-900NXS2", 0x000A, 0x000A, 5, 0,
    {0x0A, 0x0A, 0x0A, 0x0A, 0x0A},
    {0x0A, 0x0A, 0x0A, 0x0A, 0x0A},
    PioneerRouteReadMode::AllOutputs, true, false, -1, -1, 0, 0
};

constexpr PioneerMixerProfile kDjm750Mk2Profile{
    "DJM-750MK2", 0x001B, 0x001B, 5, 0,
    {0x0F, 0x0F, 0x0F, 0x0F, 0x0A},
    {0x0F, 0x0F, 0x0F, 0x0F, 0x0A},
    PioneerRouteReadMode::AllOutputs, true, false, -1, -1, 0, 0
};

inline const PioneerMixerProfile* findPioneerMixerProfile(int vendorId, int productId) {
    if (vendorId != kAlphaThetaVendorId) return nullptr;
    constexpr const PioneerMixerProfile* profiles[] = {
        &kDjmA9Profile, &kDjmV5Profile, &kDjm900Nxs2Profile, &kDjm750Mk2Profile
    };
    for (const auto* profile : profiles) {
        if (productId >= profile->productIdFirst && productId <= profile->productIdLast) {
            return profile;
        }
    }
    return nullptr;
}

} // namespace djmrec
