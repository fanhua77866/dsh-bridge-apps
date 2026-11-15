package dev.dsh.files.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.dsh.files.Api
import dev.dsh.files.ApiException
import dev.dsh.files.BuildConfig
import dev.dsh.files.DownloadHistory
import dev.dsh.files.DownloadedFile
import dev.dsh.files.Downloads
import dev.dsh.files.FileCategory
import dev.dsh.files.FileEntry
import dev.dsh.files.FileTypes
import dev.dsh.files.InboxItem
import dev.dsh.files.Listing
import dev.dsh.files.Prefs
import dev.dsh.files.PushService
import dev.dsh.files.RecentResult
import dev.dsh.files.RootEntry
import dev.dsh.files.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class DownloadState(
    val name: String,
    val percent: Int = 0,
    val done: Boolean = false,
    val uri: String? = null,
    val mime: String? = null,
    val size: Long = 0L,
)

/** 预览队列里的一项：同一文件夹 / 列表里的相邻文件。 */

@Composable
fun BrowserScreen(prefs: Prefs, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(prefs.base) }
    val token = prefs.token

    fun hostLabel(url: String): String = runCatching {
        java.net.URL(Api.trimBase(url)).host
    }.getOrDefault(url)

    fun routeName(url: String): String =
        if (hostLabel(url).startsWith("100.")) "外网" else "局域网"

    var path by remember { mutableStateOf<String?>(null) }
    var listing by remember { mutableStateOf<Listing?>(null) }
    var roots by remember { mutableStateOf<List<RootEntry>>(emptyList()) }
    var recents by remember { mutableStateOf<RecentResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var authLost by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<DownloadState?>(null) }
    var inbox by remember { mutableStateOf<List<InboxItem>>(emptyList()) }
    var downloaded by remember { mutableStateOf<List<DownloadedFile>>(emptyList()) }
    // 手机里已保存过的文件索引：文件名 + 大小 → 下载记录（用于把「下载」按钮变成「打开」）
    val downloadedIndex = remember(downloaded) {
        downloaded.associateBy { it.name + "\u0000" + it.size }
    }

    fun downloadedFor(name: String, size: Long, mtime: Long): DownloadedFile? {
        val matched = downloadedIndex[name + "\u0000" + size] ?: return null
        // 电脑上的文件比手机里保存的新 → 内容可能变了，仍然显示下载
        if (mtime > 0L && matched.time > 0L && mtime * 1000L > matched.time + 2000L) return null
        return matched
    }
    var preview by remember { mutableStateOf<PreviewItem?>(null) }
    var previewBack by remember { mutableStateOf<List<PreviewItem>>(emptyList()) }
    // 预览队列：只放同类文件（wav 连 wav），zip/apk 这类容器不进播放队列
    var previewQueue by remember { mutableStateOf<List<PreviewQueueItem>>(emptyList()) }
    var previewIndex by remember { mutableStateOf(0) }
    var pendingPreviewIndex by remember { mutableStateOf<Int?>(null) }
    var previewOriginNote by remember { mutableStateOf<String?>(null) }
    var pushEnabled by remember { mutableStateOf(prefs.pushEnabled) }
    var updateInfo by remember { mutableStateOf<Updater.Info?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var sendUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }

    /** 当前地址连不上时，自动试备用地址；成功则把两者对调（下次回家还能自动切回来）。 */
    suspend fun switchToAlternateBase(): Boolean {
        val alternate = prefs.altBase
        if (alternate.isBlank()) return false
        if (Api.trimBase(alternate) == Api.trimBase(base)) return false
        val ok = runCatching {
            withContext(Dispatchers.IO) { Api.roots(alternate, token) }
        }.isSuccess
        if (!ok) return false
        val old = base
        base = alternate
        prefs.base = alternate
        prefs.altBase = old
        PushService.start(context)
        Toast.makeText(
            context,
            "已切到${routeName(alternate)}：${hostLabel(alternate)}",
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    fun load(target: String?) {
        loading = true
        error = null
        scope.launch {
            var attempt = 0
            while (true) {
                try {
                    if (target.isNullOrBlank()) {
                        val rootList = withContext(Dispatchers.IO) { Api.roots(base, token) }
                        val recentList = withContext(Dispatchers.IO) { Api.recent(base, token, 30) }
                        val inboxRes = runCatching {
                            withContext(Dispatchers.IO) { Api.inbox(base, token, null) }
                        }.getOrNull()
                        roots = rootList
                        recents = recentList
                        inbox = inboxRes?.items.orEmpty()
                        downloaded = DownloadHistory.list(context)
                        listing = null
                        path = null
                    } else {
                        listing = withContext(Dispatchers.IO) { Api.list(base, token, target) }
                        path = target
                    }
                    loading = false
                    return@launch
                } catch (e: ApiException) {
                    if (!e.authLost && attempt == 0 && switchToAlternateBase()) {
                        attempt = 1
                        continue
                    }
                    error = e.message
                    authLost = e.authLost
                    loading = false
                    return@launch
                } catch (t: Throwable) {
                    if (attempt == 0 && switchToAlternateBase()) {
                        attempt = 1
                        continue
                    }
                    error = "加载失败：${t.message ?: "网络错误"}"
                    loading = false
                    return@launch
                }
            }
        }
    }

    fun switchRouteManually() {
        val alternate = prefs.altBase
        if (alternate.isBlank()) {
            Toast.makeText(context, "没有备用地址，请到「断开连接」页填写", Toast.LENGTH_SHORT).show()
            return
        }
        val old = base
        base = alternate
        prefs.base = alternate
        prefs.altBase = old
        Toast.makeText(
            context,
            "切到${routeName(alternate)}：${hostLabel(alternate)}",
            Toast.LENGTH_SHORT,
        ).show()
        load(path)
    }

    fun openPreview(name: String, uriString: String, mime: String?, size: Long) {
        previewQueue = emptyList()
        previewIndex = 0
        pendingPreviewIndex = null
        previewOriginNote = null
        preview = PreviewItem(
            name = name,
            uri = android.net.Uri.parse(uriString),
            mime = mime?.takeIf { it.isNotBlank() } ?: FileTypes.mimeOf(name),
            size = size,
        )
    }

    fun startDownload(requestPath: String, fileName: String, autoOpen: Boolean = false) {
        val current = download
        if (current != null && !current.done) {
            Toast.makeText(context, "还有文件正在下载…", Toast.LENGTH_SHORT).show()
            return
        }
        if (requestPath.isBlank()) {
            Toast.makeText(context, "路径为空，无法下载", Toast.LENGTH_SHORT).show()
            return
        }
        download = DownloadState(name = fileName, percent = 0)
        scope.launch {
            try {
                val outcome = Downloads.save(context, base, token, requestPath, fileName) { percent ->
                    val state = download
                    if (state != null && !state.done) download = state.copy(percent = percent)
                }
                val mime = FileTypes.mimeOf(outcome.fileName)
                DownloadHistory.add(
                    context,
                    DownloadedFile(
                        name = outcome.fileName,
                        uri = outcome.uri,
                        mime = mime,
                        size = outcome.size,
                        time = System.currentTimeMillis(),
                    ),
                )
                downloaded = DownloadHistory.list(context)
                if (autoOpen) {
                    val index = pendingPreviewIndex
                    val target = index?.let { previewQueue.getOrNull(it) }
                    pendingPreviewIndex = null
                    download = null
                    if (index != null) previewIndex = index
                    preview = PreviewItem(
                        name = target?.name ?: outcome.fileName,
                        uri = android.net.Uri.parse(outcome.uri),
                        mime = mime,
                        size = outcome.size,
                    )
                } else {
                    download = DownloadState(
                        name = outcome.fileName,
                        percent = 100,
                        done = true,
                        uri = outcome.uri,
                        mime = mime,
                        size = outcome.size,
                    )
                    Toast.makeText(context, "已保存到 ${outcome.savedPath}", Toast.LENGTH_LONG).show()
                    delay(12_000)
                    if (download?.done == true) download = null
                }
            } catch (t: Throwable) {
                pendingPreviewIndex = null
                download = null
                Toast.makeText(
                    context,
                    "下载失败：${t.message ?: "网络错误"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** 处理队列里的一项：本地缓存直接开，电脑文件已下载直接开，否则下载后自动打开。 */
    fun resolveQueueItem(target: PreviewQueueItem, index: Int) {
        val localUri = target.localUri
        if (localUri != null) {
            pendingPreviewIndex = null
            preview = PreviewItem(
                name = target.name,
                uri = android.net.Uri.parse(localUri),
                mime = target.mime.ifBlank { FileTypes.mimeOf(target.name) },
                size = target.size,
            )
            return
        }
        val found = downloadedIndex[target.name + "\u0000" + target.size]
        if (found != null) {
            pendingPreviewIndex = null
            preview = PreviewItem(
                name = found.name,
                uri = android.net.Uri.parse(found.uri),
                mime = found.mime,
                size = found.size,
            )
            return
        }
        val path = target.remotePath
        if (path.isBlank()) {
            Toast.makeText(context, "这个文件还没下载", Toast.LENGTH_SHORT).show()
            return
        }
        pendingPreviewIndex = index
        startDownload(path, target.name, autoOpen = true)
    }

    /** 从列表/文件夹打开预览：已下载直接打开，没下载的下载完自动打开。 */
    fun openPreviewQueue(queue: List<PreviewQueueItem>, index: Int) {
        if (queue.isEmpty()) return
        previewQueue = queue
        previewOriginNote = null
        val safeIndex = index.coerceIn(0, queue.lastIndex)
        previewIndex = safeIndex
        resolveQueueItem(queue[safeIndex], safeIndex)
    }

    /** 从列表/文件夹打开：只把「同类媒体」组成队列，zip/apk 等容器类不做播放队列。 */
    fun openFromList(items: List<PreviewQueueItem>, clicked: PreviewQueueItem) {
        val category = FileTypes.categoryOf(clicked.name, clicked.mime)
        val queueable = category == FileCategory.AUDIO ||
            category == FileCategory.VIDEO ||
            category == FileCategory.IMAGE ||
            category == FileCategory.TEXT
        val queue = if (queueable) {
            items.filter { FileTypes.categoryOf(it.name, it.mime) == category }
        } else {
            listOf(clicked)
        }
        val index = queue.indexOfFirst {
            it.name == clicked.name && it.size == clicked.size
        }.coerceAtLeast(0)
        openPreviewQueue(queue, index)
    }

    /** 左右滑动 / 点播放列表：切到队列里的某个文件。 */
    fun jumpPreview(index: Int) {
        if (previewQueue.isEmpty()) return
        if (index !in previewQueue.indices) {
            Toast.makeText(
                context,
                if (index > previewIndex) "已经是最后一个了" else "已经是第一个了",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        previewIndex = index
        resolveQueueItem(previewQueue[index], index)
    }

    fun shiftPreview(delta: Int) {
        if (previewQueue.isEmpty()) return
        jumpPreview(previewIndex + delta)
    }

    /** 音频播完自动连着放：只跳到后面第一个同类（音频）文件，跳过 zip/图片等。 */
    fun autoAdvancePreview() {
        if (previewQueue.isEmpty()) return
        val current = previewQueue.getOrNull(previewIndex) ?: return
        val category = FileTypes.categoryOf(current.name, current.mime)
        var index = previewIndex + 1
        while (index < previewQueue.size) {
            val next = previewQueue[index]
            if (FileTypes.categoryOf(next.name, next.mime) == category) {
                jumpPreview(index)
                return
            }
            index++
        }
    }

    fun checkUpdate(showToastWhenLatest: Boolean = false) {
        scope.launch {
            val info = runCatching {
                withContext(Dispatchers.IO) { Updater.check(base) }
            }.getOrNull()
            updateInfo = info
            when {
                info != null -> Toast.makeText(
                    context,
                    "发现新版本 v${info.versionName}，点首页横幅更新",
                    Toast.LENGTH_SHORT,
                ).show()
                showToastWhenLatest -> Toast.makeText(
                    context,
                    "已是最新版 v${Updater.currentVersionName()}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun startUpdate(info: Updater.Info) {
        if (updateBusy) return
        updateBusy = true
        scope.launch {
            try {
                val dir = File(context.cacheDir, "updates")
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(base, info, dir) { progress ->
                        if (progress in 1..99 && progress % 20 == 0) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(
                                    context,
                                    "下载更新 $progress%",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                }
                updateBusy = false
                updateInfo = null
                val error = Updater.install(context, apk)
                if (error != null) {
                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                updateBusy = false
                Toast.makeText(
                    context,
                    "更新失败：${t.message ?: "网络错误"}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        load(null)
        runCatching { withContext(Dispatchers.IO) { Updater.check(base) } }
            .onSuccess { updateInfo = it }
    }

    // 其他 App 通过系统分享发来的文件 → 直接打开「发送到电脑」
    LaunchedEffect(dev.dsh.files.ShareHub.pending.size) {
        if (dev.dsh.files.ShareHub.pending.isNotEmpty()) {
            sendUris = dev.dsh.files.ShareHub.pending.toList()
            dev.dsh.files.ShareHub.pending.clear()
            preview = null
            showSend = true
        }
    }

    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        if (listing != null || recents != null) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // 从通知/后台切回来时刷新收件箱
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { Api.inbox(base, token, null) }
                    }.onSuccess { inbox = it.items }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = showSend || preview != null || path != null) {
        if (showSend) {
            showSend = false
            return@BackHandler
        }
        if (preview != null) {
            preview = previewBack.lastOrNull()
            previewBack = previewBack.dropLast(1)
            return@BackHandler
        }
        val parent = listing?.parent
        if (parent != null) load(parent) else load(null)
    }

    val host = remember(base) {
        runCatching { java.net.URL(base).host }.getOrDefault(base)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Deep, Ink))),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TopBar(
                title = if (path == null) "DSH 文件" else (listing?.path?.let { nameOf(it) } ?: "文件"),
                subtitle = if (path == null) "已连接 · $host · ${routeName(base)}" else listing?.path.orEmpty(),
                showBack = path != null,
                onBack = {
                    val parent = listing?.parent
                    if (parent != null) load(parent) else load(null)
                },
                onRefresh = { load(path) },
                onMenu = { menuOpen = true },
                menuOpen = menuOpen,
                onDismissMenu = { menuOpen = false },
                onHome = { load(null) },
                onCheckUpdate = { checkUpdate(showToastWhenLatest = true) },
                altRouteLabel = prefs.altBase.takeIf { it.isNotBlank() }?.let { routeName(it) + " " + hostLabel(it) },
                onSwitchRoute = { switchRouteManually() },
                onSend = {
                    preview = null
                    sendUris = emptyList()
                    showSend = true
                },
                pushEnabled = pushEnabled,
                onTogglePush = {
                    prefs.pushEnabled = !prefs.pushEnabled
                    pushEnabled = prefs.pushEnabled
                    if (prefs.pushEnabled) {
                        PushService.start(context)
                        Toast.makeText(context, "已开启后台推送", Toast.LENGTH_SHORT).show()
                    } else {
                        PushService.stop(context)
                        Toast.makeText(context, "已关闭后台推送", Toast.LENGTH_SHORT).show()
                    }
                },
                onDisconnect = { menuOpen = false; onDisconnect() },
            )
            HorizontalDivider(color = Edge, thickness = 1.dp)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    error != null && listing == null && recents == null && roots.isEmpty() -> {
                        ErrorState(
                            message = error!!,
                            authLost = authLost,
                            onRetry = { load(null) },
                            onReconnect = onDisconnect,
                        )
                    }
                    path == null || listing == null -> {
                        if (loading && listing == null && path != null) {
                            LoadingState("正在读取目录…")
                        } else {
                            HomeContent(
                                loading = loading,
                                roots = roots,
                                recents = recents,
                                inbox = inbox,
                                updateVersion = updateInfo?.versionName,
                                updateBusy = updateBusy,
                                onUpdate = { updateInfo?.let { startUpdate(it) } },
                                onRoot = { load(it) },
                                onSend = {
                                    preview = null
                                    sendUris = emptyList()
                                    showSend = true
                                },
                                onDownloadRecent = { entry ->
                                    val items = recents?.items.orEmpty()
                                    val files = items.map { item ->
                                        PreviewQueueItem(
                                            name = item.name,
                                            mime = FileTypes.mimeOf(item.name),
                                            size = item.size,
                                            remotePath = fileRequest(item.fullPath),
                                        )
                                    }
                                    val clicked = files.firstOrNull {
                                        it.name == entry.name && it.size == entry.size
                                    } ?: files.firstOrNull { it.name == entry.name }
                                    if (clicked != null) openFromList(files, clicked)
                                },
                                onDownloadInbox = { item ->
                                    val files = inbox.map { entry ->
                                        PreviewQueueItem(
                                            name = entry.name,
                                            mime = FileTypes.mimeOf(entry.name),
                                            size = entry.size,
                                            remotePath = "/files/inbox/" + entry.id,
                                        )
                                    }
                                    val clicked = files.firstOrNull {
                                        it.name == item.name && it.size == item.size
                                    } ?: files.firstOrNull { it.name == item.name }
                                    if (clicked != null) openFromList(files, clicked)
                                },
                                downloaded = downloaded,
                                downloadedFor = { name, size, mtime -> downloadedFor(name, size, mtime) },
                                onOpenDownloaded = { file ->
                                    val files = downloaded.map { entry ->
                                        PreviewQueueItem(
                                            name = entry.name,
                                            mime = entry.mime,
                                            size = entry.size,
                                        )
                                    }
                                    val clicked = files.firstOrNull {
                                        it.name == file.name && it.size == file.size
                                    }
                                    if (clicked != null) openFromList(files, clicked)
                                },
                                onOpenRecentDir = { entry ->
                                    val parent = parentDir(entry.fullPath)
                                    if (parent.isNotBlank()) load(parent)
                                },
                                onRefresh = { load(null) },
                            )
                        }
                    }
                    else -> {
                        BrowseContent(
                            listing = listing!!,
                            loading = loading,
                            onHome = { load(null) },
                            onCrumb = { load(it) },
                            onDir = { load(it) },
                            downloadedFor = { name, size, mtime -> downloadedFor(name, size, mtime) },
                            onFile = { entry ->
                                val dir = listing?.path.orEmpty()
                                val files = (listing?.entries ?: emptyList()).filter { !it.dir }
                                val items = files.map { file ->
                                    PreviewQueueItem(
                                        name = file.name,
                                        mime = FileTypes.mimeOf(file.name),
                                        size = file.size,
                                        remotePath = fileRequest(joinPath(dir, file.name)),
                                    )
                                }
                                val clicked = items.firstOrNull {
                                    it.name == entry.name && it.size == entry.size
                                }
                                if (clicked != null) {
                                    openFromList(items, clicked)
                                } else {
                                    val queue = items.filter { it.name == entry.name }
                                    if (queue.isNotEmpty()) openPreviewQueue(queue, 0)
                                }
                            },
                            onZip = { entry ->
                                val dir = listing?.path.orEmpty()
                                startDownload(zipRequest(joinPath(dir, entry.name)), entry.name + ".zip")
                            },
                        )
                    }
                }
            }
        }
        download?.let { state ->
            DownloadBanner(
                state = state,
                onOpen = {
                    state.uri?.let { openPreview(state.name, it, state.mime, state.size) }
                },
                onClose = { download = null },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(14.dp),
            )
        }
        preview?.let { item ->
            FilePreview(
                item = item,
                onClose = {
                    android.util.Log.d("DSHFiles", "preview close: ${item.name}")
                    preview = previewBack.lastOrNull()
                    previewBack = previewBack.dropLast(1)
                    previewOriginNote = null
                },
                onOpen = { nested ->
                    android.util.Log.d("DSHFiles", "nested open: ${nested.name} from ${item.name}")
                    preview?.let { current -> previewBack = previewBack + current }
                    previewQueue = emptyList()
                    previewIndex = 0
                    pendingPreviewIndex = null
                    previewOriginNote = "📦 压缩包内临时预览 · 退出后自动清理缓存"
                    preview = nested
                },
                onPrev = if (previewQueue.size > 1) ({ shiftPreview(-1) }) else null,
                onNext = if (previewQueue.size > 1) ({ shiftPreview(1) }) else null,
                onAutoNext = if (previewQueue.size > 1) ({ autoAdvancePreview() }) else null,
                positionText = if (previewQueue.size > 1) "${previewIndex + 1} / ${previewQueue.size}" else null,
                playlist = previewQueue.mapIndexed { index, entry ->
                    PlaylistItem(
                        name = entry.name,
                        size = entry.size,
                        active = index == previewIndex,
                    )
                },
                onPlaylistSelect = if (previewQueue.size > 1) ({ index -> jumpPreview(index) }) else null,
                originNote = previewOriginNote,
                onOpenQueue = { queue, index ->
                    preview?.let { current -> previewBack = previewBack + current }
                    previewQueue = queue
                    previewIndex = index.coerceIn(0, queue.lastIndex)
                    previewOriginNote = "📦 压缩包内临时预览 · 退出后自动清理缓存"
                    preview = PreviewItem(
                        name = queue[previewIndex].name,
                        uri = android.net.Uri.parse(queue[previewIndex].localUri.orEmpty()),
                        mime = queue[previewIndex].mime,
                        size = queue[previewIndex].size,
                    )
                },
            )
        }
        if (showSend) {
            SendScreen(
                base = base,
                token = token,
                initialUris = sendUris,
                onClose = { showSend = false },
            )
        }
    }
}

@Composable
private fun DownloadBanner(
    state: DownloadState,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xF2111823))
            .border(1.dp, Edge, shape)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.done) "✅" else "📥", fontSize = 15.sp)
            Spacer(Modifier.width(9.dp))
            Text(
                if (state.done) "已保存：${state.name}" else "正在下载：${state.name}",
                color = TextMain,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!state.done) {
                Spacer(Modifier.width(8.dp))
                Text("${state.percent}%", color = AccentSoft, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(PanelHi),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (state.done) 1f else (state.percent / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Accent, AccentSoft))),
            )
        }
        if (state.done) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "打开",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Accent)
                        .clickable(onClick = onOpen)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "关闭",
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Edge, RoundedCornerShape(10.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 顶栏

@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onMenu: () -> Unit,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onHome: () -> Unit,
    onCheckUpdate: () -> Unit,
    onSend: () -> Unit,
    altRouteLabel: String?,
    onSwitchRoute: () -> Unit,
    pushEnabled: Boolean,
    onTogglePush: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            RoundIcon("←", onBack)
            Spacer(Modifier.width(10.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF2F6FED), Color(0xFF1B3F8F)))),
                contentAlignment = Alignment.Center,
            ) {
                Text("📁", fontSize = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = TextMain,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundIcon("↻", onRefresh)
        Spacer(Modifier.width(8.dp))
        Box {
            RoundIcon("⋮", onMenu)
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(text = { Text("刷新") }, onClick = { onDismissMenu(); onRefresh() })
                DropdownMenuItem(text = { Text("检查更新") }, onClick = { onDismissMenu(); onCheckUpdate() })
                DropdownMenuItem(text = { Text("发送文件到电脑") }, onClick = { onDismissMenu(); onSend() })
                DropdownMenuItem(text = { Text("回到首页") }, onClick = { onDismissMenu(); onHome() })
                if (altRouteLabel != null) {
                    DropdownMenuItem(
                        text = { Text("切换线路（备用：$altRouteLabel）") },
                        onClick = { onDismissMenu(); onSwitchRoute() },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (pushEnabled) "关闭后台推送" else "开启后台推送") },
                    onClick = { onDismissMenu(); onTogglePush() },
                )
                DropdownMenuItem(text = { Text("断开连接") }, onClick = { onDismissMenu(); onDisconnect() })
                DropdownMenuItem(
                    text = { Text("DSH 文件 v${BuildConfig.VERSION_NAME}", color = Color(0xFF6F8299), fontSize = 12.sp) },
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun RoundIcon(glyph: String, onClick: () -> Unit) {
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

// ---------------------------------------------------------------- 首页

@Composable
private fun HomeContent(
    loading: Boolean,
    roots: List<RootEntry>,
    recents: RecentResult?,
    inbox: List<InboxItem>,
    updateVersion: String?,
    updateBusy: Boolean,
    onUpdate: () -> Unit,
    onSend: () -> Unit,
    onRoot: (String) -> Unit,
    onDownloadRecent: (FileEntry) -> Unit,
    onDownloadInbox: (InboxItem) -> Unit,
    downloaded: List<DownloadedFile>,
    downloadedFor: (String, Long, Long) -> DownloadedFile?,
    onOpenDownloaded: (DownloadedFile) -> Unit,
    onOpenRecentDir: (FileEntry) -> Unit,
    onRefresh: () -> Unit,
) {
    val recentItems = recents?.items.orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        if (updateVersion != null) {
            item {
                val shape = RoundedCornerShape(16.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(Accent.copy(alpha = 0.16f))
                        .border(1.dp, Accent.copy(alpha = 0.45f), shape)
                        .clickable(enabled = !updateBusy, onClick = onUpdate)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (updateBusy) "⏳" else "🎉", fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (updateBusy) "正在下载新版本…" else "发现新版本 v$updateVersion",
                            color = TextMain,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "点此下载并安装（覆盖更新，配对信息保留）",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    }
                    Text("更新", color = AccentSoft, fontSize = 13.sp)
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF1B3F8F), Color(0xFF2F6FED))))
                    .clickable(onClick = onSend)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("📤", fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "发送文件到电脑",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "从手机选文件 → 电脑「手机上传」目录",
                        color = Color(0xFFCFE0FF),
                        fontSize = 11.sp,
                    )
                }
                Text("›", color = Color.White, fontSize = 20.sp)
            }
        }
        if (inbox.isNotEmpty()) {
            item {
                SectionHeader("来自电脑的推送", "已下载的点右侧图标直接打开")
            }
            items(inbox) { item ->
                val opened = downloadedFor(item.name, item.size, item.mtime)
                InboxRow(
                    item = item,
                    opened = opened,
                    onDownload = { onDownloadInbox(item) },
                    onOpen = { onDownloadInbox(item) },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        if (downloaded.isNotEmpty()) {
            item {
                SectionHeader("已下载", "点开即可在 App 里预览 / 播放 / 安装")
            }
            items(downloaded) { file ->
                DownloadedRow(file = file, onOpen = { onOpenDownloaded(file) })
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        item {
            SectionHeader("最近产出", "已下载的点右侧图标直接打开，不用重新下载")
        }
        if (loading && recents == null) {
            item { LoadingCard("正在扫描电脑上的文件…") }
        } else if (recentItems.isEmpty()) {
            item { EmptyCard("还没有可显示的近期文件") }
        } else {
            items(recentItems) { entry ->
                val opened = downloadedFor(entry.name, entry.size, entry.mtime)
                RecentRow(
                    entry = entry,
                    opened = opened,
                    onDownload = { onDownloadRecent(entry) },
                    onOpen = { onDownloadRecent(entry) },
                    onOpenDir = { onOpenRecentDir(entry) },
                )
            }
        }
        item { Spacer(Modifier.height(10.dp)) }
        item { SectionHeader("浏览电脑", "工作区、用户目录和各磁盘") }
        if (roots.isEmpty()) {
            item { LoadingCard("正在读取可浏览的位置…") }
        } else {
            items(roots) { root ->
                RootRow(root = root, onClick = { onRoot(root.path) })
            }
        }
        item {
            Text(
                "下载的文件保存在：手机 / Download / DSH文件",
                color = Color(0xFF5D6F85),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun RecentRow(
    entry: FileEntry,
    opened: DownloadedFile?,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
    onOpenDir: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.92f))
            .border(1.dp, if (opened != null) Mint.copy(alpha = 0.3f) else Edge, shape)
            .clickable(onClick = if (opened != null) onOpen else onDownload)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(visualFor(entry))
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
                "${fmtSize(entry.size)} · ${fmtAgo(entry.mtime)}${if (entry.rel.isNotBlank()) " · " + parentDir(entry.rel) else ""}${if (opened != null) " · 点开" + actionLabel(entry.name) else ""}",
                color = if (opened != null) Mint.copy(alpha = 0.8f) else Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "目录",
            color = AccentSoft,
            fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onOpenDir)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Spacer(Modifier.width(6.dp))
        if (opened != null) TypeActionDot(entry.name, onOpen) else DownloadDot(onDownload)
    }
}

@Composable
private fun InboxRow(
    item: InboxItem,
    opened: DownloadedFile?,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.92f))
            .border(1.dp, if (opened != null) Mint.copy(alpha = 0.4f) else Mint.copy(alpha = 0.25f), shape)
            .clickable(onClick = if (opened != null) onOpen else onDownload)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(FileVisual("📥", Mint))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                color = TextMain,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (item.note.isNotBlank()) item.note else "${fmtSize(item.size)} · ${fmtAgo(item.pushedAt)}",
                color = if (item.note.isNotBlank()) Mint.copy(alpha = 0.85f) else Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (opened != null) TypeActionDot(item.name, onOpen) else DownloadDot(onDownload)
    }
}

