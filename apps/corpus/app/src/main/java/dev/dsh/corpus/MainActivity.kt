package dev.dsh.corpus

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import org.json.JSONObject

private const val PREFS = "corpus"
private const val KEY_REC = "recorded"
private const val KEY_SERVER = "server"
private const val KEY_PIN = "pin"
private const val KEY_COOKIE = "cookie"
private const val KEY_ENV = "env_result"
private const val SR = 48000
private const val ASSET_SCRIPT = "script.json"

val BgColor = Color(0xFF0E1116)
val BgColor2 = Color(0xFF151A22)
val CardBg = Color(0xFF1B212B)
val Accent = Color(0xFF5B8CFF)
val Accent2 = Color(0xFF41D1C6)
val Warn = Color(0xFFFF8A80)
val Ok = Color(0xFF6EE7A8)
val Muted = Color(0xFF8A93A6)

data class Item(val batch: Int, val id: Int, val text: String) {
    val key: String get() = "b" + batch + "_" + id
}

data class Batch(val index: Int, val items: List<Item>)

data class RecInfo(
    val durationMs: Long,
    val peakDb: Float,
    val clipPct: Float,
    val silencePct: Float,
    val pace: Float,
    val noiseDb: Float,
    val fileName: String
)

data class Metrics(
    val durationSec: Float,
    val peakDb: Float,
    val clipPct: Float,
    val silencePct: Float,
    val noiseDb: Float,
    val pace: Float
) {
    fun warnings(item: Item): List<String> {
        val list = mutableListOf<String>()
        val minLen = max(1.0f, item.text.length / 6.5f)
        val maxLen = max(2.5f, item.text.length / 3.0f) + 1.0f
        if (durationSec > 15f) list.add("超过 15 秒，请拆短重录")
        if (durationSec < minLen - 0.3f) list.add("太短了（目标 %.1f 秒以上）".format(minLen))
        if (clipPct > 0.1f) list.add("有削波/喷麦（%.2f%%）".format(clipPct))
        if (noiseDb > -45f) list.add("底噪偏高（%.0f dB）".format(noiseDb))
        if (pace > 7.5f) list.add("语速太快（%.1f 字/秒），可能吞字".format(pace))
        if (pace < 2.2f) list.add("语速偏慢（%.1f 字/秒）".format(pace))
        if (silencePct > 55f) list.add("停顿过多（静音 %.0f%%）".format(silencePct))
        if (durationSec > maxLen + 2f && list.isEmpty()) list.add("时长略长，注意别拖")
        return list
    }
}

object ScriptLoader {
    fun load(context: Context): List<Batch> {
        val text = context.assets.open(ASSET_SCRIPT).bufferedReader().readText()
        val root = JSONObject(text)
        val arr = root.getJSONArray("batches")
        val result = mutableListOf<Batch>()
        for (bi in 0 until arr.length()) {
            val b = arr.getJSONArray(bi)
            val items = mutableListOf<Item>()
            for (ii in 0 until b.length()) {
                val o = b.getJSONObject(ii)
                items.add(Item(bi + 1, o.getInt("id"), o.getString("text")))
            }
            result.add(Batch(bi + 1, items))
        }
        return result
    }
}

object RecStore {
    fun all(context: Context): MutableMap<String, RecInfo> {
        val map = mutableMapOf<String, RecInfo>()
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REC, null) ?: return map
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) {
                val j = o.getJSONObject(k)
                map[k] = RecInfo(
                    j.optLong("dur", 0), j.optDouble("peak", -100.0).toFloat(),
                    j.optDouble("clip", 0.0).toFloat(), j.optDouble("silence", 0.0).toFloat(),
                    j.optDouble("pace", 0.0).toFloat(), j.optDouble("noise", -100.0).toFloat(),
                    j.optString("file", "")
                )
            }
        } catch (_: Exception) {}
        return map
    }

    fun put(context: Context, key: String, info: RecInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val map = all(context)
        map[key] = info
        val o = JSONObject()
        for ((k, v) in map) {
            val j = JSONObject()
            j.put("dur", v.durationMs)
            j.put("peak", v.peakDb.toDouble())
            j.put("clip", v.clipPct.toDouble())
            j.put("silence", v.silencePct.toDouble())
            j.put("pace", v.pace.toDouble())
            j.put("noise", v.noiseDb.toDouble())
            j.put("file", v.fileName)
            o.put(k, j)
        }
        prefs.edit().putString(KEY_REC, o.toString()).apply()
    }

    fun size(context: Context): Int = all(context).size
}

