package dev.dsh.files

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class FileEntry(
    val name: String,
    val dir: Boolean,
    val size: Long,
    val mtime: Long,
    val rel: String = "",
    val fullPath: String = "",
)

data class Listing(val path: String, val parent: String?, val entries: List<FileEntry>)
data class RecentResult(val root: String, val items: List<FileEntry>)
data class RootEntry(val label: String, val path: String)
data class InboxItem(
    val id: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val pushedAt: Long,
    val note: String,
)
data class InboxResult(val items: List<InboxItem>, val serverTime: Long)
data class UploadInfo(val defaultDir: String, val maxMb: Int, val roots: List<RootEntry>)
data class UploadedItem(val name: String, val size: Long, val mtime: Long)
data class UploadsResult(val dir: String, val items: List<UploadedItem>)

class ApiException(message: String, val authLost: Boolean = false) : Exception(message)

/** 和电脑端桥接器 /files 接口对话的最小客户端。 */
object Api {

    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 20000

    fun trimBase(raw: String): String = raw.trim().trimEnd('/')

    fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun normalizeBase(raw: String): String? {
        var s = raw.trim()
        if (s.isBlank()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return try {
            val url = URL(s)
            val host = url.host
            if (host.isBlank()) return null
            val port = if (url.port > 0) url.port else 8799
            "${url.protocol}://$host:$port"
        } catch (t: Throwable) {
            null
        }
    }

    /** PIN 配对，成功返回设备 cookie 值。 */
    fun pair(base: String, pin: String): String {
        val url = try {
            URL(trimBase(base) + "/_bridge/pair")
        } catch (t: Throwable) {
            throw ApiException("地址格式不对")
        }
        val payload = JSONObject().put("pin", pin).put("name", "DSH 文件 App").toString()
        val conn = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        } catch (t: Throwable) {
            throw ApiException("连不上电脑：${t.message ?: "网络错误"}")
        }
        val code: Int
        val body: String
        val cookies: List<String>
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            code = conn.responseCode
            body = readBody(conn, code)
            cookies = conn.headerFields.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value.orEmpty()
        } catch (t: Throwable) {
            throw ApiException("连不上桥接器：${t.message ?: "网络错误"}")
        } finally {
            conn.disconnect()
        }
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
            throw ApiException(if (msg.isBlank()) "连接失败（HTTP $code）" else msg)
        }
        if (!runCatching { JSONObject(body).optBoolean("ok", false) }.getOrDefault(false)) {
            throw ApiException("桥接器返回异常")
        }
        return cookies.firstOrNull { it.startsWith("dsh_phone=") }
            ?.substringAfter("=")?.substringBefore(";")
            ?: throw ApiException("桥接器没有下发设备 cookie")
    }

    fun roots(base: String, token: String): List<RootEntry> {
        val (code, body) = get(base, token, "/files/api/roots")
        if (code == 401) throw ApiException("配对已失效，请重新连接", authLost = true)
        if (code != 200) throw ApiException("读取位置失败（HTTP $code）")
        val arr = parse(body).optJSONArray("roots") ?: return emptyList()
        val list = ArrayList<RootEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(RootEntry(o.optString("label"), o.optString("path")))
        }
        return list
    }

    fun recent(base: String, token: String, limit: Int = 30): RecentResult {
        val (code, body) = get(base, token, "/files/api/recent?limit=$limit")
        if (code == 401) throw ApiException("配对已失效，请重新连接", authLost = true)
        if (code != 200) throw ApiException("读取最近文件失败（HTTP $code）")
        val json = parse(body)
        val arr = json.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<FileEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(
                FileEntry(
                    name = o.optString("name"),
                    dir = false,
                    size = o.optLong("size"),
                    mtime = o.optLong("mtime"),
                    rel = o.optString("rel"),
                    fullPath = o.optString("path"),
                ),
            )
        }
        return RecentResult(json.optString("root"), items)
    }

    fun inbox(base: String, token: String, since: Long? = null): InboxResult {
        val suffix = if (since != null && since > 0L) "?since=$since" else ""
        val (code, body) = get(base, token, "/files/api/inbox$suffix")
        if (code == 401) throw ApiException("配对已失效，请重新连接", authLost = true)
        if (code != 200) throw ApiException("读取收件箱失败（HTTP $code）")
        val json = parse(body)
        val arr = json.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<InboxItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(
                InboxItem(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    size = o.optLong("size"),
                    mtime = o.optLong("mtime"),
                    pushedAt = o.optLong("pushedAt"),
                    note = o.optString("note"),
                ),
            )
        }
        return InboxResult(items, json.optLong("serverTime"))
    }

    fun uploadInfo(base: String, token: String): UploadInfo {
        val (code, body) = get(base, token, "/files/api/upload-info")
        if (code == 401) throw ApiException("配对已失效，请重新连接", authLost = true)
        if (code != 200) throw ApiException("读取上传配置失败（HTTP $code）")
        val json = parse(body)
        val arr = json.optJSONArray("roots") ?: JSONArray()
        val roots = ArrayList<RootEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            roots.add(RootEntry(o.optString("label"), o.optString("path")))
        }
        return UploadInfo(
            defaultDir = json.optString("defaultDir"),
            maxMb = json.optInt("maxMb", 4096),
            roots = roots,
        )
    }

    fun uploads(base: String, token: String, limit: Int = 20): UploadsResult {
        val (code, body) = get(base, token, "/files/api/uploads?limit=$limit")
        if (code == 401) throw ApiException("配对已失效，请重新连接", authLost = true)
        if (code != 200) throw ApiException("读取最近上传失败（HTTP $code）")
        val json = parse(body)
        val arr = json.optJSONArray("items") ?: JSONArray()
        val items = ArrayList<UploadedItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(
                UploadedItem(
                    name = o.optString("name"),
                    size = o.optLong("size"),
                    mtime = o.optLong("mtime"),
                ),
            )
        }
        return UploadsResult(json.optString("dir"), items)
    }

    fun list(base: String, token: String, path: String): Listing {        val (code, body) = get(base, token, "/files/api/list?path=" + enc(path))
        if (code == 401) throw ApiException("配对已失效，请重新连接", authLost = true)
        if (code != 200) throw ApiException("读取目录失败（HTTP $code）")
        val json = parse(body)
        if (!json.optBoolean("ok", false)) {
            throw ApiException(json.optString("error").ifBlank { "读取目录失败" })
        }
        val arr = json.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<FileEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            entries.add(
                FileEntry(
                    name = o.optString("name"),
                    dir = o.optBoolean("dir"),
                    size = o.optLong("size"),
                    mtime = o.optLong("mtime"),
                ),
            )
        }
        return Listing(
            path = json.optString("path").ifBlank { path },
            parent = json.optString("parent").ifBlank { null },
            entries = entries,
        )
    }

    private fun parse(body: String): JSONObject = try {
        JSONObject(body)
    } catch (t: Throwable) {
        throw ApiException("电脑返回的数据看不懂（${t.message}）")
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun get(base: String, token: String, path: String): Pair<Int, String> {
        val url = try {
            URL(trimBase(base) + path)
        } catch (t: Throwable) {
            throw ApiException("地址格式不对")
        }
        val conn = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) setRequestProperty("Cookie", "dsh_phone=$token")
            }
        } catch (t: Throwable) {
            throw ApiException("连不上电脑：${t.message ?: "网络错误"}")
        }
        return try {
            val code = conn.responseCode
            code to readBody(conn, code)
        } catch (e: IOException) {
            throw ApiException("连不上电脑：${e.message ?: "网络错误"}")
        } finally {
            conn.disconnect()
        }
    }
}
