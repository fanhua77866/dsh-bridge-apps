package dev.dsh.recorder

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 电脑端实时识别返回的逐字状态。status: 0未读 1读对 2读错 */
data class AsrChar(
    val index: Int,
    val ch: String,
    val startMs: Long,
    val endMs: Long,
    val status: Int,
)

data class AsrState(
    val matched: Int,
    val total: Int,
    val chars: List<AsrChar>,
    val transcript: String,
    val done: Boolean,
)

/** 极简 HTTP 客户端：连电脑桥接器的 asr 接口（电脑端跑 faster-whisper）。 */
class AsrClient(private val base: String) {

    private var session: String? = null
    var total: Int = 0
        private set

    fun start(script: String, sampleRate: Int, model: String = "medium", mode: String = "loose"): Boolean {
        val body = JSONObject()
            .put("script", script)
            .put("sampleRate", sampleRate)
            .put("model", model)
            .put("mode", mode)
        val resp = post("/asr/session/start", body) ?: return false
        val sid = resp.optString("session", "")
        if (sid.isEmpty()) return false
        session = sid
        total = resp.optInt("total", 0)
        return true
    }

    fun sendChunk(pcm: ByteArray, seq: Int): AsrState? {
        val sid = session ?: return null
        val body = JSONObject()
            .put("session", sid)
            .put("seq", seq)
            .put("pcm", Base64.encodeToString(pcm, Base64.NO_WRAP))
        val resp = post("/asr/chunk", body) ?: return null
        return parse(resp)
    }

    fun end(): AsrState? {
        val sid = session ?: return null
        session = null
        val resp = post("/asr/session/end", JSONObject().put("session", sid)) ?: return null
        return parse(resp)
    }

    private fun parse(obj: JSONObject): AsrState {
        val arr: JSONArray = obj.optJSONArray("chars") ?: JSONArray()
        val chars = ArrayList<AsrChar>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            chars.add(
                AsrChar(
                    index = o.optInt("i", -1),
                    ch = o.optString("ch", ""),
                    startMs = o.optLong("startMs", 0),
                    endMs = o.optLong("endMs", 0),
                    status = o.optInt("status", 1),
                ),
            )
        }
        return AsrState(
            matched = obj.optInt("matched", 0),
            total = obj.optInt("total", 0),
            chars = chars,
            transcript = obj.optString("transcript", ""),
            done = obj.optBoolean("done", false),
        )
    }

    private fun post(path: String, body: JSONObject): JSONObject? {
        return try {
            val conn = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 4000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) null else JSONObject(text)
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        /** 探测电脑 ASR 是否就绪（首次会触发电脑端加载模型，可能要等十几秒）。 */
        fun probe(base: String, timeoutMs: Int = 60_000): JSONObject? {
            return try {
                val conn = (URL(base.trimEnd('/') + "/asr/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 3000
                    readTimeout = timeoutMs
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                if (code in 200..299) JSONObject(text) else null
            } catch (t: Throwable) {
                null
            }
        }
    }
}
