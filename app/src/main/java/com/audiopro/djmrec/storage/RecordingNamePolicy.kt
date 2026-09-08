package com.audiopro.djmrec.storage

object RecordingNamePolicy {
    fun displayName(input: String, extension: String): String {
        val name = input.trim().removeSuffix(".$extension").trim()
        require(name.isNotBlank() && name != "." && name != "..") { "Enter a recording name" }
        require(name.length <= 100) { "Use 100 characters or fewer" }
        require(name.none { it.isISOControl() || it in "/\\:*?\"<>|" }) { "Name contains unsupported characters" }
        require(extension in listOf("wav", "flac")) { "Unsupported recording format" }
        return "$name.$extension"
    }
}
