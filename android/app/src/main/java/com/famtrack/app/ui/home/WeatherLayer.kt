package com.famtrack.app.ui.home

import android.content.Context
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famtrack.app.BuildConfig
import com.famtrack.app.R
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// ETAPA 10D-4B — Condições de tempo no mapa (Open-Meteo).
//
// Fonte aprovada pelo usuário (10D-4A). API pública sem chave, HTTPS.
// Dados monetários de privacidade: enviamos APENAS a coordenada do membro
// (lat/lon), nada mais; resposta não é gravada em disco nem enviada ao
// servidor do FamTrack. Alertas INMET ficam para v2.
//
// FIX-2: o clima deixou de ser um card fixo no mapa e passou a viver dentro
// do bottom sheet de Camadas, com estado por membro (buscando / sem
// localização / indisponível / pausado). Logs técnicos são apenas de DEBUG e
// nunca registram coordenadas em builds de produção.
// ---------------------------------------------------------------------------

private const val WEATHER_TAG = "FamTrackWeather"
private const val OPEN_METEO_BASE = "https://api.open-meteo.com/v1/forecast"

/** Log meramente técnico (não visível ao usuário); apenas em debug. */
fun logWeatherDebug(msg: String) {
    if (BuildConfig.DEBUG) Log.d(WEATHER_TAG, msg)
}

data class WeatherCondition(
    val code: Int,
    val temperatureC: Double?,
    val windKph: Double?,
    val precipitationMm: Double?
)

private val weatherClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 10_000
        connectTimeoutMillis = 5_000
        socketTimeoutMillis = 10_000
    }
}

/** Consulta as condições atuais em [lat], [lon] no Open-Meteo (sem chave). */
suspend fun fetchWeatherAt(lat: Double, lon: Double): WeatherCondition? {
    return try {
        val latitude = lat.toString().replace(",", ".")
        val longitude = lon.toString().replace(",", ".")
        val url = buildString {
            append(OPEN_METEO_BASE)
            append("?latitude=").append(latitude)
            append("&longitude=").append(longitude)
            append("&current=temperature_2m,weather_code,wind_speed_10m,precipitation")
            append("&temperature_unit=celsius&wind_speed_unit=kmh&timezone=UTC")
        }
        // A URL contém coordenadas: só é registrada em build de DEBUG.
        if (BuildConfig.DEBUG) Log.d(WEATHER_TAG, "GET $url")
        val body = weatherClient.get(url).bodyAsText()
        val current = Json.parseToJsonElement(body).jsonObject["current"]?.jsonObject
            ?: run {
                logWeatherDebug("clima: resposta sem campo 'current' (body=${body.take(160)})")
                return null
            }
        WeatherCondition(
            code = current["weather_code"]?.jsonPrimitive?.int ?: -1,
            temperatureC = current["temperature_2m"]?.jsonPrimitive?.double,
            windKph = current["wind_speed_10m"]?.jsonPrimitive?.double,
            precipitationMm = current["precipitation"]?.jsonPrimitive?.double
        )
    } catch (e: Exception) {
        logWeatherDebug("clima: falha na consulta -> ${e.message}")
        null
    }
}

/** Rótulo curto do estado do tempo (código WMO 4677 → categoria). */
fun weatherLabel(code: Int, context: Context): String = when (code) {
    0 -> context.getString(R.string.weather_clear)
    1, 2 -> context.getString(R.string.weather_partly)
    3 -> context.getString(R.string.weather_cloudy)
    45, 48 -> context.getString(R.string.weather_fog)
    51, 53, 55, 56, 57 -> context.getString(R.string.weather_drizzle)
    61, 63, 65, 66, 67 -> context.getString(R.string.weather_rain)
    71, 73, 75, 77 -> context.getString(R.string.weather_snow)
    80, 81, 82 -> context.getString(R.string.weather_showers)
    95, 96, 99 -> context.getString(R.string.weather_thunder)
    else -> context.getString(R.string.weather_na)
}

/** Texto curto exibido no card: "Chuva · 24 °C · vento 12 km/h". */
fun describeWeather(cond: WeatherCondition?, context: Context): String? {
    if (cond == null) return null
    val sb = StringBuilder(weatherLabel(cond.code, context))
    val temp = cond.temperatureC?.let { Math.round(it).toInt() }
    if (temp != null) {
        sb.append(" · ").append(temp).append(" °C")
    }
    val wind = cond.windKph?.toInt()
    if (wind != null && wind > 0) {
        sb.append(" · vento ").append(wind).append(" km/h")
    }
    return sb.toString()
}

/** Estado de exibição do clima por membro (FIX-2). */
enum class WeatherDisplayState {
    OK,
    LOADING,
    NO_LOCATION,
    UNAVAILABLE
}

/** Linha de clima de um membro exibida dentro do bottom sheet de Camadas. */
data class SheetWeatherRow(
    val userId: String,
    val displayName: String,
    val paused: Boolean,
    val state: WeatherDisplayState,
    val text: String?
)

/** Ícone do estado do clima por membro (FIX-2). */
private fun weatherRowIcon(row: SheetWeatherRow): ImageVector = when {
    row.paused -> Icons.Filled.Cloud
    row.state == WeatherDisplayState.OK -> Icons.Filled.Cloud
    row.state == WeatherDisplayState.NO_LOCATION -> Icons.Filled.Place
    row.state == WeatherDisplayState.UNAVAILABLE -> Icons.Filled.Warning
    else -> Icons.Filled.Cloud
}

/** Tint do ícone por estado (FIX-2). */
@Composable
private fun weatherRowTint(row: SheetWeatherRow): androidx.compose.ui.graphics.Color {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        row.paused -> variant
        row.state == WeatherDisplayState.OK -> MaterialTheme.colorScheme.primary
        row.state == WeatherDisplayState.NO_LOCATION -> variant
        row.state == WeatherDisplayState.UNAVAILABLE -> MaterialTheme.colorScheme.error
        else -> variant
    }
}

/**
 * Cartão "Clima da família" exibido DENTRO do bottom sheet de Camadas (FIX-2),
 * somente quando o toggle Clima está ativo. Uma linha por membro com ícone de
 * condição e estado específico: buscando / sem localização / indisponível /
 * pausado, ou temperatura+vento quando disponível. Rodapé "Fonte: Open-Meteo".
 */
@Composable
fun WeatherFamilyCard(
    rows: List<SheetWeatherRow>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.weather_card_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (rows.isNotEmpty()) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Icon(
                            weatherRowIcon(row),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = weatherRowTint(row)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = buildString {
                                append(row.displayName)
                                append(" · ")
                                append(
                                    when {
                                        row.paused -> stringResource(R.string.weather_paused)
                                        row.state == WeatherDisplayState.LOADING ->
                                            stringResource(R.string.weather_loading)
                                        row.state == WeatherDisplayState.NO_LOCATION ->
                                            stringResource(R.string.weather_no_location)
                                        row.state == WeatherDisplayState.UNAVAILABLE ->
                                            stringResource(R.string.weather_unavailable)
                                        else -> row.text ?: stringResource(R.string.weather_unavailable)
                                    }
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (row.paused ||
                                row.state != WeatherDisplayState.OK
                            ) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.weather_source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}