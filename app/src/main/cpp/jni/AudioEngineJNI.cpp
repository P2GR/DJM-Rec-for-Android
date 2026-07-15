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
    jstring outputPath, jint format, jint mp3BitrateKbps) {
    const std::string path = jstringToStdString(env, outputPath);
    const auto container = static_cast<ContainerFormat>(format);
    return UsbAudioEngine::instance().startRecording(path, container, mp3BitrateKbps) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_isMp3EncodingAvailable(JNIEnv* /*env*/, jobject /*thiz*/) {
#if HAVE_LAME
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
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

JNIEXPORT jfloatArray JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_getWaveformBins(JNIEnv* env, jobject /*thiz*/) {
    constexpr int kFloats = UsbAudioEngine::kWaveformBinCount * 4;
    float bins[kFloats];
    UsbAudioEngine::instance().getWaveformBins(bins);

    jfloatArray result = env->NewFloatArray(kFloats);
    env->SetFloatArrayRegion(result, 0, kFloats, bins);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_startMicCapture(JNIEnv* /*env*/, jobject /*thiz*/) {
    return UsbAudioEngine::instance().startMicCapture();
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_stopMicCapture(JNIEnv* /*env*/, jobject /*thiz*/) {
    UsbAudioEngine::instance().stopMicCapture();
}

JNIEXPORT jfloatArray JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_getBpmResult(JNIEnv* env, jobject /*thiz*/) {
    float bpm = 0.0f;
    float confidence = 0.0f;
    float beatPhase = 0.0f;
    int leadingBand = 0;
    bool locked = UsbAudioEngine::instance().getBpmResult(bpm, confidence, beatPhase, leadingBand);

    jfloatArray result = env->NewFloatArray(5);
    float values[5] = { bpm, confidence, beatPhase, static_cast<float>(leadingBand), locked ? 1.0f : 0.0f };
    env->SetFloatArrayRegion(result, 0, 5, values);
    return result;
}

// --- RMX-1000 JNI ----------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_openRmxOutput(
    JNIEnv* /*env*/, jobject /*thiz*/, jint deviceId, jint sampleRate, jint channelCount) {
    return UsbAudioEngine::instance().openRmxOutput(deviceId, sampleRate, channelCount);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_closeRmxOutput(JNIEnv* /*env*/, jobject /*thiz*/) {
    UsbAudioEngine::instance().closeRmxOutput();
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_triggerRmxSample(
    JNIEnv* /*env*/, jobject /*thiz*/, jint soundOrdinal, jfloat gain, jfloat pitchRatio) {
    UsbAudioEngine::instance().triggerRmxSample(soundOrdinal, gain, pitchRatio);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_triggerRmxSampleLooping(
    JNIEnv* /*env*/, jobject /*thiz*/, jint soundOrdinal, jfloat gain, jfloat pitchRatio, jint loopLengthSamples) {
    UsbAudioEngine::instance().triggerRmxSampleLooping(soundOrdinal, gain, pitchRatio, loopLengthSamples);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_updateRmxVoiceLoop(
    JNIEnv* /*env*/, jobject /*thiz*/, jint soundOrdinal, jint loopLengthSamples) {
    UsbAudioEngine::instance().updateRmxVoiceLoop(soundOrdinal, loopLengthSamples);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_updateRmxVoicePitch(
    JNIEnv* /*env*/, jobject /*thiz*/, jint soundOrdinal, jfloat pitchRatio) {
    UsbAudioEngine::instance().updateRmxVoicePitch(soundOrdinal, pitchRatio);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_stopRmxSample(JNIEnv* /*env*/, jobject /*thiz*/, jint soundOrdinal) {
    UsbAudioEngine::instance().stopRmxSample(soundOrdinal);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_stopAllRmxSamples(JNIEnv* /*env*/, jobject /*thiz*/) {
    UsbAudioEngine::instance().stopAllRmxSamples();
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_setRmxEffectParam(
    JNIEnv* /*env*/, jobject /*thiz*/, jint effectId, jfloat value) {
    UsbAudioEngine::instance().setRmxEffectParam(effectId, value);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_loadRmxSample(
    JNIEnv* env, jobject /*thiz*/, jint soundOrdinal, jfloatArray jData) {
    jsize len = env->GetArrayLength(jData);
    std::vector<float> buf(len);
    env->GetFloatArrayRegion(jData, 0, len, buf.data());
    UsbAudioEngine::instance().loadRmxSample(soundOrdinal, buf.data(), len);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_updateRmxBeatClock(
    JNIEnv* /*env*/, jobject /*thiz*/, jfloat bpm, jfloat beatPhase, jboolean locked) {
    UsbAudioEngine::instance().updateRmxBeatClock(bpm, beatPhase, locked);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_setRmxManualBpm(JNIEnv* /*env*/, jobject /*thiz*/, jfloat bpm) {
    UsbAudioEngine::instance().setRmxManualBpm(bpm);
}

JNIEXPORT void JNICALL
Java_com_audiopro_djmrec_audio_AudioEngine_clearRmxManualBpm(JNIEnv* /*env*/, jobject /*thiz*/) {
    UsbAudioEngine::instance().clearRmxManualBpm();
}

} // extern "C"
