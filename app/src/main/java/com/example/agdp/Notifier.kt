package com.agdp.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

fun notify(ctx: Context, title: String, text: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    if (Build.VERSION.SDK_INT >= 26) {
        ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("budget", "Orçamento", NotificationManager.IMPORTANCE_HIGH))
    }
    val n = NotificationCompat.Builder(ctx, "budget")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setContentTitle(title).setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
    NotificationManagerCompat.from(ctx).notify(System.currentTimeMillis().toInt(), n)
}
