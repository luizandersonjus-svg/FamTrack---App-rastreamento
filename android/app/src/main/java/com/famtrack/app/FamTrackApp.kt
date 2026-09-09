package com.famtrack.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class FamTrackApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "location_tracking"
        const val NOTIFICATION_CHANNEL_NAME = "Localizacao"
        const val GEOFENCE_CHANNEL_ID = "famtrack_geofence"
        const val GEOFENCE_CHANNEL_NAME = "Geofences"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locationChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantem o rastreamento de localizacao ativo"
                setShowBadge(false)
            }

            val geofenceChannel = NotificationChannel(
                GEOFENCE_CHANNEL_ID,
                GEOFENCE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de entrada/saida de geofences"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(locationChannel)
            notificationManager.createNotificationChannel(geofenceChannel)
        }
    }
}
