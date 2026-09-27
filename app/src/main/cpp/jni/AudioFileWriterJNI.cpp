#include <jni.h>

#include <memory>

#include "../writers/AudioWriter.h"
#include "../writers/FlacWriter.h"
#include "../writers/Mp3Writer.h"
#include "../writers/WavWriter.h"

// Offline access to the recording writers for the post-set editor's export. Each handle owns
// one writer; calls for a handle must not overlap (the Kotlin side uses one worker thread).
namespace {
djmrec::AudioWriter* fromHandle(jlong handle) {
    return reinterpret_cast<djmrec::AudioWriter*>(handle);
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_audiopro_djmrec_editor_NativeAudioFileWriter_open(
    JNIEnv* /*env*/, jobject /*thiz*/, jint fd, jint format, jint sampleRate, jint channelCount,
    jint bitsPerSample) {
    std::unique_ptr<djmrec::AudioWriter> writer;
    switch (format) {
        case 0: writer = std::make_unique<djmrec::WavWriter>(); break;
        case 1: writer = std::make_unique<djmrec::FlacWriter>(); break;
        case 2: writer = std::make_unique<djmrec::Mp3Writer>(); break;
        default: return 0;
    }
    djmrec::AudioFormatInfo info;
    info.sampleRate = sampleRate;
    info.channelCount = channelCount;
    info.bitsPerSample = bitsPerSample;
    if (fd < 0 || !writer->openFd(fd, info)) return 0;
    return reinterpret_cast<jlong>(writer.release());
}

JNIEXPORT jboolean JNICALL
Java_com_audiopro_djmrec_editor_NativeAudioFileWriter_write(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jintArray interleaved, jint frameCount) {
    djmrec::AudioWriter* writer = fromHandle(handle);
    if (!writer || !interleaved || frameCount < 0) return JNI_FALSE;
    if (frameCount == 0) return JNI_TRUE;
    jint* samples = env->GetIntArrayElements(interleaved, nullptr);
    if (!samples) return JNI_FALSE;
    const bool written = writer->writeFrames(reinterpret_cast<const int32_t*>(samples),
                                             static_cast<size_t>(frameCount));
    env->ReleaseIntArrayElements(interleaved, samples, JNI_ABORT);
    return written ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_audiopro_djmrec_editor_NativeAudioFileWriter_close(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    std::unique_ptr<djmrec::AudioWriter> writer(fromHandle(handle));
    if (!writer) return JNI_FALSE;
    return writer->close() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