@Composable
private fun DownloadedRow(file: DownloadedFile, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val visual = visualFor(
        FileEntry(name = file.name, dir = false, size = file.size, mtime = 0L),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.92f))
            .border(1.dp, Edge, shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(visual)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                color = TextMain,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${fmtSize(file.size)} · ${fmtAgo(file.time / 1000)} · 点开${actionLabel(file.name)}",
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TypeActionDot(file.name, onOpen)
    }
}

@Composable
private fun RootRow(root: RootEntry, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val visual = when {
        root.label.contains("工作区") -> FileVisual("📂", AccentSoft)
        root.label.contains("用户") -> FileVisual("🏠", Mint)
        else -> FileVisual("💻", Amber)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.92f))
            .border(1.dp, Edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(visual)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(root.label, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                root.path,
                color = Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", color = Color(0xFF5D7A99), fontSize = 22.sp)
    }
}

// ---------------------------------------------------------------- 浏览目录

@Composable
private fun BrowseContent(
    listing: Listing,
    loading: Boolean,
    onHome: () -> Unit,
    onCrumb: (String) -> Unit,
    onDir: (String) -> Unit,
    downloadedFor: (String, Long, Long) -> DownloadedFile?,
    onFile: (FileEntry) -> Unit,
    onZip: (FileEntry) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        val crumbs = crumbsOf(listing.path)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrumbChip("🏠") { onHome() }
            crumbs.forEachIndexed { index, pair ->
                Text("›", color = Color(0xFF3E5470), fontSize = 12.sp)
                CrumbChip(if (index == 0) "💻" else pair.first) { onCrumb(pair.second) }
            }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentSoft)
            }
        }
        if (listing.entries.isEmpty() && !loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("这个文件夹是空的", color = Muted, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        "${listing.entries.size} 项",
                        color = Color(0xFF5D6F85),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                items(listing.entries) { entry ->
                    if (entry.dir) {
                        DirRow(
                            entry = entry,
                            onOpen = { onDir(joinPath(listing.path, entry.name)) },
                            onZip = { onZip(entry) },
                        )
                    } else {
                        val opened = downloadedFor(entry.name, entry.size, entry.mtime)
                        FileRow(
                            entry = entry,
                            opened = opened,
                            onDownload = { onFile(entry) },
                            onOpen = { onFile(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DirRow(entry: FileEntry, onOpen: () -> Unit, onZip: () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.92f))
            .border(1.dp, Edge, shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(visualFor(entry))
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
            Text("文件夹 · ${fmtTime(entry.mtime)}", color = Muted, fontSize = 11.sp)
        }
        Text(
            "打包",
            color = Violet,
            fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Violet.copy(alpha = 0.12f))
                .clickable(onClick = onZip)
                .padding(horizontal = 9.dp, vertical = 6.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("›", color = Color(0xFF5D7A99), fontSize = 22.sp)
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    opened: DownloadedFile?,
    onDownload: () -> Unit,
    onOpen: () -> Unit,
) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.92f))
            .border(1.dp, if (opened != null) Mint.copy(alpha = 0.3f) else Edge, shape)
            .clickable(onClick = if (opened != null) onOpen else onDownload)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileBadge(visualFor(entry))
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
                "${fmtSize(entry.size)} · ${fmtTime(entry.mtime)}${if (opened != null) " · 点开" + actionLabel(entry.name) else ""}",
                color = if (opened != null) Mint.copy(alpha = 0.8f) else Muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (opened != null) TypeActionDot(entry.name, onOpen) else DownloadDot(onDownload)
    }
}

// ---------------------------------------------------------------- 小组件

@Composable
internal fun FileBadge(visual: FileVisual) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(visual.color.copy(alpha = 0.14f))
            .border(1.dp, visual.color.copy(alpha = 0.32f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(visual.glyph, fontSize = 18.sp)
    }
}

@Composable
private fun DownloadDot(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Accent.copy(alpha = 0.18f))
            .border(1.dp, Accent.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("⬇", color = AccentSoft, fontSize = 15.sp)
    }
}

/** 已下载的文件：按钮变成「打开」，点开直接预览 / 播放 / 解压，不用重新下载。 */
@Composable
private fun OpenDot(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Mint.copy(alpha = 0.18f))
            .border(1.dp, Mint.copy(alpha = 0.45f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("▶", color = Mint, fontSize = 14.sp)
    }
}

/** 「点开」后面跟的动作词：容器类说解压/安装，媒体类说播放，别让 zip 长成播放键。 */
private fun actionLabel(fileName: String): String = when (
    FileTypes.categoryOf(fileName, FileTypes.mimeOf(fileName))
) {
    FileCategory.AUDIO, FileCategory.VIDEO -> "播放"
    FileCategory.IMAGE -> "预览"
    FileCategory.TEXT -> "查看"
    FileCategory.ARCHIVE -> "解压"
    FileCategory.APK -> "安装"
    FileCategory.OTHER -> "打开"
}

/** 按文件类型显示动作图标：音频/视频 ▶、图片 👁、文本 📄、压缩包 📦、APK 📲。 */
@Composable
private fun TypeActionDot(fileName: String, onClick: () -> Unit) {
    val category = FileTypes.categoryOf(fileName, FileTypes.mimeOf(fileName))
    val glyph = when (category) {
        FileCategory.AUDIO, FileCategory.VIDEO -> "▶"
        FileCategory.IMAGE -> "👁"
        FileCategory.TEXT -> "📄"
        FileCategory.ARCHIVE -> "📦"
        FileCategory.APK -> "📲"
        FileCategory.OTHER -> "↗"
    }
    val color = when (category) {
        FileCategory.AUDIO, FileCategory.VIDEO -> Mint
        FileCategory.ARCHIVE -> Amber
        FileCategory.APK -> Violet
        else -> AccentSoft
    }
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = color, fontSize = 14.sp)
    }
}

