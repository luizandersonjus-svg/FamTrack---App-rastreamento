package com.famtrack.app.ui.history

import android.location.Location
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.famtrack.app.R
import com.famtrack.app.data.model.RoutePoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** Duração da rota inteira em 1x, em millis. 2x -> 15s, 4x -> 7.5s. */
private const val PLAYBACK_BASE_DURATION_MS = 30_000f
private const val CAMERA_FOLLOW_MIN_INTERVAL_NS = 700_000_000L
private const val STOP_PAUSE_MS = 600L
private const val STOP_DISTANCE_M = 15f
private const val STOP_TIME_MS = 5 * 60_000L

/** Velocidades disponíveis no seletor. */
private val PLAYBACK_SPEEDS = floatArrayOf(1f, 2f, 4f)

/**
 * Entre dois pontos consecutivos do trajeto: distância e intervalo de tempo
 * pré-calculados (usados pela amostragem por fração da distância e pelas paradas).
 */
internal class RouteSegment(
    val start: RoutePoint,
    val end: RoutePoint,
    val distanceMeters: Float,
    val startMillis: Long,
    val endMillis: Long
)

/**
 * Motor de reprodução: o progresso (0f..1f) é uma fração da DISTÂNCIA total,
 * não do tempo — paradas longas não viram tempo morto na animação.
 * Os campos de UI são Compose State (lidos por controles/mapa sem efeitos).
 */
internal class RoutePlaybackController internal constructor(
    val points: List<RoutePoint>,
    val displayLatLngs: List<LatLng>,
    val avatarDescriptor: com.google.android.gms.maps.model.BitmapDescriptor?
) {
    val hasRoute: Boolean = points.size >= 2

    val segments: List<RouteSegment> = if (hasRoute) buildSegments(points) else emptyList()
    val cumulativeMeters: DoubleArray = if (hasRoute) {
        DoubleArray(segments.size).also { cum ->
            var acc = 0.0
            for (i in segments.indices) {
                acc += segments[i].distanceMeters
                cum[i] = acc
            }
        }
    } else DoubleArray(0)
    val totalMeters: Float = if (hasRoute) cumulativeMeters.last().toFloat() else 0f

    var progress by mutableStateOf(0f)
    var isPlaying by mutableStateOf(false)
    var finished by mutableStateOf(false)
    var speed by mutableStateOf(1f)
    var stopMessage by mutableStateOf<String?>(null)
    var stopCount by mutableIntStateOf(0)
    var followAvatar by mutableStateOf(true)

    val avatarMarker = MarkerState()

    init {
        if (hasRoute) {
            avatarMarker.position = positionFor(0f)
        }
    }

    val segmentIndex: Int
        get() = if (hasRoute) segmentIndexFor(progress) else 0

    val avatarPosition: LatLng
        get() = if (hasRoute) positionFor(progress) else displayLatLngs.firstOrNull() ?: LatLng(0.0, 0.0)

    /** Horário interpolado entre os pontos do segmento atual (null se sem recorded_at). */
    fun timeMillisFor(p: Float): Long? {
        if (!hasRoute) return points.firstOrNull()?.let { parseRouteIsoMillis(it.recorded_at) }
        val i = segmentIndexFor(p)
        val seg = segments[i]
        val before = if (i == 0) 0f else cumulativeMeters[i - 1].toFloat()
        val segLen = seg.distanceMeters
        val t = if (segLen <= 0f) 0f else ((p.coerceIn(0f, 1f) * totalMeters) - before) / segLen
        val tt = t.coerceIn(0f, 1f)
        val s = seg.startMillis
        val e = seg.endMillis
        if (s == 0L || e == 0L) return null
        return (s + ((e - s) * tt).toLong()).coerceAtLeast(0L)
    }

    /** Interpolação linear de lat/lng dentro do segmento atual. */
    private fun positionFor(p: Float): LatLng {
        val i = segmentIndexFor(p)
        val seg = segments[i]
        val before = if (i == 0) 0f else cumulativeMeters[i - 1].toFloat()
        val segLen = seg.distanceMeters
        val t = if (segLen <= 0f) 0f else ((p.coerceIn(0f, 1f) * totalMeters) - before) / segLen
        val tt = t.coerceIn(0f, 1f)
        return LatLng(
            seg.start.latitude + (seg.end.latitude - seg.start.latitude) * tt,
            seg.start.longitude + (seg.end.longitude - seg.start.longitude) * tt
        )
    }

    private fun segmentIndexFor(p: Float): Int {
        if (!hasRoute) return 0
        val target = p.coerceIn(0f, 1f) * totalMeters
        var i = 0
        while (i < cumulativeMeters.size && cumulativeMeters[i] < target) i++
        return i.coerceIn(0, segments.size - 1)
    }

    fun advance(dtMillis: Float) {
        if (!isPlaying || progress >= 1f) return
        progress = (progress + dtMillis / PLAYBACK_BASE_DURATION_MS).coerceAtMost(1f)
        avatarMarker.position = positionFor(progress)
    }

    fun seek(p: Float) {
        if (!hasRoute) return
        progress = p.coerceIn(0f, 1f)
        finished = false
        stopMessage = null
        avatarMarker.position = positionFor(progress)
    }

    fun togglePlayPause() {
        if (!hasRoute) return
        if (isPlaying) {
            isPlaying = false
        } else {
            if (finished) seek(0f)
            isPlaying = true
        }
    }

    /** Conclui a reprodução (execução única, sem loop): para e volta o avatar ao início. */
    fun finish() {
        isPlaying = false
        finished = true
        stopMessage = null
        progress = 0f
        if (hasRoute) avatarMarker.position = positionFor(0f)
    }

    fun restart() {
        if (!hasRoute) return
        isPlaying = false
        seek(0f)
        isPlaying = true
    }

    fun changeSpeed(newSpeed: Float) {
        speed = newSpeed
    }
}

