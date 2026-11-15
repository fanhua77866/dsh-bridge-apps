package dev.dsh.recorder

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

data class ImportResult(
    val lines: List<String>,
    val fileCount: Int,
)

/** 导入逐字稿：TXT / MD / CSV，或包含多个文本的 ZIP（按文件名自然排序展开）。 */
object ScriptImporter {

    private val textExtensions = setOf("txt", "md", "markdown", "csv", "log", "rtf")
    private const val MAX_TOTAL_LINES = 5000
    private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024L

    fun import(context: Context, uri: Uri, displayName: String, mime: String?): ImportResult {
        val zipLike = displayName.endsWith(".zip", ignoreCase = true) ||
            (mime?.contains("zip", ignoreCase = true) == true)
        return if (zipLike) importZip(context, uri) else {
            val text = readTextSmart(context, uri) ?: throw IllegalStateException("读取文件失败")
            ImportResult(parseScriptLines(text), 1)
        }
    }

    private fun importZip(context: Context, uri: Uri): ImportResult {
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val temp = File(dir, "script-" + System.currentTimeMillis() + ".zip")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("读取压缩包失败")

            val zip = openZip(temp) ?: throw IllegalStateException("不是有效的 zip 压缩包")
            zip.use { zf ->
                val entries = zf.entries().toList()
                    .filter { !it.isDirectory }
                    .filter { entry ->
                        entry.name.substringAfterLast('.', "").lowercase() in textExtensions
                    }
                    .sortedWith { a, b -> naturalCompare(a.name, b.name) }
                if (entries.isEmpty()) throw IllegalStateException("压缩包里没有 TXT/MD/CSV 文本")

                val all = ArrayList<String>()
                var totalBytes = 0L
                var fileCount = 0
                for (entry in entries) {
                    if (entry.size > 0) totalBytes += entry.size
                    if (totalBytes > MAX_TOTAL_BYTES) break
                    val bytes = runCatching {
                        zf.getInputStream(entry).use { it.readBytes() }
                    }.getOrNull() ?: continue
                    val text = decodeSmart(bytes)
                    val lines = parseScriptLines(text)
                    if (lines.isEmpty()) continue
                    fileCount++
                    all.addAll(lines)
                    if (all.size >= MAX_TOTAL_LINES) break
                }
                if (all.isEmpty()) throw IllegalStateException("压缩包里没解析到句子")
                return ImportResult(all.take(MAX_TOTAL_LINES), fileCount)
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun openZip(file: File): ZipFile? {
        for (charset in listOf(Charsets.UTF_8, Charset.forName("GBK"))) {
            runCatching { ZipFile(file, charset) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** 文件名自然排序：01 < 02 < 10（数字按数值比较）。 */
    fun naturalCompare(a: String, b: String): Int {
        val token = Regex("""(\d+|\D+)""")
        val ta = token.findAll(a).map { it.value }.toList()
        val tb = token.findAll(b).map { it.value }.toList()
        val n = minOf(ta.size, tb.size)
        for (i in 0 until n) {
            val x = ta[i]
            val y = tb[i]
            val nx = x.toLongOrNull()
            val ny = y.toLongOrNull()
            val cmp = when {
                nx != null && ny != null -> nx.compareTo(ny)
                else -> x.compareTo(y, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return ta.size.compareTo(tb.size)
    }

    fun readTextSmart(context: Context, uri: Uri): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        decodeSmart(bytes)
    }.getOrNull()

    fun decodeSmart(bytes: ByteArray): String {
        var text = bytes.toString(Charsets.UTF_8)
        if (text.contains('\uFFFD')) {
            val gbk = runCatching { bytes.toString(charset("GBK")) }.getOrNull()
            if (gbk != null && !gbk.contains('\uFFFD')) text = gbk
        }
        return text
    }

    /** 一行一句；去掉空行、# 注释、行首编号/项目符号。 */
    fun parseScriptLines(raw: String): List<String> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .map { line ->
                var s = line
                repeat(2) {
                    s = s.replace(Regex("""^\s*\d{1,4}\s*[.、)．:：]?\s+"""), "")
                    s = s.replace(Regex("""^\s*[-*·]\s+"""), "")
                    s = s.trim()
                }
                s
            }
            .filter { it.isNotEmpty() }
            .toList()
    }
}
