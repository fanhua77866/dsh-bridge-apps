package dev.dsh.files.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.files.Api
import dev.dsh.files.ApiException
import dev.dsh.files.Uploader
import dev.dsh.files.UploadsResult
import dev.dsh.files.UploadedItem
import dev.dsh.files.UploadInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class SelectedFile(val uri: Uri, val name: String, val size: Long)

@Composable
fun SendScreen(
    base: String,
    token: String,
    initialUris: List<Uri>,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var info by remember { mutableStateOf<UploadInfo?>(null) }
    var infoError by remember { mutableStateOf<String?>(null) }
    var customMode by remember { mutableStateOf(false) }
    var customDir by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<List<SelectedFile>>(emptyList()) }
    var sending by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableStateOf(0) }
    var currentPercent by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<Uploader.Result>>(emptyList()) }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var uploads by remember { mutableStateOf<UploadsResult?>(null) }

    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val existing = selected.map { it.uri }.toSet()
        selected = selected + uris.filter { it !in existing }.map { toSelected(context, it) }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> addUris(uris.orEmpty()) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                Api.uploadInfo(base, token) to Api.uploads(base, token, 20)
            }
        }.onSuccess { (uploadInfo, uploadsResult) ->
            info = uploadInfo
            uploads = uploadsResult
            if (customDir.isBlank() && uploadInfo.defaultDir.isNotBlank()) {
                customDir = uploadInfo.defaultDir
            }
        }.onFailure {
            infoError = it.message ?: "读取上传配置失败"
        }
        addUris(initialUris)
    }

    fun sendAll() {
        if (sending) return
        if (selected.isEmpty()) {
            Toast.makeText(context, "先选择要发送的文件", Toast.LENGTH_SHORT).show()
            return
        }
        val defaultDir = info?.defaultDir.orEmpty()
        val targetDir = if (customMode) customDir.trim() else defaultDir
        if (targetDir.isBlank()) {
            Toast.makeText(context, "请填写电脑上的目标文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        results = emptyList()
        errors = emptyList()
        scope.launch {
            val success = ArrayList<Uploader.Result>()
            val fail = ArrayList<String>()
            selected.forEachIndexed { index, file ->
                currentIndex = index
                currentPercent = 0
                try {
                    val result = Uploader.upload(
                        context = context,
                        base = base,
                        token = token,
                        uri = file.uri,
                        displayName = file.name,
                        knownSize = file.size,
                        targetDir = targetDir,
                    ) { percent -> currentPercent = percent }
                    success.add(result)
                    results = success.toList()
                } catch (t: Throwable) {
                    fail.add("${file.name}：${t.message ?: "发送失败"}")
                    errors = fail.toList()
                }
            }
            sending = false
            if (fail.isEmpty()) {
                selected = emptyList()
                Toast.makeText(context, "已发送 ${success.size} 个文件到电脑", Toast.LENGTH_LONG).show()
            }
            runCatching {
                uploads = withContext(Dispatchers.IO) { Api.uploads(base, token, 20) }
            }
        }
    }

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
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Panel)
                        .border(1.dp, Edge, CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("←", color = TextMain, fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "发送到电脑",
                        color = TextMain,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("手机文件 → 电脑（同一 Wi-Fi）", color = Muted, fontSize = 11.sp)
                }
            }
            HorizontalDivider(color = Edge, thickness = 1.dp)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (infoError != null) {
                    item {
                        Text(infoError!!, color = Color(0xFFFF9A9A), fontSize = 13.sp)
                    }
                }
                item {
                    Text("保存位置", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChip(
                            label = "默认目录",
                            selected = !customMode,
                            onClick = { customMode = false },
                        )
                        ModeChip(
                            label = "自定义目录",
                            selected = customMode,
                            onClick = { customMode = true },
                        )
                    }
                }
                item {
                    Text(
                        if (customMode) "电脑上的任意目录（需在允许范围内）" else
                            "默认：${info?.defaultDir ?: "读取中…"}",
                        color = Color(0xFF6F8299),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (customMode) {
                    item {
                        OutlinedTextField(
                            value = customDir,
                            onValueChange = { customDir = it.trim() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("电脑目录，例如 D:/资料") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = Edge,
                                focusedTextColor = TextMain,
                                unfocusedTextColor = TextMain,
                                cursorColor = AccentSoft,
                                focusedLabelColor = AccentSoft,
                                unfocusedLabelColor = Muted,
                                focusedContainerColor = Color(0xFF0D1522),
                                unfocusedContainerColor = Color(0xFF0D1522),
                            ),
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "要发送的文件（${selected.size}）",
                            color = TextMain,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable(enabled = !sending) { picker.launch(arrayOf("*/*")) }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text("选择文件", color = AccentSoft, fontSize = 12.sp)
                        }
                    }
                }
                if (selected.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Panel.copy(alpha = 0.5f))
                                .border(1.dp, Edge, RoundedCornerShape(14.dp))
                                .padding(vertical = 22.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "还没有选择文件，也可以从其他 App 直接「分享」到 DSH 文件",
                                color = Color(0xFF5D6F85),
                                fontSize = 12.sp,
                            )
                        }
                    }
                } else {
                    items(selected) { file ->
                        SelectedRow(
                            file = file,
                            enabled = !sending,
                            onRemove = { selected = selected.filterNot { it.uri == file.uri } },
                        )
                    }
                }
                item {
                    val target = if (customMode) customDir.trim() else info?.defaultDir.orEmpty()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (sending) Brush.horizontalGradient(listOf(Edge, Edge))
                                else Brush.horizontalGradient(listOf(Accent, Color(0xFF5B8CFF))),
                            )
                            .clickable(enabled = !sending) { sendAll() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sending) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "正在发送 ${currentIndex + 1}/${selected.size} · $currentPercent%",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                )
                            }
                        } else {
                            Text(
                                if (target.isBlank()) "发送到电脑" else "发送到 $target",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                    }
                }
                if (results.isNotEmpty()) {
                    item { SectionHeader("已发送到电脑", "电脑端已收到的文件") }
                    items(results) { result ->
                        ResultRow(result.name, result.path, result.size)
                    }
                }
                if (errors.isNotEmpty()) {
                    item { SectionHeader("发送失败", "检查一下再试") }
                    items(errors) { message ->
                        Text(message, color = Color(0xFFFF9A9A), fontSize = 12.sp)
                    }
                }
                if (uploads != null && uploads!!.items.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        SectionHeader("电脑上最近收到的", uploads!!.dir)
                    }
                    items(uploads!!.items) { item ->
                        RecentUploadRow(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else AccentSoft,
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent else Panel)
            .border(1.dp, if (selected) Accent else Edge, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SelectedRow(file: SelectedFile, enabled: Boolean, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Panel.copy(alpha = 0.92f))
            .border(1.dp, Edge, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("📎", fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                color = TextMain,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (file.size > 0) fmtSize(file.size) else "大小未知",
                color = Muted,
                fontSize = 11.sp,
            )
        }
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(PanelHi)
                .clickable(enabled = enabled, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ResultRow(name: String, path: String, size: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Mint.copy(alpha = 0.10f))
            .border(1.dp, Mint.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✅", fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                color = TextMain,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                path,
                color = Mint.copy(alpha = 0.85f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(fmtSize(size), color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun RecentUploadRow(item: UploadedItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Panel.copy(alpha = 0.7f))
            .border(1.dp, Edge, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("🖥", fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            item.name,
            color = TextMain,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${fmtSize(item.size)} · ${fmtAgo(item.mtime)}",
            color = Muted,
            fontSize = 10.sp,
        )
    }
}

private fun toSelected(context: Context, uri: Uri): SelectedFile {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = -1L
    runCatching {
        val projection = arrayOf(
            android.provider.OpenableColumns.DISPLAY_NAME,
            android.provider.OpenableColumns.SIZE,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    if (size <= 0) {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        }
    }
    if (name.all { it.isDigit() }) {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val ext = mime?.let {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        }
        name = "分享文件-" + System.currentTimeMillis() +
            if (ext.isNullOrBlank()) "" else ".$ext"
    }
    return SelectedFile(uri, name, size)
}
