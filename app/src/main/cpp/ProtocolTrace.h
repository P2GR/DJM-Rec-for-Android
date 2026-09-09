#pragma once
#include <libusb.h>
#include <string>
namespace djmrec {
void setProtocolTracing(bool enabled);
std::string drainProtocolTrace();
int tracedUsbControl(libusb_device_handle* handle, uint8_t type, uint8_t request,
                     uint16_t value, uint16_t index, unsigned char* data,
                     uint16_t length, unsigned int timeout);
}
