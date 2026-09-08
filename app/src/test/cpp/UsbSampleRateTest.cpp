#include "UsbSampleRate.h"
#include <cassert>
#include <iostream>
int main() {
    using namespace djmrec;
    assert(nominalUsbSampleRate(99271) == 0); // Must never become a PCM format.
    assert(usbPacketSampleRate(48000, 4000, 8000) == 96000);
    assert(usbPacketSampleRate(47952, 4000, 8000) == 96000); // Four startup empties.
    assert(usbPacketSampleRate(24000, 4000, 8000) == 48000);
    assert(usbPacketSampleRate(22050, 500, 1000) == 44100);
    assert(usbPacketSampleRate(48000, 2000, 4000) == 96000); // bInterval=2.
    assert(usbPacketSampleRate(0, 4000, 8000) == 0);
    assert(usbPacketSampleRate(48000, 0, 8000) == 0);
    std::cout << "USB packet-rate checks passed, including 99271 Hz rejection\n";
}
