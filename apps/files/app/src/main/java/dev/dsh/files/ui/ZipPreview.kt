package dev.dsh.files.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.dsh.files.Archive
import dev.dsh.files.ArchiveEntryInfo
import dev.dsh.files.FileCategory
import dev.dsh.files.FileTypes
import dev.dsh.files.Openers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ZipPreview(
    item: PreviewItem,
    onOpen: (PreviewItem) -> Unit,
    onOpenQueue: ((List<PreviewQueueItem>, Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var archiveFile by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<ArchiveEntryInfo>>(emptyList()) }
    var currentDir by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var extractedDir by remember { mutableStateOf<File?>(null) }
    var opening by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(false) }

    if (!Archive.isZip(item.name, item.mime)) {
        UnsupportedArchive(item)
        return
    }

    LaunchedEffect(item.uri) {
        try {
            val file = Archive.copyToCache(context, item.uri)
            archiveFile = file
            entries = withContext(Dispatchers.IO) { Archive.list(file) }
        } catch (t: Throwable) {
            error = t.message ?: "读取压缩包失败"
        }
    }

    fun openEntry(path: String) {
        val file = archiveFile ?: return
        android.util.Log.d("DSHFiles", "zip openEntry: $path")
        scope.launch {
            opening = path
            try {
                val category = FileTypes.categoryOf(path, FileTypes.mimeOf(path))
                val queueable = category == FileCategory.AUDIO ||
                    category == FileCategory.VIDEO ||
                    category == FileCategory.IMAGE ||
                    category == FileCategory.TEXT
                val siblings = entries.filter { entry ->
                    entry.path.startsWith(currentDir) &&
                        !entry.path.removePrefix(currentDir).contains('/') &&
                        FileTypes.categoryOf(entry.path, FileTypes.mimeOf(entry.path)) == category
                }
                if (queueable && onOpenQueue != null && siblings.size in 2..40) {
                    // 同一类文件较多时，预解压到缓存组成播放列表（点开即播、可连播/侧滑）
                    preparing = true
                    val queue = ArrayList<PreviewQueueItem>(siblings.size)
                    siblings.forEach { entry ->
                        val extracted = withContext(Dispatchers.IO) {
                            Archive.extractEntry(context, file, entry.path)
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            context.packageName + ".updates",
                            extracted,
                        )
                        queue.add(
                            PreviewQueueItem(
                                name = entry.name,
                                mime = FileTypes.mimeOf(entry.name),
                                size = entry.size,
                                localUri = uri.toString(),
                            ),
                        )
                    }
                    val index = siblings.indexOfFirst { it.path == path }.coerceAtLeast(0)
                    onOpenQueue.invoke(queue, index)
                } else {
                    val extracted = withContext(Dispatchers.IO) {
                        Archive.extractEntry(context, file, path)
                    }
                    val name = path.substringAfterLast('/')
                    val uri = FileProvider.getUriForFile(
                        context,
                        context.packageName + ".updates",
                        extracted,
                    )
                    android.util.Log.d("DSHFiles", "zip extracted: ${extracted.absolutePath} -> $uri")
                    onOpen(
                        PreviewItem(
                            name = name,
                            uri = uri,
                            mime = FileTypes.mimeOf(name),
                            size = extracted.length(),
                        ),
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.e("DSHFiles", "zip extract failed: $path", t)
                Toast.makeText(context, "解压失败：${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                opening = null
                preparing = false
            }
        }
    }

    /** 从压缩包里解出一个文件后直接走系统分享（微信/QQ 等）。 */
    fun shareEntry(path: String) {
        val file = archiveFile ?: return
        scope.launch {
            try {
                val extracted = withContext(Dispatchers.IO) {
                    Archive.extractEntry(context, file, path)
                }
                val name = path.substringAfterLast('/')
                val ok = Openers.shareFile(context, extracted, FileTypes.mimeOf(name))
                if (!ok) Toast.makeText(context, "没有能分享的应用", Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(context, "分享失败：${t.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun extractAll() {
        val file = archiveFile ?: return
        scope.launch {
            progress = 0
            try {
                extractedDir = withContext(Dispatchers.IO) {
                    Archive.extractAll(context, file, item.name) { percent -> progress = percent }
                }
            } catch (t: Throwable) {
                error = t.message ?: "解压失败"
            } finally {
                progress = null
            }
        }
    }

    if (extractedDir != null) {
        ExtractedList(
            folder = extractedDir!!,
            onOpen = onOpen,
            onBack = { extractedDir = null },
        )
    } else {
        when {
            error != null -> ArchiveError(item, error!!) { error = null }
            preparing -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentSoft)
                    Spacer(Modifier.height(12.dp))
                    Text("正在准备播放列表…", color = Muted, fontSize = 13.sp)
                }
            }
            archiveFile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentSoft)
                    Spacer(Modifier.height(12.dp))
                    Text("正在读取压缩包…", color = Muted, fontSize = 13.sp)
                }
            }
            progress != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth(0.7f),
                ) {
                    Text("正在解压全部…", color = TextMain, fontSize = 14.sp)
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier.fillMaxWidth().height(5.dp)
                            .clip(RoundedCornerShape(3.dp)).background(PanelHi),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(((progress ?: 0) / 100f).coerceIn(0f, 1f))
                                .height(5.dp)
                                .background(Brush.horizontalGradient(listOf(Accent, AccentSoft))),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${progress ?: 0}%", color = AccentSoft, fontSize = 12.sp)
                }
            }
            else -> BrowseArchive(
                item = item,
                entries = entries,
                currentDir = currentDir,
                opening = opening,
                onNavigate = { currentDir = it },
                onOpenEntry = { openEntry(it) },
                onShareEntry = { shareEntry(it) },
                onExtractAll = { extractAll() },
            )
        }
    }
}

