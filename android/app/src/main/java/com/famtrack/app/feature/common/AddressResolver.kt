package com.famtrack.app.feature.common

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

sealed interface AddressOutcome {
    data class Found(val text: String) : AddressOutcome
    data object NotFound : AddressOutcome
    data object Unavailable : AddressOutcome
}

/**
 * Reverse geocoding simples com cache em memória.
 *
 * - Chave: coordenada arredondada em ~4 casas (≈11m), para não duplicar
 *   chamadas ao Geocoder em pontos quase iguais.
 * - Nunca chama o Geocoder dentro de recomposition: use funções suspend
 *   chamadas de LaunchedEffect ou withContext.
 * - IOException vira [AddressOutcome.Unavailable]; ausência de resultado vira
 *   [AddressOutcome.NotFound] (o chamador decide o texto final).
 */
object AddressResolver {

    private val cache = LruCache<String, String>(512)

    suspend fun resolve(context: Context, latitude: Double, longitude: Double): AddressOutcome {
        val key = resolutionKey(latitude, longitude)
        cache.get(key)?.let { return AddressOutcome.Found(it) }

        val outcome = withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                val text = addresses?.firstOrNull()?.let(::addressText)
                if (text.isNullOrBlank()) AddressOutcome.NotFound else AddressOutcome.Found(text)
            } catch (e: IOException) {
                AddressOutcome.Unavailable
            } catch (e: Exception) {
                AddressOutcome.Unavailable
            }
        }
        if (outcome is AddressOutcome.Found) cache.put(key, outcome.text)
        return outcome
    }

    private fun addressText(address: Address): String? {
        val joined = buildString {
            address.subThoroughfare?.let { append(it).append(", ") }
            address.thoroughfare?.let { append(it).append(", ") }
            val sub = address.subLocality?.takeIf { it.isNotBlank() }
            if (sub != null && address.locality != sub) append(sub).append(", ")
            address.locality?.let { append(it) }
        }.trimEnd(',', ' ').trim()
        if (joined.isNotBlank()) return joined
        return address.getAddressLine(0)?.takeIf { it.isNotBlank() }
    }

    private fun resolutionKey(latitude: Double, longitude: Double): String {
        val latRound = "%.4f".format(latitude)
        val lonRound = "%.4f".format(longitude)
        return "$latRound,$lonRound"
    }
}