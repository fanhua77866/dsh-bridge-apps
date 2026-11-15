package dev.dsh.phone

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

/** 从桥接器拉新版本：/app/version → /app/latest.apk。 */
object Updater {

    data class Info(val versionCode: Int, val versionName: String, val size: Long, val url: String)

    fun currentVersionCode(): Int = BuildConfig.VERSION_CODE
    fun currentVersionName(): String = BuildConfig.VERSION_NAME

    private fun open(base: String, path: String, timeout: Int = 15000): HttpURLConnection =
        (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = timeout
        }

    fun check(base: String): Info? {
        if (base.isBlank()) return null
        val c = open(base, "/app/version")
        val body = try {
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val j = JSONObject(body)
        val code = j.optInt("versionCode", 0)
        if (code <= currentVersionCode()) return null
        return Info(code, j.optString("versionName", "?"), j.optLong("size", 0), j.optString("url"))
    }

    fun download(base: String, info: Info, dir: File, onProgress: (Int) -> Unit): File {
        val target = File(dir, "update-${info.versionCode}.apk")
        val c = open(info.url.ifBlank { base.trimEnd('/') + "/app/latest.apk" }, "", 120000)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: info.size
            var read = 0L
            c.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                    }
                }
            }
            return target
        } finally {
            c.disconnect()
        }
    }

    fun install(ctx: Context, apk: File): String? {
        if (!apk.exists()) return "安装包不存在"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "请先允许「安装未知应用」，然后重新点一次更新"
        }
        return runCatching {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            null
        }.getOrElse { "调起安装器失败：${it.message}" }
    }
}