/**
 * Cria o controller e o bitmap do avatar UMA ÚNICA VEZ por membro.
 * O BitmapDescriptor é estável entre frames; só o MarkerState.position muda.
 */
@Composable
internal fun rememberRoutePlayback(
    points: List<RoutePoint>,
    displayLatLngs: List<LatLng>,
    memberName: String?,
    avatarColor: Color
): RoutePlaybackController {
    val density = LocalDensity.current.density
    val descriptor = remember(points, memberName, avatarColor, density) {
        if (points.size >= 2) {
            BitmapDescriptorFactory.fromBitmap(
                playbackAvatarBitmap(density, memberName, avatarColor.toArgb())
            )
        } else null
    }
    return remember(points, displayLatLngs) {
        RoutePlaybackController(points, displayLatLngs, descriptor)
    }
}

/**
 * Loop de animação (Compose frame clock). Avança pelo tempo real decorrido
 * multiplicado pela velocidade; pausa 600ms ao cruzar trecho que parece uma
 * parada (dist < 15m E intervalo > 5min) e exibe "Parado por X min".
 * Cancelado no dispose/ao sair da composição.
 */
@Composable
internal fun RoutePlaybackEngine(controller: RoutePlaybackController) {
    LaunchedEffect(controller.isPlaying, controller.speed) {
        if (!controller.isPlaying || !controller.hasRoute) return@LaunchedEffect
        var lastFrameNanos = 0L
        var consumedIndex = -1
        while (true) {
            val crossedIndex = withFrameNanos { now ->
                if (lastFrameNanos == 0L) lastFrameNanos = now
                val dtMillis = (now - lastFrameNanos) / 1_000_000f
                lastFrameNanos = now
                controller.advance(dtMillis * controller.speed)
                val idx = controller.segmentIndex
                if (idx != consumedIndex) {
                    consumedIndex = idx
                    idx
                } else -1
            }
            if (crossedIndex in controller.segments.indices) {
                val seg = controller.segments[crossedIndex]
                if (seg.distanceMeters < STOP_DISTANCE_M &&
                    seg.startMillis > 0L && seg.endMillis > 0L &&
                    (seg.endMillis - seg.startMillis) > STOP_TIME_MS
                ) {
                    controller.stopMessage = ((seg.endMillis - seg.startMillis) / 60_000L).toString()
                    controller.stopCount++
                    controller.isPlaying = false
                    return@LaunchedEffect
                }
            }
            if (controller.progress >= 1f) {
                controller.finish()
                return@LaunchedEffect
            }
        }
    }

    // Retoma automaticamente 600ms após anunciar uma parada.
    LaunchedEffect(controller.stopCount) {
        if (controller.stopCount > 0) {
            delay(STOP_PAUSE_MS)
            controller.stopMessage = null
            if (controller.progress < 1f && !controller.finished) {
                controller.isPlaying = true
            }
        }
    }
}

