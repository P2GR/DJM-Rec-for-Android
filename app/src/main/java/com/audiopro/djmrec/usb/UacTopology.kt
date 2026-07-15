package com.audiopro.djmrec.usb

data class UacTopology(
    val audioControlInterface: AudioControlInterface?,
    val audioControlInterfaces: List<AudioControlInterface>,
    val audioStreamingInterfaces: List<AudioStreamingInterfaceInfo>,
    val clockSources: List<ClockSourceInfo>,
    val clockSelectors: List<ClockSelectorInfo>,
    val featureUnits: List<FeatureUnitInfo>,
    val mixerUnits: List<MixerUnitInfo>,
    val selectorUnits: List<SelectorUnitInfo>,
    val inputTerminals: List<TerminalInfo>,
    val outputTerminals: List<TerminalInfo>,
    val descriptorSampleRates: List<Int>
)

data class AudioControlInterface(
    val interfaceNumber: Int,
    val totalLength: Int,
    val audioClassVersion: Int
)

data class ClockSourceInfo(
    val id: Int,
    val interfaceNumber: Int,
    val attributes: Int,
    val controls: Int,
    val associatedTerminalId: Int,
    val nameStringIndex: Int,
    val supportsFrequencyControl: Boolean,
    val supportsFrequencySet: Boolean
)

data class ClockSelectorInfo(
    val id: Int,
    val sourceIds: List<Int>,
    val controls: Int,
    val nameStringIndex: Int
)

data class FeatureUnitInfo(
    val id: Int,
    val sourceId: Int,
    val controls: List<Int>,
    val nameStringIndex: Int
)

data class MixerUnitInfo(
    val id: Int,
    val sourceIds: List<Int>,
    val outputChannelCount: Int,
    val controls: List<Int>,
    val nameStringIndex: Int
)

data class SelectorUnitInfo(
    val id: Int,
    val sourceIds: List<Int>,
    val controls: Int,
    val nameStringIndex: Int
)

data class TerminalInfo(
    val id: Int,
    val terminalType: Int,
    val sourceId: Int,
    val clockSourceId: Int,
    val channelCount: Int,
    val channelConfig: Long,
    val nameStringIndex: Int
)