class RecorderEngine(
    private val onLevel: (Float) -> Unit,
    private val onProgress: (Long) -> Unit,
    private val onAutoStop: () -> Unit
) {
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private val chunks: MutableList<ShortArray> = Collections.synchronizedList(mutableListOf())
    private var thread: Thread? = null

    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = max(minBuf * 2, 8192)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) return false
        record = r
        chunks.clear()
        running = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(1024)
            var total = 0L
            var voicedMs = 0
            var silentMs = 0
            while (running) {
                val n = try { r.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                if (n <= 0) continue
                chunks.add(buf.copyOf(n))
                total += n
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / n)
                val db = 20 * log10(max(rms, 1e-7))
                val level = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
                onLevel(level)
                onProgress(total * 1000 / SR)
                val voiced = db > -38.0
                if (voiced) { voicedMs += n * 1000 / SR; silentMs = 0 } else if (voicedMs > 300) { silentMs += n * 1000 / SR }
                if (total * 1000 / SR > 16000) { onAutoStop(); break }
            }
        }.also { it.start() }
        return true
    }

    fun stop(): ShortArray {
        running = false
        try { thread?.join(300) } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        val total = synchronized(chunks) { chunks.sumOf { it.size } }
        val out = ShortArray(total)
        var off = 0
        synchronized(chunks) { for (c in chunks) { System.arraycopy(c, 0, out, off, c.size); off += c.size } }
        return out
    }
}

fun analyze(samples: ShortArray, textLen: Int): Metrics {
    if (samples.isEmpty()) return Metrics(0f, -100f, 0f, 100f, -100f, 0f)
    val dur = samples.size.toFloat() / SR
    var peak = 0
    var clip = 0
    for (s in samples) { val a = abs(s.toInt()); if (a > peak) peak = a; if (a >= 32000) clip++ }
    val peakDb = 20 * log10(max(peak / 32768.0, 1e-7)).toFloat()
    val clipPct = clip * 100f / samples.size
    val frame = SR / 50
    val rmsList = mutableListOf<Double>()
    var i = 0
    while (i + frame <= samples.size) {
        var sum = 0.0
        for (j in i until i + frame) { val v = samples[j] / 32768.0; sum += v * v }
        rmsList.add(sqrt(sum / frame)); i += frame
    }
    val sorted = rmsList.sorted()
    val noise = if (sorted.isEmpty()) 0.0 else sorted[max(0, sorted.size / 10)]
    val noiseDb = 20 * log10(max(noise, 1e-7)).toFloat()
    val threshold = max(noise * 3.0, 0.004)
    val silentFrames = rmsList.count { it < threshold }
    val silencePct = if (rmsList.isEmpty()) 100f else silentFrames * 100f / rmsList.size
    val speechSec = dur * (1f - silencePct / 100f)
    val pace = if (speechSec > 0.2f) textLen / speechSec else 0f
    return Metrics(dur, peakDb, clipPct, silencePct, noiseDb, pace)
}

