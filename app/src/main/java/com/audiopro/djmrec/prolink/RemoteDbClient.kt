package com.audiopro.djmrec.prolink

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** Short-lived, serialized, read-only metadata queries. No media download or playback commands. */
class RemoteDbClient(private val connect: (String, Int) -> Socket) {
    fun query(address: String, ourNumber: Int, key: TrackKey): TrackMetadata? {
        require(ourNumber in 1..4 && key.slot in 1..4 && key.type in setOf(1, 2, 5))
        val port = connect(address, 12523).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            out.writeInt(15); out.write("RemoteDBServer\u0000".toByteArray(Charsets.US_ASCII)); out.flush()
            DataInputStream(socket.getInputStream()).readUnsignedShort()
        }
        require(port in 1..65535) { "Player has no metadata server" }
        return connect(address, port).use { socket ->
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(0x11, 0, 0, 0, 1)); output.flush()
            require(DbWire.number(input) == 1L) { "Invalid metadata greeting" }
            DbWire.write(output, 0xfffffffeL, 0, listOf(ourNumber.toLong()))
            val setup = DbWire.read(input, 0xfffffffeL)
            require(setup.type == 0x4000 && setup.args.firstOrNull() == 0L && setup.args.getOrNull(1) == key.sourcePlayer.toLong())
            val dmst = (ourNumber.toLong() shl 24) or (1L shl 16) or (key.slot.toLong() shl 8) or key.type.toLong()
            val request = if (key.type == 1) 0x2002 else 0x2202
            DbWire.write(output, 1, request, listOf(dmst, key.id))
            val available = DbWire.read(input, 1)
            require(available.type == 0x4000 && available.args.firstOrNull() == request.toLong())
            val count = (available.args.getOrNull(1) as? Long) ?: return null
            if (count == 0xffffffffL || count == 0L) return null
            require(count in 1..64) { "Unexpected metadata item count" }
            DbWire.write(output, 2, 0x3000, listOf(dmst, 0, count, 0, count, 0))
            require(DbWire.read(input, 2).type == 0x4001) { "Missing metadata header" }
            var title = ""
            var artist = ""
            var duration: Long? = null
            repeat(count.toInt()) {
                val item = DbWire.read(input, 2)
                require(item.type == 0x4101 && item.args.size == 12) { "Invalid metadata item" }
                when ((item.args[6] as? Long)?.and(0xffff)) {
                    4L -> title = item.args[3] as? String ?: ""
                    7L -> artist = item.args[3] as? String ?: ""
                    11L -> duration = item.args[1] as? Long
                }
            }
            require(DbWire.read(input, 2).type == 0x4201) { "Missing metadata footer" }
            title.takeIf { it.isNotBlank() }?.let { TrackMetadata(clean(it), clean(artist), duration) }
        }
    }

    private fun clean(value: String) = value.filterNot(Char::isISOControl).trim().take(512)
}

/** Bounded framing, also usable in JVM fixture tests without Android or sockets. */
internal object DbWire {
    data class Message(val type: Int, val args: List<Any>)
    private fun number(out: DataOutputStream, value: Long, bytes: Int = 4) {
        out.writeByte(when (bytes) { 1 -> 0x0f; 2 -> 0x10; else -> 0x11 })
        for (i in bytes - 1 downTo 0) out.writeByte((value shr (8 * i)).toInt())
    }
    fun number(input: InputStream): Long {
        val data = DataInputStream(input)
        return when (data.readUnsignedByte()) {
            0x0f -> data.readUnsignedByte().toLong()
            0x10 -> data.readUnsignedShort().toLong()
            0x11 -> data.readInt().toLong() and 0xffffffffL
            else -> error("Expected metadata number")
        }
    }
    fun write(output: OutputStream, transaction: Long, type: Int, args: List<Long>) {
        require(args.size <= 12)
        val out = DataOutputStream(output)
        number(out, 0x872349aeL); number(out, transaction); number(out, type.toLong(), 2)
        number(out, args.size.toLong(), 1)
        out.writeByte(0x14); out.writeInt(12)
        out.write(ByteArray(12) { if (it < args.size) 6 else 0 })
        args.forEach { number(out, it) }
        out.flush()
    }
    fun read(input: InputStream, transaction: Long): Message {
        val data = DataInputStream(input)
        require(number(data) == 0x872349aeL && number(data) == transaction) { "Metadata transaction mismatch" }
        val type = number(data).toInt()
        val count = number(data).toInt()
        require(count in 0..12 && data.readUnsignedByte() == 0x14 && data.readInt() == 12)
        val tags = ByteArray(12).also(data::readFully)
        val args = List(count) { index ->
            when (tags[index].toInt()) {
                6 -> number(data)
                2 -> {
                    require(data.readUnsignedByte() == 0x26)
                    val length = data.readInt()
                    require(length in 1..4096) { "Metadata string too large" }
                    val bytes = ByteArray(length * 2).also(data::readFully)
                    require(bytes[bytes.lastIndex] == 0.toByte() && bytes[bytes.lastIndex - 1] == 0.toByte())
                    String(bytes, Charsets.UTF_16BE).trimEnd('\u0000')
                }
                else -> error("Unexpected metadata field type")
            }
        }
        return Message(type, args)
    }
}