/**
 * Política de câmera:
 * - segue o avatar no máximo 1 vez a cada ~700ms (nunca por frame);
 * - liga/desliga pelo toggle (controller.followAvatar);
 * - se o usuário arrastar o mapa (GESTURE), desliga automaticamente.
 */
@Composable
internal fun RoutePlaybackCameraFollow(
    controller: RoutePlaybackController,
    cameraPositionState: CameraPositionState
) {
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.cameraMoveStartedReason }
            .distinctUntilChanged()
            .collect { reason ->
                if (reason == CameraMoveStartedReason.GESTURE) {
                    controller.followAvatar = false
                }
            }
    }
    LaunchedEffect(controller.followAvatar, controller.isPlaying) {
        if (!controller.followAvatar || !controller.isPlaying) return@LaunchedEffect
        var lastMoveNanos = 0L
        while (true) {
            delay(400)
            val now = java.lang.System.nanoTime()
            if (controller.followAvatar && controller.isPlaying &&
                now - lastMoveNanos >= CAMERA_FOLLOW_MIN_INTERVAL_NS
            ) {
                lastMoveNanos = now
                try {
                    val pos = controller.avatarPosition
                    cameraPositionState.animate(CameraUpdateFactory.newLatLng(pos), 700)
                } catch (e: Exception) {
                    // Mapa ainda não pronto; ignora.
                }
            }
        }
    }
}

/**
 * Camadas do mapa da reprodução: trecho percorrido (Polyline opaca, recalculada
 * apenas quando o ÍNDICE do segmento muda) + avatar. O trecho restante (alpha
 * 0.35) é desenhado por trás pela tela.
 */
@Composable
internal fun PlaybackMapLayers(
    controller: RoutePlaybackController,
    primaryColor: Color
) {
    val traveledByIndex = remember(controller.segmentIndex) {
        controller.displayLatLngs
            .take((controller.segmentIndex + 1).coerceAtMost(controller.displayLatLngs.size))
    }
    if (traveledByIndex.size >= 2) {
        Polyline(
            points = traveledByIndex,
            color = primaryColor,
            width = 6f
        )
    }
    controller.avatarDescriptor?.let { descriptor ->
        Marker(
            state = controller.avatarMarker,
            icon = descriptor,
            anchor = Offset(0.5f, 0.5f),
            zIndex = 10f,
            flat = true,
            title = controller.timeMillisFor(controller.progress)
                ?.let(::formatPlaybackTime)
        )
    }
}

/**
 * Barra de controles flutuante e compacta sobre o mapa (máx. ~84dp, mapa ~80% visível):
 * linha 1 = slider fino + horário atual (esq.) / duração total ou status (dir.);
 * linha 2 = botões centrados e compactos (recomeçar, play/pause, velocidades, seguir).
 * Todos os botões têm contentDescription. Sem trajeto (< 2 pontos), só o aviso.
 */
