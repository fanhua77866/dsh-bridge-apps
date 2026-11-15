package dev.dsh.phone

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import java.io.File
import java.net.URLDecoder
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip

private val Ink = Color(0xFF0B0F14)
private val Panel = Color(0xFF111823)
private val Edge = Color(0xFF243247)
private val Accent = Color(0xFF2F6FED)
private val Muted = Color(0xFF9FB0C6)

class MainActivity : ComponentActivity() {

    private var pendingShare: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingShare = intent?.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
        val prefs = Prefs(this)
        if (prefs.paired && prefs.base.isNotBlank()) BridgeService.start(this)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Accent, background = Ink, surface = Panel,
                    onPrimary = Color.White, onBackground = Color(0xFFE6EDF6),
                    onSurface = Color(0xFFE6EDF6),
                ),
            ) {
                var paired by remember { mutableStateOf(prefs.paired && prefs.base.isNotBlank()) }
                var keepAwake by remember { mutableStateOf(prefs.keepAwake) }

                LaunchedEffect(keepAwake) {
                    if (keepAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
                    if (!paired) {
                        PairScreen(
                            initialBase = prefs.base.ifBlank { Prefs.guessBase() },
                            initialAlt = prefs.altBase,
                        ) { base, alt ->
                            prefs.base = base
                            prefs.altBase = alt
                            prefs.paired = true
                            paired = true
                            BridgeService.start(this@MainActivity)
                        }
                    } else {
                        RemoteScreen(
                            base = prefs.base,
                            keepAwake = keepAwake,
                            pendingShare = pendingShare,
                            onShareConsumed = { pendingShare = null },
                            onToggleAwake = {
                                keepAwake = !keepAwake
                                prefs.keepAwake = keepAwake
                            },
                            onDisconnect = {
                                prefs.clearPairing()
                                paired = false
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            pendingShare = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}


/** 把网页选中的文件复制到 App 缓存，返回可交给 WebView 读取的 file:// Uri。
 *  相册里「仅云端」的照片本地没有实体，直接交给 WebView 会失败，所以先落地。 */
private fun stageForUpload(context: android.content.Context, uri: Uri): Uri? = runCatching {
    val out = File(context.cacheDir, "upload-" + System.currentTimeMillis() + "-" + (0..9999).random())
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { o -> input.copyTo(o) }
    } ?: return@runCatching null
    out.takeIf { it.length() > 0 }?.let { Uri.fromFile(it) }
}.getOrNull()

// ---------------------------------------------------------------- 配对页

@Composable
private fun PairScreen(initialBase: String, initialAlt: String, onPaired: (String, String) -> Unit) {
    val context = LocalContext.current
    var base by remember { mutableStateOf(initialBase) }
    var altBase by remember { mutableStateOf(initialAlt) }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var barVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF132033), Ink)))
            .padding(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("DSH 遥控", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "手机连接电脑上的桥接器，直接遥控 DeepSeek Harness。",
            color = Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = base,
            onValueChange = { base = it.trim() },
            label = { Text("桥接地址") },
            placeholder = { Text("http://192.168.1.100:8799") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = altBase,
            onValueChange = { altBase = it.trim() },
            label = { Text("备用地址（可选，外网/Tailscale）") },
            placeholder = { Text("http://100.64.0.1:8799") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "在家填局域网地址；出门填电脑的 Tailscale 地址。两个都填时，一个连不上会自动切另一个。",
            color = Muted, fontSize = 11.sp, lineHeight = 16.sp,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
            label = { Text("6 位 PIN") },
            placeholder = { Text("电脑端桥接器窗口里显示") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = Color(0xFFFF8A8A), fontSize = 13.sp)
        }

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = {
                val primary = normalizeBase(base)
                val alternate = normalizeBase(altBase)
                if (primary == null && alternate == null) {
                    error = "地址格式不对，应该像 http://192.168.1.100:8799"
                    return@Button
                }
                if (pin.length != 6) {
                    error = "PIN 是 6 位数字"
                    return@Button
                }
                busy = true
                error = null
                scope.launch {
                    val candidates = listOfNotNull(primary, alternate).distinct()
                    var working: String? = null
                    var lastError: String? = null
                    for (candidate in candidates) {
                        val result = pair(candidate, pin)
                        if (result.isSuccess) {
                            working = candidate
                            break
                        }
                        lastError = result.exceptionOrNull()?.message
                    }
                    busy = false
                    if (working == null) {
                        error = lastError ?: "配对失败"
                        return@launch
                    }
                    val store = Prefs(context)
                    store.base = working
                    store.altBase = candidates.firstOrNull { it != working } ?: ""
                    store.lastPin = pin
                    store.paired = true
                    onPaired(working, store.altBase)
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White,
                )
            } else {
                Text("连接", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "电脑端先运行 dsh-phone 桥接器（Windows 双击 start-bridge.cmd），" +
                "窗口里会打印本机地址和 PIN。",
            color = Muted, fontSize = 12.sp,
        )
    }
}

private fun prefsStore(context: android.content.Context, base: String) {
    Prefs(context).base = base
}

/** 切线路前确保目标地址有设备 cookie：没有就用上次的 PIN 静默重配一次。 */
private suspend fun ensureSession(base: String, prefs: Prefs): Boolean {
    val existing = CookieManager.getInstance().getCookie(base)
    if (!existing.isNullOrBlank() && existing.contains(COOKIE)) return true
    val pin = prefs.lastPin
    if (pin.length != 6) return false
    return pair(base, pin).isSuccess
}

private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault(url)

private fun routeName(url: String): String =
    if (hostOf(url).startsWith("100.")) "外网" else "局域网"

private fun normalizeBase(raw: String): String? {
    var s = raw.trim()
    if (s.isEmpty()) return null
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
    return try {
        val u = URL(s)
        val port = if (u.port > 0) u.port else 80
        "${u.protocol}://${u.host}:$port"
    } catch (t: Throwable) {
        null
    }
}

private suspend fun pair(base: String, pin: String): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        val url = URL("$base/_bridge/pair")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write("""{"pin":"$pin"}""".toByteArray()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val cookies = conn.headerFields["Set-Cookie"].orEmpty()
        conn.disconnect()

        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()
            return@withContext Result.failure(Exception(msg?.ifBlank { null } ?: "HTTP $code"))
        }
        val cookie = cookies.firstOrNull { it.startsWith("$COOKIE=") }?.substringBefore(";")
            ?: return@withContext Result.failure(Exception("桥接器没有下发设备 cookie"))
        CookieManager.getInstance().setCookie(base, "$cookie; path=/")
        CookieManager.getInstance().flush()
        Result.success(Unit)
    } catch (t: Throwable) {
        Result.failure(Exception("连不上桥接器：${t.message}"))
    }
}

private const val COOKIE = "dsh_phone"

// ---------------------------------------------------------------- 遥控页

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RemoteScreen(
    base: String,
    keepAwake: Boolean,
    pendingShare: String?,
    onShareConsumed: () -> Unit,
    onToggleAwake: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var currentBase by remember { mutableStateOf(base) }
    var menuOpen by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var barVisible by remember { mutableStateOf(false) }

    // 网页里的「上传图片/文件」按钮 → 系统选择器
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val cb = fileCallback
        fileCallback = null
        if (cb == null) return@rememberLauncherForActivityResult
        val data = res.data
        val picked: Array<Uri>? = when {
            res.resultCode != android.app.Activity.RESULT_OK -> null
            data?.clipData != null -> {
                val clip = data.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            data?.data != null -> arrayOf(data.data!!)
            else -> null
        }
        if (picked == null) {
            cb.onReceiveValue(null)
            return@rememberLauncherForActivityResult
        }
        // 相册里「仅云端」的照片本地没有实体，直接交给 WebView 读取会失败。
        // 先复制到 App 缓存，再给网页本地路径。
        val staged = picked.mapNotNull { stageForUpload(context, it) }
        if (staged.size < picked.size) {
            Toast.makeText(
                context,
                "有 ${picked.size - staged.size} 个文件不在本机（可能在云端），请先下载到手机再发",
                Toast.LENGTH_LONG,
            ).show()
        }
        cb.onReceiveValue(if (staged.isEmpty()) null else staged.toTypedArray())
    }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(AColor.parseColor("#0B0F14"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = false
                // 必须为 true：网页拿到的是 content:// 的图片 URI，
                // 关掉的话 FileReader 读不到，页面就会显示「发送失败」
                allowContentAccess = true
                builtInZoomControls = false
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = false
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "$userAgentString DSHPhone/1.0"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            addJavascriptInterface(JsBridge(context), "DshPhone")
            var retryLeft = 2
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    failed = null
                    retryLeft = 2
                    super.onPageFinished(view, url)
                }

                override fun onReceivedError(
                    view: WebView?, request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?,
                ) {
                    if (request?.isForMainFrame != true) return
                    failed = error?.description?.toString()
                    // 桥接器重连瞬间会返回空响应（ERR_EMPTY_RESPONSE），先自动重试两次
                    if (retryLeft > 0) {
                        retryLeft--
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            view?.reload()
                        }, 1200L)
                        return
                    }
                    // 主地址确实连不上 → 自动切备用地址（例如从家 Wi-Fi 换成 Tailscale）
                    val alternate = prefs.altBase
                    if (alternate.isNotBlank() && alternate != currentBase) {
                        scope.launch {
                            if (!ensureSession(alternate, prefs)) return@launch
                            val old = currentBase
                            currentBase = alternate
                            prefs.base = alternate
                            prefs.altBase = old
                            retryLeft = 2
                            failed = null
                            view?.loadUrl(alternate)
                            Toast.makeText(
                                context,
                                "已切到${routeName(alternate)}：${hostOf(alternate)}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean = true

                // 图片/文件选择：DSH 网页里的附件按钮走这里
                override fun onShowFileChooser(
                    view: WebView?,
                    callback: ValueCallback<Array<Uri>>?,
                    params: FileChooserParams?,
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    return runCatching {
                        filePicker.launch(intent)
                        true
                    }.getOrElse {
                        fileCallback = null
                        false
                    }
                }

                override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                    runCatching { request?.grant(request.resources) }
                }
            }
            // 文件下载：交给系统 DownloadManager（带上桥接器的设备 cookie + 直链 token），
            // 文件落到手机「Download」目录，通知栏可看进度、点开即用。
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    return@setDownloadListener
                }
                val cookies = CookieManager.getInstance().getCookie(url)
                val fileName = downloadFileName(contentDisposition, url, mimeType)
                runCatching {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setTitle(fileName)
                        setDescription("来自电脑 · DSH 遥控")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                        if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                        addRequestHeader("User-Agent", userAgent ?: "DSHPhone/1.0")
                        addRequestHeader("Referer", url)
                        if (!cookies.isNullOrBlank()) addRequestHeader("Cookie", cookies)
                    }
                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(context, "开始下载：$fileName", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "下载失败：${it.message}", Toast.LENGTH_LONG).show()
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }
            }
            loadUrl(currentBase)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { webView.destroy() }
        }
    }

    BackHandler {
        // 先问页面：有没有开着的抽屉/浮层，有就关掉它，别让返回退出 App
        webView.evaluateJavascript(
            "(function(){try{return !!(window.__dshClose && window.__dshClose());}catch(e){return false;}})()",
        ) { res ->
            if (res?.contains("true") != true) {
                if (webView.canGoBack()) webView.goBack() else (context as? ComponentActivity)?.finish()
            }
        }
    }

    LaunchedEffect(pendingShare) {
        val text = pendingShare ?: return@LaunchedEffect
        val js = """
            (function(){
              try {
                var t = ${JSONObject.quote(text)};
                var els = Array.prototype.slice.call(
                  document.querySelectorAll('textarea,[contenteditable="true"]'));
                var el = els[els.length - 1];
                if (!el) return;
                el.focus();
                if (el.tagName === 'TEXTAREA') {
                  el.value = t;
                  el.dispatchEvent(new Event('input', {bubbles:true}));
                } else {
                  document.execCommand('insertText', false, t);
                }
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        onShareConsumed()
    }

    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        AndroidView(
            factory = { webView },
            // 收起时只留把手那一条的高度
            modifier = Modifier.fillMaxSize().padding(bottom = if (barVisible) 78.dp else 24.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 把手在按钮上方：整条 24dp 都能点，箭头画在胶囊里
            Surface(
                color = Color.Transparent,
                modifier = Modifier.fillMaxWidth().height(24.dp),
                onClick = { barVisible = !barVisible },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .width(72.dp)
                            .height(22.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(Color(0xFF1B2533)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (barVisible) "⌄" else "⌃",
                            color = Color(0xFF9DB0C8), fontSize = 13.sp, lineHeight = 13.sp,
                        )
                    }
                }
            }

        if (barVisible) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xF20C131C))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (failed != null) {
                Surface(
                    color = Color(0xCC7F1D1D), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(bottom = 10.dp),
                ) {
                    Text(
                        "加载失败：$failed",
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton("↻", "刷新") { webView.reload() }
                ToolButton("≡", "会话") { webView.loadUrl(currentBase) }
                ToolButton("📁", "文件") { webView.loadUrl(currentBase.trimEnd('/') + "/files") }
                Box {
                    ToolButton("⋮", if (busy) "更新中" else "更多") { menuOpen = true }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("版本 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", color = Color(0xFF7D8CA0), fontSize = 12.sp) },
                            enabled = false,
                            onClick = {},
                        )
                        DropdownMenuItem(
                            text = { Text("刷新页面") },
                            onClick = { menuOpen = false; webView.reload() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (busy) "更新中…" else "检查更新") },
                            enabled = !busy,
                            onClick = {
                                menuOpen = false
                                if (busy) return@DropdownMenuItem
                                busy = true
                                scope.launch {
                                    val info = withContext(Dispatchers.IO) {
                                        runCatching { Updater.check(currentBase) }.getOrNull()
                                    }
                                    if (info == null) {
                                        busy = false
                                        Toast.makeText(context, "已是最新版（v${Updater.currentVersionName()}）", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    Toast.makeText(context, "发现 v${info.versionName}，开始下载…", Toast.LENGTH_SHORT).show()
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val dir = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
                                            Updater.download(currentBase, info, dir) { p ->
                                                // Toast 必须在主线程，否则会抛异常被当成“下载失败”
                                                if (p % 25 == 0) {
                                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                        Toast.makeText(context, "下载 $p%", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    busy = false
                                    val file = result.getOrNull()
                                    if (file == null) {
                                        val why = result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "未知"
                                        Toast.makeText(context, "下载失败（$why）", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    val err = Updater.install(context, file)
                                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                        if (prefs.altBase.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("切换线路（备用：${routeName(prefs.altBase)} ${hostOf(prefs.altBase)}）") },
                                onClick = {
                                    menuOpen = false
                                    val alternate = prefs.altBase
                                    if (alternate.isBlank()) return@DropdownMenuItem
                                    scope.launch {
                                        if (!ensureSession(alternate, prefs)) {
                                            Toast.makeText(context, "备用线路连不上", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        val old = currentBase
                                        currentBase = alternate
                                        prefs.base = alternate
                                        prefs.altBase = old
                                        webView.loadUrl(alternate)
                                        Toast.makeText(context, "已切到${routeName(alternate)}：${hostOf(alternate)}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (keepAwake) "取消屏幕常亮" else "屏幕常亮") },
                            onClick = { menuOpen = false; onToggleAwake() },
                        )
                        DropdownMenuItem(
                            text = { Text("电脑文件 / 下载") },
                            onClick = {
                                menuOpen = false
                                webView.loadUrl(currentBase.trimEnd('/') + "/files")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("回到会话列表") },
                            onClick = { menuOpen = false; webView.loadUrl(currentBase) },
                        )
                        DropdownMenuItem(
                            text = { Text("断开配对") },
                            onClick = { menuOpen = false; onDisconnect() },
                        )
                    }
                }
            }
        }
        }   // if (barVisible)
        }
    }
}

/** 从 Content-Disposition 里取中文文件名（优先 RFC 5987 的 filename*，再退回 filename）。 */
private fun downloadFileName(contentDisposition: String?, url: String, mimeType: String?): String {
    val cd = contentDisposition.orEmpty()
    val patterns = listOf(
        Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE),
        Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE),
    )
    for (re in patterns) {
        val m = re.find(cd) ?: continue
        val raw = m.groupValues[1].trim().trim('"')
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
        if (!decoded.isNullOrBlank()) return decoded
    }
    val guessed = URLUtil.guessFileName(url, contentDisposition, mimeType)
    return guessed.ifBlank { "dsh-download" }
}

@Composable
private fun ToolButton(icon: String, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, color = Color(0xFFDCE6F5), fontSize = 18.sp, lineHeight = 20.sp)
        Text(label, color = Color(0xFF8FA1B8), fontSize = 10.sp, lineHeight = 12.sp)
    }
}


@Composable
private fun RoundButton(label: String, onClick: () -> Unit) {
    Surface(
        color = Color(0xE6111823),
        shape = CircleShape,
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier.size(46.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = Color.White, fontSize = 20.sp)
        }
    }
}

class JsBridge(private val context: android.content.Context) {
    @JavascriptInterface
    fun toast(msg: String) {
        android.os.Handler(context.mainLooper).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun onVisibility(visible: Boolean) = Unit

    @JavascriptInterface
    fun onNetwork(online: Boolean) = Unit
}
