package dev.dsh.voice

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import org.json.JSONObject

private const val PREFS = "dsh_voice"
private const val KEY_MODEL = "current_model"
private const val KEY_TEXT = "last_text"
private const val KEY_SPEED = "speed"
private const val KEY_PENDING = "autoload_pending"
private const val KEY_SERVER = "pc_server"
private const val DEFAULT_SERVER = "192.168.1.100:8799"
private const val ASSET_PREFIX = "内置:"
private const val IMPORT_PREFIX = "导入:"

val DeepBg = Color(0xFF0E1116)
val DeepBg2 = Color(0xFF151A22)
val Accent = Color(0xFF7C5CFF)
val Accent2 = Color(0xFF41D1C6)
val CardBg = Color(0xFF1B212B)
val Muted = Color(0xFF8A93A6)
val Muted2 = Color(0xFF6B7280)

data class ModelEntry(val key: String, val label: String, val tag: String, val dateText: String, val ts: Long)

fun friendlyName(m: String): String {
    return when {
        m.contains("419") -> "清晰 419 · 最佳"
        m.contains("804") -> "清晰 804"
        m.contains("254") -> "清晰 254"
        m.contains("337") -> "本地 337"
        m.contains("080") -> "旧版 080"
        m.startsWith("更新_") -> "云端更新 " + m.takeLast(4)
        m.startsWith("旧导入_") -> "旧导入 " + m.takeLast(4)
        m.startsWith("模型_") -> "导入 " + m.takeLast(4)
        else -> m
    }
}

fun fmtDate(ts: Long): String {
    if (ts <= 0) return ""
    return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(filesDir)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, secondary = Accent2, background = DeepBg, surface = CardBg)) {
                App()
            }
        }
    }
}

