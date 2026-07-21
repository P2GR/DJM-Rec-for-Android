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

// requiresPlaybackTraffic added 2026-07-20: a raw hex dump of the capture endpoint's untouched
// wire bytes (taken before any channel/format demux) confirmed genuine all-zero payload across
// every MIX-routed output pair, with music confirmed audibly playing on the mixer at the time --
// ruling out both "no signal at the source" and "wrong channel/bit-depth guess" (a format error
// would misplace real nonzero bytes, not zero them). The remaining hypothesis, mirrored from
// DJM-A9's confirmed-working requiresPlaybackTraffic mechanism: this endpoint may only emit real
// audio once the host is also driving its OUT direction. Unlike DJM-A9 (separate playback
// interface/alt-setting from its capture interface), DJM-900NXS2's OUT endpoint (0x01) lives on
// the SAME vendor-class interface+alt-setting (if0/alt1) as its IN capture endpoint (0x82) --
// untested territory, since no prior USBPcap capture confirms what this OUT endpoint expects.
constexpr PioneerMixerProfile kDjm900Nxs2Profile{
    "DJM-900NXS2", 0x000A, 0x000A, 5, 0,
    {0x0A, 0x0A, 0x0A, 0x0A, 0x0A},
    {0x0A, 0x0A, 0x0A, 0x0A, 0x0A},
    // playbackChannels/playbackSubframeBytes only size the zero-filled OUT silence buffer (see
    // "washing machine" wire-format correction in PioneerMixerProfile.kt: real capture data is
    // 12ch/24-bit, not 10ch as originally guessed here) -- kept consistent for documentation, but
    // since the OUT content is always zero this has no effect on captured audio either way.
    PioneerRouteReadMode::AllOutputs, true, true, 0, 1, 12, 3
};

// Same vendor-class interface topology as DJM-900NXS2: isochronous IN endpoint (0x82) lives on
// if0/alt1 declared as USB_CLASS_VENDOR_SPEC (255), OUT endpoint (0x01) on the same interface.
// requiresPlaybackTraffic and vendor-capture override applied per the same evidence chain.
// Channel count 10 is the best initial guess (5 outputs x 2ch); the raw hex dump feature will
// reveal the actual packet size on first capture if this turns out wrong (NXS2 was 12ch).
constexpr PioneerMixerProfile kDjm750Mk2Profile{
    "DJM-750MK2", 0x001B, 0x001B, 5, 0,
    {0x0F, 0x0F, 0x0F, 0x0F, 0x0A},
    {0x0F, 0x0F, 0x0F, 0x0F, 0x0A},
    PioneerRouteReadMode::AllOutputs, true, true, 0, 1, 10, 3
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
