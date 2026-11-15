package dev.dsh.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.dsh.files.ui.fmtSize

/**
 * 常驻前台服务：每 6 秒问一次桥接器收件箱，有新推送就弹通知。
 * 这样 agent 在任意对话里往手机推送文件，用户能立刻收到提醒。
 */
class PushService : Service() {

    private var worker: Thread? = null
    @Volatile private var running = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(SERVICE_NOTIF_ID, buildServiceNotification("正在监听电脑推送…"))
        if (worker == null) {
            running = true
            worker = Thread { loop() }.also {
                it.isDaemon = true
                it.start()
            }
        }
        return START_STICKY
    }

    private fun loop() {
        val prefs = Prefs(this)
        val seen = mutableSetOf<String>()
        var failures = 0
        while (running) {
            if (!prefs.paired || !prefs.pushEnabled) {
                stopSelf()
                return
            }
            try {
                val firstSync = prefs.pushSince <= 0L
                val since = if (firstSync) null else (prefs.pushSince - 2L).coerceAtLeast(0L)
                val result = Api.inbox(prefs.base, prefs.token, since)
                if (firstSync) {
                    // 首次启动只校准时间线，不补发历史通知
                    seen.addAll(result.items.map { it.id })
                    prefs.pushSince = result.serverTime
                } else {
                    result.items
                        .filter { it.id !in seen }
                        .sortedBy { it.pushedAt }
                        .forEach {
                            seen.add(it.id)
                            postItemNotification(it)
                        }
                    if (seen.size > 500) seen.clear()
                    prefs.pushSince = maxOf(prefs.pushSince, result.serverTime)
                }
                failures = 0
                updateServiceNotification("已连接 · 有新文件会弹通知")
                Thread.sleep(6_000)
            } catch (t: Throwable) {
                failures++
                updateServiceNotification("连接中断，正在重试…")
                try {
                    Thread.sleep(if (failures > 3) 20_000 else 8_000)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun postItemNotification(item: InboxItem) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_inbox", true)
        }
        val pending = PendingIntent.getActivity(
            this,
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = if (item.note.isNotBlank()) item.note else "点击查看并下载（" + fmtSize(item.size) + "）"
        val notification = Notification.Builder(this, CHANNEL_INBOX)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("📥 " + item.name)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(item.id.hashCode() and 0x7FFFFFFF, notification)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "推送监听",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持与电脑的连接，接收 agent 推送的文件"
                    setShowBadge(false)
                },
            )
        }
        if (nm.getNotificationChannel(CHANNEL_INBOX) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INBOX,
                    "电脑推送文件",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "agent 从电脑推送到手机的文件"
                    enableVibration(true)
                },
            )
        }
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildServiceNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PushService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭推送", stop).build())
            .build()
    }

    private fun updateServiceNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(SERVICE_NOTIF_ID, buildServiceNotification(text))
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_SERVICE = "dsh_files_service"
        const val CHANNEL_INBOX = "dsh_files_inbox"
        const val SERVICE_NOTIF_ID = 2101
        const val ACTION_STOP = "dev.dsh.files.STOP_PUSH"

        fun start(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(Intent(ctx, PushService::class.java))
            } else {
                ctx.startService(Intent(ctx, PushService::class.java))
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, PushService::class.java))
        }
    }
}
