#include <jni.h>
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
    JNIEnv* /*env*/, jobject /*thiz*/,
    jint fd, jint interfaceNumber, jint alternateSetting, jint endpointAddress, jint maxPacketSize,
    jint totalChannels, jint subframeSize, jint bitResolution, jint extractChannelOffset,
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
    return UsbAudioEngine::instance().openUsbIso(config, sampleRateHint);
}

JNIEXPORT jboolean JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_startRecording(
    JNIEnv* env, jobject /*thiz*/,
    jstring outputPath, jint format, jint mp3BitrateKbps) {
    const std::string path = jstringToStdString(env, outputPath);
    const auto container = static_cast<ContainerFormat>(format);
    return UsbAudioEngine::instance().startRecording(path, container, mp3BitrateKbps) ? JNI_TRUE : JNI_FALSE;
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

} // extern "C"
