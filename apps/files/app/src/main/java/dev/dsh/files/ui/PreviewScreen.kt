package dev.dsh.files.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.dsh.files.FileCategory
import dev.dsh.files.FileEntry
import dev.dsh.files.FileTypes
import dev.dsh.files.Openers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class PreviewItem(
    val name: String,
    val uri: Uri,
    val mime: String,
    val size: Long,
)

/** 预览侧边栏（本文件夹内容）里的一个条目。 */
data class PlaylistItem(
    val name: String,
    val size: Long,
    val active: Boolean,
)

/**
 * 播放队列里的一项：
 * - [remotePath] 非空：电脑上的文件，未缓存时需要下载；
 * - [localUri] 非空：已经在手机缓存里（例如从压缩包内临时解出来的）。
 */
data class PreviewQueueItem(
    val name: String,
    val mime: String,
    val size: Long,
    val remotePath: String = "",
    val localUri: String? = null,
)

@Composable
fun FilePreview(
    item: PreviewItem,
    onClose: () -> Unit,
    onOpen: (PreviewItem) -> Unit = {},
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onAutoNext: (() -> Unit)? = null,
    positionText: String? = null,
    playlist: List<PlaylistItem> = emptyList(),
    onPlaylistSelect: ((Int) -> Unit)? = null,
    originNote: String? = null,
    onOpenQueue: ((List<PreviewQueueItem>, Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val category = remember(item) { FileTypes.categoryOf(item.name, item.mime) }
    var dragX by remember(item.uri) { mutableStateOf(0f) }
    var listOpen by remember(item.uri) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Deep, Ink))),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundGlyph("←", onClose)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.name,
                        color = TextMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${fmtSize(item.size)} · ${categoryLabel(category)}" +
                            (positionText?.let { " · $it" } ?: ""),
                        color = Muted,
                        fontSize = 11.sp,
                    )
                }
                if (onPrev != null) {
                    SmallGlyph("‹") { onPrev() }
                    Spacer(Modifier.width(6.dp))
                }
                if (onNext != null) {
                    SmallGlyph("›") { onNext() }
                    Spacer(Modifier.width(6.dp))
                }
                if (onPlaylistSelect != null) {
                    SmallGlyph("☰") { listOpen = true }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    "分享",
                    color = AccentSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable {
                            val ok = Openers.share(context, item.uri, item.mime.ifBlank { "*/*" })
                            if (!ok) {
                                Toast.makeText(context, "没有能分享的应用", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "其他应用",
                    color = AccentSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Edge, RoundedCornerShape(10.dp))
                        .clickable {
                            val ok = Openers.openExternal(context, item.uri, item.mime)
                            if (!ok) {
                                Toast.makeText(context, "没有能打开它的应用", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
            HorizontalDivider(color = Edge, thickness = 1.dp)
            if (originNote != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Accent.copy(alpha = 0.08f))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(originNote, color = AccentSoft, fontSize = 11.sp)
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(onPrev, onNext) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, dragAmount -> dragX += dragAmount },
                            onDragEnd = {
                                if (dragX > 90f) onPrev?.invoke()
                                else if (dragX < -90f) onNext?.invoke()
                                dragX = 0f
                            },
                            onDragCancel = { dragX = 0f },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (category) {
                    FileCategory.IMAGE -> ImagePreview(item)
                    FileCategory.VIDEO -> VideoPreview(item)
                    FileCategory.AUDIO -> AudioPreview(
                        item = item,
                        onPrev = onPrev,
                        onNext = onNext,
                        onAutoNext = onAutoNext,
                        onShowList = if (playlist.isNotEmpty()) ({ listOpen = true }) else null,
                    )
                    FileCategory.TEXT -> TextPreview(item)
                    FileCategory.APK -> ApkPreview(item)
                    FileCategory.ARCHIVE -> ZipPreview(
                        item = item,
                        onOpen = onOpen,
                        onOpenQueue = onOpenQueue,
                    )
                    FileCategory.OTHER -> OtherPreview(item)
                }
            }
        }

        // 侧边栏：当前文件夹 / 列表里的文件，点一下直接切过去
        if (listOpen && playlist.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable { listOpen = false },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(320.dp)
                        .align(Alignment.CenterStart)
                        .background(Color(0xF20D1522))
                        .border(1.dp, Edge)
                        .padding(top = 16.dp, bottom = 12.dp)
                        .clickable { },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "播放列表",
                                color = TextMain,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${playlist.size} 项 · 点一下切换",
                                color = Muted,
                                fontSize = 11.sp,
                            )
                        }
                        Text(
                            "✕",
                            color = Muted,
                            fontSize = 16.sp,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { listOpen = false }
                                .padding(8.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = Edge, thickness = 1.dp)
                    LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(playlist) { index, entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (entry.active) Accent.copy(alpha = 0.16f) else Color.Transparent,
                                    )
                                    .clickable {
                                        listOpen = false
                                        onPlaylistSelect?.invoke(index)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (entry.active) {
                                    Text("▶", fontSize = 12.sp, color = AccentSoft)
                                    Spacer(Modifier.width(8.dp))
                                }
                                FileBadge(
                                    visualFor(
                                        FileEntry(
                                            name = entry.name,
                                            dir = false,
                                            size = entry.size,
                                            mtime = 0L,
                                        ),
                                    ),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.name,
                                        color = if (entry.active) AccentSoft else TextMain,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        fmtSize(entry.size),
                                        color = Muted,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreview(item: PreviewItem) {
    val context = LocalContext.current
    var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.uri) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { loadBitmap(context, item.uri) }.getOrNull()
        }
        bitmap = loaded
        if (loaded == null) error = "图片解码失败"
    }
    when {
        error != null -> Text(error!!, color = Color(0xFFFF9A9A), fontSize = 13.sp)
        bitmap == null -> CircularProgressIndicator(color = AccentSoft)
        else -> Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        )
    }
}

@Composable
private fun VideoPreview(item: PreviewItem) {
    val context = LocalContext.current
    DisposableEffect(item.uri) {
        onDispose { }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(item.uri)
                val controller = MediaController(ctx)
                controller.setAnchorView(this)
                setMediaController(controller)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(ctx, "视频无法播放，试试「其他应用」", Toast.LENGTH_LONG).show()
                    true
                }
            }
        },
    )
}

