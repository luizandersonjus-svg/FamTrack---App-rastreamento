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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val CHECKIN_TIMEOUT_MS = 15000L

/** Sinaliza que a localização não pôde ser obtida (null/falha). */
private class NoLocationException : Exception()

/**
 * Resultado do check-in, para feedback coerente na tela.
 */
enum class CheckInOutcome {
    /** Check-in registrado com sucesso. */
    SUCCESS,
    /** Sem permissão de localização. */
    NO_PERMISSION,
    /** Localização indisponível no momento. */
    NO_LOCATION,
    /** Estourou o tempo máximo de espera pela localização. */
    TIMEOUT,
    /** Falha ao registrar (backend/família). */
    ERROR
}

/**
 * Registra o check-in do usuário na localização atual (F5).
 */
class CheckInRepository {

    suspend fun checkIn(context: Context): CheckInOutcome {
        val user = SupabaseClient.getInstance().auth.currentUserOrNull()
            ?: return CheckInOutcome.ERROR

        return withContext(Dispatchers.IO) {
            try {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    return@withContext CheckInOutcome.NO_PERMISSION
                }

                val locationClient = LocationServices.getFusedLocationProviderClient(context)

                // Timeout de 15s; cancela a espera e propaga o cancelamento.
                val last = withTimeoutOrNull(CHECKIN_TIMEOUT_MS) {
                    awaitLastLocation(locationClient)
                } ?: return@withContext CheckInOutcome.TIMEOUT

                val member = com.famtrack.app.data.remote.FamilyRepository()
                    .getUserFamily(user.id) ?: return@withContext CheckInOutcome.ERROR

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
                    type = "system"
                )
                CheckInOutcome.SUCCESS
            } catch (e: NoLocationException) {
                CheckInOutcome.NO_LOCATION
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                CheckInOutcome.ERROR
            }
        }
    }

    private suspend fun awaitLastLocation(
        client: FusedLocationProviderClient
    ): Pair<Double, Double> {
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val task: Task<android.location.Location> = client.lastLocation
            task.addOnSuccessListener { location ->
                if (location == null) {
                    // Sem cache (lastLocation null): busca única via getCurrentLocation.
                    client.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                        null
                    ).addOnSuccessListener { current ->
                        if (continuation.isCancelled) return@addOnSuccessListener
                        if (current != null) {
                            continuation.resume(current.latitude to current.longitude)
                        } else {
                            continuation.resumeWithException(NoLocationException())
                        }
                    }.addOnFailureListener {
                        if (continuation.isCancelled) return@addOnFailureListener
                        continuation.resumeWithException(NoLocationException())
                    }
                } else {
                    if (continuation.isCancelled) return@addOnSuccessListener
                    continuation.resume(location.latitude to location.longitude)
                }
            }.addOnFailureListener {
                if (continuation.isCancelled) return@addOnFailureListener
                continuation.resumeWithException(NoLocationException())
            }
        }
    }
}