fun writeWav(file: File, samples: ShortArray) {
    val n = samples.size
    val hdr = ByteArray(44)
    fun putInt(o: Int, v: Int) { hdr[o] = (v and 0xff).toByte(); hdr[o+1] = ((v shr 8) and 0xff).toByte(); hdr[o+2] = ((v shr 16) and 0xff).toByte(); hdr[o+3] = ((v shr 24) and 0xff).toByte() }
    fun putShort(o: Int, v: Int) { hdr[o] = (v and 0xff).toByte(); hdr[o+1] = ((v shr 8) and 0xff).toByte() }
    "RIFF".toByteArray().copyInto(hdr, 0)
    putInt(4, 36 + n * 2)
    "WAVE".toByteArray().copyInto(hdr, 8)
    "fmt ".toByteArray().copyInto(hdr, 12)
    putInt(16, 16); putShort(20, 1); putShort(22, 1)
    putInt(24, SR); putInt(28, SR * 2); putShort(32, 2); putShort(34, 16)
    "data".toByteArray().copyInto(hdr, 36)
    putInt(40, n * 2)
    file.outputStream().use { out ->
        out.write(hdr)
        val buf = ByteArray(n * 2)
        for (k in 0 until n) {
            val v = samples[k].toInt()
            buf[k * 2] = (v and 0xff).toByte()
            buf[k * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        out.write(buf)
    }
}

fun recDir(context: Context): File {
    val d = File(context.filesDir, "rec")
    if (!d.exists()) d.mkdirs()
    return d
}

fun exportBatchZip(context: Context, batch: Batch, recorded: Map<String, RecInfo>): String {
    val dir = recDir(context)
    val name = "DSH语料_批次%02d.zip".format(batch.index)
    val zipFile = File(context.cacheDir, name)
    if (zipFile.exists()) zipFile.delete()
    ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
        val meta = StringBuilder()
        for (item in batch.items) {
            val info = recorded[item.key] ?: continue
            val wav = File(dir, info.fileName)
            if (!wav.exists()) continue
            zos.putNextEntry(ZipEntry("wav/" + info.fileName))
            FileInputStream(wav).use { it.copyTo(zos) }
            zos.closeEntry()
            meta.append("wav/").append(info.fileName).append('\t').append(item.text).append('\n')
        }
        zos.putNextEntry(ZipEntry("metadata.txt"))
        zos.write(meta.toString().toByteArray(Charsets.UTF_8))
        zos.closeEntry()
        val bj = JSONObject()
        bj.put("batch", batch.index)
        bj.put("count", batch.items.size)
        bj.put("created", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        bj.put("device", "DSH-Corpus")
        zos.putNextEntry(ZipEntry("batch.json"))
        zos.write(bj.toString().toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }
    if (Build.VERSION.SDK_INT >= 29) {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSH语料")
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(zipFile).use { it.copyTo(out) } }
        }
    } else {
        val target = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DSH语料")
        if (!target.exists()) target.mkdirs()
        FileInputStream(zipFile).use { inp -> FileOutputStream(File(target, name)).use { out -> inp.copyTo(out) } }
    }
    return name
}

fun baseUrl(raw: String): String {
    var s = raw.trim()
    if (s.isEmpty()) s = "192.168.1.100:8799"
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s
    if (!s.contains(":")) s += ":8799"
    return s.trimEnd('/')
}

object Bridge {
    fun pair(base: String, pin: String): String? {
        return try {
            val conn = URL(base + "/_bridge/pair").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(("{\"pin\":\"" + pin + "\"}").toByteArray()) }
            val code = conn.responseCode
            val cookies = conn.headerFields["Set-Cookie"] ?: emptyList()
            conn.disconnect()
            if (code < 200 || code > 299) null
            else cookies.firstOrNull { it.startsWith("dsh_phone=") }?.substringBefore(';')
        } catch (e: Throwable) { null }
    }

    fun upload(base: String, cookie: String?, file: File, targetDir: String): String {
        return try {
            val encDir = URLEncoder.encode(targetDir, "UTF-8")
            val encName = URLEncoder.encode(file.name, "UTF-8")
            val conn = URL(base + "/files/upload?dir=" + encDir + "&name=" + encName).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8000
            conn.readTimeout = 180000
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", file.length().toString())
            if (cookie != null) conn.setRequestProperty("Cookie", cookie)
            FileInputStream(file).use { inp -> conn.outputStream.use { out -> inp.copyTo(out) } }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            if (code in 200..299) "上传成功" else "上传失败(" + code + "): " + body.take(120)
        } catch (e: Throwable) { "上传失败: " + (e.message ?: "网络错误") }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Accent2, background = BgColor, surface = CardBg)) {
                App()
            }
        }
    }
}

