package dev.dsh.files

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 直接下载到手机「Download/DSH文件」：API 29+ 走 MediaStore，无需存储权限。 */
object Downloads {

    data class Outcome(
        val folder: String,
        val fileName: String,
        val uri: String,
        val size: Long,
    ) {
        val savedPath: String get() = "$folder/$fileName"
    }

    /**
     * @param requestPath 相对桥接器的路径，例如
     *   "/files/download?path=..." / "/files/zip?path=..." / "/files/inbox/<id>"
     * @param fileName 手机端保存的文件名（含扩展名）
     */
    suspend fun save(
        context: Context,
        base: String,
        token: String,
        requestPath: String,
        fileName: String,
        onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        val url = Api.trimBase(base) + requestPath
        val safeName = sanitize(fileName).ifBlank { "download" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 300000
            setRequestProperty("Cookie", "dsh_phone=$token")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException("电脑返回 HTTP $code")
            val total = conn.contentLengthLong
            val extension = safeName.substringAfterLast('.', "").lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, conn, safeName, mime, total, onProgress)
            } else {
                saveToPublicDir(context, conn, safeName, total, onProgress)
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        conn: HttpURLConnection,
        fileName: String,
        mime: String,
        total: Long,
        onProgress: (Int) -> Unit,
    ): Outcome {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSH文件")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw ApiException("无法创建下载文件")
        try {
            val written = resolver.openOutputStream(uri)?.use { output ->
                copyWithProgress(conn, output, total, onProgress)
            } ?: throw ApiException("无法写入下载文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Outcome("Download/DSH文件", fileName, uri.toString(), written)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicDir(
        context: Context,
        conn: HttpURLConnection,
        fileName: String,
        total: Long,
        onProgress: (Int) -> Unit,
    ): Outcome {
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "DSH文件",
        )
        try {
            if (!folder.exists()) folder.mkdirs()
            val target = File(folder, fileName)
            FileOutputStream(target).use { output ->
                copyWithProgress(conn, output, total, onProgress)
            }
            return Outcome("Download/DSH文件", fileName, android.net.Uri.fromFile(target).toString(), target.length())
        } catch (t: SecurityException) {
            val fallback = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                "DSH文件",
            )
            if (!fallback.exists()) fallback.mkdirs()
            val target = File(fallback, fileName)
            FileOutputStream(target).use { output ->
                copyWithProgress(conn, output, total, onProgress)
            }
            return Outcome("App 专属目录/DSH文件", fileName, android.net.Uri.fromFile(target).toString(), target.length())
        }
    }

    private fun copyWithProgress(
        conn: HttpURLConnection,
        output: OutputStream,
        total: Long,
        onProgress: (Int) -> Unit,
    ): Long {
        var written = 0L
        conn.inputStream.use { input ->
            val buffer = ByteArray(64 * 1024)
            var lastPercent = -1
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                output.write(buffer, 0, count)
                written += count
                if (total > 0) {
                    val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent)
                    }
                }
            }
            output.flush()
        }
        return written
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim()
        return when {
            cleaned.isBlank() -> "download"
            cleaned.length > 120 -> cleaned.take(117) + "…"
            else -> cleaned
        }
    }
}
