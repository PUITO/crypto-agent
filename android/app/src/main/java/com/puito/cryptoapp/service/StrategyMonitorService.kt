package com.puito.cryptoapp.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.puito.cryptoapp.CryptoApp
import com.puito.cryptoapp.data.model.Interval
import com.puito.cryptoapp.notify.NotificationHelper
import kotlinx.coroutines.*

class StrategyMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cryptoapp:monitor").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitor()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startMonitor()
        }
        return START_STICKY
    }

    private fun startMonitor() {
        val repo = (application as CryptoApp).repository
        val settings = repo.loadSettings()
        val symbol = settings.defaultSymbol
        val interval = Interval.fromCode(settings.defaultInterval)
        startForeground(
            NOTIFICATION_ID,
            NotificationHelper.serviceNotification(this, "$symbol ${interval.code} 监控中…"),
        )
        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire(6 * 60 * 60 * 1000L)
        }

        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try {
                    if (!repo.strategyRunning) {
                        updateFg("$symbol ${interval.code} · 请先启动策略")
                    } else {
                        val newMarks = repo.pollAndDetectNewSignals(symbol, interval)
                        newMarks.forEach { mark ->
                            NotificationHelper.notifySignal(
                                this@StrategyMonitorService,
                                symbol,
                                interval.code,
                                mark,
                            )
                        }
                        val st = repo.stats
                        updateFg(
                            "$symbol ${interval.code} · 成交 ${st.signals} · 胜率 ${"%.0f".format(st.winRate * 100)}%",
                        )
                    }
                } catch (e: Exception) {
                    updateFg("监控出错: ${e.message?.take(40)}")
                }
                delay(POLL_MS)
            }
        }
    }

    private fun updateFg(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIFICATION_ID, NotificationHelper.serviceNotification(this, text))
    }

    private fun stopMonitor() {
        job?.cancel()
        job = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopMonitor()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.puito.cryptoapp.STOP_MONITOR"
        private const val NOTIFICATION_ID = 1001
        private const val POLL_MS = 30_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, StrategyMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StrategyMonitorService::class.java).apply { action = ACTION_STOP },
            )
        }
    }
}