@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val batches = remember { ScriptLoader.load(context) }
    var tick by remember { mutableStateOf(0) }
    val recorded = remember(tick) { RecStore.all(context) }

    var screen by remember { mutableStateOf("home") }
    var selBatch by remember { mutableStateOf(0) }
    var selIdx by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(prefs.getString(KEY_SERVER, "192.168.1.100:8799") ?: "") }
    var pin by remember { mutableStateOf(prefs.getString(KEY_PIN, "000000") ?: "") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        msg = if (ok) "麦克风权限已允许" else "需要麦克风权限才能录音，请到系统设置里打开"
    }

    LaunchedEffect(Unit) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("电脑连接设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("电脑地址") }, singleLine = true)
                    OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("配对 PIN") }, singleLine = true)
                    Text("配对后录音 zip 可直接上传到电脑", fontSize = 11.sp, color = Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString(KEY_SERVER, server).putString(KEY_PIN, pin).apply()
                    showSettings = false
                    scope.launch(Dispatchers.IO) {
                        val cookie = Bridge.pair(baseUrl(server), pin)
                        withContext(Dispatchers.Main) {
                            if (cookie != null) { prefs.edit().putString(KEY_COOKIE, cookie).apply(); msg = "配对成功" }
                            else msg = "配对失败，检查地址/PIN"
                        }
                    }
                }) { Text("保存并配对") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } }
        )
    }

    when (screen) {
        "home" -> HomeScreen(
            batches = batches, recorded = recorded,
            envResult = prefs.getString(KEY_ENV, ""),
            onRecordBatch = { bi ->
                selBatch = bi
                var start = 0
                for ((i, it) in batches[bi].items.withIndex()) if (!recorded.containsKey(it.key)) { start = i; break }
                selIdx = start
                screen = "record"
            },
            onExport = { bi ->
                scope.launch(Dispatchers.IO) {
                    val name = exportBatchZip(context, batches[bi], recorded)
                    withContext(Dispatchers.Main) { msg = "已导出到 下载/DSH语料/" + name }
                }
            },
            onUpload = { bi ->
                scope.launch(Dispatchers.IO) {
                    var cookie = prefs.getString(KEY_COOKIE, null)
                    if (cookie == null) cookie = Bridge.pair(baseUrl(server), pin)
                    if (cookie == null) {
                        withContext(Dispatchers.Main) { msg = "未配对，先点右上角设置" }
                    } else {
                        prefs.edit().putString(KEY_COOKIE, cookie).apply()
                        val name = exportBatchZip(context, batches[bi], recorded)
                        val zip = File(context.cacheDir, name)
                        val r = Bridge.upload(baseUrl(server), cookie, zip, "手机上传")
                        withContext(Dispatchers.Main) { msg = r }
                    }
                }
            },
            onEnv = { screen = "env" },
            onSettings = { showSettings = true },
            msg = msg
        )
        "env" -> EnvScreen(
            onBack = { screen = "home" },
            onResult = { r -> prefs.edit().putString(KEY_ENV, r).apply(); msg = r; tick++; screen = "home" },
            context = context
        )
        else -> RecordScreen(
            batch = batches[selBatch], startIndex = selIdx,
            context = context,
            onBack = { screen = "home" },
            onSaved = { tick++ }
        )
    }
}

