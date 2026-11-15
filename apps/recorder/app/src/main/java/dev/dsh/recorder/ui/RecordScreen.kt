package dev.dsh.recorder.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.recorder.AsrChar
import dev.dsh.recorder.AsrClient
import dev.dsh.recorder.AsrState
import dev.dsh.recorder.AudioDevices
import dev.dsh.recorder.CharTime
import dev.dsh.recorder.Prefs
import dev.dsh.recorder.Project
import dev.dsh.recorder.ProjectStore
import dev.dsh.recorder.Recorder
import dev.dsh.recorder.Uploader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 逐句录制页：自动断句 + 自动下一句（无缝连录）。 */
@Composable
fun RecordScreen(
    project: Project,
    startIndex: Int,
    prefs: Prefs,
    onSaved: (sentenceId: String, file: File, durationMs: Long, sampleRate: Int, deviceName: String, chars: List<CharTime>, rate: Float) -> Unit,
    onClearTake: (sentenceId: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 55) }
    val engineRef = remember { mutableStateOf<Recorder?>(null) }
    val sentences = project.sentences

    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))) }
    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var speaking by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var chainStopped by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf("准备开始…") }
    var voicedMs by remember { mutableLongStateOf(0L) }
    // 语速估计（字/秒），每录完一句用实际发声时长校准
    var charsPerSec by remember { mutableFloatStateOf(4.2f) }

    // 实时识别（电脑 ASR）
    var asrActive by remember { mutableStateOf(false) }
    var asrState by remember { mutableStateOf<AsrState?>(null) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val asrExec = remember { Executors.newSingleThreadExecutor() }
    val asrClientRef = remember { mutableStateOf<AsrClient?>(null) }
    val asrPending = remember { ConcurrentLinkedQueue<ByteArray>() }
    val asrPcmBuf = remember { java.io.ByteArrayOutputStream() }
    val asrSeq = remember { AtomicInteger(0) }

    val deviceLabel = remember(prefs.deviceId) {
        AudioDevices.find(context, prefs.deviceId)?.let {
            "${it.productName ?: AudioDevices.typeLabel(it.type)}（${AudioDevices.typeLabel(it.type)}）"
        } ?: "系统默认麦克风"
    }

    DisposableEffect(Unit) {
        onDispose {
            engineRef.value?.cancel()
            engineRef.value = null
            asrClientRef.value?.let { client ->
                runCatching { asrExec.execute { client.end() } }
            }
            asrExec.shutdownNow()
            tone.release()
        }
    }

    fun currentSentence() = sentences.getOrNull(index)

    // 允许 saveAndAdvance/finishAsrTake 回调触发录音（Kotlin 局部函数不能前向引用）
    var startAtRef: ((Int, Boolean) -> Unit)? = null

    /** 把电脑返回的逐字状态映射成可保存/可切分的时间轴（补齐标点）。 */
    fun toCharTimes(text: String, list: List<AsrChar>): List<CharTime> {
        if (text.isEmpty()) return emptyList()
        val byIndex = list.filter { it.index in text.indices }.associateBy { it.index }
        val out = ArrayList<CharTime>(text.length)
        var lastEnd = 0L
        for (i in text.indices) {
            val a = byIndex[i]
            if (a != null) {
                out.add(CharTime(text[i].toString(), a.startMs, a.endMs, wrong = a.status == 2))
                lastEnd = a.endMs
            } else {
                out.add(CharTime(text[i].toString(), lastEnd, lastEnd))
            }
        }
        return out
    }

    /** 统一的保存/自动下一句逻辑（估算模式与识别模式共用）。 */
    fun saveAndAdvance(result: Recorder.Result, chars: List<CharTime>, rate: Float) {
        recording = false
        engineRef.value = null
        currentSentence()?.let { sentence ->
            onSaved(
                sentence.id,
                result.file,
                result.durationMs,
                prefs.sampleRate,
                deviceLabel,
                chars,
                rate,
            )
        }
        if (prefs.beep) runCatching { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 100) }
        val next = index + 1
        if (!chainStopped && prefs.autoContinue && next < sentences.size) {
            hint = "已保存 · 下一句准备中…"
            scope.launch {
                delay(420)
                if (prefs.beep) runCatching { tone.startTone(ToneGenerator.TONE_PROP_ACK, 90) }
                startAtRef?.invoke(next, true)
            }
        } else {
            hint = if (next >= sentences.size) "这个项目录完了 🎉" else "已保存"
        }
    }

    /** 识别模式收尾：拿到服务器最终对齐结果，再决定保存/重录。 */
    fun finishAsrTake(result: Recorder.Result) {
        val client = asrClientRef.value
        val snapshot = asrState
        asrActive = false
        asrClientRef.value = null
        scope.launch {
            hint = "识别收尾中…"
            val final = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { client?.end() }.getOrNull() ?: snapshot
            }
            val sentence = currentSentence()
            val text = sentence?.text.orEmpty()
            val speakable = text.count { !it.isWhitespace() }
            // 识别失败（电脑掉线等）→ 退回估算时间轴，保证录音不丢
            if (final == null) {
                val timeline = estimateTimeline(text, charsPerSec, result.voicedSegments)
                saveAndAdvance(result, timeline, charsPerSec)
                return@launch
            }
            val total = final.total
            val matched = final.matched
            // 双闸门：①识别率不够 ②实际发声时长明显短于脚本所需时长（防 Whisper 脑补）
            val expectedMs = if (charsPerSec > 0.5f) speakable / charsPerSec * 1000f else 0f
            val durationOk = expectedMs <= 0f || result.voicedMs >= expectedMs * 0.5f
            // 有口音时识别率天然偏低：宽松模式主要靠“实际发声时长”兜底，不因识别率误杀
            val minRatio = when (prefs.asrStrict) {
                "strict" -> 0.90f
                "normal" -> 0.80f
                else -> 0.55f
            }
            val matchedOk = total < 4 || matched >= total * minRatio
            if (!result.manual && (!matchedOk || !durationOk)) {
                runCatching { result.file.delete() }
                hint = buildString {
                    append("没读完/识别没确认完（识别 $matched/$total 字")
                    append("、发声 ${"%.1f".format(result.voicedMs / 1000f)}s）")
                    append("，重录这句…")
                }
                if (prefs.beep) runCatching { tone.startTone(ToneGenerator.TONE_PROP_NACK, 180) }
                if (!chainStopped) {
                    delay(800)
                    startAtRef?.invoke(index, true)
                }
                return@launch
            }
            val chars = toCharTimes(text, final.chars)
            val measured = if (result.voicedMs > 500 && speakable > 0) {
                (speakable / (result.voicedMs / 1000f)).coerceIn(2f, 8f)
            } else {
                charsPerSec
            }
            charsPerSec = charsPerSec * 0.7f + measured * 0.3f
            saveAndAdvance(result, chars, measured)
        }
    }

    fun startAt(target: Int, chain: Boolean) {
        val sentence = sentences.getOrNull(target) ?: return
        index = target
        chainStopped = !chain
        error = null
        val dir = ProjectStore.projectDir(context, project.id)
        val out = File(dir, ProjectStore.wavName(target, sentence.text))
        if (out.exists()) out.delete()
        recording = true
        startedAt = System.currentTimeMillis()
        elapsedMs = 0
        level = 0f
        speaking = false
        voicedMs = 0L
        val effectiveSilenceMs = if (prefs.asrEnabled) maxOf(prefs.silenceMs, 1500) else prefs.silenceMs
        hint = "等待开口…（说完停顿 ${(effectiveSilenceMs / 1000.0)} 秒自动保存）"
        asrActive = false
        asrState = null
        asrClientRef.value = null
        asrPending.clear()
        asrSeq.set(0)
        if (prefs.asrEnabled) {
            hint = "等待开口…（正在连接电脑识别）"
            asrExec.execute {
                val candidates = listOf(prefs.base, prefs.altBase)
                    .mapNotNull { Uploader.normalizeBase(it) }
                    .distinct()
                var working: String? = null
                for (candidate in candidates) {
                    if (AsrClient.probe(candidate, 90_000) != null) {
                        working = candidate
                        break
                    }
                }
                if (working == null) {
                    mainHandler.post {
                        if (engineRef.value != null) hint = "电脑识别未连接，改用估算模式"
                    }
                } else {
                    val client = AsrClient(working)
                    val started = runCatching { client.start(sentence.text, prefs.sampleRate, prefs.asrModel, prefs.asrStrict) }.getOrDefault(false)
                    if (!started) {
                        mainHandler.post {
                            if (engineRef.value != null) hint = "电脑识别启动失败，改用估算模式"
                        }
                    } else {
                        asrClientRef.value = client
                        mainHandler.post {
                            if (engineRef.value != null) {
                                asrActive = true
                                hint = "实时识别中…（说完停顿自动保存）"
                            }
                        }
                        while (!asrPending.isEmpty()) {
                            val chunk = asrPending.poll() ?: break
                            val seq = asrSeq.incrementAndGet()
                            runCatching { client.sendChunk(chunk, seq) }.getOrNull()?.let { st ->
                                mainHandler.post { asrState = st }
                            }
                        }
                    }
                }
            }
        }
        val listener = object : Recorder.Listener {
            override fun onLevel(levelNow: Float, speakingNow: Boolean) {
                level = levelNow
                if (speakingNow && !speaking) hint = "正在录…"
                speaking = speakingNow
                if (recording) elapsedMs = System.currentTimeMillis() - startedAt
            }

            override fun onStarted() {
                hint = "等待开口…（说完停顿 ${(effectiveSilenceMs / 1000.0)} 秒自动保存）"
            }

            override fun onVoiceProgress(voicedNow: Long, speakingNow: Boolean) {
                voicedMs = voicedNow
            }

            override fun onPcm(pcm: ByteArray, totalNow: Long) {
                if (!prefs.asrEnabled) return
                val audioChunk: ByteArray
                synchronized(asrPcmBuf) {
                    asrPcmBuf.write(pcm)
                    if (asrPcmBuf.size() < prefs.sampleRate) return
                    audioChunk = asrPcmBuf.toByteArray()
                    asrPcmBuf.reset()
                }
                val client = asrClientRef.value
                if (!asrActive || client == null) {
                    if (asrPending.size < 400) asrPending.add(audioChunk)
                    return
                }
                val seq = asrSeq.incrementAndGet()
                asrExec.execute {
                    val st = runCatching { client.sendChunk(audioChunk, seq) }.getOrNull()
                    if (st != null) mainHandler.post { asrState = st }
                }
            }

            override fun onAutoStopped(result: Recorder.Result) {
                recording = false
                engineRef.value = null
                // 自动断句时几乎没声音 → 不算一句，重新等待；手动结束则只要录到一点就保存
                if (!result.manual && result.voicedMs < prefs.minSpeechMs) {
                    runCatching { result.file.delete() }
                    if (!chainStopped) {
                        hint = "没听到声音，重新等待…"
                        scope.launch {
                            delay(350)
                            startAt(index, true)
                        }
                    } else {
                        hint = "已停止"
                    }
                    return
                }
                // 识别模式：先拿服务器最终结果，再按匹配率决定保存/重录
                if (asrActive && asrClientRef.value != null) {
                    finishAsrTake(result)
                    return
                }
                // 按“发声时长 × 语速”估算念到第几个字；明显没念完就不保存、重录本句
                val sentenceNow = currentSentence()
                val totalChars = sentenceNow?.text?.count { !it.isWhitespace() } ?: 0
                val spokenChars = (result.voicedMs / 1000f * charsPerSec).toInt()
                    .coerceIn(0, totalChars)
                val coverage = if (totalChars > 0) spokenChars.toFloat() / totalChars else 1f
                if (!result.manual && totalChars >= 4 && coverage < 0.6f) {
                    runCatching { result.file.delete() }
                    hint = "好像没念完（约 $spokenChars/$totalChars 字），重录这句…"
                    if (prefs.beep) runCatching {
                        tone.startTone(ToneGenerator.TONE_PROP_NACK, 180)
                    }
                    if (!chainStopped) {
                        scope.launch {
                            delay(700)
                            startAt(index, true)
                        }
                    }
                    return
                }
                if (result.voicedMs > 600 && totalChars > 0) {
                    val measured = (totalChars / (result.voicedMs / 1000f)).coerceIn(2f, 8f)
                    charsPerSec = charsPerSec * 0.7f + measured * 0.3f
                }
                val sentenceText = currentSentence()?.text.orEmpty()
                val timeline = estimateTimeline(
                    text = sentenceText,
                    rate = charsPerSec,
                    segments = result.voicedSegments,
                )
                saveAndAdvance(result, timeline, charsPerSec)
            }

            override fun onError(message: String) {
                recording = false
                engineRef.value = null
                error = message
                hint = "录音出错"
            }
        }
        val recorder = Recorder(
            context = context,
            sampleRate = prefs.sampleRate,
            deviceId = prefs.deviceId,
            silenceMs = prefs.silenceMs,
            minSpeechMs = prefs.minSpeechMs,
            listener = listener,
        )
        engineRef.value = recorder
        recorder.start(out)
    }

    startAtRef = { target, chain -> startAt(target, chain) }

    fun stopAndSave() {
        chainStopped = true
        engineRef.value?.stopAndSave()
    }

    fun skipCurrent() {
        chainStopped = true
        engineRef.value?.cancel()
        engineRef.value = null
        recording = false
        val next = index + 1
        if (next < sentences.size) {
            hint = "已跳过"
            scope.launch { delay(200); startAt(next, false) }
        } else {
            hint = "已经是最后一句了"
        }
    }

    fun retakeCurrent() {
        chainStopped = false
        engineRef.value?.cancel()
        engineRef.value = null
        recording = false
        currentSentence()?.let { onClearTake(it.id) }
        scope.launch { delay(200); startAt(index, true) }
    }

    LaunchedEffect(Unit) {
        val sentence = sentences.getOrNull(index)
        if (sentence != null && sentence.recorded) {
            onClearTake(sentence.id)
        }
        startAt(index, true)
    }

    // 计时刷新（录音中）
    LaunchedEffect(recording) {
        while (recording) {
            elapsedMs = System.currentTimeMillis() - startedAt
            delay(100)
        }
    }

    val total = sentences.size
    val currentText = currentSentence()?.text.orEmpty()

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF11202F), Ink))),
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon("←") {
                engineRef.value?.cancel()
                engineRef.value = null
                onBack()
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    color = TextMain,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "第 ${index + 1} / $total 句 · 已录 ${project.recordedCount}",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
            Text(
                deviceLabel,
                color = Color(0xFF6F8299),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(110.dp),
                textAlign = TextAlign.End,
            )
        }

        // 提词器
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val prev = sentences.getOrNull(index - 1)?.text
            val next = sentences.getOrNull(index + 1)?.text
            if (prev != null) {
                Text(
                    prev,
                    color = Color(0xFF4E6179),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
            }
            val useAsr = asrActive && asrState != null
            val asrCharList = asrState?.chars.orEmpty()
            val showWrong = prefs.asrStrict != "loose"
            val spokenChars = (voicedMs / 1000f * charsPerSec).toInt()
                .coerceIn(0, currentText.length)
            val annotated = if (useAsr) {
                val byIndex = asrCharList.filter { it.index in currentText.indices }.associateBy { it.index }
                buildAnnotatedString {
                    for (i in currentText.indices) {
                        when (byIndex[i]?.status) {
                            1 -> withStyle(SpanStyle(color = AccentSoft)) { append(currentText[i]) }
                            2 -> if (showWrong) withStyle(
                                SpanStyle(
                                    color = Color(0xFFFF8A8A),
                                    background = Color(0x33FF6B6B),
                                ),
                            ) { append(currentText[i]) } else append(currentText[i])
                            else -> append(currentText[i])
                        }
                    }
                }
            } else {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = AccentSoft)) {
                        append(currentText.take(spokenChars))
                    }
                    append(currentText.drop(spokenChars))
                }
            }
            Text(
                annotated,
                color = TextMain,
                fontSize = 25.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
            val progressText = when {
                useAsr -> {
                    val st = asrState!!
                    buildString {
                        append("🎧 识别 ${st.matched}/${st.total} 字")
                        if (st.transcript.isNotBlank()) append(" · ${st.transcript.takeLast(16)}")
                    }
                }
                currentText.isNotEmpty() -> "读到 $spokenChars / ${currentText.length} 字"
                else -> ""
            }
            Text(
                progressText,
                color = if (useAsr || (currentText.isNotEmpty() && spokenChars >= currentText.length)) Mint else Muted,
                fontSize = 11.sp,
            )
            if (useAsr && showWrong && asrCharList.any { it.status == 2 }) {
                Text("红字 = 识别到读错/漏字（口音可能误判）；可继续读完", color = Color(0xFFFF8A8A), fontSize = 10.sp)
            } else if (useAsr && !showWrong) {
                Text("口音宽容模式：不标红，只按实际读到的字标蓝", color = Muted, fontSize = 10.sp)
            }
            if (next != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    next,
                    color = Color(0xFF4E6179),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            error?.let {
                Spacer(Modifier.height(18.dp))
                Text("⚠ $it", color = Color(0xFFFF9A9A), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }

        // 底部控制
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(hint, color = if (recording) AccentSoft else Muted, fontSize = 11.sp)
            if (asrActive) {
                Text("🎧 电脑实时识别", color = Mint, fontSize = 10.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    fmtDuration(elapsedMs),
                    color = if (recording) TextMain else Color(0xFF6F8299),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(64.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(PanelHi),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(level.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    if (level > 0.92f) listOf(Amber, Color(0xFFFF6B6B))
                                    else listOf(Mint, AccentSoft),
                                ),
                            ),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (recording && speaking) "● 说" else if (recording) "… 等" else "⏸",
                    color = if (recording && speaking) Mint else Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.width(44.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ControlChip("↻ 重录", enabled = recording, onClick = { retakeCurrent() }, modifier = Modifier.weight(1f))
                ControlChip(
                    text = if (recording) "■ 结束本句" else "▶ 开始录制",
                    primary = true,
                    onClick = {
                        if (recording) stopAndSave() else startAt(index, false)
                    },
                    modifier = Modifier.weight(1.2f),
                )
                ControlChip("⏭ 跳过", enabled = recording, onClick = { skipCurrent() }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "结束本句=手动保存；关掉自动下一句可在 ⚙ 设置里改",
                color = Color(0xFF5D6F85),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ControlChip(
    text: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgModifier: Modifier = if (primary) {
        Modifier.background(Brush.horizontalGradient(listOf(Accent, Color(0xFF5B8CFF))))
    } else {
        Modifier.background(Panel)
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(13.dp))
            .then(bgModifier)
            .border(1.dp, if (primary) Accent else Edge, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = when {
                !enabled -> Color(0xFF506279)
                primary -> Color.White
                else -> AccentSoft
            },
            fontSize = 13.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 用“实际发声区间 + 语速”把每个字映射到相对 wav 的起止毫秒（估算值）。 */
private fun estimateTimeline(
    text: String,
    rate: Float,
    segments: List<Pair<Long, Long>>,
): List<CharTime> {
    if (text.isEmpty() || segments.isEmpty() || rate <= 0.1f) return emptyList()
    val cleaned = segments.filter { it.second > it.first }
    if (cleaned.isEmpty()) return emptyList()
    val speakable = text.count { !it.isWhitespace() }
    if (speakable == 0) return emptyList()
    val totalVoiced = cleaned.fold(0L) { acc, (s, e) -> acc + (e - s) }.toFloat()
    val perChar = totalVoiced / speakable.toFloat()
    fun voiceToWall(target: Float): Long {
        var acc = 0f
        for ((s, e) in cleaned) {
            val len = (e - s).toFloat()
            if (target <= acc + len) {
                val within = (target - acc).coerceIn(0f, len)
                return s + within.toLong()
            }
            acc += len
        }
        return cleaned.last().second
    }
    val result = ArrayList<CharTime>(text.length)
    var idx = 0
    var cursor = cleaned.first().first
    for (ch in text) {
        if (ch.isWhitespace()) {
            result.add(CharTime(ch.toString(), cursor, cursor))
            continue
        }
        val start = voiceToWall(idx * perChar)
        val end = voiceToWall((idx + 1) * perChar)
        result.add(CharTime(ch.toString(), start, end))
        cursor = end
        idx++
    }
    return result
}