package com.famtrack.app.feature.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.famtrack.app.data.remote.NotificationRepository
import com.famtrack.app.data.remote.SupabaseClient
import com.famtrack.app.feature.activity.ActivityRepository
import com.famtrack.app.feature.activity.Event
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Task
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Registra o check-in do usuário na localização atual (F5).
 */
class CheckInRepository {

    suspend fun checkIn(context: Context): Boolean {
        val user = SupabaseClient.getInstance().auth.currentUserOrNull() ?: return false

        return withContext(Dispatchers.IO) {
            try {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    return@withContext false
                }

                val locationClient = LocationServices.getFusedLocationProviderClient(context)
                val last = awaitLastLocation(locationClient) ?: return@withContext false

                val member = com.famtrack.app.data.remote.FamilyRepository()
                    .getUserFamily(user.id) ?: return@withContext false

                ActivityRepository().recordEvent(
                    Event(
                        family_id = member.family_id,
                        member_id = user.id,
                        type = "CHECKIN",
                        lat = last.first,
                        lng = last.second
                    )
                )

                NotificationRepository().notifyFamily(
                    familyId = member.family_id,
                    senderUserId = user.id,
                    title = "Check-in",
                    message = "Fez check-in na localização atual.",
                    type = "checkin"
                )
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    private suspend fun awaitLastLocation(
        client: FusedLocationProviderClient
    ): Pair<Double, Double>? {
        return suspendCancellableCoroutine { continuation ->
            val task: Task<android.location.Location> = client.lastLocation
            task.addOnSuccessListener { location ->
                if (location == null) {
                    // Sem cache (lastLocation null): busca única via getCurrentLocation.
                    client.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                        null
                    ).addOnSuccessListener { current ->
                        if (current != null) {
                            continuation.resume(current.latitude to current.longitude)
                        } else {
                            continuation.resume(null)
                        }
                    }.addOnFailureListener {
                        continuation.resume(null)
                    }
                } else {
                    continuation.resume(location.latitude to location.longitude)
                }
            }.addOnFailureListener {
                continuation.resume(null)
            }
        }
    }
}