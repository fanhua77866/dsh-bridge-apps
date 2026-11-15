package dev.dsh.recorder.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.dsh.recorder.Project
import dev.dsh.recorder.ScriptImporter
import dev.dsh.recorder.Sentence

// ---------------------------------------------------------------- 项目列表

@Composable
fun ProjectListScreen(
    projects: List<Project>,
    onNewProject: (String) -> Unit,
    onOpenProject: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
    onCheckUpdate: () -> Unit,
    versionLabel: String,
) {
    var showNew by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var newName by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF132033), Ink))),
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title = "DSH 录音", subtitle = "逐句连续录制 · 自动断句", onMenu = onSettings)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "项目 ${projects.size} 个 · 卡片右侧 ⋯ 可重命名/删除",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                    )
                }
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onOpen = { onOpenProject(project.id) },
                        onRename = { renameTarget = project; renameText = project.name },
                        onDelete = { deleteTarget = project },
                    )
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .clickable { newName = ""; showNew = true }
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("＋ 新建项目", color = AccentSoft, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                item {
                    Text(
                        "用法：新建项目 → 导入逐字稿（TXT/MD/CSV 或 ZIP）→ 开始连录。\n" +
                            "说一句停顿 0.8 秒自动保存并跳下一句。",
                        color = Color(0xFF6F8299),
                        fontSize = 11.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 10.dp, start = 2.dp),
                    )
                }
                item {
                    Text(
                        "版本 $versionLabel · 右上角 ⚙ 设置/检查更新",
                        color = Color(0xFF5D6F85),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                    )
                }
            }
        }
    }

    if (showNew) {
        InputDialog(
            title = "新建项目",
            label = "项目名称",
            value = newName,
            onChange = { newName = it },
            onConfirm = { showNew = false; if (newName.isNotBlank()) onNewProject(newName) },
            onDismiss = { showNew = false },
        )
    }
    renameTarget?.let { target ->
        InputDialog(
            title = "重命名项目",
            label = "项目名称",
            value = renameText,
            onChange = { renameText = it },
            onConfirm = { renameTarget = null; if (renameText.isNotBlank()) onRename(target.id, renameText) },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目") },
            text = { Text("确定删除「${target.name}」及其 ${target.sentences.size} 句录音吗？\n删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = { deleteTarget = null; onDelete(target.id) }) {
                    Text("删除", color = Color(0xFFFF8A8A))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val total = project.sentences.size
    val done = project.recordedCount
    val shape = RoundedCornerShape(18.dp)
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.94f))
            .border(1.dp, Edge, shape)
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) { Text("🎙", fontSize = 20.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    color = TextMain,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (total == 0) "还没有句子，先导入逐字稿" else
                        "已录 $done / $total 句 · 共 ${fmtDuration(project.totalMs)}",
                    color = if (total > 0 && done == total) Mint else Muted,
                    fontSize = 11.sp,
                )
            }
            Box {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(PanelHi)
                        .border(1.dp, Edge, CircleShape)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) { Text("⋯", color = Muted, fontSize = 18.sp) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除项目", color = Color(0xFFFF8A8A)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
        if (total > 0) {
            Spacer(Modifier.height(10.dp))
            ProgressBar(done.toFloat() / total)
            Spacer(Modifier.height(6.dp))
            Text(
                "${(done * 100 / total)}% · ${fmtTime(project.createdAt)} 创建",
                color = Color(0xFF6F8299),
                fontSize = 10.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- 项目详情

@Composable
fun ProjectDetailScreen(
    project: Project,
    dataVersion: Int,
    onBack: () -> Unit,
    onImportTexts: (List<String>) -> Unit,
    onAddSentence: (String) -> Unit,
    onStartRecord: (Int) -> Unit,
    onPlay: (Sentence) -> Unit,
    onSettings: () -> Unit,
    onUpload: () -> Unit,
    onShareScript: () -> Unit,
    onCheckUpdate: () -> Unit,
    onRenameProject: (String) -> Unit,
    onDeleteProject: () -> Unit,
    onEditSentence: (Sentence, String) -> Unit,
    onDeleteSentence: (Sentence) -> Unit,
) {
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var addText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(project.name) }
    var deleteProjectConfirm by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Sentence?>(null) }
    var editText by remember { mutableStateOf("") }
    var deleteSentenceTarget by remember { mutableStateOf<Sentence?>(null) }
    var timelineTarget by remember { mutableStateOf<Sentence?>(null) }
    var filter by remember { mutableStateOf(0) } // 0 全部 / 1 未录 / 2 已录

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "script"
            val mime = context.contentResolver.getType(uri)
            ScriptImporter.import(context, uri, name, mime)
        }.getOrNull()
        if (result == null || result.lines.isEmpty()) {
            importError = "没有解析到句子：请确认是 TXT/MD/CSV，或包含这些文本的 ZIP"
        } else {
            if (result.fileCount > 1) {
                Toast.makeText(context, "从 ${result.fileCount} 个文件导入 ${result.lines.size} 句", Toast.LENGTH_SHORT).show()
            }
            onImportTexts(result.lines)
        }
    }

    val filtered = project.sentences.withIndex().filter { (_, s) ->
        when (filter) {
            1 -> !s.recorded
            2 -> s.recorded
            else -> true
        }
    }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon("←", onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    color = TextMain,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val total = project.sentences.size
                Text(
                    if (total == 0) "空项目 · 先导入逐字稿" else "已录 ${project.recordedCount} / $total 句",
                    color = if (total > 0 && project.recordedCount == total) Mint else Muted,
                    fontSize = 11.sp,
                )
            }
            Box {
                RoundIcon("⋯") { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("重命名项目") },
                        onClick = { menuOpen = false; renameText = project.name; renameOpen = true },
                    )
                    DropdownMenuItem(
                        text = { Text("导入剧本") },
                        onClick = { menuOpen = false; importLauncher.launch(arrayOf("*/*")) },
                    )
                    DropdownMenuItem(
                        text = { Text("上传到电脑") },
                        onClick = { menuOpen = false; onUpload() },
                    )
                    DropdownMenuItem(
                        text = { Text("导出剧本") },
                        onClick = { menuOpen = false; onShareScript() },
                    )
                    DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = { menuOpen = false; onSettings() },
                    )
                    DropdownMenuItem(
                        text = { Text("删除项目", color = Color(0xFFFF8A8A)) },
                        onClick = { menuOpen = false; deleteProjectConfirm = true },
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundIcon("⚙", onSettings)
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionChip("▶ 继续连录", primary = true) {
                    val first = project.sentences.indexOfFirst { !it.recorded }
                        .let { if (it < 0) (project.sentences.size - 1).coerceAtLeast(0) else it }
                    onStartRecord(first)
                }
                ActionChip("导入剧本") { importLauncher.launch(arrayOf("*/*")) }
                ActionChip(" ＋ 一句") { addText = ""; showAdd = true }
                ActionChip("上传电脑") { onUpload() }
                ActionChip("剧本") { onShareScript() }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("全部" to 0, "未录" to 1, "已录" to 2).forEach { (label, value) ->
                    val active = filter == value
                    Text(
                        label,
                        color = if (active) Color.White else Muted,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) Accent else PanelHi)
                            .clickable { filter = value }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("${filtered.size} 条", color = Color(0xFF6F8299), fontSize = 10.sp)
            }
            if (project.sentences.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📄", fontSize = 44.sp)
                        Spacer(Modifier.height(12.dp))
                        Text("导入逐字稿，一行一句", color = TextMain, fontSize = 15.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "支持 TXT / MD / CSV，也可以直接选 ZIP（按文件名顺序展开）",
                            color = Muted,
                            fontSize = 12.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 90.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.value.id }) { indexed ->
                        SentenceRow(
                            index = indexed.index,
                            sentence = indexed.value,
                            onTap = {
                                if (indexed.value.recorded) onPlay(indexed.value)
                                else onStartRecord(indexed.index)
                            },
                            onRetake = { onStartRecord(indexed.index) },
                            onEdit = { editTarget = indexed.value; editText = indexed.value.text },
                            onDelete = { deleteSentenceTarget = indexed.value },
                            onTimeline = { timelineTarget = indexed.value },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    if (showAdd) {
        InputDialog(
            title = "添加一句话",
            label = "这一句要念的内容",
            value = addText,
            onChange = { addText = it },
            onConfirm = { showAdd = false; if (addText.isNotBlank()) onAddSentence(addText) },
            onDismiss = { showAdd = false },
        )
    }
    if (renameOpen) {
        InputDialog(
            title = "重命名项目",
            label = "项目名称",
            value = renameText,
            onChange = { renameText = it },
            onConfirm = { renameOpen = false; onRenameProject(renameText) },
            onDismiss = { renameOpen = false },
        )
    }
    if (deleteProjectConfirm) {
        AlertDialog(
            onDismissRequest = { deleteProjectConfirm = false },
            title = { Text("删除项目") },
            text = { Text("确定删除「${project.name}」及所有录音吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = { deleteProjectConfirm = false; onDeleteProject() }) {
                    Text("删除", color = Color(0xFFFF8A8A))
                }
            },
            dismissButton = { TextButton(onClick = { deleteProjectConfirm = false }) { Text("取消") } },
        )
    }
    editTarget?.let { target ->
        InputDialog(
            title = "修改句子",
            label = "这一句的内容",
            value = editText,
            onChange = { editText = it },
            onConfirm = {
                editTarget = null
                if (editText.isNotBlank()) onEditSentence(target, editText)
            },
            onDismiss = { editTarget = null },
        )
    }
    deleteSentenceTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteSentenceTarget = null },
            title = { Text("删除这句") },
            text = { Text(target.text) },
            confirmButton = {
                TextButton(onClick = { deleteSentenceTarget = null; onDeleteSentence(target) }) {
                    Text("删除", color = Color(0xFFFF8A8A))
                }
            },
            dismissButton = { TextButton(onClick = { deleteSentenceTarget = null }) { Text("取消") } },
        )
    }
    timelineTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { timelineTarget = null },
            title = { Text("逐字时间轴（估算）") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "文件：${target.wav ?: "-"}　语速 ${"%.1f".format(target.rate)} 字/秒",
                        color = Muted,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (target.chars.isEmpty()) {
                        Text(
                            "这句还没有时间数据（升级前的录音，或没检测到发声）。重新录一遍就会生成。",
                            color = Muted,
                            fontSize = 11.sp,
                        )
                    } else {
                        target.chars.take(400).forEach { c ->
                            Text(
                                "${c.ch}   ${"%.2f".format(c.startMs / 1000.0)}s → ${"%.2f".format(c.endMs / 1000.0)}s",
                                color = TextMain,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                        if (target.chars.size > 400) {
                            Text("…… 共 ${target.chars.size} 字（此处只显示前 400）", color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { timelineTarget = null }) { Text("关闭") } },
        )
    }
    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("导入失败") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("好") } },
        )
    }
}

