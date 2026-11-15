package dev.dsh.files

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 手机上保存过的下载记录（用于「已下载」列表与随时重新打开）。 */
data class DownloadedFile(
    val name: String,
    val uri: String,
    val mime: String,
    val size: Long,
    val time: Long,
)

object DownloadHistory {

    private const val PREF = "dsh_files"
    private const val KEY = "download_history"
    private const val MAX = 40

    fun list(context: Context): List<DownloadedFile> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                DownloadedFile(
                    name = obj.optString("name"),
                    uri = obj.optString("uri"),
                    mime = obj.optString("mime"),
                    size = obj.optLong("size"),
                    time = obj.optLong("time"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, file: DownloadedFile) {
        val items = list(context).toMutableList()
        items.removeAll { it.uri == file.uri || (it.name == file.name && it.size == file.size) }
        items.add(0, file)
        val array = JSONArray()
        items.take(MAX).forEach { item ->
            array.put(
                JSONObject().apply {
                    put("name", item.name)
                    put("uri", item.uri)
                    put("mime", item.mime)
                    put("size", item.size)
                    put("time", item.time)
                },
            )
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }

    fun remove(context: Context, uri: String) {
        val items = list(context).filterNot { it.uri == uri }
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("name", item.name)
                    put("uri", item.uri)
                    put("mime", item.mime)
                    put("size", item.size)
                    put("time", item.time)
                },
            )
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
