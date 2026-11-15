package dev.dsh.files

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 手机 → 电脑 文件上传（流式 POST，带进度）。 */
object Uploader {

    data class Result(val dir: String, val name: String, val path: String, val size: Long)

    suspend fun upload(
        context: Context,
        base: String,
        token: String,
        uri: Uri,
        displayName: String,
        knownSize: Long,
        targetDir: String,
        onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        var size = knownSize
        var sourceUri = uri
        var temp: File? = null
        if (size <= 0) {
            // 拿不到大小就落一份临时文件，保证可以定长流式上传
            val dir = File(context.cacheDir, "upload").apply { mkdirs() }
            temp = File(dir, "u-" + System.currentTimeMillis())
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw ApiException("读取手机文件失败")
            size = temp.length()
            sourceUri = Uri.fromFile(temp)
        }

        val url = buildString {
            append(Api.trimBase(base))
            append("/files/upload?dir=").append(Api.enc(targetDir))
            append("&name=").append(Api.enc(displayName))
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 300000
            doOutput = true
            setFixedLengthStreamingMode(size)
            setRequestProperty("Cookie", "dsh_phone=$token")
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                conn.outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        sent += count
                        val percent = if (size > 0) ((sent * 100) / size).toInt().coerceIn(0, 100) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    output.flush()
                }
            } ?: throw ApiException("读取手机文件失败")

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw ApiException(message?.ifBlank { null } ?: "上传失败（HTTP $code）")
            }
            val json = JSONObject(body)
            Result(
                dir = json.optString("dir"),
                name = json.optString("name"),
                path = json.optString("path"),
                size = json.optLong("size", size),
            )
        } finally {
            conn.disconnect()
            temp?.delete()
        }
    }
}