@Composable
private fun CrumbChip(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        color = AccentSoft,
        fontSize = 12.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(Panel)
            .border(1.dp, Edge, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

@Composable
internal fun SectionHeader(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp)) {
        Box(
            Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(AccentSoft, Accent))),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF6F8299), fontSize = 11.sp)
        }
    }
}

@Composable
private fun LoadingCard(text: String) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.6f))
            .border(1.dp, Edge, shape)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentSoft)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun EmptyCard(text: String) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.5f))
            .border(1.dp, Edge, shape)
            .padding(vertical = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF5D6F85), fontSize = 13.sp)
    }
}

@Composable
private fun LoadingState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp, color = AccentSoft)
            Spacer(Modifier.height(14.dp))
            Text(text, color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    authLost: Boolean,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Panel.copy(alpha = 0.9f))
                .border(1.dp, Edge, shape)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("😕", fontSize = 30.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                if (authLost) "配对已失效" else "连接好像出了问题",
                color = TextMain,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Accent)
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text("重试", color = Color.White, fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Edge, RoundedCornerShape(12.dp))
                        .clickable(onClick = onReconnect)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text("重新配对", color = TextMain, fontSize = 13.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 工具函数

private fun fileRequest(remotePath: String): String = "/files/download?path=" + Api.enc(remotePath)

private fun zipRequest(remotePath: String): String = "/files/zip?path=" + Api.enc(remotePath)

private fun nameOf(path: String): String {
    val trimmed = path.trimEnd('\\', '/')
    val index = maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'))
    return if (index >= 0 && index < trimmed.length - 1) trimmed.substring(index + 1) else trimmed
}

private fun parentDir(path: String): String {
    val normalized = path.trimEnd('\\', '/')
    val index = maxOf(normalized.lastIndexOf('\\'), normalized.lastIndexOf('/'))
    return if (index > 2) normalized.substring(0, index) else ""
}

private fun joinPath(dir: String, name: String): String {
    val trimmed = dir.trimEnd('\\', '/')
    return "$trimmed\\$name"
}

private fun crumbsOf(path: String): List<Pair<String, String>> {
    val normalized = path.trimEnd('\\', '/')
    val parts = normalized.split('\\', '/').filter { it.isNotBlank() }
    if (parts.isEmpty()) return emptyList()
    val result = ArrayList<Pair<String, String>>()
    var current = parts[0] + "\\"
    result.add(parts[0] to current)
    for (index in 1 until parts.size) {
        current = if (current.endsWith("\\")) current + parts[index] else "$current\\${parts[index]}"
        result.add(parts[index] to current)
    }
    return result
}
