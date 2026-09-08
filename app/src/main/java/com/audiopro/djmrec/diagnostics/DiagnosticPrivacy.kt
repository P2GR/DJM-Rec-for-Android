package com.audiopro.djmrec.diagnostics

/** Remote diagnostics never need audio payloads, file names or authentication material. */
internal object DiagnosticPrivacy {
    private val urls = Regex("(?i)(?:https?|rtmps?|content|file)://[^\\s]+")
    private val bearer = Regex("(?i)\\bbearer\\s+[^\\s,;]+")
    private val secrets = Regex("(?i)(authorization|bearer|access_token|refresh_token|stream_key|password|token|serial)(\\s*[:=]\\s*|\\s+)[^\\s,;]+")
    private val paths = Regex("(?:/storage/|/sdcard/|/data/)[^\\r\\n]+")
    fun redact(message: String): String = paths.replace(secrets.replace(bearer.replace(urls.replace(message, "[URL omitted]"), "Bearer [redacted]")) {
        "${it.groupValues[1]}=[redacted]"
    }, "[private path omitted]").take(12_000)

    fun allowLogcat(tag: String, message: String): Boolean = tag in setOf(
        "UsbAudioManager", "UsbAudioEngine", "UsbIsoAudioSource", "AlsaPcmAudioSource",
        "RecordingService", "WavWriter", "FlacWriter"
    ) && !message.contains("raw iso packet", ignoreCase = true) &&
        !message.contains("kernel", ignoreCase = true) && !message.contains("dmesg", ignoreCase = true) &&
        !message.contains("root persistent host", ignoreCase = true)

    /** Configuration descriptors only. USB string descriptors may contain serial numbers. */
    fun descriptorHex(raw: ByteArray): String {
        val out = StringBuilder()
        var offset = 0
        while (offset + 1 < raw.size && offset < 16_384) {
            val length = raw[offset].toInt() and 255
            if (length < 2 || offset + length > raw.size) { out.append(" [truncated]"); break }
            if ((raw[offset + 1].toInt() and 255) != 3) {
                for (i in offset until offset + length) out.append("%02x".format(raw[i].toInt() and 255))
                out.append(' ')
            }
            offset += length
        }
        return out.toString()
    }
}