@Composable
private fun SentenceRow(
    index: Int,
    sentence: Sentence,
    onTap: () -> Unit,
    onRetake: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTimeline: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.92f))
            .border(1.dp, if (sentence.recorded) Mint.copy(alpha = 0.3f) else Edge, shape)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%03d".format(index + 1),
            color = if (sentence.recorded) Mint else Muted,
            fontSize = 11.sp,
            modifier = Modifier.width(34.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                sentence.text,
                color = TextMain,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (sentence.recorded) "已录 ${fmtDuration(sentence.durationMs)}" else "未录",
                color = if (sentence.recorded) Mint.copy(alpha = 0.85f) else Muted,
                fontSize = 10.sp,
            )
        }
        if (sentence.recorded) {
            Text("▶", color = Mint, fontSize = 14.sp, modifier = Modifier.padding(6.dp))
        } else {
            Text("●", color = AccentSoft, fontSize = 16.sp, modifier = Modifier.padding(6.dp))
        }
        Box {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) { Text("⋯", color = Muted, fontSize = 16.sp) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("重录这句") },
                    onClick = { menuOpen = false; onRetake() },
                )
                DropdownMenuItem(
                    text = { Text("修改文字") },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("播放") },
                    onClick = { menuOpen = false; onTap() },
                )
                DropdownMenuItem(
                    text = { Text("查看逐字时间轴") },
                    onClick = { menuOpen = false; onTimeline() },
                )
                DropdownMenuItem(
                    text = { Text("删除这句", color = Color(0xFFFF8A8A)) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 通用小组件

@Composable
fun TopBar(title: String, subtitle: String, onMenu: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Accent, Color(0xFF1B3F8F)))),
            contentAlignment = Alignment.Center,
        ) { Text("🎙", fontSize = 17.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        RoundIcon("⚙", onMenu)
    }
}

@Composable
fun RoundIcon(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Panel)
            .border(1.dp, Edge, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = TextMain, fontSize = 16.sp) }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(PanelHi),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .background(Brush.horizontalGradient(listOf(Accent, AccentSoft))),
        )
    }
}

@Composable
fun InputDialog(
    title: String,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(label) },
                singleLine = false,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Edge,
                    focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain,
                    cursorColor = AccentSoft,
                ),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ActionChip(
    text: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (primary) Color.White else AccentSoft,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) Accent else PanelHi)
            .border(1.dp, if (primary) Accent else Edge, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else null
    }
}.getOrNull()