@Composable
fun HomeScreen(
    batches: List<Batch>, recorded: Map<String, RecInfo>,
    envResult: String?,
    onRecordBatch: (Int) -> Unit, onExport: (Int) -> Unit, onUpload: (Int) -> Unit,
    onEnv: () -> Unit, onSettings: () -> Unit, msg: String
) {
    val total = batches.sumOf { it.items.size }
    val done = recorded.size
    val totalSec = recorded.values.sumOf { it.durationMs / 1000.0 }.toFloat()
    Column(
        Modifier.fillMaxSize().background(BgColor).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("DSH 语料", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("录音采集 · 训练语音素材", fontSize = 12.sp, color = Muted)
            }
            TextButton(onClick = onSettings) { Text("设置", color = Accent2) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("总进度 " + done + " / " + total + " 句", fontSize = 14.sp, color = Color.White)
                LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth(), color = Accent)
                Text("有效时长 %.1f 分钟 · 目标约 50 分钟".format(totalSec / 60), fontSize = 12.sp, color = Muted)
                Text(if (envResult.isNullOrEmpty()) "环境检测：未做" else "环境检测：" + envResult, fontSize = 12.sp, color = Accent2)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onEnv, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))) { Text("环境检测", color = Color(0xFFD5DAE3), fontSize = 13.sp) }
            val nextBatch = batches.indexOfFirst { b -> b.items.any { !recorded.containsKey(it.key) } }
            Button(
                onClick = { if (nextBatch >= 0) onRecordBatch(nextBatch) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text(if (nextBatch >= 0) "继续录制" else "全部完成") }
        }
        if (msg.isNotEmpty()) Text(msg, fontSize = 12.sp, color = Accent2)
        Text("批次", fontSize = 12.sp, color = Muted)
        batches.forEachIndexed { bi, b ->
            val cnt = b.items.count { recorded.containsKey(it.key) }
            val sec = b.items.mapNotNull { recorded[it.key]?.durationMs }.sum() / 1000.0
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("批次 %02d".format(b.index), fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        Text("" + cnt + "/" + b.items.size + " · %.1f 分钟".format(sec / 60), fontSize = 12.sp, color = if (cnt == b.items.size) Ok else Muted)
                    }
                    LinearProgressIndicator(progress = { cnt.toFloat() / b.items.size }, modifier = Modifier.fillMaxWidth(), color = Accent2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRecordBatch(bi) }, modifier = Modifier.weight(1f).height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(if (cnt == 0) "录制" else "继续", fontSize = 13.sp) }
                        Button(onClick = { onExport(bi) }, enabled = cnt > 0, modifier = Modifier.weight(1f).height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))) { Text("导出", fontSize = 13.sp) }
                        Button(onClick = { onUpload(bi) }, enabled = cnt > 0, modifier = Modifier.weight(1f).height(40.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))) { Text("上传", fontSize = 13.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun EnvScreen(onBack: () -> Unit, onResult: (String) -> Unit, context: Context) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("准备就绪：找安静房间，手机 10~15cm，别动别说话") }
    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    Column(Modifier.fillMaxSize().background(BgColor).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("环境检测", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(state, fontSize = 13.sp, color = Muted)
        Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFF232B38), RoundedCornerShape(5.dp))) {
            Box(Modifier.fillMaxWidth(level).height(10.dp).background(Accent2, RoundedCornerShape(5.dp)))
        }
        Button(
            onClick = {
                if (recording) return@Button
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    state = "没有麦克风权限，请先在系统设置里允许"
                    return@Button
                }
                recording = true
                state = "请保持安静 3 秒…"
                val e = RecorderEngine(onLevel = { l -> level = l }, onProgress = {}, onAutoStop = {})
                scope.launch(Dispatchers.IO) {
                    val ok = e.start()
                    if (!ok) {
                        withContext(Dispatchers.Main) { recording = false; state = "录音初始化失败" }
                        return@launch
                    }
                    delay(3000)
                    val samples = e.stop()
                    val m = analyze(samples, 10)
                    val good = m.noiseDb < -45f && m.peakDb < -3f
                    val r = "底噪 %.0f dB · 峰值 %.0f dB · %s".format(m.noiseDb, m.peakDb, if (good) "通过" else "不达标（换更安静环境或调整距离）")
                    withContext(Dispatchers.Main) {
                        recording = false
                        level = 0f
                        state = r
                        onResult(r)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) { Text(if (recording) "检测中…" else "开始 3 秒检测", fontSize = 15.sp) }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))) { Text("返回") }
    }
}

