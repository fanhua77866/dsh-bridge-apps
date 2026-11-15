package dev.dsh.recorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.dsh.recorder.ui.AppTheme
import dev.dsh.recorder.ui.Accent
import dev.dsh.recorder.ui.AccentSoft
import dev.dsh.recorder.ui.Edge
import dev.dsh.recorder.ui.Ink
import dev.dsh.recorder.ui.Muted
import dev.dsh.recorder.ui.Panel
import dev.dsh.recorder.ui.ProjectDetailScreen
import dev.dsh.recorder.ui.ProjectListScreen
import dev.dsh.recorder.ui.RecordScreen
import dev.dsh.recorder.ui.SettingsDialog
import dev.dsh.recorder.ui.TextMain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme { AppRoot() }
        }
    }
}

sealed class Screen {
    data object Projects : Screen()
    data class ProjectDetail(val id: String) : Screen()
    data class Record(val id: String, val startIndex: Int) : Screen()
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }
    val projects = remember { androidx.compose.runtime.mutableStateListOf<Project>() }
    LaunchedEffect(Unit) { projects.addAll(ProjectStore.load(context)) }
    var screen by remember { mutableStateOf<Screen>(Screen.Projects) }
    var showSettings by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<Uploader.UpdateInfo?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var dataVersion by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        if (!ok) toast(context, "没有麦克风权限，无法录音")
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 默认地址 + 默认 PIN：首次启动静默尝试直连（家里 LAN / 外面 Tailscale 各试一次）
    LaunchedEffect(Unit) {
        if (!prefs.paired && prefs.lastPin.length == 6) {
            val candidates = listOf(prefs.base, prefs.altBase)
                .filter { it.isNotBlank() }
                .distinct()
            for (candidate in candidates) {
                val token = runCatching {
                    withContext(Dispatchers.IO) { Uploader.pair(candidate, prefs.lastPin) }
                }.getOrNull()
                if (token != null) {
                    prefs.base = candidate
                    prefs.altBase = candidates.firstOrNull { it != candidate } ?: ""
                    prefs.token = token
                    toast(context, "已自动连接电脑：$candidate")
                    break
                }
            }
        }
    }

    val activity = context as? Activity
    LaunchedEffect(screen) {
        if (screen is Screen.Record) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    fun reload() {
        val fresh = ProjectStore.load(context)
        projects.clear()
        projects.addAll(fresh)
        dataVersion++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink)
            .systemBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (!granted) {
            PermissionScreen { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        } else when (val s = screen) {
        is Screen.Projects -> ProjectListScreen(
            projects = projects,
            onNewProject = { name ->
                val p = ProjectStore.create(context, projects, name)
                reload()
                screen = Screen.ProjectDetail(p.id)
            },
            onOpenProject = { screen = Screen.ProjectDetail(it) },
            onRename = { id, name ->
                ProjectStore.rename(context, projects, id, name)
                reload()
            },
            onDelete = { id ->
                ProjectStore.deleteProject(context, projects, id)
                reload()
            },
            onSettings = { showSettings = true },
            onCheckUpdate = { showSettings = false; startUpdateCheck(context, prefs.base, scope) { updateInfo = it } },
            versionLabel = BuildConfig.VERSION_NAME,
        )

        is Screen.ProjectDetail -> {
            val project = projects.firstOrNull { it.id == s.id }
            if (project == null) {
                LaunchedEffect(Unit) { screen = Screen.Projects }
            } else {
                ProjectDetailScreen(
                    project = project,
                    dataVersion = dataVersion,
                    onBack = { screen = Screen.Projects },
                    onImportTexts = { lines ->
                        val added = ProjectStore.addSentences(context, projects, project.id, lines)
                        reload()
                        toast(context, "导入 $added 句")
                    },
                    onAddSentence = { text ->
                        ProjectStore.addSentence(context, projects, project.id, text)
                        reload()
                    },
                    onStartRecord = { index -> screen = Screen.Record(project.id, index) },
                    onPlay = { sentence ->
                        Player.play(context, ProjectStore.fileFor(context, project.id, sentence))
                    },
                    onSettings = { showSettings = true },
                    onUpload = { uploadProject(context, prefs, project, scope) },
                    onShareScript = { shareScript(context, project) },
                    onCheckUpdate = { showSettings = false; startUpdateCheck(context, prefs.base, scope) { updateInfo = it } },
                    onRenameProject = { name ->
                        ProjectStore.rename(context, projects, project.id, name)
                        reload()
                        toast(context, "已重命名")
                    },
                    onDeleteProject = {
                        ProjectStore.deleteProject(context, projects, project.id)
                        reload()
                        screen = Screen.Projects
                        toast(context, "项目已删除")
                    },
                    onEditSentence = { sentence, text ->
                        ProjectStore.updateSentenceText(context, projects, project.id, sentence.id, text)
                        reload()
                    },
                    onDeleteSentence = { sentence ->
                        ProjectStore.deleteSentence(context, projects, project.id, sentence.id)
                        reload()
                    },
                )
            }
        }

        is Screen.Record -> {
            val project = projects.firstOrNull { it.id == s.id }
            if (project == null) {
                LaunchedEffect(Unit) { screen = Screen.Projects }
            } else {
                RecordScreen(
                    project = project,
                    startIndex = s.startIndex,
                    prefs = prefs,
                    onSaved = { sentenceId, file, durationMs, sampleRate, deviceName, chars, rate ->
                        ProjectStore.markRecorded(
                            context = context,
                            projects = projects,
                            projectId = project.id,
                            sentenceId = sentenceId,
                            file = file,
                            durationMs = durationMs,
                            sampleRate = sampleRate,
                            deviceName = deviceName,
                            chars = chars,
                            rate = rate,
                        )
                        reload()
                    },
                    onClearTake = { sentenceId ->
                        ProjectStore.clearRecording(context, projects, project.id, sentenceId)
                        reload()
                    },
                    onBack = { screen = Screen.ProjectDetail(project.id) },
                )
            }
        }
    }
    }

    if (showSettings) {
        SettingsDialog(
            prefs = prefs,
            onClose = { showSettings = false },
            onCheckUpdate = {
                showSettings = false
                startUpdateCheck(context, prefs.base, scope) { updateInfo = it }
            },
        )
    }

    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!updateBusy) updateInfo = null },
            title = { Text("发现新版本 v${info.versionName}") },
            text = {
                Text(
                    if (updateBusy) "正在下载并校验 v${info.versionName}…" else "当前 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）→ v${info.versionName}（${info.versionCode}），下载后请确认安装器版本",
                )
            },
            confirmButton = {
                TextButton(enabled = !updateBusy, onClick = {
                    if (prefs.base.isBlank()) {
                        toast(context, "请先在设置里填写电脑地址")
                        updateInfo = null
                        return@TextButton
                    }
                    updateBusy = true
                    scope.launch {
                        try {
                            val apk = withContext(Dispatchers.IO) {
                                Uploader.downloadApk(context, prefs.base, context.cacheDir, info.versionCode)
                            }
                            toast(context, "已下载 v${info.versionName}（${info.versionCode}），请在安装器里确认版本后安装")
                            val err = Uploader.installApk(context, apk)
                            if (err != null) toast(context, err)
                            updateInfo = null
                        } catch (t: Throwable) {
                            toast(context, "更新失败：${t.message}")
                            updateInfo = null
                        } finally {
                            updateBusy = false
                        }
                    }
                }) { Text("下载并安装") }
            },
            dismissButton = {
                TextButton(enabled = !updateBusy, onClick = { updateInfo = null }) { Text("以后再说") }
            },
        )
    }
}

