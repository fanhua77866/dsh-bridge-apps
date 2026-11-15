package dev.dsh.recorder

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL

/** 在同一 Wi-Fi 的 /24 网段里快速探测电脑端桥接器（/_bridge/info）。 */
object Discover {

    private const val PORT = 8799

    suspend fun scan(context: Context, port: Int = PORT): String? = withContext(Dispatchers.IO) {
        val subnet = localSubnet(context) ?: return@withContext null
        val ip = subnet.first
        val parts = ip.split(".")
        if (parts.size != 4) return@withContext null
        val self = parts[3].toIntOrNull() ?: return@withContext null
        val hosts = (1..254)
            .filter { it != self }
            .map { "${parts[0]}.${parts[1]}.${parts[2]}.$it" }
        coroutineScope {
            hosts.map { host -> async(Dispatchers.IO) { probe(host, port) } }
                .awaitAll()
                .firstOrNull { it != null }
        }
    }

    private fun localSubnet(context: Context): Pair<String, Int>? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
            val linkAddress = lp.linkAddresses.firstOrNull {
                it.address is Inet4Address && !it.address.isLoopbackAddress
            } ?: return null
            val host = linkAddress.address.hostAddress ?: return null
            host to linkAddress.prefixLength
        } catch (t: Throwable) {
            null
        }
    }

    private fun probe(host: String, port: Int): String? {
        return try {
            val url = URL("http://$host:$port/_bridge/info")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 700
                readTimeout = 900
            }
            try {
                if (conn.responseCode != 200) {
                    null
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    if (body.contains("\"ok\": true") && body.contains("version")) {
                        "http://$host:$port"
                    } else {
                        null
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (t: Throwable) {
            null
        }
    }
}
