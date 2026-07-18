#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <memory>

namespace djmrec {

/**
 * Lock-free single-producer / single-consumer ring buffer.
 *
 * Producer: the AAudio real-time data callback (writes interleaved PCM frames, must never
 *           block, never allocate, never take a mutex).
 * Consumer: the encoder thread (reads frames at its own pace and feeds WAV/FLAC writers).
 *
 * Capacity is rounded up to the next power of two so index wrapping is a cheap bitmask.
 * Byte-oriented: callers decide the "frame" size (channels * bytesPerSample) themselves,
 * which keeps this class reusable regardless of the negotiated bit depth.
 */
class RingBuffer {
public:
    explicit RingBuffer(size_t capacityBytes) {
        size_t capacity = 1;
        while (capacity < capacityBytes) capacity <<= 1;
        mCapacity = capacity;
        mMask = capacity - 1;
        mBuffer = std::make_unique<uint8_t[]>(capacity);
        mWriteIndex.store(0, std::memory_order_relaxed);
        mReadIndex.store(0, std::memory_order_relaxed);
    }

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    /** Bytes currently available to read (safe to call from either thread). */
    size_t availableToRead() const {
        const size_t w = mWriteIndex.load(std::memory_order_acquire);
        const size_t r = mReadIndex.load(std::memory_order_acquire);
        return w - r;
    }

    size_t availableToWrite() const {
        return mCapacity - availableToRead();
    }

    /**
     * Producer-side write. Returns the number of bytes actually written; if the buffer is
     * full, older un-consumed audio is intentionally NOT overwritten (that would corrupt the
     * stream) — instead the excess is dropped and the caller should count it as an overrun.
     */
    size_t write(const uint8_t* data, size_t bytes) {
        const size_t freeSpace = availableToWrite();
        const size_t toWrite = bytes < freeSpace ? bytes : freeSpace;
        if (toWrite == 0) return 0;

        const size_t w = mWriteIndex.load(std::memory_order_relaxed);
        const size_t writePos = w & mMask;
        const size_t firstChunk = std::min(toWrite, mCapacity - writePos);

        std::memcpy(mBuffer.get() + writePos, data, firstChunk);
        if (toWrite > firstChunk) {
            std::memcpy(mBuffer.get(), data + firstChunk, toWrite - firstChunk);
        }

        mWriteIndex.store(w + toWrite, std::memory_order_release);
        return toWrite;
    }

    /** Consumer-side read. Returns the number of bytes actually read (<= bytes requested). */
    size_t read(uint8_t* out, size_t bytes) {
        const size_t available = availableToRead();
        const size_t toRead = bytes < available ? bytes : available;
        if (toRead == 0) return 0;

        const size_t r = mReadIndex.load(std::memory_order_relaxed);
        const size_t readPos = r & mMask;
        const size_t firstChunk = std::min(toRead, mCapacity - readPos);

        std::memcpy(out, mBuffer.get() + readPos, firstChunk);
        if (toRead > firstChunk) {
            std::memcpy(out + firstChunk, mBuffer.get(), toRead - firstChunk);
        }

        mReadIndex.store(r + toRead, std::memory_order_release);
        return toRead;
    }

    void reset() {
        mWriteIndex.store(0, std::memory_order_relaxed);
        mReadIndex.store(0, std::memory_order_relaxed);
    }

    size_t capacity() const { return mCapacity; }

private:
    std::unique_ptr<uint8_t[]> mBuffer;
    size_t mCapacity;
    size_t mMask;
    // Monotonically increasing counters (not wrapped) so availableToRead()/Write() are branch-free;
    // only the physical index (counter & mask) wraps. Safe for 64-bit size_t at realistic audio
    // durations (multi-year continuous recording before this could theoretically wrap).
    std::atomic<size_t> mWriteIndex;
    std::atomic<size_t> mReadIndex;
};

} // namespace djmrec
