package dev.dsh.recorder.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.dsh.recorder.AsrClient
import dev.dsh.recorder.AudioDevices
import dev.dsh.recorder.BuildConfig
import dev.dsh.recorder.Discover
import dev.dsh.recorder.Prefs
import dev.dsh.recorder.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsDialog(
    prefs: Prefs,
    onClose: () -> Unit,
    onCheckUpdate: () -> Unit,
    onRequestUpdateCheck: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices = remember { AudioDevices.list(context) }

    var base by remember { mutableStateOf(prefs.base) }
    var altBase by remember { mutableStateOf(prefs.altBase) }
    var pin by remember { mutableStateOf(prefs.lastPin) }
    var uploadDir by remember { mutableStateOf(prefs.uploadDir) }
    var pairing by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var asrChecking by remember { mutableStateOf(false) }
    var asrStatus by remember { mutableStateOf("") }
    var discovered by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (base.isBlank()) {
            discovering = true
            val found = runCatching { withContext(Dispatchers.IO) { Discover.scan(context) } }.getOrNull()
            discovering = false
            if (found != null) {
                base = found
                discovered = found
            }
        }
    }

    Dialog(onDismissRequest = onClose) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Panel)
                .border(1.dp, Edge, RoundedCornerShape(22.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "✕",
                        color = Muted,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(onClick = onClose)
                            .padding(8.dp),
                    )
                }

                SectionTitle("输入设备")
                DeviceRow(
                    label = "系统默认",
                    detail = "跟随手机当前选择",
                    selected = prefs.deviceId < 0,
                ) { prefs.deviceId = -1 }
                devices.forEach { device ->
                    DeviceRow(
                        label = device.name,
                        detail = device.typeLabel,
                        selected = prefs.deviceId == device.id,
                    ) { prefs.deviceId = device.id }
                }
                Text(
                    "提示：插拔耳机/USB 麦后重新打开本页刷新列表。",
                    color = Color(0xFF6F8299),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                SectionTitle("采样率")
                ChipsRow(
                    options = listOf(16000 to "16k", 44100 to "44.1k", 48000 to "48k"),
                    selected = prefs.sampleRate,
                ) { prefs.sampleRate = it }
                Text(
                    "TTS 素材建议 48k / 单声道 / 16bit（WAV 无损）。",
                    color = Color(0xFF6F8299),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                SectionTitle("自动断句")
                Text("说完后静音多久算一句结束", color = Muted, fontSize = 11.sp)
                ChipsRow(
                    options = listOf(500 to "0.5s", 800 to "0.8s", 1200 to "1.2s", 1500 to "1.5s"),
                    selected = prefs.silenceMs,
                ) { prefs.silenceMs = it }
                Spacer(Modifier.height(6.dp))
                Text("最短有效语音（太短/咳嗽不算一句）", color = Muted, fontSize = 11.sp)
                ChipsRow(
                    options = listOf(200 to "0.2s", 300 to "0.3s", 500 to "0.5s"),
                    selected = prefs.minSpeechMs,
                ) { prefs.minSpeechMs = it }

                SectionTitle("连录")
                SwitchRow("自动下一句", "保存后自动跳到下一句并开始录", prefs.autoContinue) {
                    prefs.autoContinue = it
                }
                SwitchRow("提示音", "保存/开始录制时响一声", prefs.beep) {
                    prefs.beep = it
                }
                SwitchRow("实时识别（电脑 ASR）", "逐字蓝/读错红；连不上自动退回估算", prefs.asrEnabled) {
                    prefs.asrEnabled = it
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("识别模型", color = Muted, fontSize = 11.sp, modifier = Modifier.width(58.dp))
                    ChipsRow(
                        options = listOf("small" to "快速（默认）", "medium" to "精准·CPU慢"),
                        selected = prefs.asrModel,
                    ) { prefs.asrModel = it }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("错字提示", color = Muted, fontSize = 11.sp, modifier = Modifier.width(58.dp))
                    ChipsRow(
                        options = listOf("loose" to "宽松(不标红)", "normal" to "标准", "strict" to "严格"),
                        selected = prefs.asrStrict,
                    ) { prefs.asrStrict = it }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        asrStatus.ifBlank { "电脑识别：未检测（需电脑端 ASR 服务在跑）" },
                        color = if (asrStatus.contains("就绪")) Mint else Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !asrChecking,
                        onClick = {
                            val target = Uploader.normalizeBase(base)
                            if (target == null) {
                                asrStatus = "先填好电脑地址"
                                return@TextButton
                            }
                            asrChecking = true
                            scope.launch {
                                val h = withContext(Dispatchers.IO) { AsrClient.probe(target) }
                                asrChecking = false
                                asrStatus = if (h != null) {
                                    "就绪：${h.optString("model")} / ${h.optString("device")} / ${h.optString("compute")}"
                                } else {
                                    "连不上电脑识别（录音会用估算模式）"
                                }
                            }
                        },
                    ) { Text(if (asrChecking) "检测中…" else "检测", color = AccentSoft) }
                }

                SectionTitle("上传到电脑（可选）")
                OutlinedTextField(
                    value = base,
                    onValueChange = { base = it.trim() },
                    label = { Text("电脑地址") },
                    placeholder = { Text("http://192.168.1.100:8799 或 Tailscale 地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when {
                            discovering -> "正在同一 Wi-Fi 搜索电脑…"
                            discovered != null -> "已发现：$discovered"
                            else -> "在家/同 Wi-Fi 可自动发现"
                        },
                        color = if (discovered != null) Mint else Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !discovering,
                        onClick = {
                            discovering = true
                            scope.launch {
                                val found = runCatching {
                                    withContext(Dispatchers.IO) { Discover.scan(context) }
                                }.getOrNull()
                                discovering = false
                                if (found != null) {
                                    base = found
                                    discovered = found
                                } else {
                                    Toast.makeText(context, "没找到电脑：确认手机和电脑同一 Wi-Fi", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                    ) { Text(if (discovering) "搜索中…" else "自动发现", color = AccentSoft) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = altBase,
                    onValueChange = { altBase = it.trim() },
                    label = { Text("备用地址（出门/Tailscale）") },
                    placeholder = { Text("http://100.64.0.1:8799") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("配对 PIN") },
                    placeholder = { Text("电脑桥接器窗口里的 6 位数字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = uploadDir,
                    onValueChange = { uploadDir = it.trim() },
                    label = { Text("上传目录（可空）") },
                    placeholder = { Text("留空 = 电脑「手机上传」目录") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = fieldColors(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (prefs.paired) "已配对 · ${prefs.base}" else "默认地址/PIN 已填好，点「连接电脑」即可",
                        color = if (prefs.paired) Mint else Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !pairing,
                        onClick = {
                            val primary = Uploader.normalizeBase(base)
                            val alternate = Uploader.normalizeBase(altBase)
                            if (primary == null && alternate == null) {
                                Toast.makeText(context, "电脑地址格式不对", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            if (pin.length != 6) {
                                Toast.makeText(context, "PIN 是 6 位数字", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            pairing = true
                            scope.launch {
                                val candidates = listOfNotNull(primary, alternate).distinct()
                                var working: String? = null
                                var lastError: String? = null
                                var token = ""
                                for (candidate in candidates) {
                                    try {
                                        token = withContext(Dispatchers.IO) { Uploader.pair(candidate, pin) }
                                        working = candidate
                                        break
                                    } catch (t: Throwable) {
                                        lastError = t.message
                                    }
                                }
                                pairing = false
                                if (working == null) {
                                    Toast.makeText(context, lastError ?: "配对失败", Toast.LENGTH_LONG).show()
                                } else {
                                    prefs.base = working
                                    prefs.altBase = candidates.firstOrNull { it != working } ?: ""
                                    prefs.token = token
                                    prefs.lastPin = pin
                                    prefs.uploadDir = uploadDir
                                    Toast.makeText(
                                        context,
                                        if (working == primary) "配对成功" else "主地址连不上，已用备用地址配对",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        },
                    ) { Text(if (pairing) "连接中…" else "连接电脑", color = AccentSoft) }
                }

                SectionTitle("版本")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "DSH 录音 v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCheckUpdate) { Text("检查更新", color = AccentSoft) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = TextMain,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun DeviceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) Accent.copy(alpha = 0.5f) else Edge, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (selected) "◉" else "○", color = if (selected) AccentSoft else Muted, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(detail, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun <T> ChipsRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Text(
                label,
                color = if (active) Color.White else AccentSoft,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) Accent else PanelHi)
                    .border(1.dp, if (active) Accent else Edge, RoundedCornerShape(9.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 12.sp)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = PanelHi,
            ),
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent,
    unfocusedBorderColor = Edge,
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
    cursorColor = AccentSoft,
    focusedLabelColor = AccentSoft,
    unfocusedLabelColor = Muted,
    focusedContainerColor = Color(0xFF0D1522),
    unfocusedContainerColor = Color(0xFF0D1522),
)
