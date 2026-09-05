package com.famtrack.app.feature.sos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.famtrack.app.MainActivity
import com.famtrack.app.R

/**
 * Criação de canais de notificação e exibição do alerta SOS em prioridade
 * alta (F5). As notificações são apenas locais: o FCM fica como próximo passo.
 * O ID da notificação é determinístico a partir do ID do SOS para que a
 * resolução/cancelamento possa remover a notificação corretamente.
 */
object AlertNotifier {

    const val CHANNEL_ID_SOS = "sos_alerts"

    @JvmStatic
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID_SOS,
            context.getString(R.string.sos_notif_channel),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    @JvmStatic
    fun showSosNotification(
        context: Context,
        displayName: String,
        alertLat: Double,
        alertLng: Double,
        sosId: String
    ) {
        ensureChannels(context)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationId = sosId.hashCode()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("sos_lat", alertLat)
            putExtra("sos_lng", alertLng)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_SOS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sos_notif_title))
            .setContentText(context.getString(R.string.sos_notif_text, displayName))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun cancelSosNotification(context: Context, sosId: String) {
        val notificationId = sosId.hashCode()
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (_: Exception) {
            // Falha ao cancelar: notificação pode já ter sido removida pelo sistema.
        }
    }
}
