package com.puito.cryptoagent.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.puito.cryptoagent.MainActivity
import com.puito.cryptoagent.data.AiEvalResult
import com.puito.cryptoagent.data.SignalMark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notify {
    /** v2 通道：高优先级+震动铃声，避免旧通道 IMPORTANCE 无法升级 */
    const val CH_SIGNAL = "signals_v2"
    const val CH_BG = "monitor"
    private var idSeq = 4000

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val shortFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val signalCh = NotificationChannel(
            CH_SIGNAL,
            "交易信号（强提醒）",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "策略产生买入/卖出信号时强提醒，含时间与方向"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 600)
            enableLights(true)
            lightColor = Color.YELLOW
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(sound, attrs)
            if (Build.VERSION.SDK_INT >= 29) {
                setAllowBubbles(true)
            }
        }
        nm.createNotificationChannel(signalCh)

        val bgCh = NotificationChannel(
            CH_BG,
            "后台监控",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "常驻监控状态，低打扰"
            setShowBadge(false)
        }
        nm.createNotificationChannel(bgCh)
    }

    fun signal(
        ctx: Context,
        symbol: String,
        interval: String,
        m: SignalMark,
        intervalWinRatePct: Double = 0.0,
        intervalTrades: Int = 0,
        ai: AiEvalResult? = null,
    ) {
        channels(ctx)
        val direction = if (m.side == "B") "买入 / 看涨 (B)" else "卖出 / 看跌 (S)"
        val directionShort = if (m.side == "B") "▲ 买入 B" else "▼ 卖出 S"
        val timeStr = timeFmt.format(Date(m.openTime))
        val shortTime = shortFmt.format(Date(m.openTime))
        val wrLine = "周期总胜率 ${"%.1f".format(intervalWinRatePct)}% (${intervalTrades}笔)"

        val aiLine = when {
            ai == null -> null
            ai.winRatePct != null -> {
                val pass = when (ai.passThreshold) {
                    true -> "达阈值"
                    false -> "未达阈值"
                    null -> ""
                }
                "AI ${"%.1f".format(ai.winRatePct)}%/$pass"
            }
            else -> "AI: ${ai.summary.take(40)}"
        }

        val title = "$directionShort · $symbol · 时间段 $interval"
        val summary = buildString {
            append("$wrLine · 时间 $shortTime · 价 ${"%.2f".format(m.price)}")
            if (aiLine != null) append(" · ").append(aiLine)
        }
        val bigText = buildString {
            appendLine("方向：$direction")
            appendLine("时间：$timeStr")
            appendLine("品种：$symbol")
            appendLine("时间段（周期）：$interval")
            appendLine("该时间段总胜率：${"%.1f".format(intervalWinRatePct)}%（模拟 ${intervalTrades} 笔）")
            appendLine("价格：${"%.4f".format(m.price)}")
            appendLine("信号侧：${m.side}")
            if (ai != null) {
                appendLine("—— AI 评估 ——")
                if (ai.winRatePct != null) {
                    appendLine("预估胜率：${"%.1f".format(ai.winRatePct)}%")
                }
                appendLine("阈值：${"%.1f".format(ai.thresholdPct)}%")
                appendLine(
                    "是否达阈值：" + when (ai.passThreshold) {
                        true -> "是（可考虑执行）"
                        false -> "否（建议观望）"
                        null -> "未知"
                    },
                )
                if (ai.summary.isNotBlank()) appendLine("说明：${ai.summary}")
                if (ai.error != null) appendLine("备注：${ai.error}")
            } else {
                appendLine("AI 评估：未开启（下单页可单独开启，无需真实交易）")
            }
            append("请及时查看策略与下单设置。")
        }

        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("from_signal", true)
            putExtra("signal_side", m.side)
            putExtra("signal_time", m.openTime)
        }
        val reqCode = (m.openTime xor symbol.hashCode().toLong()).toInt()
        val pi = PendingIntent.getActivity(
            ctx,
            reqCode,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notifId = idSeq++
        val n = NotificationCompat.Builder(ctx, CH_SIGNAL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText).setSummaryText(directionShort))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 400, 200, 400, 200, 600))
            .setLights(Color.YELLOW, 800, 400)
            .setWhen(m.openTime)
            .setShowWhen(true)
            .setNumber(1)
            .setGroup("crypto_agent_signals")
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(notifId, n)
            // 分组摘要，锁屏/通知栏更容易注意到多条信号
            val summaryNotif = NotificationCompat.Builder(ctx, CH_SIGNAL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Crypto Agent 信号")
                .setContentText("有新的交易信号")
                .setStyle(
                    NotificationCompat.InboxStyle()
                        .setBigContentTitle("交易信号")
                        .addLine(title)
                        .addLine(summary)
                        .setSummaryText("信号提醒"),
                )
                .setGroup("crypto_agent_signals")
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .build()
            NotificationManagerCompat.from(ctx).notify(1999, summaryNotif)
        } catch (_: SecurityException) {
        }
    }

    fun monitor(ctx: Context, text: String) = run {
        channels(ctx)
        val pi = PendingIntent.getActivity(
            ctx,
            1,
            Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(ctx, CH_BG)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Crypto Agent 监控中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }
}