object AppLog {
    private var file: File? = null
    fun init(filesDir: File) {
        file = File(filesDir, "app.log")
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { file?.appendText("\n[CRASH] " + t.name + "\n" + e.stackTraceToString()) } catch (_: Exception) {}
            android.util.Log.e("DshVoice", "uncaught", e)
        }
    }
    fun write(line: String) {
        android.util.Log.i("DshVoice", line)
        try { file?.appendText(line + "\n") } catch (_: Exception) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var log by remember { mutableStateOf("") }
    fun addLog(msg: String) {
        AppLog.write(msg)
        log = (log + msg + "\n").takeLast(4000)
    }

    var status by remember { mutableStateOf("正在准备…") }
    var modelName by remember { mutableStateOf("") }
    var modelLabel by remember { mutableStateOf("") }
    var loadingKey by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var perf by remember { mutableStateOf("") }
    var showLog by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<OfflineTts?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var text by remember { mutableStateOf(prefs.getString(KEY_TEXT, null) ?: "宝宝，我呆呆的大宝宝。我爱你哦，很爱很爱。") }
    var speed by remember { mutableStateOf(prefs.getFloat(KEY_SPEED, 1.0f)) }
    var serverAddr by remember { mutableStateOf(prefs.getString(KEY_SERVER, DEFAULT_SERVER) ?: DEFAULT_SERVER) }
    var updInfo by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var pendingModelName by remember { mutableStateOf("") }
    var dlProgress by remember { mutableStateOf(0f) }
    var showServerDialog by remember { mutableStateOf(false) }
    var apkMsg by remember { mutableStateOf("") }
    var apkAvailable by remember { mutableStateOf(false) }
    var apkDownloading by remember { mutableStateOf(false) }
    var apkProgress by remember { mutableStateOf(0f) }
    var synProgress by remember { mutableStateOf(0f) }
    var synInfo by remember { mutableStateOf("") }
    var musicTracks by remember { mutableStateOf(MediaStoreHelper.listTracks(context)) }
    var musicIdx by remember { mutableStateOf(-1) }
    var musicPlaying by remember { mutableStateOf(false) }
    var musicPos by remember { mutableStateOf(0) }
    var musicDur by remember { mutableStateOf(0) }
    var musicPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var savedMsg by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun modelList(): List<ModelEntry> {
        refreshTick
        val bundled = BundledModels.list(context)
        val imported = ImportedModels.list(context)
        val list = mutableListOf<ModelEntry>()
        imported.forEach { name ->
            val f = File(ImportedModels.dir(context, name), "model.onnx")
            val ts = f.lastModified()
            list.add(ModelEntry(IMPORT_PREFIX + name, friendlyName(name), "导入", fmtDate(ts), ts))
        }
        bundled.forEach { name ->
            val m = Regex("(\\d+)").find(name)?.value?.toLongOrNull() ?: 0L
            list.add(ModelEntry(ASSET_PREFIX + name, friendlyName(name), "内置", "内置", m))
        }
        return list.sortedWith(compareByDescending<ModelEntry> { it.ts })
    }

    fun baseUrl(): String {
        var s = serverAddr.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s
        if (!s.contains(":")) s = s + ":8799"
        return s.trimEnd('/')
    }

    fun loadModel(key: String) {
        if (loadingKey.isNotEmpty()) return
        loadingKey = key
        ready = false
        status = "加载中…"
        addLog("加载: " + key)
        prefs.edit().putString(KEY_PENDING, key).apply()
        scope.launch(Dispatchers.Default) {
            try {
                val isAsset = key.startsWith(ASSET_PREFIX)
                val name = key.removePrefix(ASSET_PREFIX).removePrefix(IMPORT_PREFIX)
                val vits: OfflineTtsVitsModelConfig
                var assetManager: android.content.res.AssetManager? = null
                if (isAsset) {
                    val base = "models/" + name
                    vits = OfflineTtsVitsModelConfig(
                        model = base + "/model.onnx",
                        lexicon = base + "/lexicon.txt",
                        tokens = base + "/tokens.txt",
                        noiseScale = 0.6f, noiseScaleW = 0.7f, lengthScale = 1.0f
                    )
                    assetManager = context.assets
                } else {
                    val d = ImportedModels.dir(context, name)
                    val mf = File(d, "model.onnx")
                    require(mf.exists()) { "模型文件不存在" }
                    vits = OfflineTtsVitsModelConfig(
                        model = mf.absolutePath,
                        lexicon = File(d, "lexicon.txt").absolutePath,
                        tokens = File(d, "tokens.txt").absolutePath,
                        noiseScale = 0.6f, noiseScaleW = 0.7f, lengthScale = 1.0f
                    )
                }
                val cfg = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(vits = vits, numThreads = 4, debug = false, provider = "cpu"),
                    maxNumSentences = 1
                )
                val t = OfflineTts(assetManager, cfg)
                addLog("引擎就绪 sampleRate=" + t.sampleRate())
                withContext(Dispatchers.Main) {
                    tts?.release()
                    tts = t
                    modelName = key
                    modelLabel = friendlyName(name)
                    ready = true
                    loadingKey = ""
                    status = "就绪，可以朗读了"
                    perf = ""
                    prefs.edit().putString(KEY_MODEL, key).putString(KEY_PENDING, "").apply()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    status = "加载失败"
                    loadingKey = ""
                    ready = false
                    addLog("失败: " + (e.message ?: e.javaClass.name))
                }
            }
        }
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "导入中…"
        scope.launch(Dispatchers.IO) {
            try {
                val name = ImportedModels.importZip(context, uri, "")
                addLog("import ok" + name)
                withContext(Dispatchers.Main) {
                    busy = false
                    refreshTick++
                    loadModel(IMPORT_PREFIX + name)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    status = "导入失败"
                    busy = false
                    addLog("导入失败: " + (e.message ?: e.javaClass.name))
                }
            }
        }
    }

    fun checkApkUpdate() {
        apkMsg = "检查中…"
        apkAvailable = false
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL(baseUrl() + "/app/voice/version")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val j = JSONObject(body)
                val vc = j.optInt("versionCode", 0)
                val vn = j.optString("versionName", "")
                val cur = BuildConfig.VERSION_CODE
                withContext(Dispatchers.Main) {
                    if (vc > cur) {
                        apkAvailable = true
                        apkMsg = "发现新版本 v" + vn + "（当前 v" + BuildConfig.VERSION_NAME + "）"
                    } else {
                        apkMsg = "已是最新版本 v" + BuildConfig.VERSION_NAME
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    apkMsg = "检查失败：确认电脑地址和桥接器"
                }
            }
        }
    }

    fun downloadAndInstallApk() {
        if (apkDownloading) return
        apkDownloading = true
        apkProgress = 0f
        apkMsg = "下载 APK 中…"
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL(baseUrl() + "/app/voice/latest.apk")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 180000
                val total = conn.contentLengthLong
                val f = File(context.cacheDir, "update.apk")
                if (f.exists()) f.delete()
                conn.inputStream.use { inp ->
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(65536)
                        var read = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) withContext(Dispatchers.Main) { apkProgress = (read * 100 / total / 100f) }
                        }
                    }
                }
                conn.disconnect()
                withContext(Dispatchers.Main) {
                    apkDownloading = false
                    apkMsg = "下载完成，正在调起安装…"
                    try {
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", f)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    } catch (ex: Throwable) {
                        apkMsg = "调起安装失败：" + (ex.message ?: "")
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    apkDownloading = false
                    apkMsg = "下载失败：检查网络/地址"
                }
            }
        }
    }

    fun checkUpdate() {
        busy = true
        status = "检查更新中…"
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL(baseUrl() + "/app/voicemodel/version")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                val j = JSONObject(body)
                val name = j.optString("name", "新模型")
                val ver = j.optString("versionName", "")
                val size = j.optLong("size", 0)
                val pub = j.optString("publishedAt", "")
                val modelRealName = j.optString("name", "")
                withContext(Dispatchers.Main) {
                    pendingModelName = modelRealName
                    updInfo = name + "（" + ver + "）· " + String.format(Locale.US, "%.1f", size / 1048576.0) + "MB · " + pub
                    status = "发现新模型，点【下载并启用】"
                    busy = false
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    status = "检查失败：确认电脑地址和桥接器"
                    busy = false
                    addLog("检查失败: " + (e.message ?: e.javaClass.name))
                }
            }
        }
    }

    fun downloadUpdate() {
        if (downloading) return
        downloading = true
        dlProgress = 0f
        status = "下载中…"
        scope.launch(Dispatchers.IO) {
            try {
                val url = URL(baseUrl() + "/app/voicemodel/latest.apk")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 60000
                val total = conn.contentLengthLong
                val f = File(context.cacheDir, "newmodel.zip")
                conn.inputStream.use { inp ->
                    FileOutputStream(f).use { out ->
                        val buf = ByteArray(65536)
                        var read = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) withContext(Dispatchers.Main) { dlProgress = (read * 100 / total / 100f) }
                        }
                    }
                }
                conn.disconnect()
                val name = ImportedModels.importZipFile(context, f, pendingModelName)
                addLog("更新导入: " + name)
                withContext(Dispatchers.Main) {
                    downloading = false
                    refreshTick++
                    status = "已下载并启用新模型"
                    updInfo = ""
                    loadModel(IMPORT_PREFIX + name)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    downloading = false
                    status = "下载失败：检查地址/桥接器"
                    addLog("下载失败: " + (e.message ?: e.javaClass.name))
                }
            }
        }
    }

    fun splitChunks(input: String): List<String> {
        val parts = mutableListOf<String>()
        var cur = StringBuilder()
        for (ch in input) {
            cur.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '；' || ch == ';' || ch == '\n') {
                val s = cur.toString().trim()
                if (s.isNotEmpty()) parts.add(s)
                cur = StringBuilder()
            }
        }
        val tail = cur.toString().trim()
        if (tail.isNotEmpty()) parts.add(tail)
        val merged = mutableListOf<String>()
        var acc = StringBuilder()
        for (s in parts) {
            acc.append(s)
            if (acc.length >= 40) { merged.add(acc.toString()); acc = StringBuilder() }
        }
        if (acc.isNotEmpty()) merged.add(acc.toString())
        return if (merged.isEmpty()) listOf(input) else merged
    }

    fun refreshMusic() { musicTracks = MediaStoreHelper.listTracks(context) }

    fun startMusic(index: Int) {
        if (index < 0 || index >= musicTracks.size) return
        try {
            musicPlayer?.release()
            val t = musicTracks[index]
            val mp = MediaPlayer()
            mp.setDataSource(context, t.uri)
            mp.prepare()
            mp.start()
            musicPlayer = mp
            musicIdx = index
            musicDur = mp.duration
            musicPlaying = true
            musicPos = 0
            scope.launch {
                while (musicPlaying) {
                    try { musicPos = musicPlayer?.currentPosition ?: 0 } catch (_: Exception) {}
                    delay(400)
                }
            }
        } catch (e: Throwable) {
            savedMsg = "播放失败：" + (e.message ?: "")
        }
    }

    fun toggleMusic() {
        val mp = musicPlayer
        if (mp == null) {
            if (musicTracks.isNotEmpty()) startMusic(if (musicIdx < 0) 0 else musicIdx)
            else refreshMusic()
            return
        }
        try {
            if (mp.isPlaying) { mp.pause(); musicPlaying = false } else { mp.start(); musicPlaying = true; scope.launch { while (musicPlaying) { try { musicPos = musicPlayer?.currentPosition ?: 0 } catch (_: Exception) {}; delay(400) } } }
        } catch (_: Exception) {}
    }

    fun nextMusic(delta: Int) {
        if (musicTracks.isEmpty()) return
        val n = musicTracks.size
        var idx = if (musicIdx < 0) 0 else (musicIdx + delta + n) % n
        startMusic(idx)
    }

    val addAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val name = MediaStoreHelper.importAudio(context, uri)
                withContext(Dispatchers.Main) {
                    refreshMusic()
                    savedMsg = "已添加音频：" + name
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { savedMsg = "添加失败：" + (e.message ?: "") }
            }
        }
    }

    fun synthAndPlay() {
        val engine = tts ?: run { addLog("请先选择模型"); return }
        if (text.isBlank()) return
        busy = true
        synProgress = 0f
        synInfo = "准备合成…"
        status = "合成中…"
        scope.launch {
            try {
                val chunks = splitChunks(text)
                val total = chunks.size
                val start = System.currentTimeMillis()
                val all = mutableListOf<FloatArray>()
                var sr = 16000
                for (i in chunks.indices) {
                    val a = withContext(Dispatchers.IO) { engine.generate(chunks[i], 0, speed) }
                    sr = a.sampleRate
                    all.add(a.samples)
                    val done = i + 1
                    val elapsed = (System.currentTimeMillis() - start) / 1000.0
                    val eta = elapsed / done * (total - done)
                    synProgress = done / total.toFloat()
                    synInfo = String.format(Locale.US, "合成中 %d/%d · 已用 %.0f 秒 · 预计剩余 %.0f 秒", done, total, elapsed, eta)
                }
                var totalLen = 0
                for (s in all) totalLen += s.size
                val merged = FloatArray(totalLen)
                var off = 0
                for (s in all) { System.arraycopy(s, 0, merged, off, s.size); off += s.size }
                val wav = File(context.cacheDir, "out.wav")
                withContext(Dispatchers.IO) { WavWriter.write(wav, merged, sr) }
                val elapsed = (System.currentTimeMillis() - start) / 1000.0
                val dur = merged.size.toDouble() / sr
                player?.release()
                val mp = MediaPlayer()
                mp.setDataSource(wav.absolutePath)
                mp.prepare()
                mp.start()
                player = mp
                val savedName = MediaStoreHelper.saveWav(context, merged, sr)
                refreshMusic()
                if (savedName != null) savedMsg = "已保存到 音乐/DSH语音/" + savedName
                scope.launch { delay(1500); refreshMusic() }
                perf = String.format(Locale.US, "音频 %.1f 秒 · 合成 %.2f 秒 · RTF %.2f", dur, elapsed, elapsed / dur)
                status = "播放中"
                synInfo = ""
                busy = false
            } catch (e: Throwable) {
                status = "合成失败"
                synInfo = ""
                busy = false
                addLog("合成失败: " + (e.message ?: e.javaClass.name))
            }
        }
    }

    remember(Unit) {
        val pending = prefs.getString(KEY_PENDING, "")
        val last = prefs.getString(KEY_MODEL, null)
        if (!pending.isNullOrEmpty()) {
            addLog("上次加载未完成，已跳过自动加载")
            prefs.edit().putString(KEY_PENDING, "").apply()
        } else if (last != null) {
            scope.launch { delay(1200); loadModel(last) }
        } else {
            val bundled = BundledModels.list(context)
            if (bundled.isNotEmpty()) scope.launch { delay(1200); loadModel(ASSET_PREFIX + bundled[0]) }
        }
        true
    }

    val quick = listOf("你好呀，我是小智", "宝宝，我爱你", "今天天气不错", "我在呢，你说", "帮我把今天记一下")

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(DeepBg, DeepBg2, Color(0xFF0B1A1C))))) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("DSH 语音", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("离线本地合成 · 用你自己的声音说话", fontSize = 12.sp, color = Muted)

            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(if (ready) Accent2 else Color(0xFF6B7280), RoundedCornerShape(4.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(status, fontSize = 13.sp, color = Color(0xFFB4BCCB))
                    }
                    if (perf.isNotEmpty()) Text(perf, fontSize = 12.sp, color = Accent2)
                    if (loadingKey.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent, trackColor = Color(0xFF2A3140))
                    } else if (synInfo.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { synProgress }, Modifier.fillMaxWidth(), color = Accent2, trackColor = Color(0xFF2A3140))
                        Text(synInfo, fontSize = 11.sp, color = Muted)
                    }
                }
            }

            // 当前模型 + 抽屉入口
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前模型", fontSize = 11.sp, color = Muted)
                        Text(if (modelLabel.isEmpty()) "未选择" else modelLabel, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
                    }
                    Button(
                        onClick = { showDrawer = true },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent.copy(alpha = 0.25f)),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text("切换模型 ▾", fontSize = 13.sp, color = Color.White) }
                }
            }

            // 输入区
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; prefs.edit().putString(KEY_TEXT, it).apply() },
                        placeholder = { Text("输入要朗读的文字…", color = Muted2) },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Color(0xFF2A3140),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color(0xFFD5DAE3),
                            cursorColor = Accent
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("字数 " + text.length + (if (text.isNotEmpty()) " · 预计 " + String.format(Locale.US, "%.1f", text.length * 0.28) + " 秒" else ""), fontSize = 11.sp, color = Muted2)
                }
            }

            Text("常用语", fontSize = 12.sp, color = Muted)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.forEach { q ->
                    Surface(onClick = { text = q }, shape = RoundedCornerShape(20.dp), color = Color(0xFF232B38)) {
                        Text(q, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontSize = 12.sp, color = Color(0xFFB4BCCB))
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("语速", fontSize = 12.sp, color = Muted)
                        Text(String.format(Locale.US, "%.1fx", speed), fontSize = 12.sp, color = Accent2)
                    }
                    Slider(value = speed, onValueChange = { speed = it; prefs.edit().putFloat(KEY_SPEED, it).apply() }, valueRange = 0.7f..1.4f, steps = 6)
                }
            }

            if (synInfo.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(progress = { synProgress }, Modifier.fillMaxWidth(), color = Accent2, trackColor = Color(0xFF2A3140))
                    Text(synInfo, fontSize = 11.sp, color = Muted)
                }
            }
            Button(
                onClick = { synthAndPlay() },
                enabled = ready && !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) { Text(if (busy && synInfo.isNotEmpty()) "生成中…" else if (busy) "合成中…" else "🔊  朗读这段文字", fontSize = 17.sp, fontWeight = FontWeight.Bold) }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { player?.stop(); player?.release(); player = null; status = "已停止" },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))
                ) { Text("⏹ 停止", fontSize = 14.sp, color = Color(0xFFD5DAE3)) }
                Button(
                    onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))
                ) { Text("📦 导入模型", fontSize = 14.sp, color = Color(0xFFD5DAE3)) }
            }

            // 从电脑拉取新模型
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("从电脑获取新模型", fontSize = 13.sp, color = Color(0xFFD5DAE3))
                        TextButton(onClick = { showServerDialog = true }) { Text("地址", fontSize = 12.sp, color = Accent2) }
                    }
                    Text(baseUrl(), fontSize = 11.sp, color = Muted2)
                    if (updInfo.isNotEmpty()) Text(updInfo, fontSize = 12.sp, color = Accent2)
                    if (downloading) {
                        LinearProgressIndicator(progress = { dlProgress }, Modifier.fillMaxWidth(), color = Accent, trackColor = Color(0xFF2A3140))
                        Text("下载中 " + (dlProgress * 100).toInt() + "%", fontSize = 11.sp, color = Muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { checkUpdate() }, enabled = !busy && !downloading, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))) { Text("检查更新", fontSize = 13.sp, color = Color(0xFFD5DAE3)) }
                        Button(onClick = { downloadUpdate() }, enabled = !downloading, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("下载并启用", fontSize = 13.sp, color = Color.White) }
                    }
                }
            }


            // App 自身更新
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("App 更新", fontSize = 13.sp, color = Color(0xFFD5DAE3))
                        Text("当前 v" + BuildConfig.VERSION_NAME, fontSize = 11.sp, color = Muted2)
                    }
                    if (apkMsg.isNotEmpty()) Text(apkMsg, fontSize = 12.sp, color = if (apkAvailable) Accent2 else Muted)
                    if (apkDownloading) {
                        LinearProgressIndicator(progress = { apkProgress }, Modifier.fillMaxWidth(), color = Accent, trackColor = Color(0xFF2A3140))
                        Text("下载中 " + (apkProgress * 100).toInt() + "%", fontSize = 11.sp, color = Muted)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { checkApkUpdate() }, enabled = !apkDownloading, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))) { Text("检查更新", fontSize = 13.sp, color = Color(0xFFD5DAE3)) }
                        Button(onClick = { downloadAndInstallApk() }, enabled = apkAvailable && !apkDownloading, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("下载并安装", fontSize = 13.sp, color = Color.White) }
                    }
                }
            }
            // 音乐播放器
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("音乐播放器", fontSize = 13.sp, color = Color(0xFFD5DAE3))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { refreshMusic() }) { Text("刷新", fontSize = 12.sp, color = Accent2) }
                            TextButton(onClick = { addAudio.launch(arrayOf("audio/*", "*/*")) }) { Text("添加音频", fontSize = 12.sp, color = Accent2) }
                        }
                    }
                    if (musicTracks.isEmpty()) {
                        Text("暂无音乐 · 朗读后会保存到 音乐/DSH语音，自动出现在这里", fontSize = 11.sp, color = Muted2)
                    } else {
                        val cur = if (musicIdx in musicTracks.indices) musicTracks[musicIdx] else musicTracks[0]
                        Text(cur.name, fontSize = 13.sp, color = Color.White, maxLines = 1)
                        Slider(
                            value = if (musicDur > 0) (musicPos.toFloat() / musicDur).coerceIn(0f, 1f) else 0f,
                            onValueChange = { v -> try { musicPlayer?.seekTo((v * musicDur).toInt()) } catch (_: Exception) {} },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fmtMs(musicPos) + " / " + fmtMs(musicDur), fontSize = 11.sp, color = Muted2)
                            Text("共 " + musicTracks.size + " 首", fontSize = 11.sp, color = Muted2)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { nextMusic(-1) }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))) { Text("⏮", fontSize = 16.sp) }
                            Button(onClick = { toggleMusic() }, modifier = Modifier.weight(1.4f).height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(if (musicPlaying) "⏸ 暂停" else "▶ 播放", fontSize = 14.sp, color = Color.White) }
                            Button(onClick = { nextMusic(1) }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A3140))) { Text("⏭", fontSize = 16.sp) }
                        }
                    }
                    if (savedMsg.isNotEmpty()) Text(savedMsg, fontSize = 11.sp, color = Accent2)
                }
            }

            TextButton(onClick = { showLog = !showLog }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (showLog) "收起诊断日志" else "查看诊断日志", fontSize = 12.sp, color = Muted2)
            }
            if (showLog) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF141922)), modifier = Modifier.fillMaxWidth()) {
                    Text(log.ifEmpty { "（空）" }, Modifier.padding(10.dp), fontSize = 10.sp, color = Muted)
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // 抽屉：模型列表（按时间排序）
        if (showDrawer) {
            ModalBottomSheet(onDismissRequest = { showDrawer = false }, sheetState = sheetState, containerColor = Color(0xFF151A22)) {
                Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
                    Text("选择模型（最新在前）", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    modelList().forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (loadingKey.isEmpty()) {
                                    showDrawer = false
                                    loadModel(entry.key)
                                }
                            }.padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(entry.label, fontSize = 15.sp, color = if (entry.key == modelName) Accent2 else Color.White, fontWeight = if (entry.key == modelName) FontWeight.Bold else FontWeight.Normal)
                                    Spacer(Modifier.width(8.dp))
                                    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF232B38)) {
                                        Text(entry.tag, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 10.sp, color = Muted)
                                    }
                                }
                                if (entry.dateText.isNotEmpty() && entry.tag == "导入") {
                                    Text(entry.dateText, fontSize = 11.sp, color = Muted2)
                                }
                            }
                            if (loadingKey == entry.key) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                            } else if (entry.key == modelName) {
                                Text("✓", fontSize = 16.sp, color = Accent2)
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF232A36)))
                    }
                }
            }
        }

        if (showServerDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showServerDialog = false },
                title = { Text("电脑地址") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = serverAddr,
                            onValueChange = { serverAddr = it },
                            label = { Text("例如 192.168.1.100:8799") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putString(KEY_SERVER, serverAddr).apply()
                        showServerDialog = false
                        addLog("电脑地址设为 " + serverAddr)
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { showServerDialog = false }) { Text("取消") } }
            )
        }
    }
}

