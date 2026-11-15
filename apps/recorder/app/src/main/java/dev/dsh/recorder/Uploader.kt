package dev.dsh.recorder

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 桥接器配对 / 上传项目 / App 自更新。 */
object Uploader {

    data class UpdateInfo(val versionName: String, val versionCode: Int, val size: Long)

    fun normalizeBase(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return try {
            val u = URL(s)
            val port = if (u.port > 0) u.port else 8799
            "${u.protocol}://${u.host}:$port"
        } catch (t: Throwable) {
            null
        }
    }

    fun pair(base: String, pin: String): String {
        val url = URL("$base/_bridge/pair")
        val payload = JSONObject().put("pin", pin).put("name", "DSH 录音").toString()
            .toByteArray(Charsets.UTF_8)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("配对失败（HTTP $code）")
            val ok = runCatching { JSONObject(body).optBoolean("ok", false) }.getOrDefault(false)
            if (!ok) throw IllegalStateException("配对失败")
            val cookies = conn.headerFields.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value.orEmpty()
            return cookies.firstOrNull { it.startsWith("dsh_phone=") }
                ?.substringAfter("=")?.substringBefore(";")
                ?: throw IllegalStateException("桥接器没有下发设备 cookie")
        } finally {
            conn.disconnect()
        }
    }

    fun upload(base: String, token: String, dir: String, name: String, file: File): String {
        val url = buildString {
            append(base.trimEnd('/'))
            append("/files/upload?dir=").append(URLEncoder.encode(dir, "UTF-8"))
            append("&name=").append(URLEncoder.encode(name, "UTF-8"))
        }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 120000
            doOutput = true
            setFixedLengthStreamingMode(file.length())
            setRequestProperty("Cookie", "dsh_phone=$token")
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("上传失败（HTTP $code）")
            return runCatching { JSONObject(body).optString("path") }.getOrDefault("")
        } finally {
            conn.disconnect()
        }
    }

    fun checkUpdate(base: String): UpdateInfo? = runCatching {
        val url = URL(base.trimEnd('/') + "/app/recorder/version")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            UpdateInfo(
                versionName = json.optString("versionName"),
                versionCode = json.optInt("versionCode"),
                size = json.optLong("size"),
            )
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    fun downloadApk(
        context: android.content.Context,
        base: String,
        cacheDir: File,
        expectedVersionCode: Int,
    ): File {
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        // 按版本号命名，避免安装器打开上一次的旧文件
        val target = File(dir, "recorder-update-$expectedVersionCode.apk")
        val conn = (URL(base.trimEnd('/') + "/app/recorder/latest.apk").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 120000
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("下载失败（HTTP ${conn.responseCode}）")
            conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        // 下载后校验 APK 里真实的版本号，防止拿到旧文件却提示"已下载"
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(target.absolutePath, 0)
        val realCode = info?.longVersionCode ?: -1L
        val realName = info?.versionName ?: "?"
        if (expectedVersionCode > 0 && realCode != expectedVersionCode.toLong()) {
            target.delete()
            throw IllegalStateException("下载到的安装包版本不对（期望 $expectedVersionCode，实际 $realCode / $realName），请确认电脑已发布最新 APK")
        }
        // 清掉旧版本的安装包缓存
        dir.listFiles()?.forEach { old ->
            if (old != target && old.name.startsWith("recorder-update-")) old.delete()
        }
        return target
    }

    fun installApk(context: Context, file: File): String? = runCatching {
        val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        // 优先用安装器专用 action；部分 ROM 只认 ACTION_VIEW，失败再回退
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(installIntent)
            null
        } catch (t: Throwable) {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(view)
            null
        }
    }.getOrElse { "无法调起安装器：${it.message}" }
}
