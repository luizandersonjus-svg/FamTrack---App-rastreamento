package com.famtrack.app.feature.delivery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.famtrack.app.feature.common.ErrorMessages
import com.famtrack.app.ui.history.osrmTripOrder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val PREFS = "delivery_prefs"
private const val KEY_CITY = "city"
private const val MAPS_PACKAGE = "com.google.android.apps.maps"

/** Cidade usada na busca de endereços (lembrada entre cadastros). */
internal fun loadDeliveryCity(context: Context): String =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CITY, "").orEmpty()

internal fun saveDeliveryCity(context: Context, city: String) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putString(KEY_CITY, city.trim()).apply()
}

internal sealed class GeocodeResult {
    data class Found(val position: LatLng) : GeocodeResult()
    data object NotFound : GeocodeResult()
    data object Error : GeocodeResult()
}

/**
 * Endereço -> coordenada pelo Geocoder do Android (mesmo padrão da busca de
 * PlaceEditScreen). API 33+: versão assíncrona; abaixo: síncrona em IO.
 */
internal suspend fun geocodeDeliveryAddress(context: Context, query: String): GeocodeResult {
    val text = query.trim()
    if (text.isEmpty()) return GeocodeResult.NotFound
    val geocoder = Geocoder(context, Locale.getDefault())
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        try {
            suspendCancellableCoroutine<GeocodeResult> { cont ->
                geocoder.getFromLocationName(
                    text, 1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<android.location.Address>) {
                            val first = addresses.firstOrNull()
                            if (cont.isActive) {
                                cont.resume(
                                    if (first != null) {
                                        GeocodeResult.Found(LatLng(first.latitude, first.longitude))
                                    } else {
                                        GeocodeResult.NotFound
                                    }
                                )
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(GeocodeResult.Error)
                        }
                    }
                )
            }
        } catch (e: Exception) {
            GeocodeResult.Error
        }
    } else {
        withContext(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val first = geocoder.getFromLocationName(text, 1)?.firstOrNull()
                if (first != null) GeocodeResult.Found(LatLng(first.latitude, first.longitude))
                else GeocodeResult.NotFound
            } catch (e: Exception) {
                GeocodeResult.Error
            }
        }
    }
}

/** Última posição conhecida do aparelho, ou null sem permissão/posição. */
internal suspend fun lastKnownPosition(context: Context): LatLng? {
    // Aproximada também serve (o entregador pode ter negado a precisa).
    val granted = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ).any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    if (!granted) return null
    return try {
        suspendCancellableCoroutine { cont ->
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { location ->
                    if (cont.isActive) {
                        cont.resume(location?.let { LatLng(it.latitude, it.longitude) })
                    }
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }
    } catch (e: SecurityException) {
        null
    }
}

/**
 * Abre a navegação até a parada: Google Maps em modo navegação e, se não
 * houver, o link universal de rotas (app de mapas padrão ou navegador).
 */
internal fun openDeliveryNavigation(context: Context, delivery: Delivery): Boolean {
    val coords = String.format(Locale.US, "%.6f,%.6f", delivery.latitude, delivery.longitude)
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$coords&mode=d"))
            .setPackage(MAPS_PACKAGE),
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$coords&travelmode=driving")
        )
    )
    for (intent in attempts) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            // tenta a próxima opção
        }
    }
    return false
}

/** Resultado da otimização: nova ordem e se veio do servidor de rotas. */
internal class OptimizedRoute(val orderById: Map<String, Int>, val byRoads: Boolean)

/**
 * Reordena os pacotes PENDENTES para rodar menos, partindo de [start] (a
 * posição atual; sem ela, parte do primeiro pendente). Os já entregues ficam
 * no início, na ordem em que estavam.
 *
 * Usa o serviço /trip do OSRM (distância pelas ruas). Se o servidor falhar,
 * cai para "vizinho mais próximo" em linha reta, e [OptimizedRoute.byRoads]
 * volta false para a tela avisar que a ordem é aproximada.
 */
internal suspend fun optimizeDeliveryRoute(all: List<Delivery>, start: LatLng?): OptimizedRoute {
    val ordered = all.inRouteOrder()
    val done = ordered.filter { it.delivered && it.id != null }
    val pending = ordered.filter { !it.delivered && it.id != null }
    val result = LinkedHashMap<String, Int>()
    var next = 1
    for (d in done) result[d.id!!] = next++
    if (pending.isEmpty()) return OptimizedRoute(result, byRoads = true)

    val points = pending.map { LatLng(it.latitude, it.longitude) }
    var byRoads = true
    val sequence: List<Delivery> = if (pending.size == 1) {
        pending
    } else {
        val fromServer = try {
            tripSequence(pending, points, start)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorMessages.logSafe(e, "otimizar rota de entregas")
            null
        }
        if (fromServer != null) {
            fromServer
        } else {
            byRoads = false
            nearestNeighbour(pending, start)
        }
    }
    for (d in sequence) result[d.id!!] = next++
    return OptimizedRoute(result, byRoads)
}

/** Ordem devolvida pelo OSRM, ou null se a resposta não puder ser usada. */
private suspend fun tripSequence(
    pending: List<Delivery>,
    points: List<LatLng>,
    start: LatLng?
): List<Delivery>? {
    // Com posição atual, ela entra como primeira coordenada (source=first).
    val coords = if (start != null) listOf(start) + points else points
    val positions = osrmTripOrder(coords) ?: return null
    if (positions.size != coords.size) return null
    val offset = if (start != null) 1 else 0
    val indexed = pending.mapIndexed { i, d -> positions[i + offset] to d }
    if (indexed.map { it.first }.toSet().size != pending.size) return null
    return indexed.sortedBy { it.first }.map { it.second }
}

/** Plano B sem servidor: sempre vai para o pendente mais perto do último. */
private fun nearestNeighbour(pending: List<Delivery>, start: LatLng?): List<Delivery> {
    val remaining = pending.toMutableList()
    val sequence = ArrayList<Delivery>(pending.size)
    var current = start ?: LatLng(remaining.first().latitude, remaining.first().longitude)
    while (remaining.isNotEmpty()) {
        val nearest = remaining.minByOrNull { distanceMeters(current, LatLng(it.latitude, it.longitude)) }
            ?: break
        remaining.remove(nearest)
        sequence.add(nearest)
        current = LatLng(nearest.latitude, nearest.longitude)
    }
    return sequence
}

private fun distanceMeters(a: LatLng, b: LatLng): Float {
    val out = FloatArray(1)
    android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
    return out[0]
}
