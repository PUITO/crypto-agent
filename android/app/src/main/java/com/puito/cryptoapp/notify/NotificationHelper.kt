package com.puito.cryptoapp.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.puito.cryptoapp.MainActivity
import com.puito.cryptoapp.data.model.SignalMark

object NotificationHelper {
    const val CHANNEL_SIGNALS = "signals"
    const val CHANNEL_SERVICE = "monitor_service"
    private var signalNotifyId = 2000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SIGNALS,
                "交易信号",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "策略产生买入/卖出信号时通知" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "策略监控",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "后台监控运行中" }
        )
    }

    fun notifySignal(context: Context, symbol: String, interval: String, mark: SignalMark) {
        ensureChannels(context)
        val sideText = if (mark.side == "B") "买入 B" else "卖出 S"
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_SIGNALS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$symbol · $interval · $sideText")
            .setContentText("价格 ${"%.2f".format(mark.price)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(signalNotifyId++, notif)
        } catch (_: SecurityException) {
        }
    }

    fun serviceNotification(context: Context, text: String): android.app.Notification {
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Crypto App 策略监控中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