@Composable
internal fun RoutePlaybackControls(
    controller: RoutePlaybackController,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            if (!controller.hasRoute) {
                Text(
                    text = stringResource(R.string.playback_insufficient),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                )
                return@Column
            }

            // Linha 1: tempo de agora (esq.) — slider slim — duração total (dir.)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = controller.timeMillisFor(controller.progress)
                        ?.let(::formatPlaybackTime) ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = controller.progress,
                    onValueChange = { controller.seek(it) },
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        controller.finished -> stringResource(R.string.playback_finished)
                        controller.stopMessage != null -> stringResource(
                            R.string.playback_stopped,
                            controller.stopMessage.orEmpty()
                        )
                        else -> controller.timeMillisFor(1f)?.let(::formatPlaybackTime) ?: ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    color = when {
                        controller.finished -> MaterialTheme.colorScheme.primary
                        controller.stopMessage != null -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Linha 2: controles compactos centralizados
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { controller.restart() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay,
                        contentDescription = stringResource(R.string.playback_restart),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { controller.togglePlayPause() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (controller.isPlaying) R.string.playback_pause else R.string.playback_play
                        ),
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                PLAYBACK_SPEEDS.forEach { s ->
                    FilterChip(
                        selected = controller.speed == s,
                        onClick = { controller.changeSpeed(s) },
                        label = {
                            Text(
                                text = "${s.toInt()}x",
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.height(32.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { controller.followAvatar = !controller.followAvatar },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = stringResource(
                            if (controller.followAvatar) R.string.playback_follow_on
                            else R.string.playback_follow_off
                        ),
                        modifier = Modifier.size(20.dp),
                        tint = if (controller.followAvatar) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/**
 * Bitmap do avatar do trajeto: o mesmo estilo do marcador principal — círculo
 * branco com iniciais (cor primária) e borda branca 3.5dp. Sem pino (o avatar
 * "flutua" sobre o mapa). Anchor do Marker = (0.5f, 0.5f).
 */
private fun playbackAvatarBitmap(
    density: Float,
    name: String?,
    avatarColor: Int
): android.graphics.Bitmap {
    val avatarPx = (48f * density).toInt().coerceAtLeast(1)
    val borderPx = (3.5f * density).coerceAtLeast(2f)
    val r = avatarPx / 2f

    val out = android.graphics.Bitmap.createBitmap(
        avatarPx, avatarPx, android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(out)

    val whiteFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
    }
    canvas.drawCircle(r, r, r, whiteFill)

    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = avatarColor
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD
        )
        textSize = avatarPx * 0.5f
    }
    val fm = textPaint.fontMetrics
    canvas.drawText(
        playbackInitials(name),
        r,
        r - (fm.ascent + fm.descent) / 2f,
        textPaint
    )

    val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = android.graphics.Color.WHITE
        strokeWidth = borderPx
    }
    canvas.drawCircle(r, r, r - borderPx / 2f, borderPaint)
    return out
}

/** Iniciais do nome (mesma lógica do marcador principal do mapa). */
private fun playbackInitials(name: String?): String =
    (name ?: "")
        .trim()
        .split(Regex("\\s+"))
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { "?" }

/** Pré-calcula distância e intervalo por segmento (Location.distanceBetween). */
private fun buildSegments(points: List<RoutePoint>): List<RouteSegment> {
    val out = ArrayList<RouteSegment>(points.size - 1)
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val d = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, d)
        out += RouteSegment(
            start = a,
            end = b,
            distanceMeters = d[0].coerceAtLeast(0f),
            startMillis = parseRouteIsoMillis(a.recorded_at) ?: 0L,
            endMillis = parseRouteIsoMillis(b.recorded_at) ?: 0L
        )
    }
    return out
}

/** Tolerante a sufixo de fuso (mesma lógica do histórico). */
private fun parseRouteIsoMillis(timestamp: String?): Long? {
    if (timestamp == null) return null
    return try {
        val cleaned = timestamp.trim().substringBefore('.')
        val fmt = if (cleaned.endsWith("Z")) {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
        } else {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        }
        fmt.parse(cleaned)?.time
    } catch (e: Exception) {
        null
    }
}

/** Formata o horário interpolado como HH:mm (fuso do aparelho). */
internal fun formatPlaybackTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(millis))