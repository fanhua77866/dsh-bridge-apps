package dev.dsh.files.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.dsh.files.FileEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Ink = Color(0xFF0B0F14)
val Deep = Color(0xFF101B2D)
val Panel = Color(0xFF111823)
val PanelHi = Color(0xFF172234)
val Edge = Color(0xFF243247)
val Accent = Color(0xFF2F6FED)
val AccentSoft = Color(0xFF7FB2FF)
val Mint = Color(0xFF3DDC97)
val Amber = Color(0xFFF5B84C)
val Pink = Color(0xFFFF8FB1)
val Violet = Color(0xFFB58CFF)
val Muted = Color(0xFF8FA1B8)
val TextMain = Color(0xFFE6EDF6)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondary = AccentSoft,
            background = Ink,
            onBackground = TextMain,
            surface = Panel,
            onSurface = TextMain,
            surfaceVariant = PanelHi,
            onSurfaceVariant = Muted,
            outline = Edge,
        ),
        typography = Typography(),
        content = content,
    )
}

fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[index])
}

fun fmtTime(seconds: Long): String {
    if (seconds <= 0L) return ""
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
    } catch (t: Throwable) {
        ""
    }
}

fun fmtAgo(seconds: Long): String {
    if (seconds <= 0L) return ""
    val delta = (System.currentTimeMillis() / 1000 - seconds).coerceAtLeast(0)
    return when {
        delta < 60 -> "刚刚"
        delta < 3600 -> "${delta / 60} 分钟前"
        delta < 86400 -> "${delta / 3600} 小时前"
        delta < 86400 * 30 -> "${delta / 86400} 天前"
        else -> fmtTime(seconds).substringBefore(' ')
    }
}

data class FileVisual(val glyph: String, val color: Color)

fun visualFor(entry: FileEntry): FileVisual {
    if (entry.dir) return FileVisual("📁", Amber)
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "heic", "ico" -> FileVisual("🖼", Pink)
        "mp4", "mov", "mkv", "avi", "webm", "flv", "m4v" -> FileVisual("🎬", Violet)
        "mp3", "wav", "flac", "aac", "m4a", "ogg" -> FileVisual("🎵", Mint)
        "zip", "rar", "7z", "tar", "gz", "xz", "bz2" -> FileVisual("🗜", Violet)
        "apk" -> FileVisual("📦", Amber)
        "pdf" -> FileVisual("📕", Pink)
        "doc", "docx", "rtf" -> FileVisual("📘", AccentSoft)
        "xls", "xlsx", "csv" -> FileVisual("📗", Mint)
        "ppt", "pptx" -> FileVisual("📙", Amber)
        "txt", "md", "log", "json", "yaml", "yml", "xml", "toml", "py", "kt", "js", "ts",
        "java", "c", "cpp", "h", "html", "css", "sh", "ps1", "bat" -> FileVisual("📄", AccentSoft)
        "exe", "msi", "dll" -> FileVisual("⚙", Muted)
        else -> FileVisual("📄", Muted)
    }
}