private enum class LoopMode(val label: String) {
    LIST("列表循环"),
    SINGLE("单曲循环"),
    OFF("不循环"),
}

@Composable
private fun AudioPreview(
    item: PreviewItem,
    onPrev: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onAutoNext: (() -> Unit)? = null,
    onShowList: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var player by remember(item.uri) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(item.uri) { mutableStateOf(false) }
    var position by remember(item.uri) { mutableStateOf(0) }
    var duration by remember(item.uri) { mutableStateOf(0) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    var speed by remember(item.uri) { mutableStateOf(1.0f) }
    var loopMode by remember { mutableStateOf(LoopMode.LIST) }
    var sliderValue by remember(item.uri) { mutableStateOf(0f) }
    var dragging by remember(item.uri) { mutableStateOf(false) }

    fun applySpeed(mp: MediaPlayer, value: Float) {
        runCatching {
            val params = android.media.PlaybackParams().setSpeed(value)
            mp.playbackParams = params
        }
    }

    fun seekToFraction(fraction: Float) {
        val mp = player ?: return
        if (duration <= 0) return
        val target = (fraction.coerceIn(0f, 1f) * duration).toInt()
        mp.seekTo(target)
        position = target
    }

    DisposableEffect(item.uri) {
        onDispose {
            runCatching { player?.release() }
            player = null
        }
    }
    LaunchedEffect(item.uri) {
        try {
            val mp = MediaPlayer()
            context.contentResolver.openAssetFileDescriptor(item.uri, "r")?.use { afd ->
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            } ?: throw IllegalStateException("无法打开音频")
            mp.prepare()
            mp.setOnCompletionListener {
                when (loopMode) {
                    LoopMode.SINGLE -> runCatching {
                        mp.seekTo(0)
                        mp.start()
                    }
                    LoopMode.LIST -> {
                        if (onAutoNext != null) onAutoNext.invoke() else playing = false
                    }
                    LoopMode.OFF -> playing = false
                }
            }
            player = mp
            duration = mp.duration.coerceAtLeast(0)
            applySpeed(mp, speed)
            // 点开音频即播放；自动连播下一首时也会继续播
            mp.start()
            playing = true
        } catch (t: Throwable) {
            error = "音频打开失败：${t.message ?: t.javaClass.simpleName}"
        }
    }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { position = it.currentPosition }
            delay(300)
        }
    }
    LaunchedEffect(position, duration, dragging) {
        if (!dragging && duration > 0) {
            sliderValue = (position.toFloat() / duration).coerceIn(0f, 1f)
        }
    }
    if (error != null) {
        Text(error!!, color = Color(0xFFFF9A9A), fontSize = 13.sp)
        return
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
    ) {
        Text("🎵", fontSize = 52.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            item.name,
            color = TextMain,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(14.dp))
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                dragging = true
                sliderValue = value
            },
            onValueChangeFinished = {
                dragging = false
                seekToFraction(sliderValue)
            },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = AccentSoft,
                activeTrackColor = Accent,
                inactiveTrackColor = PanelHi,
            ),
            modifier = Modifier.fillMaxWidth().height(26.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmtTimeMs(position), color = Muted, fontSize = 11.sp)
            Text(fmtTimeMs(duration), color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            MediaControl("⏮", enabled = onPrev != null) { onPrev?.invoke() }
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Accent, Color(0xFF5B8CFF))))
                    .clickable {
                        val mp = player ?: return@clickable
                        if (mp.isPlaying) {
                            mp.pause()
                            playing = false
                        } else {
                            mp.start()
                            playing = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (playing) "⏸" else "▶", color = Color.White, fontSize = 25.sp)
            }
            MediaControl("⏭", enabled = onNext != null) { onNext?.invoke() }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { value ->
                SelectChip(
                    label = if (value == 1.0f) "1.0x" else "${value}x",
                    selected = speed == value,
                ) {
                    speed = value
                    player?.let { applySpeed(it, value) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(LoopMode.LIST, LoopMode.SINGLE, LoopMode.OFF).forEach { mode ->
                SelectChip(label = mode.label, selected = loopMode == mode) { loopMode = mode }
            }
        }
        if (onShowList != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "☰ 播放列表",
                color = AccentSoft,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Edge, RoundedCornerShape(10.dp))
                    .clickable(onClick = onShowList)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun MediaControl(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        glyph,
        color = if (enabled) TextMain else Color(0xFF3E5470),
        fontSize = 24.sp,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(10.dp),
    )
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else AccentSoft,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Accent else Panel)
            .border(1.dp, if (selected) Accent else Edge, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun TextPreview(item: PreviewItem) {
    val context = LocalContext.current
    var text by remember(item.uri) { mutableStateOf<String?>(null) }
    var error by remember(item.uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.uri) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching { readText(context, item.uri) }.getOrNull()
        }
        text = loaded
        if (loaded == null) error = "读取文本失败"
    }
    when {
        error != null -> Text(error!!, color = Color(0xFFFF9A9A), fontSize = 13.sp)
        text == null -> CircularProgressIndicator(color = AccentSoft)
        else -> SelectionContainer {
            Text(
                text = text!!,
                color = TextMain,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 19.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
            )
        }
    }
}