@Composable
fun RecordScreen(batch: Batch, startIndex: Int, context: Context, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var idx by remember { mutableStateOf(startIndex) }
    val item = batch.items[idx]
    var recording by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(0f) }
    var elapsed by remember { mutableStateOf(0L) }
    var metrics by remember { mutableStateOf<Metrics?>(null) }
    var saved by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var envMsg by remember { mutableStateOf("") }
    var lastEngine by remember { mutableStateOf<RecorderEngine?>(null) }
    val recFile = remember(idx) { File(recDir(context), "b%02d_%03d.wav".format(batch.index, item.id)) }

    fun startRec() {
        if (recording) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            envMsg = "没有麦克风权限，请先在系统设置里允许"
            return
        }
        recording = true
        saved = false
        metrics = null
        elapsed = 0
        level = 0f
        envMsg = ""
        val e = RecorderEngine(
            onLevel = { l -> level = l },
            onProgress = { ms -> elapsed = ms },
            onAutoStop = {}
        )
        lastEngine = e
        val ok = e.start()
        if (!ok) { recording = false; envMsg = "录音初始化失败" }
    }

    fun stopRec() {
        if (!recording) return
        recording = false
        val e = lastEngine
        if (e == null) { metrics = null; return }
        scope.launch(Dispatchers.IO) {
            val samples = e.stop()
            if (samples.isEmpty()) {
                withContext(Dispatchers.Main) { envMsg = "没有录到声音" }
                return@launch
            }
            writeWav(recFile, samples)
            val m = analyze(samples, item.text.length)
            val info = RecInfo((m.durationSec * 1000).toLong(), m.peakDb, m.clipPct, m.silencePct, m.pace, m.noiseDb, recFile.name)
            RecStore.put(context, item.key, info)
            withContext(Dispatchers.Main) {
                metrics = m
                saved = true
                onSaved()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(BgColor).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("批次 %02d · %d/%d".format(batch.index, idx + 1, batch.items.size), fontSize = 14.sp, color = Muted)
            TextButton(onClick = onBack) { Text("返回", color = Accent2) }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.text, fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Medium, lineHeight = 34.sp)
                Text("字数 " + item.text.length + " · 目标 %.1f~%.1f 秒".format(max(1.0f, item.text.length / 6.5f), max(2.5f, item.text.length / 3.0f) + 1f), fontSize = 12.sp, color = Muted)
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(Color(0xFF232B38), RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(level).height(8.dp).background(if (level > 0.9f) Warn else Accent2, RoundedCornerShape(4.dp)))
        }
        Text("时长 %.1f 秒".format(elapsed / 1000.0), fontSize = 12.sp, color = Muted)
        if (envMsg.isNotEmpty()) Text(envMsg, fontSize = 12.sp, color = Warn)
        Button(
            onClick = { if (recording) stopRec() else startRec() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (recording) Warn else Accent)
        ) { Text(if (recording) "停止录音" else "开始录音", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

        val m = metrics
        if (m != null) {
            val warns = m.warnings(item)
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (warns.isEmpty()) "质检通过" else "需要重录", fontSize = 14.sp, color = if (warns.isEmpty()) Ok else Warn, fontWeight = FontWeight.Bold)
                    Text("时长 %.1f 秒 · 峰值 %.0f dB · 削波 %.2f%% · 静音 %.0f%% · 语速 %.1f 字/秒".format(m.durationSec, m.peakDb, m.clipPct, m.silencePct, m.pace), fontSize = 12.sp, color = Muted)
                    if (warns.isNotEmpty()) Text(warns.joinToString("\n"), fontSize = 12.sp, color = Warn)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        try {
                            player?.release()
                            val mp = MediaPlayer()
                            mp.setDataSource(recFile.absolutePath)
                            mp.prepare()
                            mp.start()
                            player = mp
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))
                ) { Text("试听", fontSize = 13.sp) }
                Button(
                    onClick = { metrics = null; saved = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))
                ) { Text("重录", fontSize = 13.sp) }
                Button(
                    onClick = { if (idx < batch.items.size - 1) { idx++; metrics = null; saved = false } },
                    modifier = Modifier.weight(1f),
                    enabled = warns.isEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text(if (idx < batch.items.size - 1) "下一句" else "本批完成", fontSize = 13.sp) }
            }
        } else if (saved) {
            Button(
                onClick = { if (idx < batch.items.size - 1) { idx++; saved = false } },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text("下一句", fontSize = 14.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (idx > 0) { idx--; metrics = null; saved = false } }) { Text("上一句", color = Muted) }
            Text("已录 " + RecStore.size(context) + " 句", fontSize = 12.sp, color = Muted)
        }
        Spacer(Modifier.height(16.dp))
    }
}