fun fmtMs(ms: Int): String {
    val s = ms / 1000
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

object MediaStoreHelper {
    data class Track(val uri: Uri, val name: String)

    fun listTracks(context: Context): List<Track> {
        val out = mutableListOf<Track>()
        try {
            val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME)
            val sel: String?
            val args: Array<String>?
            if (Build.VERSION.SDK_INT >= 29) {
                sel = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?"
                args = arrayOf("Music/DSH语音%")
            } else {
                sel = MediaStore.Audio.Media.DATA + " LIKE ?"
                args = arrayOf("%Music/DSH语音%")
            }
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    out.add(Track(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), name))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun saveWav(context: Context, samples: FloatArray, sampleRate: Int): String? {
        return try {
            val name = "小智_TTS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DSH语音")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out -> writeWav(out, samples, sampleRate) }
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "DSH语音")
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, name)).use { out -> writeWav(out, samples, sampleRate) }
            }
            name
        } catch (e: Throwable) {
            android.util.Log.e("DshVoice", "save wav fail", e)
            null
        }
    }

    fun writeWav(out: java.io.OutputStream, samples: FloatArray, sampleRate: Int) {
        val n = samples.size
        val hdr = ByteArray(44)
        fun putInt(off: Int, v: Int) { hdr[off] = (v and 0xff).toByte(); hdr[off+1] = ((v shr 8) and 0xff).toByte(); hdr[off+2] = ((v shr 16) and 0xff).toByte(); hdr[off+3] = ((v shr 24) and 0xff).toByte() }
        fun putShort(off: Int, v: Int) { hdr[off] = (v and 0xff).toByte(); hdr[off+1] = ((v shr 8) and 0xff).toByte() }
        "RIFF".toByteArray().copyInto(hdr, 0)
        putInt(4, 36 + n * 2)
        "WAVE".toByteArray().copyInto(hdr, 8)
        "fmt ".toByteArray().copyInto(hdr, 12)
        putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, sampleRate); putInt(28, sampleRate * 2)
        putShort(32, 2); putShort(34, 16)
        "data".toByteArray().copyInto(hdr, 36)
        putInt(40, n * 2)
        out.write(hdr)
        val buf = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
            buf[i * 2] = (v and 0xff).toByte()
            buf[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        out.write(buf)
    }

    fun importAudio(context: Context, uri: Uri): String {
        val name = "导入_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DSH语音")
        }
        val outUri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法创建音频文件")
        context.contentResolver.openInputStream(uri)!!.use { inp ->
            context.contentResolver.openOutputStream(outUri)!!.use { out -> inp.copyTo(out) }
        }
        return name
    }
}