@Composable
private fun BrowseArchive(
    item: PreviewItem,
    entries: List<ArchiveEntryInfo>,
    currentDir: String,
    opening: String?,
    onNavigate: (String) -> Unit,
    onOpenEntry: (String) -> Unit,
    onShareEntry: (String) -> Unit,
    onExtractAll: () -> Unit,
) {
    val inDir = entries.filter { it.path.startsWith(currentDir) }
    val directFiles = inDir.filter {
        val rest = it.path.removePrefix(currentDir)
        rest.isNotEmpty() && !rest.contains('/')
    }
    val dirs = inDir.mapNotNull {
        val rest = it.path.removePrefix(currentDir)
        if (rest.contains('/')) rest.substringBefore('/') else null
    }.distinct().sorted()

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${entries.size} 个文件 · ${dirs.size} 个文件夹",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Accent)
                    .clickable(onClick = onExtractAll)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("解压全部", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "压缩包",
                color = AccentSoft,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Panel)
                    .clickable { onNavigate("") }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
            var acc = ""
            currentDir.split('/').filter { it.isNotBlank() }.forEach { segment ->
                acc = if (acc.isBlank()) "$segment/" else "$acc$segment/"
                val target = acc
                Text(" / ", color = Color(0xFF3E5470), fontSize = 12.sp)
                Text(
                    segment,
                    color = AccentSoft,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Panel)
                        .clickable { onNavigate(target) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        Text(
            "不解压也能试听：点音频会临时缓存到 App 内播放，退出后自动清理；要保存到手机点「解压全部」。",
            color = Color(0xFF6F8299),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(dirs) { dir ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(Panel.copy(alpha = 0.92f))
                        .border(1.dp, Edge, RoundedCornerShape(15.dp))
                        .clickable { onNavigate(currentDir + dir + "/") }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileBadge(FileVisual("📁", Amber))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        dir + "/",
                        color = TextMain,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text("›", color = Color(0xFF5D7A99), fontSize = 22.sp)
                }
            }
            items(directFiles) { entry ->
                val visual = visualFor(
                    dev.dsh.files.FileEntry(
                        name = entry.name,
                        dir = false,
                        size = entry.size,
                        mtime = 0L,
                    ),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(Panel.copy(alpha = 0.92f))
                        .border(1.dp, Edge, RoundedCornerShape(15.dp))
                        .clickable(enabled = opening != entry.path) { onOpenEntry(entry.path) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileBadge(visual)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            color = TextMain,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            fmtSize(entry.size),
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    }
                    if (opening == entry.path) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AccentSoft,
                        )
                    } else {
                        Text(
                            "分享",
                            color = AccentSoft,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, Edge, RoundedCornerShape(8.dp))
                                .clickable { onShareEntry(entry.path) }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(fileActionGlyph(entry.name), color = AccentSoft, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtractedList(
    folder: File,
    onOpen: (PreviewItem) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val files = remember(folder) {
        runCatching {
            folder.walkTopDown()
                .filter { it.isFile }
                .take(800)
                .map { file ->
                    val rel = file.absolutePath
                        .removePrefix(folder.absolutePath)
                        .trimStart(File.separatorChar)
                    rel to file
                }
                .toList()
        }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("已解压 ${files.size} 个文件", color = TextMain, fontSize = 14.sp)
                Text(
                    "位置：${folder.absolutePath}",
                    color = Muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "返回",
                color = AccentSoft,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, Edge, RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(files) { pair ->
                val (rel, file) = pair
                val name = file.name
                val visual = visualFor(
                    dev.dsh.files.FileEntry(name = name, dir = false, size = file.length(), mtime = 0L),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(Panel.copy(alpha = 0.92f))
                        .border(1.dp, Edge, RoundedCornerShape(15.dp))
                        .clickable {
                            runCatching {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    context.packageName + ".updates",
                                    file,
                                )
                                onOpen(
                                    PreviewItem(
                                        name = name,
                                        uri = uri,
                                        mime = FileTypes.mimeOf(name),
                                        size = file.length(),
                                    ),
                                )
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileBadge(visual)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            rel,
                            color = TextMain,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text("${fmtSize(file.length())} · 点开预览", color = Muted, fontSize = 11.sp)
                    }
                    Text(
                        "分享",
                        color = AccentSoft,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Edge, RoundedCornerShape(8.dp))
                            .clickable {
                                val ok = Openers.shareFile(context, file, FileTypes.mimeOf(name))
                                if (!ok) Toast.makeText(context, "没有能分享的应用", Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(fileActionGlyph(name), color = AccentSoft, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun UnsupportedArchive(item: PreviewItem) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🗜", fontSize = 56.sp)
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
        Spacer(Modifier.height(16.dp))
        Text(
            "RAR / 7Z 等格式暂不支持 App 内解压，可以交给其他应用打开",
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

@Composable
private fun ArchiveError(item: PreviewItem, message: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("😕", fontSize = 40.sp)
        Spacer(Modifier.height(12.dp))
        Text(message, color = Color(0xFFFF9A9A), fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        TextButton(onClick = {
            onDismiss()
            Openers.openExternal(context, item.uri, item.mime)
        }) {
            Text("用其他应用打开", color = AccentSoft)
        }
    }
}

/** 压缩包内 / 解压后的文件按类型显示对应动作图标，避免所有文件都长成「播放」。 */
private fun fileActionGlyph(name: String): String = when (
    FileTypes.categoryOf(name, FileTypes.mimeOf(name))
) {
    FileCategory.AUDIO, FileCategory.VIDEO -> "▶"
    FileCategory.IMAGE -> "👁"
    FileCategory.TEXT -> "📄"
    FileCategory.APK -> "📲"
    FileCategory.ARCHIVE -> "📦"
    FileCategory.OTHER -> "↗"
}
