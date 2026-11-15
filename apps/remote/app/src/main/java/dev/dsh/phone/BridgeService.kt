package dev.dsh.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 常驻前台服务：每隔一段时间问一次桥接器「上游还活着吗」，
 * 把状态放进常驻通知。App 退到后台也不会立刻被系统回收连接。
 */
class BridgeService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = true
    private var lastText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("正在连接…"))
        if (worker == null) {
            running = true
            worker = Thread { loop() }.also { it.isDaemon = true; it.start() }
        }
        return START_STICKY
    }

    private fun loop() {
        val prefs = Prefs(this)
        while (running) {
            if (!prefs.paired || prefs.base.isBlank()) {
                stopSelf()
                return
            }
            val text = probe(prefs.base)
            if (text != lastText) {
                lastText = text
                notify(buildNotification(text))
            }
            try {
                Thread.sleep(30_000)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun probe(base: String): String {
        return try {
            val url = URL(base.trimEnd('/') + "/_bridge/info")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                CookieManager.getInstance().getCookie(base)?.let {
                    setRequestProperty("Cookie", it)
                }
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                "桥接器异常 · HTTP $code"
            } else {
                val json = JSONObject(body)
                when {
                    !json.optBoolean("upstream", false) -> "上游未就绪"
                    json.optBoolean("paired", false) -> "已连接 · 遥控中就绪"
                    else -> "已连接 · 待配对"
                }
            }
        } catch (t: Throwable) {
            "连不上桥接器"
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                description = getString(R.string.channel_status_desc)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null, getString(R.string.action_stop), stop,
                ).build(),
            )
            .build()
    }

    private fun notify(n: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "dsh_phone_status"
        const val NOTIF_ID = 1001
        const val ACTION_STOP = "dev.dsh.phone.STOP"

        fun start(ctx: android.content.Context) {
            val intent = Intent(ctx, BridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }
    }
}
