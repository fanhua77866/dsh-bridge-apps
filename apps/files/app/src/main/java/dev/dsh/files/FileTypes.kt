package dev.dsh.files

import android.webkit.MimeTypeMap

enum class FileCategory { IMAGE, VIDEO, AUDIO, TEXT, APK, ARCHIVE, OTHER }

object FileTypes {

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    fun categoryOf(name: String, mime: String? = null): FileCategory {
        val resolved = (mime?.takeIf { it.isNotBlank() } ?: mimeOf(name)).lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            resolved.startsWith("image/") -> FileCategory.IMAGE
            resolved.startsWith("video/") -> FileCategory.VIDEO
            resolved.startsWith("audio/") -> FileCategory.AUDIO
            resolved == "application/vnd.android.package-archive" || ext == "apk" -> FileCategory.APK
            ext in setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso") ||
                resolved in setOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/x-rar-compressed",
                    "application/x-7z-compressed",
                    "application/gzip",
                    "application/x-tar",
                ) -> FileCategory.ARCHIVE
            resolved.startsWith("text/") -> FileCategory.TEXT
            ext in setOf(
                "txt", "md", "log", "json", "xml", "yaml", "yml", "csv", "ini", "conf",
                "kt", "java", "py", "js", "ts", "html", "css", "sh", "ps1", "bat",
                "toml", "srt", "vtt", "properties",
            ) -> FileCategory.TEXT
            else -> FileCategory.OTHER
        }
    }
}
