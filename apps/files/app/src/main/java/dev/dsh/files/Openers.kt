package dev.dsh.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object Openers {

    /** 把 APK 复制到 cache 后用 FileProvider 调起系统安装器。 */
    fun installApk(context: Context, uri: Uri, displayName: String): String? {
        return try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "open-" + System.currentTimeMillis() + ".apk")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return "读取安装包失败"
            Updater.install(context, target)
        } catch (t: Throwable) {
            "读取安装包失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    fun openExternal(context: Context, uri: Uri, mime: String): Boolean {
        return runCatching {
            val shareUri = toShareableUri(context, uri)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(shareUri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "用其他应用打开")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * 系统「分享」：ACTION_SEND + content URI。
     * 微信/QQ 这类 App 接收的是「分享」而不是「打开」，压缩包也走这里。
     */
    fun share(context: Context, uri: Uri, mime: String, chooserTitle: String = "分享到"): Boolean {
        return runCatching {
            val shareUri = toShareableUri(context, uri)
            val type = mime.ifBlank { "*/*" }
            val intent = Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, shareUri)
                clipData = android.content.ClipData.newRawUri("DSH 文件", shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, chooserTitle)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    /** 分享缓存里的文件（例如从压缩包内临时解出来的文件）。 */
    fun shareFile(context: Context, file: File, mime: String): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        share(context, uri, mime)
    }.getOrDefault(false)

    private fun toShareableUri(context: Context, uri: Uri): Uri {
        if (uri.scheme == "content") return uri
        val file = File(uri.path ?: return uri)
        return runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".updates", file)
        }.getOrDefault(uri)
    }
}
