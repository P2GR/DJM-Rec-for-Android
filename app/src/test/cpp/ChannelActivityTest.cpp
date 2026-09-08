#include "ChannelActivity.h"
#include <cassert>
#include <iostream>

int main() {
    djmrec::ChannelActivity activity;
    activity.reset();
    assert(activity.summary(3).find("pending") != std::string::npos);
    // Three packed-24 channels: silence, +0.5, -1.0. Last unpaired channel must be measured.
    const uint8_t input[]{0,0,0, 0,0,0x40, 0,0,0x80};
    activity.observe(input, 1, 3, 3, false);
    assert(activity.peak(0) == 0);
    assert(activity.peak(1) == 0x40000000);
    assert(activity.peak(2) == 0x80000000u);
    activity.publish();
    auto report = activity.summary(3);
    assert(report.find("USB1=-120.0dBFS(below threshold)") != std::string::npos);
    assert(report.find("USB2=-6.0dBFS(active)") != std::string::npos);
    assert(report.find("USB3=0.0dBFS(active)") != std::string::npos);
    assert(activity.peak(1) == 0);
    activity.publish();
    assert(activity.summary(3).find("USB2=-120.0dBFS(below threshold)") != std::string::npos);
    const uint8_t mono[]{0,0x40};
    activity.observe(mono, 1, 1, 2, false);
    assert(activity.peak(0) == 0x40000000);
    activity.reset();
    assert(activity.peak(0) == 0);
    assert(activity.summary(1).find("pending") != std::string::npos);
    std::cout << "Channel activity checks passed: stereo separation, odd/mono channels, signs, silence, window reset\n";
}
