package dev.dsh.recorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 录音引擎：AudioRecord 直录 WAV + 能量 VAD 自动断句。
 * - 检测到开口后开始计时；
 * - 说完后静音超过 silenceMs → 自动结束本句；
 * - 没开口时会一直等，不自动切走。
 */
class Recorder(
    private val context: Context,
    private val sampleRate: Int,
    private val deviceId: Int,
    private val silenceMs: Int,
    private val minSpeechMs: Int,
    private val listener: Listener,
) {

    interface Listener {
        fun onLevel(level: Float, speaking: Boolean)
        fun onStarted()
        fun onAutoStopped(result: Result)
        fun onError(message: String)
        fun onVoiceProgress(voicedMs: Long, speaking: Boolean) {}
        /** 原始 PCM16 分片（小端），用于把音频实时送给电脑做识别。 */
        fun onPcm(pcm: ByteArray, totalMs: Long) {}
    }

    data class Result(
        val file: File,
        val durationMs: Long,
        val voicedMs: Long,
        val manual: Boolean = false,
        /** 实际发声区间，相对录音开始的毫秒。用于逐字时间轴估算。 */
        val voicedSegments: List<Pair<Long, Long>> = emptyList(),
    )

    private val main = Handler(Looper.getMainLooper())
    private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var manualStop = false
    @Volatile private var cancelFlag = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun start(output: File) {
        if (running) return
        running = true
        manualStop = false
        cancelFlag = false
        thread = Thread({ loop(output) }, "recorder").apply { start() }
    }

    /** 手动结束：保存当前录音。 */
    fun stopAndSave() {
        manualStop = true
    }

    /** 放弃当前录音（不保存）。 */
    fun cancel() {
        cancelFlag = true
        manualStop = true
    }

    private fun loop(output: File) {
        var wav: WavWriter? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufSize = max(minBuf * 2, sampleRate / 5 * 2)
            @Suppress("DEPRECATION")
            val arec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
            if (arec.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("麦克风初始化失败（可能被其他 App 占用）")
            }
            if (deviceId >= 0) {
                AudioDevices.find(context, deviceId)?.let { arec.preferredDevice = it }
            }
            record = arec
            wav = WavWriter(output, sampleRate)
            arec.startRecording()
            main.post { listener.onStarted() }

            val frameSamples = max(320, sampleRate / 50) // 20ms
            val buffer = ShortArray(frameSamples)
            var noiseFloor = -60.0
            var speaking = false
            var voicedMs = 0L
            var silenceRunMs = 0L
            var speakingElapsedMs = 0L
            var voicedSinceStart = 0L
            var consecutiveSpeech = 0
            var totalMs = 0L
            var lastLevelPost = 0L
            val segments = ArrayList<Pair<Long, Long>>()
            var segStart = -1L

            while (running) {
                if (manualStop) break
                val read = arec.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                var sum = 0.0
                for (i in 0 until read) {
                    val v = buffer[i].toDouble()
                    sum += v * v
                }
                val rms = sqrt(sum / read)
                val db = if (rms > 0.0) 20.0 * log10(rms / 32767.0) else -90.0
                val frameMs = read * 1000L / sampleRate
                totalMs += frameMs

                // 自适应噪声底：没在说话时缓慢跟踪
                if (!speaking) {
                    noiseFloor = noiseFloor * 0.95 + db * 0.05
                }
                val threshold = max(noiseFloor + 9.0, -46.0)
                val voiced = db > threshold
                if (voiced) {
                    consecutiveSpeech++
                } else {
                    consecutiveSpeech = 0
                }
                if (!speaking && consecutiveSpeech >= 3) {
                    speaking = true
                    silenceRunMs = 0
                    speakingElapsedMs = 0
                }
                if (speaking) {
                    voicedSinceStart += frameMs
                    if (voiced) {
                        voicedMs += frameMs
                        silenceRunMs = 0
                        if (segStart < 0) segStart = totalMs - frameMs
                    } else {
                        silenceRunMs += frameMs
                        if (segStart >= 0) {
                            segments.add(segStart to totalMs)
                            segStart = -1L
                        }
                    }
                    if (silenceRunMs >= silenceMs && voicedSinceStart >= minSpeechMs) {
                        if (segStart >= 0) {
                            segments.add(segStart to totalMs)
                            segStart = -1L
                        }
                        main.post { listener.onLevel(0f, false) }
                        break // 自动断句
                    }
                    if (voicedSinceStart > 60_000L) break // 单句最长 60 秒
                } else {
                    voicedSinceStart = 0
                    // 一直不开口：等 15 秒后提示一次（不自动结束）
                    if (totalMs in 15_000L..15_100L) {
                        main.post { listener.onLevel(0f, false) }
                    }
                }

                wav.write(buffer, read)
                // 仅当录音真正开始（VAD 检测到开口）后才送识别，避免把等待期的静音当内容
                if (speaking) {
                    val pcm = ByteArray(read * 2)
                    for (i in 0 until read) {
                        val v = buffer[i].toInt()
                        pcm[i * 2] = (v and 0xFF).toByte()
                        pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    val t = totalMs
                    main.post { listener.onPcm(pcm, t) }
                }
                val now = System.currentTimeMillis()
                if (now - lastLevelPost >= 40) {
                    lastLevelPost = now
                    val level = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
                    val sp = speaking
                    val voicedNow = voicedMs
                    main.post {
                        listener.onLevel(level, sp)
                        listener.onVoiceProgress(voicedNow, sp)
                    }
                }
            }

            arec.stop()
            arec.release()
            record = null
            if (cancelFlag) {
                wav.abort()
                return
            }
            val duration = wav.closeAndPatch()
            if (segStart >= 0) {
                segments.add(segStart to totalMs)
                segStart = -1L
            }
            val result = Result(output, duration, voicedMs, manualStop && !cancelFlag, segments)
            main.post { listener.onAutoStopped(result) }
        } catch (t: Throwable) {
            runCatching { record?.release() }
            record = null
            runCatching { wav?.abort() }
            main.post { listener.onError(t.message ?: t.javaClass.simpleName) }
        } finally {
            running = false
        }
    }
}
