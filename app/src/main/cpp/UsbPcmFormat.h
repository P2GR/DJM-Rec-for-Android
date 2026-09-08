#pragma once
#include <cstdint>
#include <cstddef>
#include <vector>

namespace djmrec {
// UAC PCM is left-justified in its subslot. Preserve the existing DJM-specific
// right-justified 24-in-32 contract only when explicitly requested by its profile.
inline int32_t decodeUsbPcm(const uint8_t* sample, int bytes, bool legacy24In32 = false) {
    uint32_t value = 0;
    if (bytes < 2 || bytes > 4) return 0;
    for (int i = 0; i < bytes; ++i) value |= static_cast<uint32_t>(sample[i]) << (8 * i);
    value <<= (bytes == 4 && legacy24In32) ? 8 : (4 - bytes) * 8;
    return static_cast<int32_t>(value);
}

// Issue UAC1 endpoint frequency controls only when the selected endpoint advertises them.
inline bool hasEndpointFrequencyControl(const std::vector<uint8_t>& raw, int interfaceNumber,
                                       int alternateSetting, int endpointAddress) {
    bool selected = false;
    bool endpoint = false;
    for (size_t offset = 0; offset + 1 < raw.size();) {
        const auto length = raw[offset];
        if (length < 2 || offset + length > raw.size()) return false;
        if (raw[offset + 1] == 4) {
            if (length < 9) return false;
            selected = raw[offset + 2] == interfaceNumber && raw[offset + 3] == alternateSetting &&
                raw[offset + 5] == 1 && raw[offset + 6] == 2 && raw[offset + 7] == 0;
            endpoint = false;
        } else if (raw[offset + 1] == 5) {
            if (length < 7) return false;
            endpoint = selected && raw[offset + 2] == endpointAddress;
        } else if (raw[offset + 1] == 0x25 && length >= 7 && endpoint && raw[offset + 2] == 1) {
            return (raw[offset + 3] & 1) != 0;
        }
        offset += length;
    }
    return false;
}
}