object BundledModels {
    fun list(context: Context): List<String> {
        return try {
            (context.assets.list("models") ?: arrayOf()).filter { it.isNotEmpty() }.sorted()
        } catch (e: Exception) { emptyList() }
    }
}

object ImportedModels {
    fun root(context: Context): File {
        val d = File(context.filesDir, "models")
        if (!d.exists()) d.mkdirs()
        return d
    }
    fun dir(context: Context, name: String): File = File(root(context), name)
    fun list(context: Context): List<String> {
        return root(context).listFiles()?.filter { it.isDirectory && File(it, "model.onnx").exists() && !it.name.startsWith("_") }?.map { it.name }?.sorted() ?: emptyList()
    }

    private fun sanitize(raw: String): String {
        var s = raw.replace(Regex("[\\\\/:*?\"<>|\n\r\t]"), "_").trim().trim('.')
        if (s.isEmpty()) s = "导入_模型"
        if (s.length > 40) s = s.take(40)
        return s
    }

    private fun extract(context: Context, input: java.io.InputStream): String {
        val root = root(context)
        val tmp = File(root, "_tmp_import")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()
        var metaName = ""
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val base = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && base in listOf("model.onnx", "tokens.txt", "lexicon.txt", "meta.json")) {
                    File(tmp, base).outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        require(File(tmp, "model.onnx").exists()) { "压缩包里没有 model.onnx" }
        val metaFile = File(tmp, "meta.json")
        if (metaFile.exists()) {
            try {
                val j = JSONObject(metaFile.readText())
                metaName = j.optString("name", "")
            } catch (_: Exception) {}
        }
        val display = if (metaName.isNotEmpty()) metaName else "导入模型"
        val dirName = sanitize(display)
        val target = File(root, dirName)
        if (target.exists()) target.deleteRecursively()
        tmp.renameTo(target)
        return dirName
    }

