package dev.dsh.recorder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Ink = Color(0xFF0B0F14)
val Panel = Color(0xFF141C27)
val PanelHi = Color(0xFF1C2836)
val Edge = Color(0xFF263445)
val Accent = Color(0xFF2F6FED)
val AccentSoft = Color(0xFF79A8FF)
val Mint = Color(0xFF39D98A)
val Amber = Color(0xFFF5A524)
val Violet = Color(0xFF8B7CFF)
val Pink = Color(0xFFE36FA6)
val Muted = Color(0xFF8FA1B8)
val TextMain = Color(0xFFE6EDF6)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Ink,
            surface = Panel,
            onPrimary = Color.White,
            onBackground = TextMain,
            onSurface = TextMain,
        ),
    ) {
        content()
    }
}

fun fmtDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun fmtTime(ms: Long): String {
    if (ms <= 0) return "-"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}

fun fmtSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    return String.format(Locale.getDefault(), "%.1f MB", kb / 1024)
}