@Composable
private fun ApkPreview(item: PreviewItem) {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(28.dp),
    ) {
        Text("📦", fontSize = 56.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            item.name,
            color = TextMain,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text("${fmtSize(item.size)} · 安卓安装包", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(22.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.horizontalGradient(listOf(Accent, Color(0xFF5B8CFF))))
                .clickable(enabled = !busy) {
                    busy = true
                    val err = Openers.installApk(context, item.uri, item.name)
                    busy = false
                    if (err != null) message = err
                },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text("安装", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message!!, color = Color(0xFFFFCD9A), fontSize = 12.sp)
        }
    }
}

@Composable
private fun OtherPreview(item: PreviewItem) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(28.dp),
    ) {
        Text(visualFor(FileEntryPreview(item)).glyph, fontSize = 56.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            item.name,
            color = TextMain,
            fontSize = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(fmtSize(item.size), color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        Text(
            "这个 App 里不能直接预览，可以交给其他应用打开",
            color = Color(0xFF6F8299),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Panel)
                .border(1.dp, Edge, RoundedCornerShape(14.dp))
                .clickable {
                    val ok = Openers.openExternal(context, item.uri, item.mime)
                    if (!ok) {
                        Toast.makeText(context, "没有能打开它的应用", Toast.LENGTH_SHORT).show()
                    }
                }
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            Text("用其他应用打开", color = AccentSoft, fontSize = 14.sp)
        }
    }
}

// ---------------------------------------------------------------- 小工具

@Composable
private fun RoundGlyph(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Panel)
            .border(1.dp, Edge, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = TextMain, fontSize = 17.sp)
    }
}

@Composable
private fun SmallGlyph(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Panel)
            .border(1.dp, Edge, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = AccentSoft, fontSize = 18.sp)
    }
}

private fun FileEntryPreview(item: PreviewItem) = dev.dsh.files.FileEntry(
    name = item.name,
    dir = false,
    size = item.size,
    mtime = 0L,
)

private fun categoryLabel(category: FileCategory): String = when (category) {
    FileCategory.IMAGE -> "图片"
    FileCategory.VIDEO -> "视频"
    FileCategory.AUDIO -> "音频"
    FileCategory.TEXT -> "文本"
    FileCategory.APK -> "安装包"
    FileCategory.ARCHIVE -> "压缩包"
    FileCategory.OTHER -> "文件"
}

private fun fmtTimeMs(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (t: Throwable) {
        android.util.Log.e("DSHFiles", "open image failed: $uri", t)
        null
    } ?: return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth > 0 && bounds.outHeight > 0 &&
            (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048)
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (t: Throwable) {
        android.util.Log.e("DSHFiles", "decode image failed: $uri", t)
        null
    }
}

private fun readText(context: Context, uri: Uri, limit: Int = 400_000): String? {
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        stream.bufferedReader().use { reader ->
            val builder = StringBuilder()
            val buffer = CharArray(8192)
            var total = 0
            while (total < limit) {
                val count = reader.read(buffer)
                if (count <= 0) break
                val take = minOf(count, limit - total)
                builder.append(buffer, 0, take)
                total += take
            }
            builder.toString()
        }
    }
}
