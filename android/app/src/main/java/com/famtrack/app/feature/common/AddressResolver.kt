package com.famtrack.app.feature.common

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.LruCache
import com.famtrack.app.data.offline.AddressCacheEntity
import com.famtrack.app.data.offline.RouteTrackDatabase
import com.famtrack.app.feature.places.Place
import com.famtrack.app.util.isInsideGeofence
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

    private val memCache = LruCache<String, String>(512)

    /** Recency do cache Room só é regravado depois deste intervalo (evita
     * escrita em todo hit). */
    private const val RECENCY_BUMP_MIN_MS = 5 * 60_000L

    suspend fun resolve(context: Context, latitude: Double, longitude: Double): AddressOutcome =
        resolveFor(context, latitude, longitude, short = false)

    /** Versão curta (rua+nº ou bairro) para títulos de percurso. */
    suspend fun resolveShort(context: Context, latitude: Double, longitude: Double): AddressOutcome =
        resolveFor(context, latitude, longitude, short = true)

    /**
     * Resolução com cache em 2 camadas (ETAPA 11A):
     * 1. Memória (LruCache, por chave "lat,lng" ou "s:lat,lng").
     * 2. Room (address_cache) — hit é promovido à memória.
     * 3. Geocoder em IO: um único lookup gera AMBOS os textos (completo e curto)
     *    do mesmo [Address] e persiste a linha mergida; só resultados positivos
     *    são cacheados (transitório IOException/ausência nunca envenena o cache).
     */
    private suspend fun resolveFor(
        context: Context,
        latitude: Double,
        longitude: Double,
        short: Boolean
    ): AddressOutcome {
        val keyBase = resolutionKey(latitude, longitude)
        val memKey = if (short) "s:$keyBase" else keyBase

        memCache.get(memKey)?.let { return AddressOutcome.Found(it) }

        val dao = RouteTrackDatabase.getInstance(context).addressCacheDao()
        val row = dao.find(keyBase)
        if (row != null) {
            val text = if (short) row.shortText else row.fullText
            if (text != null) {
                memCache.put(memKey, text)
                if (now() - row.lastUsedAt > RECENCY_BUMP_MIN_MS) {
                    dao.upsert(row.copy(lastUsedAt = now()))
                }
                return AddressOutcome.Found(text)
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(latitude, longitude, 1)
                val address = addresses?.firstOrNull()
                val full = address?.let(::addressText)?.takeIf { it.isNotBlank() }
                val shortened = address?.let(::shortAddressText)?.takeIf { it.isNotBlank() }
                if (full == null && shortened == null) {
                    AddressOutcome.NotFound
                } else {
                    val merged = AddressCacheEntity(
                        cacheKey = keyBase,
                        fullText = full ?: row?.fullText,
                        shortText = shortened ?: row?.shortText,
                        lastUsedAt = now()
                    )
                    dao.upsert(merged)
                    if (row == null) dao.trimTo(RouteTrackDatabase.addressCacheMaxRows())
                    val text = if (short) shortened else full
                    if (text != null) {
                        memCache.put(memKey, text)
                        AddressOutcome.Found(text)
                    } else {
                        AddressOutcome.NotFound
                    }
                }
            } catch (e: IOException) {
                AddressOutcome.Unavailable
            } catch (e: Exception) {
                AddressOutcome.Unavailable
            }
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    /**
     * Rótulo único e consistente para coordenadas (ETAPA 10A).
     *
     * Cadeia de decisão:
     * 1. GEOFENCE — o PRIMEIRO local da lista cuja coordenada está DENTRO do
     *    raio real dele (distance <= radius; NUNCA o "mais próximo" fora do
     *    raio): devolve o nome do local.
     * 2. ENDEREÇO REVERSO CURTO — "Rua, Nº" (no máx. 2 partes).
     * 3. BAIRRO/CIDADE — sem rua, "Bairro, Cidade" (ou só um dos dois).
     * 4. FALLBACK — null (a superfície escolhe o texto seguro: coordenadas no
     *    Home/detalhe/SOS, "Local aproximado" nos títulos de percurso).
     *
     * Reutiliza o cache compartilhado de endereços (resolveShort): a mesma
     * coordenada nunca roda duas resoluções de Geocoder em duplicidade.
     */
    suspend fun resolveForDisplay(
        context: Context,
        latitude: Double,
        longitude: Double,
        places: List<Place>
    ): String? {
        // Prioridade 1: só o local cujo raio REAL contém a coordenada.
        val place = places.firstOrNull {
            isInsideGeofence(
                latitude,
                longitude,
                it.center_lat,
                it.center_lon,
                it.radius_meters.toDouble()
            )
        }
        if (place != null) return place.name
        // Prioridades 2 e 3: endereço curto ou bairro/cidade.
        return when (val outcome = resolveShort(context, latitude, longitude)) {
            is AddressOutcome.Found -> outcome.text
            else -> null
        }
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

    /**
     * Rótulo curto para títulos de percurso: prioriza "Rua, N°" (até 2 partes);
     * sem rua, "Bairro, Cidade" (ou só o bairro/cidade); nunca devolve a linha
     * completa do endereço (que deixa o título de percurso ilegível).
     */
    private fun shortAddressText(address: Address): String? {
        val thoroughfare = address.thoroughfare?.takeIf { it.isNotBlank() }
        val subThoroughfare = address.subThoroughfare?.takeIf { it.isNotBlank() }
        if (thoroughfare != null) {
            return if (subThoroughfare != null) "$thoroughfare, $subThoroughfare" else thoroughfare
        }
        val sub = address.subLocality?.takeIf { it.isNotBlank() }
        val locality = address.locality?.takeIf { it.isNotBlank() }
        if (sub != null) {
            return if (locality != null && locality != sub) "$sub, $locality" else sub
        }
        return locality?.takeIf { it.isNotBlank() }
    }

    private fun resolutionKey(latitude: Double, longitude: Double): String {
        val latRound = "%.4f".format(latitude)
        val lonRound = "%.4f".format(longitude)
        return "$latRound,$lonRound"
    }
}