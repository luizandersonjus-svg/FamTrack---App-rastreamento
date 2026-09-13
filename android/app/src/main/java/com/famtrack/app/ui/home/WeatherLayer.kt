package com.famtrack.app.ui.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.Locale

// ---------------------------------------------------------------------------
// ETAPA 10D-4B — Condições de tempo no mapa (Open-Meteo).
//
// Fonte aprovada pelo usuário (10D-4A). API pública sem chave, HTTPS.
// Dados monetários de privacidade: enviamos APENAS a coordenada do membro
// (lat/lon), nada mais; resposta não é gravada em disco nem enviada ao
// servidor do FamTrack. Alertas INMET ficam para v2.
// ---------------------------------------------------------------------------

private const val WEATHER_TAG = "FamTrackWeather"
private const val OPEN_METEO_BASE = "https://api.open-meteo.com/v1/forecast"

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
        val body = weatherClient.get(url).bodyAsText()
        val current = Json.parseToJsonElement(body).jsonObject["current"]?.jsonObject
            ?: return null
        WeatherCondition(
            code = current["weather_code"]?.jsonPrimitive?.int ?: -1,
            temperatureC = current["temperature_2m"]?.jsonPrimitive?.double,
            windKph = current["wind_speed_10m"]?.jsonPrimitive?.double,
            precipitationMm = current["precipitation"]?.jsonPrimitive?.double
        )
    } catch (e: Exception) {
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

data class WeatherRow(
    val userId: String,
    val displayName: String,
    val paused: Boolean,
    val text: String?
)

/** Cartão compacto da camada Clima (canto inferior direito do mapa). */
@Composable
fun WeatherLayerCard(
    rows: List<WeatherRow>,
    loading: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.weather_card_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = null)
                }
            }
            Spacer(modifier = Modifier.size(4.dp))
            if (loading && rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.weather_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                rows.forEachIndexed { index, row ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    Text(
                        text = if (row.paused) {
                            row.displayName + " · " + stringResource(R.string.weather_paused)
                        } else {
                            row.displayName + " · " + (row.text
                                ?: stringResource(R.string.weather_no_data))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.paused) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.weather_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}