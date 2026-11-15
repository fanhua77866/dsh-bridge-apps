package dev.dsh.files.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.dsh.files.Api
import dev.dsh.files.ApiException
import dev.dsh.files.Discover
import dev.dsh.files.Prefs
import dev.dsh.files.PushService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ConnectScreen(prefs: Prefs, onConnected: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf(prefs.base) }
    var altBase by remember { mutableStateOf(prefs.altBase) }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Deep, Ink))),
    ) {
        Box(
            modifier = Modifier
                .size(380.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-170).dp)
                .background(
                    Brush.radialGradient(listOf(Accent.copy(alpha = 0.35f), Color.Transparent)),
                    CircleShape,
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(64.dp))
            LogoMark()
            Spacer(Modifier.height(20.dp))
            Text(
                "DSH 文件",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text("电脑上的产出文件，一键下载到手机", color = Muted, fontSize = 14.sp)
            Spacer(Modifier.height(34.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Panel.copy(alpha = 0.94f))
                    .border(1.dp, Edge, RoundedCornerShape(24.dp))
                    .padding(18.dp),
            ) {
                Text("连接电脑", color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = base,
                    onValueChange = {
                        base = it.trim()
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("电脑地址") },
                    placeholder = { Text("http://192.168.1.100:8799") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = altBase,
                    onValueChange = {
                        altBase = it.trim()
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("备用地址（可选，外网/Tailscale）") },
                    placeholder = { Text("http://100.64.0.1:8799") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "在家填局域网地址；出门填电脑的 Tailscale 地址。两个都填时，一个连不上会自动切到另一个。",
                    color = Color(0xFF6F8299),
                    fontSize = 11.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        pin = value.filter { it.isDigit() }.take(8)
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("配对 PIN") },
                    placeholder = { Text("电脑端显示的 6 位数字") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showPin) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(
                            if (showPin) "隐藏" else "显示",
                            color = AccentSoft,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showPin = !showPin }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    },
                    colors = fieldColors(),
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (discovering) "正在搜索同一 Wi-Fi 下的电脑…" else "不知道地址？",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    TextButton(
                        onClick = {
                            if (discovering || busy) return@TextButton
                            discovering = true
                            error = null
                            scope.launch {
                                val found = Discover.scan(context)
                                discovering = false
                                if (found != null) {
                                    base = found
                                    Toast.makeText(context, "已发现电脑：$found", Toast.LENGTH_SHORT).show()
                                } else {
                                    error = "没找到电脑：请确认手机和电脑在同一 Wi-Fi，且桥接器正在运行"
                                }
                            }
                        },
                        enabled = !discovering && !busy,
                    ) {
                        if (discovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = AccentSoft,
                            )
                        } else {
                            Text("自动发现", color = AccentSoft, fontSize = 13.sp)
                        }
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error!!, color = Color(0xFFFF9A9A), fontSize = 13.sp, lineHeight = 19.sp)
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.horizontalGradient(
                                if (busy) listOf(Edge, Edge) else listOf(Accent, Color(0xFF5B8CFF)),
                            ),
                        )
                        .clickable(enabled = !busy) {
                            if (busy) return@clickable
                            val primary = Api.normalizeBase(base)
                            val alternate = Api.normalizeBase(altBase)
                            if (primary == null && alternate == null) {
                                error = "地址格式不对，例如 http://192.168.1.100:8799"
                                return@clickable
                            }
                            if (pin.isBlank()) {
                                error = "请填写配对 PIN（电脑端启动窗口里有）"
                                return@clickable
                            }
                            error = null
                            busy = true
                            scope.launch {
                                val candidates = listOfNotNull(primary, alternate).distinct()
                                var working: String? = null
                                var lastError: String? = null
                                var token = ""
                                for (candidate in candidates) {
                                    try {
                                        token = withContext(Dispatchers.IO) {
                                            Api.pair(candidate, pin)
                                        }
                                        working = candidate
                                        break
                                    } catch (e: ApiException) {
                                        lastError = e.message ?: "连接失败"
                                    } catch (t: Throwable) {
                                        lastError = "连接失败：${t.message ?: "网络错误"}"
                                    }
                                }
                                busy = false
                                if (working == null) {
                                    error = lastError ?: "连接失败"
                                    return@launch
                                }
                                prefs.base = working
                                prefs.altBase = candidates.firstOrNull { it != working } ?: ""
                                prefs.token = token
                                prefs.pushSince = 0L
                                PushService.start(context)
                                Toast.makeText(
                                    context,
                                    if (working == alternate && primary != null) "主地址连不上，已切到备用地址" else "连接成功",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                onConnected()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text("连接电脑", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "电脑端运行「DSH 手机桥接器」后，窗口里会显示地址和 PIN。\n" +
                    "同一 Wi-Fi 填局域网地址；出门在外填电脑的 Tailscale 地址（两个都填会自动切换）。",
                color = Color(0xFF6F8299),
                fontSize = 12.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun LogoMark() {
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF2F6FED), Color(0xFF1B3F8F))))
            .border(1.dp, Color(0x552F6FED), RoundedCornerShape(26.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("📁", fontSize = 38.sp)
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
    focusedPlaceholderColor = Color(0xFF5B6B80),
    unfocusedPlaceholderColor = Color(0xFF5B6B80),
)
