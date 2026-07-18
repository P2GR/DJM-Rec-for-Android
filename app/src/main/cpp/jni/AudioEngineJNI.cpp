#include <jni.h>
#include <cstring>
#include <string>

#include "../UsbAudioEngine.h"

using djmrec::ContainerFormat;
using djmrec::UsbAudioEngine;

namespace {
std::string jstringToStdString(JNIEnv* env, jstring jStr) {
    if (!jStr) return {};
    const char* chars = env->GetStringUTFChars(jStr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jStr, chars);
    return result;
}
} // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_open(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint audioManagerDeviceId, jint sampleRateHint, jint channelCount, jint bitDepth) {
    return UsbAudioEngine::instance().open(audioManagerDeviceId, sampleRateHint, channelCount, bitDepth);
}

JNIEXPORT jint JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_openUsbIso(
    JNIEnv* env, jobject /*thiz*/,
    jint fd, jint interfaceNumber, jint alternateSetting, jint endpointAddress, jint maxPacketSize,
    jint totalChannels, jint subframeSize, jint bitResolution, jint extractChannelOffset,
    jint clockControlInterfaceNumber, jint clockSourceId, jboolean clockSupportsFrequencySet,
    jint feedbackEndpointAddress, jint feedbackMaxPacketSize, jint vendorId, jint productId,
    jbyteArray rawDescriptors,
    jint sampleRateHint) {
    djmrec::UsbIsoAudioSource::Config config;
    config.fd = fd;
    config.interfaceNumber = interfaceNumber;
    config.alternateSetting = alternateSetting;
    config.endpointAddress = endpointAddress;
    config.maxPacketSize = maxPacketSize;
    config.totalChannels = totalChannels;
    config.subframeSize = subframeSize;
    config.bitResolution = bitResolution;
    config.extractChannelOffset = extractChannelOffset;
    config.clockControlInterfaceNumber = clockControlInterfaceNumber;
    config.clockSourceId = clockSourceId;
    config.clockSupportsFrequencySet = clockSupportsFrequencySet == JNI_TRUE;
    config.requestedSampleRate = sampleRateHint;
    config.feedbackEndpointAddress = feedbackEndpointAddress;
    config.feedbackMaxPacketSize = feedbackMaxPacketSize;
    config.vendorId = vendorId;
    config.productId = productId;
    if (rawDescriptors) {
        const jsize length = env->GetArrayLength(rawDescriptors);
        const auto* bytes = env->GetByteArrayElements(rawDescriptors, nullptr);
        if (bytes && length > 0) {
            const auto* unsignedBytes = reinterpret_cast<const uint8_t*>(bytes);
            config.rawDescriptors.assign(unsignedBytes, unsignedBytes + length);
        }
        if (bytes) {
            env->ReleaseByteArrayElements(rawDescriptors, const_cast<jbyte*>(bytes), JNI_ABORT);
        }
    }
    return UsbAudioEngine::instance().openUsbIso(config, sampleRateHint);
}

JNIEXPORT jint JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_openRootAlsa(
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint card, jint device, jint sampleRate, jint channels, jint bitDepth, jint extractChannelOffset) {
    djmrec::AlsaPcmAudioSource::Config config;
    config.card = card;
    config.device = device;
    config.sampleRate = sampleRate;
    config.channels = channels;
    config.bitDepth = bitDepth;
    config.extractChannelOffset = extractChannelOffset;
    return UsbAudioEngine::instance().openRootAlsa(config);
}

JNIEXPORT jboolean JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_startRecording(
    JNIEnv* env, jobject /*thiz*/,
    jstring outputPath, jint format) {
    const std::string path = jstringToStdString(env, outputPath);
    const auto container = static_cast<ContainerFormat>(format);
    return UsbAudioEngine::instance().startRecording(path, container) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_pauseRecording(JNIEnv* /*env*/, jobject /*thiz*/) {
    UsbAudioEngine::instance().pauseRecording();
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_resumeRecording(JNIEnv* /*env*/, jobject /*thiz*/) {
    UsbAudioEngine::instance().resumeRecording();
}

JNIEXPORT jlong JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_stopRecording(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(UsbAudioEngine::instance().stopRecording());
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_close(JNIEnv* /*env*/, jobject /*thiz*/) {
    UsbAudioEngine::instance().closeEngine();
}

JNIEXPORT jfloatArray JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_getLevels(JNIEnv* env, jobject /*thiz*/) {
    float levels[4];
    UsbAudioEngine::instance().getLevels(levels);

    jfloatArray result = env->NewFloatArray(4);
    env->SetFloatArrayRegion(result, 0, 4, levels);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_isClipping(JNIEnv* /*env*/, jobject /*thiz*/) {
    return UsbAudioEngine::instance().isClipping() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_getElapsedMillis(JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(UsbAudioEngine::instance().getElapsedMillis());
}

JNIEXPORT jint JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_getXRunCount(JNIEnv* /*env*/, jobject /*thiz*/) {
    return UsbAudioEngine::instance().getXRunCount();
}

JNIEXPORT jlongArray JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_getUsbIsoTransferStats(JNIEnv* env, jobject /*thiz*/) {
    uint64_t stats[7]{};
    UsbAudioEngine::instance().getUsbIsoTransferStats(stats);
    jlong values[7]{};
    for (int index = 0; index < 7; ++index) {
        values[index] = static_cast<jlong>(stats[index]);
    }
    jlongArray result = env->NewLongArray(7);
    env->SetLongArrayRegion(result, 0, 7, values);
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_getDiagnosticSummary(JNIEnv* env, jobject /*thiz*/) {
    const std::string summary = UsbAudioEngine::instance().getDiagnosticSummary();
    return env->NewStringUTF(summary.c_str());
}

JNIEXPORT jfloatArray JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_getWaveformBins(JNIEnv* env, jobject /*thiz*/) {
    constexpr int kFloats = UsbAudioEngine::kWaveformBinCount * 4;
    float bins[kFloats];
    UsbAudioEngine::instance().getWaveformBins(bins);

    jfloatArray result = env->NewFloatArray(kFloats);
    env->SetFloatArrayRegion(result, 0, kFloats, bins);
    return result;
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_setWaveformEnabled(
    JNIEnv* /*env*/, jobject /*thiz*/, jboolean enabled) {
    UsbAudioEngine::instance().setWaveformEnabled(enabled == JNI_TRUE);
}
} // extern "C"
