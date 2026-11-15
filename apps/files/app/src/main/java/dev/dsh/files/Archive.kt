package dev.dsh.files

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class ArchiveEntryInfo(
    val path: String,
    val name: String,
    val size: Long,
)

/** zip 压缩包读取：列表 / 解压单文件 / 解压全部。中文文件名兼容 UTF-8 与 GBK。 */
object Archive {

    private const val MAX_ENTRIES = 8000
    private const val MAX_TOTAL = 2L * 1024 * 1024 * 1024

    fun isZip(name: String, mime: String?): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext == "zip" ||
            mime == "application/zip" ||
            mime == "application/x-zip-compressed"
    }

    suspend fun copyToCache(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "archive").apply { mkdirs() }
        val target = File(dir, "a-" + System.currentTimeMillis() + ".zip")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw ApiException("读取压缩包失败")
        target
    }

    private fun open(file: File): ZipFile? {
        for (charset in listOf(Charsets.UTF_8, Charset.forName("GBK"))) {
            try {
                return ZipFile(file, charset)
            } catch (t: Throwable) {
                // 换一种字符集再试
            }
        }
        return null
    }

    suspend fun list(file: File): List<ArchiveEntryInfo> = withContext(Dispatchers.IO) {
        val zip = open(file) ?: throw ApiException("不是有效的 zip 压缩包")
        zip.use { zf ->
            val result = ArrayList<ArchiveEntryInfo>()
            val enumeration = zf.entries()
            var count = 0
            while (enumeration.hasMoreElements() && count < MAX_ENTRIES) {
                val entry = enumeration.nextElement() ?: continue
                val raw = entry.name.replace('\\', '/')
                if (raw.startsWith("__MACOSX/") || raw.endsWith("/")) continue
                result.add(
                    ArchiveEntryInfo(
                        path = raw,
                        name = raw.substringAfterLast('/'),
                        size = entry.size.coerceAtLeast(0L),
                    ),
                )
                count++
            }
            result
        }
    }

    /** 解压单个条目到 cache/preview，返回文件。 */
    suspend fun extractEntry(context: Context, file: File, path: String): File =
        withContext(Dispatchers.IO) {
            val zip = open(file) ?: throw ApiException("不是有效的 zip 压缩包")
            zip.use { zf ->
                var found: ZipEntry? = null
                val enumeration = zf.entries()
                while (enumeration.hasMoreElements()) {
                    val entry = enumeration.nextElement() ?: continue
                    if (entry.name.replace('\\', '/') == path) {
                        found = entry
                        break
                    }
                }
                val entry = found ?: throw ApiException("压缩包内找不到：$path")
                // 放到一个独立子目录里，文件名保持原样（分享到微信时显示正常）
                val baseDir = File(context.cacheDir, "preview").apply { mkdirs() }
                val subDir = File(baseDir, "e-" + System.currentTimeMillis() + "-" + (0..999).random())
                subDir.mkdirs()
                val baseName = path.substringAfterLast('/').ifBlank { "entry" }
                val target = File(subDir, baseName)
                zf.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }
        }

    /** 解压全部到 App 专属目录（Android/data/dev.dsh.files/files/unzip/…）。 */
    suspend fun extractAll(
        context: Context,
        file: File,
        zipName: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val base = File(context.getExternalFilesDir(null) ?: context.filesDir, "unzip")
        val folderName = sanitize(zipName.substringBeforeLast('.').ifBlank { "archive" })
        val dest = File(base, folderName + "-" + (System.currentTimeMillis() / 1000))
        dest.mkdirs()
        val zip = open(file) ?: throw ApiException("不是有效的 zip 压缩包")
        zip.use { zf ->
            val files = ArrayList<ZipEntry>()
            val enumeration = zf.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement() ?: continue
                if (!entry.isDirectory && !entry.name.startsWith("__MACOSX/")) {
                    files.add(entry)
                }
                if (files.size >= MAX_ENTRIES) break
            }
            var total = 0L
            var done = 0
            files.forEach { entry ->
                val rel = entry.name.replace('\\', '/').trimStart('/')
                if (!rel.contains("..")) {
                    val target = File(dest, rel)
                    target.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    total += target.length()
                    if (total > MAX_TOTAL) throw ApiException("解压后超过 2GB 限制")
                }
                done++
                onProgress(if (files.isNotEmpty()) done * 100 / files.size else 100)
            }
        }
        dest
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim()
        return cleaned.ifBlank { "archive" }.take(60)
    }
}
