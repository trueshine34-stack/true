package com.plovault.sync.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.plovault.sync.MainActivity
import com.plovault.sync.R

object Notifier {
    private const val CHANNEL = "sync"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Синхронизация", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "Результаты выгрузки рук из PokerCraft"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun show(context: Context, title: String, text: String) {
        ensureChannel(context)
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(1001, n) }
    }
}
