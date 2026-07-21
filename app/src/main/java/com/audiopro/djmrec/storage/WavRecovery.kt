package com.audiopro.djmrec.storage

import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WavRecovery {
    fun patch(descriptor: ParcelFileDescriptor, fileSize: Long): Boolean {
        val sizes = RecordingOutputManager.wavHeaderSizes(fileSize) ?: return false
        return runCatching {
            FileOutputStream(descriptor.fileDescriptor).channel.use { channel ->
                val value = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                value.putInt(sizes.first).flip()
                channel.position(4)
                while (value.hasRemaining()) channel.write(value)
                value.clear()
                value.putInt(sizes.second).flip()
                channel.position(40)
                while (value.hasRemaining()) channel.write(value)
                channel.force(true)
            }
            true
        }.getOrDefault(false)
    }
}
