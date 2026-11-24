package app.pocketagent.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * 回复期间的前台服务。
 *
 * Android 会把切到后台的进程当成 cached process 冻结，网络请求随之停摆、最后超时，
 * 用户看到的就是“切出去/锁屏一下回答就中断并报错”。这里在整轮回复期间挂一个前台通知，
 * 顺带持有 partial wake lock：锁屏后 CPU 不休眠，流式请求能一直跑到结束。
 */
class ChatRunService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        synchronized(runs) {
            // startForegroundService 可能在任务结束后才送达，仍须先兑现前台启动。
            val text = runs.values.lastOrNull() ?: DEFAULT_TEXT
            startForeground(NOTIFICATION_ID, buildNotification(this, text))
            if (runs.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            } else {
                acquireWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketAgent:reply").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "chat_run"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_START = "app.pocketagent.android.CHAT_RUN_START"
        private const val DEFAULT_TEXT = "正在处理回复，后台继续运行"
        private val runs = linkedMapOf<Any, String>()
        /** 兜底上限：即使异常退出没走 stop，唤醒锁也会在一小时后自动释放。 */
        private const val MAX_WAKE_LOCK_MS = 60L * 60L * 1000L

        /** 在用户操作入口调用，每个任务仅释放自己持有的保活。 */
        fun start(context: Context, text: String = DEFAULT_TEXT): AutoCloseable {
            val app = context.applicationContext
            val token = Any()
            synchronized(runs) {
                val needsStart = runs.isEmpty()
                runs[token] = text
                if (needsStart) {
                    val intent = Intent(app, ChatRunService::class.java).setAction(ACTION_START)
                    try {
                        app.startForegroundService(intent)
                    } catch (e: RuntimeException) {
                        runs.remove(token)
                        Log.e("ChatRunService", "无法启动后台保活服务", e)
                    }
                }
            }
            return AutoCloseable {
                synchronized(runs) {
                    if (runs.remove(token) != null && runs.isEmpty()) {
                        // 后台完成时使用 stopService，避免后台启动服务限制。
                        app.stopService(Intent(app, ChatRunService::class.java))
                    }
                }
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "回复进行中", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "回复期间保持连接，切到后台或锁屏也不会中断"
                    setShowBadge(false)
                },
            )
        }

        private fun buildNotification(context: Context, text: String): Notification {
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("PocketAgent 正在工作")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        }
    }
}
