#include "ProtocolTrace.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <sstream>

namespace djmrec {
#if DJMREC_PROTOCOL_RESEARCH
namespace {
std::atomic<bool> enabled{false};
std::mutex traceMutex;
std::string pending;
unsigned long dropped = 0;
}
#endif
void setProtocolTracing(bool value) {
#if DJMREC_PROTOCOL_RESEARCH
    std::lock_guard<std::mutex> lock(traceMutex);
    enabled.store(value);
    if (value) { pending.clear(); dropped = 0; }
#endif
}
std::string drainProtocolTrace() {
#if DJMREC_PROTOCOL_RESEARCH
    std::lock_guard<std::mutex> lock(traceMutex);
    std::string result;
    result.swap(pending);
    if (dropped) result += "{\"dropped\":" + std::to_string(dropped) + "}\n";
    dropped = 0;
    return result;
#else
    return {};
#endif
}
int tracedUsbControl(libusb_device_handle* handle, uint8_t type, uint8_t request,
                     uint16_t value, uint16_t index, unsigned char* data,
                     uint16_t length, unsigned int timeout) {
    const int result = libusb_control_transfer(handle, type, request, value, index, data, length, timeout);
#if DJMREC_PROTOCOL_RESEARCH
    if (enabled.load()) {
        const int payload = data ? std::min<int>(4096, type & 0x80 ? std::max(0, result) : length) : 0;
        unsigned char setup[] = {type, request, static_cast<uint8_t>(value), static_cast<uint8_t>(value >> 8),
            static_cast<uint8_t>(index), static_cast<uint8_t>(index >> 8), static_cast<uint8_t>(length), static_cast<uint8_t>(length >> 8)};
        const char* digits = "0123456789abcdef";
        std::string hex;
        auto append = [&](uint8_t b) { hex += digits[b >> 4]; hex += digits[b & 15]; };
        for (auto b : setup) append(b);
        for (int i = 0; i < payload; ++i) append(data[i]);
        const auto now = std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        const auto line = "{\"steadyNs\":" + std::to_string(now) + ",\"result\":" + std::to_string(result) +
            ",\"capturedPayload\":" + std::to_string(payload) + ",\"setupAndDataHex\":\"" + hex + "\"}\n";
        std::lock_guard<std::mutex> lock(traceMutex);
        if (enabled.load()) {
            if (pending.size() + line.size() <= 60000) pending += line;
            else ++dropped;
        }
    }
#endif
    return result;
}
}