    fun importZip(context: Context, uri: Uri, preferredName: String = ""): String {
        return context.contentResolver.openInputStream(uri)!!.use { input ->
            val dirName = extract(context, input)
            if (dirName == "导入模型" && preferredName.isNotEmpty()) {
                renameTo(context, dirName, preferredName)
            } else dirName
        }
    }

    fun importZipFile(context: Context, file: File, preferredName: String = ""): String {
        return FileInputStream(file).use { input ->
            val dirName = extract(context, input)
            if (dirName == "导入模型" && preferredName.isNotEmpty()) {
                renameTo(context, dirName, preferredName)
            } else dirName
        }
    }

    private fun renameTo(context: Context, current: String, preferred: String): String {
        val root = root(context)
        val from = File(root, current)
        val to = File(root, sanitize(preferred))
        if (to.exists()) to.deleteRecursively()
        from.renameTo(to)
        return to.name
    }
}

object WavWriter {
    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val n = samples.size
        val bytes = ByteArray(44 + n * 2)
        fun putInt(off: Int, v: Int) {
            bytes[off] = (v and 0xff).toByte()
            bytes[off + 1] = ((v shr 8) and 0xff).toByte()
            bytes[off + 2] = ((v shr 16) and 0xff).toByte()
            bytes[off + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) {
            bytes[off] = (v and 0xff).toByte()
            bytes[off + 1] = ((v shr 8) and 0xff).toByte()
        }
        val riff = "RIFF".toByteArray(); System.arraycopy(riff, 0, bytes, 0, 4)
        putInt(4, 36 + n * 2)
        val wave = "WAVE".toByteArray(); System.arraycopy(wave, 0, bytes, 8, 4)
        val fmt = "fmt ".toByteArray(); System.arraycopy(fmt, 0, bytes, 12, 4)
        putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, sampleRate); putInt(28, sampleRate * 2)
        putShort(32, 2); putShort(34, 16)
        val data = "data".toByteArray(); System.arraycopy(data, 0, bytes, 36, 4)
        putInt(40, n * 2)
        for (i in 0 until n) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767).toInt()
            bytes[44 + i * 2] = (v and 0xff).toByte()
            bytes[45 + i * 2] = ((v shr 8) and 0xff).toByte()
        }
        file.writeBytes(bytes)
    }
}