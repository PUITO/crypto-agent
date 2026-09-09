package com.puito.cryptoagent.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.puito.cryptoagent.MainActivity
import com.puito.cryptoagent.data.SignalMark

object Notify {
    const val CH_SIGNAL = "signals"
    const val CH_BG = "monitor"
    private var id = 3000

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SIGNAL, "交易信号", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_BG, "后台监控", NotificationManager.IMPORTANCE_LOW))
    }

    fun signal(ctx: Context, symbol: String, interval: String, m: SignalMark) {
        channels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "$symbol $interval ${if (m.side == "B") "买入 B" else "卖出 S"}"
        val n = NotificationCompat.Builder(ctx, CH_SIGNAL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("价格 ${"%.2f".format(m.price)}")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(id++, n) } catch (_: SecurityException) {}
    }

    fun monitor(ctx: Context, text: String) = run {
        channels(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        NotificationCompat.Builder(ctx, CH_BG)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Crypto Agent 监控中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
