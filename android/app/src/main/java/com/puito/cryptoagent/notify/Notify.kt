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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.puito.cryptoagent.MainActivity
import com.puito.cryptoagent.data.AiEvalResult
import com.puito.cryptoagent.data.SignalMark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notify {
    /** 带震动 / 不震动 分通道，便于设置里开关（Android 8+ 通道属性创建后不可改） */
    const val CH_SIGNAL_VIB = "signals_v3_vibrate"
    const val CH_SIGNAL_QUIET = "signals_v3_quiet"
    const val CH_BG = "monitor"

    /** 默认震动节奏：等待,震,停,震,停,长震 */
    val DEFAULT_VIBRATE_PATTERN = longArrayOf(0, 450, 180, 450, 180, 700)

    private var idSeq = 5000
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val shortFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val vibCh = NotificationChannel(
            CH_SIGNAL_VIB,
            "交易信号（震动）",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "信号提醒，默认震动+铃声"
            enableVibration(true)
            vibrationPattern = DEFAULT_VIBRATE_PATTERN
            enableLights(true)
            lightColor = Color.YELLOW
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(sound, attrs)
            if (Build.VERSION.SDK_INT >= 29) setAllowBubbles(true)
        }
        nm.createNotificationChannel(vibCh)

        val quietCh = NotificationChannel(
            CH_SIGNAL_QUIET,
            "交易信号（无震动）",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "信号提醒，仅铃声不震动"
            enableVibration(false)
            enableLights(true)
            lightColor = Color.YELLOW
            setShowBadge(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(sound, attrs)
        }
        nm.createNotificationChannel(quietCh)

        val bgCh = NotificationChannel(
            CH_BG,
            "后台监控",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "常驻监控状态，低打扰"
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(bgCh)
    }

    fun vibrateNow(ctx: Context, pattern: LongArray = DEFAULT_VIBRATE_PATTERN) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                val vm = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    fun signal(
        ctx: Context,
        symbol: String,
        interval: String,
        m: SignalMark,
        intervalWinRatePct: Double = 0.0,
        intervalTrades: Int = 0,
        ai: AiEvalResult? = null,
        vibrate: Boolean = true,
    ) {
        channels(ctx)
        val channelId = if (vibrate) CH_SIGNAL_VIB else CH_SIGNAL_QUIET
        val direction = if (m.side == "B") "买入 / 看涨 (B)" else "卖出 / 看跌 (S)"
        val directionShort = if (m.side == "B") "▲ 买入 B" else "▼ 卖出 S"
        val timeStr = timeFmt.format(Date(m.openTime))
        val shortTime = shortFmt.format(Date(m.openTime))
        val wrLine = "回测总胜率 ${"%.1f".format(intervalWinRatePct)}% (${intervalTrades}笔)"

        val aiLine = when {
            ai == null -> null
            ai.winRatePct != null -> {
                val pass = when (ai.passThreshold) {
                    true -> "≥阈值可下"
                    false -> "未达阈值"
                    null -> ""
                }
                "AI行情评估 ${"%.1f".format(ai.winRatePct)}%(阈${"%.0f".format(ai.thresholdPct)}) $pass"
            }
            ai.error != null -> "AI评估失败:${ai.error}"
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
            appendLine("回测总胜率：${"%.1f".format(intervalWinRatePct)}%（${intervalTrades}笔，非AI）")
            if (ai != null) {
                appendLine("—— AI 行情评估（本信号/本周期）——")
                if (ai.winRatePct != null) {
                    appendLine("AI预估胜率：${"%.1f".format(ai.winRatePct)}%")
                } else {
                    appendLine("AI预估胜率：无（未成功评估）")
                }
                appendLine("下单阈值：${"%.1f".format(ai.thresholdPct)}%")
                appendLine(
                    "是否达阈值：" + when (ai.passThreshold) {
                        true -> "是（允许自动下单）"
                        false -> "否（拦截自动下单）"
                        null -> "未知"
                    },
                )
                if (ai.summary.isNotBlank()) appendLine("说明：${ai.summary}")
                if (ai.error != null) appendLine("错误：${ai.error}")
            } else {
                appendLine("AI 评估：未开启（下单页可开，仅评估不实盘也可）")
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

        val builder = NotificationCompat.Builder(ctx, channelId)
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
            .setWhen(m.openTime)
            .setShowWhen(true)
            .setNumber(1)
            .setGroup("crypto_agent_signals")

        if (vibrate) {
            builder
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
                .setVibrate(DEFAULT_VIBRATE_PATTERN)
                .setLights(Color.YELLOW, 800, 400)
        } else {
            builder
                .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
                .setVibrate(null)
        }

        val notifId = idSeq++
        try {
            NotificationManagerCompat.from(ctx).notify(notifId, builder.build())
            if (vibrate) {
                // 通道外再触发一次，提高部分机型前台/后台感知
                vibrateNow(ctx)
            }
            val summaryNotif = NotificationCompat.Builder(ctx, channelId)
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
                .apply {
                    if (vibrate) setVibrate(DEFAULT_VIBRATE_PATTERN) else setVibrate(null)
                }
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
