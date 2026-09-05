package com.famtrack.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.famtrack.app.MainActivity
import com.famtrack.app.FamTrackApp
import com.famtrack.app.R
import com.famtrack.app.data.remote.FamilyRepository
import com.famtrack.app.data.remote.GeofenceRepository
import com.famtrack.app.data.remote.LocationRepository
import com.famtrack.app.data.remote.NotificationRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.activity.ActivityRepository
import com.famtrack.app.feature.activity.Event
import io.github.jan.supabase.auth.auth

class GeofenceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val geofenceRepository = GeofenceRepository()
    private val locationRepository = LocationRepository()
    private val familyRepository = FamilyRepository()
    private val notificationRepository = NotificationRepository()
    private val activityRepository = ActivityRepository()

    override suspend fun doWork(): Result {
        return try {
            val client = SupabaseClient.getInstance()
            val user = client.auth.currentUserOrNull() ?: return Result.success()

            val member = familyRepository.getUserFamily(user.id)
                ?: return Result.success()
            val familyId = member.family_id

            val geofences = geofenceRepository.getFamilyGeofences(familyId)
            val activeGeofences = geofences.filter { it.active }
            if (activeGeofences.isEmpty()) return Result.success()

            val locations = locationRepository.getFamilyLocations(familyId)
            val memberLocations = locations.groupBy { it.user_id }

            // Nome de exibição de cada membro (para avisar quem chegou/saiu)
            val displayNames = try {
                familyRepository.getFamilyMemberDisplay(familyId)
                    .associate { it.user_id to it.display_name }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyMap()
            }

            for ((userId, userLocations) in memberLocations) {
                val isSelf = userId == user.id

                val latestLocation = userLocations.maxByOrNull { it.created_at ?: "" }
                    ?: continue

                for (geofence in activeGeofences) {
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        latestLocation.latitude,
                        latestLocation.longitude,
                        geofence.center_lat,
                        geofence.center_lon,
                        distance
                    )

                    val isInside = distance[0] <= geofence.radius_meters
                    val wasInside = isUserInsideGeofence(userId, geofence.id!!)
                    val memberName = displayNames[userId] ?: "Um membro"

                    if (!wasInside && isInside) {
                        val title = geofence.name
                        val message = "$memberName chegou em ${geofence.name}"
                        sendGeofenceNotification(title, message, geofence.id.hashCode())
                        insertGeofenceNotification(user.id, familyId, title, message)
                        // RLS: eventos apenas no nome do proprio usuario (member_id = auth.uid()).
                        if (isSelf) {
                            recordEvent(familyId, userId, "ENTER", geofence.placeId, latestLocation)
                        }
                    } else if (wasInside && !isInside) {
                        val title = geofence.name
                        val message = "$memberName saiu de ${geofence.name}"
                        sendGeofenceNotification(title, message, geofence.id.hashCode() + 1)
                        insertGeofenceNotification(user.id, familyId, title, message)
                        if (isSelf) {
                            recordEvent(familyId, userId, "EXIT", geofence.placeId, latestLocation)
                        }
                    }

                    updateGeofenceState(userId, geofence.id, isInside)
                }
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun isUserInsideGeofence(userId: String, geofenceId: String): Boolean {
        val prefs = applicationContext.getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
        return prefs.getBoolean("${userId}_${geofenceId}", false)
    }

    private suspend fun insertGeofenceNotification(
        recipientUserId: String,
        familyId: String,
        title: String,
        message: String
    ) {
        try {
            notificationRepository.createNotification(
                com.famtrack.app.data.model.Notification(
                    family_id = familyId,
                    user_id = recipientUserId,
                    title = title,
                    message = message,
                    type = "geofence"
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateGeofenceState(userId: String, geofenceId: String, inside: Boolean) {
        val prefs = applicationContext.getSharedPreferences("geofence_state", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("${userId}_${geofenceId}", inside).apply()
    }

    private suspend fun recordEvent(
        familyId: String,
        memberId: String,
        type: String,
        placeId: String?,
        location: com.famtrack.app.data.model.Location
    ) {
        try {
            activityRepository.recordEvent(
                Event(
                    family_id = familyId,
                    type = type,
                    member_id = memberId,
                    place_id = placeId,
                    lat = location.latitude,
                    lng = location.longitude
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendGeofenceNotification(title: String, message: String, notificationId: Int) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, FamTrackApp.GEOFENCE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId, notification)
        } catch (_: SecurityException) { }
    }
}