// ---------------------------------------------------------------- 辅助

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun startUpdateCheck(
    context: Context,
    base: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onFound: (Uploader.UpdateInfo?) -> Unit,
) {
    if (base.isBlank()) {
        toast(context, "请先在设置里填写电脑地址")
        onFound(null)
        return
    }
    scope.launch {
        val info = withContext(Dispatchers.IO) { Uploader.checkUpdate(base) }
        if (info == null) {
            toast(context, "已是最新版，或连不上电脑")
            onFound(null)
        } else if (info.versionCode <= BuildConfig.VERSION_CODE) {
            toast(context, "已是最新版 v${BuildConfig.VERSION_NAME}")
            onFound(null)
        } else {
            onFound(info)
        }
    }
}

private fun uploadProject(
    context: Context,
    prefs: Prefs,
    project: Project,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (!prefs.paired) {
        toast(context, "先在设置里填电脑地址 + PIN 连接一次")
        return
    }
    scope.launch {
        try {
            val saved = withContext(Dispatchers.IO) {
                val zip = ProjectStore.zipProject(context, project)
                Uploader.upload(prefs.base, prefs.token, prefs.uploadDir, zip.name, zip)
            }
            toast(context, if (saved.isBlank()) "已上传到电脑" else "已上传到：$saved")
        } catch (t: Throwable) {
            // 主地址连不上时自动换备用地址（同一 token 跨 host 有效）
            val alternate = prefs.altBase
            if (alternate.isNotBlank() && alternate != prefs.base) {
                val saved = runCatching {
                    withContext(Dispatchers.IO) {
                        val zip = ProjectStore.zipProject(context, project)
                        Uploader.upload(alternate, prefs.token, prefs.uploadDir, zip.name, zip)
                    }
                }.getOrNull()
                if (saved != null) {
                    val old = prefs.base
                    prefs.base = alternate
                    prefs.altBase = old
                    toast(context, if (saved.isBlank()) "已通过备用地址上传到电脑" else "已上传到：$saved")
                    return@launch
                }
            }
            toast(context, "上传失败：${t.message}")
        }
    }
}

private fun shareScript(context: Context, project: Project) {
    runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val name = project.name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").take(40).ifBlank { "script" }
        val file = File(dir, "$name.txt")
        file.writeText(ProjectStore.scriptText(project))
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("剧本", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "导出/分享逐字稿").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { toast(context, "导出失败：${it.message}") }
}

private object Player {
    private var player: MediaPlayer? = null

    fun play(context: Context, file: File) {
        if (!file.isFile) {
            toast(context, "录音文件不存在")
            return
        }
        runCatching { player?.release() }
        player = null
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { media ->
                    media.release()
                    if (player === media) player = null
                }
                start()
                player = this
            }
            toast(context, "播放：${file.name}")
        }.onFailure { toast(context, "播放失败：${it.message}") }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF132033), Ink))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🎙", fontSize = 46.sp)
            Spacer(Modifier.height(14.dp))
            Text(
                "需要麦克风权限",
                color = TextMain,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text("DSH 录音要用麦克风录制语音素材", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(1.dp, Edge, RoundedCornerShape(14.dp))
                    .clickable(onClick = onRequest)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Text("授权麦克风", color = AccentSoft, fontSize = 14.sp)
            }
        }
    }
}
