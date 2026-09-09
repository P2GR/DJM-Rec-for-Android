package com.audiopro.djmrec.prolink

import java.io.InputStream
import java.io.OutputStream

/** Optional observation at the transport boundary. TCP events are stream chunks, not messages. */
fun interface WireObserver {
    fun packet(transport: String, direction: String, peer: String, port: Int, bytes: ByteArray)
    companion object { val NONE = WireObserver { _, _, _, _, _ -> } }
}

internal fun InputStream.observed(observer: WireObserver, peer: String, port: Int): InputStream {
    if (observer === WireObserver.NONE) return this
    val source = this
    return object : InputStream() {
        override fun read(): Int = source.read().also {
            if (it >= 0) observer.packet("tcp", "in", peer, port, byteArrayOf(it.toByte()))
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, len).also {
            if (it > 0) observer.packet("tcp", "in", peer, port, b.copyOfRange(off, off + it))
        }
        override fun close() = source.close()
    }
}

internal fun OutputStream.observed(observer: WireObserver, peer: String, port: Int): OutputStream {
    if (observer === WireObserver.NONE) return this
    val target = this
    return object : OutputStream() {
        override fun write(b: Int) {
            target.write(b)
            observer.packet("tcp", "out", peer, port, byteArrayOf(b.toByte()))
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            observer.packet("tcp", "out", peer, port, b.copyOfRange(off, off + len))
        }
        override fun flush() = target.flush()
        override fun close() = target.close()
    }
}
