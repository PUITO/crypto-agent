package com.puito.cryptoagent.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import com.puito.cryptoagent.AgentApp
import com.puito.cryptoagent.notify.Notify
import kotlinx.coroutines.*

/**
 * 前台服务：清理界面后仍可轮询行情、信号通知与（可选）自动下单。
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notify.channels(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cryptoagent:mon").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopMon(); stopSelf(); return START_NOT_STICKY
        }
        startMon()
        return START_STICKY
    }

    private fun startMon() {
        val repo = (application as AgentApp).repo
        val s = repo.settings()
        startForeground(1001, Notify.monitor(this, "${s.symbol} ${s.interval} 后台监控中"))
        if (wake?.isHeld != true) wake?.acquire(12 * 60 * 60 * 1000L)
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                try {
                    val st = repo.settings()
                    if (!st.backgroundEnabled) {
                        update("后台已关闭")
                    } else if (!st.strategyRunning) {
                        update("${st.symbol} · 策略暂停")
                    } else {
                        val fresh = repo.poll()
                        fresh.forEach {
                            Notify.signal(
                                this@MonitorService,
                                st.symbol,
                                it.interval,
                                it.mark,
                                it.intervalWinRatePct,
                                it.intervalTrades,
                                it.ai,
                                vibrate = st.notifyVibrate,
                            )
                        }
                        val stats = repo.stats
                        val auto = if (st.hibt.autoTrade) "·自动下单" else ""
                        update("${st.symbol} 全周期监控 · 当前界面${st.interval} 成交${stats.trades} 胜率${"%.0f".format(stats.winRate * 100)}%$auto")
                    }
                } catch (e: Exception) {
                    update("监控异常: ${e.message?.take(40)}")
                }
                delay(12_000L) // 约 12s 一轮，降低 5m 信号过期
            }
        }
    }

    private fun update(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(1001, Notify.monitor(this, text))
    }

    private fun stopMon() {
        job?.cancel(); job = null
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopMon(); scope.cancel(); super.onDestroy()
    }

    companion object {
        const val STOP = "com.puito.cryptoagent.STOP_MON"
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MonitorService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MonitorService::class.java).setAction(STOP))
        }
    }
}
