package dev.dsh.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 从桥接器拉「DSH 文件」新版本：/app/files/version → /app/files/latest.apk。 */
object Updater {

    data class Info(val versionCode: Int, val versionName: String, val size: Long, val url: String)

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE
    fun currentVersionName(): String = BuildConfig.VERSION_NAME

    private fun open(url: String, readTimeout: Int = 15000): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            this.requestMethod = "GET"
            this.connectTimeout = 5000
            this.readTimeout = readTimeout
        }

    /** 有新版本返回 Info，否则 null。 */
    fun check(base: String): Info? {
        if (base.isBlank()) return null
        val conn = open(base.trimEnd('/') + "/app/files/version")
        val body = try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(body)
        val code = json.optInt("versionCode", 0)
        if (code <= currentVersionCode()) return null
        return Info(
            code,
            json.optString("versionName", "?"),
            json.optLong("size", 0),
            json.optString("url"),
        )
    }

    fun download(base: String, info: Info, dir: File, onProgress: (Int) -> Unit = {}): File {
        dir.mkdirs()
        val target = File(dir, "update-${info.versionCode}.apk")
        val url = info.url.ifBlank { base.trimEnd('/') + "/app/files/latest.apk" }
        val conn = open(url, 180000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException("下载更新失败（HTTP $code）")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            var read = 0L
            var last = -1
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        read += count
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != last) {
                                last = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            return target
        } finally {
            conn.disconnect()
        }
    }

    /** 调起系统安装器；返回 null 表示已调起，否则返回给用户看的错误。 */
    fun install(context: Context, apk: File): String? {
        if (!apk.exists()) return "安装包不存在"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "请先允许「安装未知应用」，然后再点一次检查更新"
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            null
        }.getOrElse { "调起安装器失败：${it.message}" }
    }
}
