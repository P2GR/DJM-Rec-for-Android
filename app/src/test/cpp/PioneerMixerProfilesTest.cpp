#include "PioneerMixerProfiles.h"
#include <cassert>
#include <iostream>

int main() {
    using namespace djmrec;
    // A manually selected USB5/6 pair must receive 0x030a, never the default 0x010a.
    assert(pioneerMixRouteValue(kDjm450Profile, -1) == 0x010a);
    assert(pioneerMixRouteValue(kDjm450Profile, 0) == 0x010a);
    assert(pioneerMixRouteValue(kDjm450Profile, 2) == 0x020a);
    assert(pioneerMixRouteValue(kDjm450Profile, 4) == 0x030a);
    assert(pioneerMixRouteValue(kDjm450Profile, 6) == -1); // USB7/8 has no configurable MIX route.
    assert(pioneerMixRouteValue(kDjm450Profile, 3) == -1);
    assert(pioneerMixRouteValue(kDjm450Profile, -2) == -1);
    assert(kDjm450Profile.requiresPlaybackTraffic);
    assert(kDjm450Profile.playbackInterface == 0);
    assert(kDjm450Profile.playbackAlternateSetting == 1);
    assert(kDjm450Profile.playbackOutChannels == 8);
    assert(kDjm450Profile.playbackOutSubframeBytes == 3);
    assert(kDjm450Profile.fixedCaptureInSampleRate == 48000);
    // Existing default MIX routes remain model-specific.
    assert(pioneerMixRouteValue(kDjmA9Profile, -1) == 0x050a);
    assert(pioneerMixRouteValue(kDjm750Mk2Profile, -1) == 0x010f);
    assert(pioneerMixRouteValue(kDjmS11Profile, 0) == -1);
    assert(pioneerMixRouteValue(kDjmS11Profile, -1) == 0x030a);
    std::cout << "Pioneer selected-pair routing and DJM-450 duplex checks passed\n";
}
