#include "UsbPcmFormat.h"
#include <cassert>
#include <iostream>

int main() {
    const uint8_t pcm16[] = {0, 0x40};
    const uint8_t pcm24[] = {0, 0, 0x40};
    const uint8_t pcm32[] = {0, 0, 0, 0x40};
    const uint8_t negative24[] = {0, 0, 0x80};
    const uint8_t legacy24[] = {0, 0, 0x40, 0};
    assert(djmrec::decodeUsbPcm(pcm16, 2) == 0x40000000);
    assert(djmrec::decodeUsbPcm(pcm24, 3) == 0x40000000);
    assert(djmrec::decodeUsbPcm(pcm32, 4) == 0x40000000);
    assert(djmrec::decodeUsbPcm(negative24, 3) == INT32_MIN);
    assert(djmrec::decodeUsbPcm(legacy24, 4, true) == 0x40000000);
    const std::vector<uint8_t> descriptors = {
        9,4,1,1,1,1,2,0,0,
        7,5,0x81,1,0,2,1,
        7,0x25,1,1,0,0,0
    };
    assert(djmrec::hasEndpointFrequencyControl(descriptors,1,1,0x81));
    assert(!djmrec::hasEndpointFrequencyControl(descriptors,1,2,0x81));
    assert(!djmrec::hasEndpointFrequencyControl(descriptors,1,1,0x82));
    auto uac2 = descriptors; uac2[7] = 0x20;
    assert(!djmrec::hasEndpointFrequencyControl(uac2,1,1,0x81));
    auto readOnly = descriptors; readOnly[19] = 0;
    assert(!djmrec::hasEndpointFrequencyControl(readOnly,1,1,0x81));
    auto truncated = descriptors; truncated.pop_back();
    assert(!djmrec::hasEndpointFrequencyControl(truncated,1,1,0x81));
    std::cout << "USB PCM and endpoint-frequency checks passed\n";
}
