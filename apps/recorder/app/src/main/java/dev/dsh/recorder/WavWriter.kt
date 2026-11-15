package dev.dsh.recorder

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 流式写 WAV（16bit 单声道 PCM），结束时回填 header。 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int = 1,
) {
    private val out = FileOutputStream(file)
    private var dataBytes: Long = 0

    init {
        out.write(header(0, sampleRate, channels))
    }

    fun write(frames: ShortArray, count: Int) {
        if (count <= 0) return
        val bytes = ByteArray(count * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putShort(frames[i])
        out.write(bytes)
        dataBytes += count * 2L
    }

    /** 返回时长毫秒。 */
    fun closeAndPatch(): Long {
        out.flush()
        out.close()
        val durationMs = if (sampleRate > 0) {
            dataBytes * 1000L / (sampleRate.toLong() * 2L * channels)
        } else 0L
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header(dataBytes, sampleRate, channels))
        }
        return durationMs
    }

    fun abort() {
        runCatching { out.close() }
        runCatching { file.delete() }
    }

    private fun header(dataLen: Long, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val total = 36 + dataLen
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(total.toInt())
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray())
        buffer.putInt(dataLen.toInt())
        return buffer.array()
    }
}
