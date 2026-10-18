package com.famtrack.app.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PatternItem
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Polyline
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Rastro ao vivo "da largura da rua".
 *
 * Antes o rastro era uma linha fixa de 8 px — fina demais perto do nível de rua.
 * Agora a largura acompanha o zoom: calculamos quantos metros cabem em 1 dp na
 * posição atual da câmera e desenhamos a linha com a largura de uma via urbana
 * típica, de modo que o rastro "pinta" a rua por onde o membro passou.
 *
 * Unidades: PolylineOptions.width é em PIXELS de tela. A escala Web Mercator
 * (156543.03392 m por unidade no zoom 0, no equador) é por unidade de tile de
 * 256 — no SDK de Maps do Android essa unidade é o dp (o tile ocupa 256 dp),
 * então: metros/dp = 156543.03392 · cos(lat) / 2^zoom, e px = dp · densidade.
 */

/**
 * Largura de referência (metros) de uma via urbana comum: 2–3 faixas de ~3,5 m
 * mais um pouco de margem. 12 m cobre bem ruas residenciais e avenidas simples
 * sem transbordar demais nas ruas estreitas.
 */
private const val TRAIL_ROAD_WIDTH_METERS = 12.0

/** Largura mínima (dp): mantém o rastro visível com o mapa afastado. */
private const val TRAIL_MIN_WIDTH_DP = 8

/** Largura máxima (dp): evita que, muito perto, o rastro vire uma "mancha". */
private const val TRAIL_MAX_WIDTH_DP = 28

/** Contorno escuro (dp, de cada lado) dos trechos encaixados, para contraste. */
private const val TRAIL_OUTLINE_DP = 1f

/** Metros por dp no zoom 0, no equador (Web Mercator, tile de 256 dp). */
private const val METERS_PER_DP_ZOOM0_EQUATOR = 156543.03392

/**
 * Largura (px) para a qual o padrão tracejado base foi desenhado (o antigo
 * `width = 8f`). O padrão é escalado na proporção da largura atual, para que os
 * traços não virem "pontinhos" quando a linha engorda.
 */
private const val TRAIL_PATTERN_BASE_WIDTH_PX = 8f

// Opacidades: preenchimento translúcido para o nome da rua continuar legível.
private const val TRAIL_FILL_ALPHA = 0.6f
private const val TRAIL_OUTLINE_ALPHA = 0.45f
private const val TRAIL_PROVISIONAL_ALPHA = 0.4f

// Ordem de desenho: sempre ABAIXO dos marcadores (eventos 0.5, membros 1+).
private const val TRAIL_OUTLINE_Z = 0.38f
private const val TRAIL_PROVISIONAL_Z = 0.39f
private const val TRAIL_FILL_Z = 0.4f

/** Instância única: evita que o Polyline reaplique a ponta a cada recomposição. */
private val TRAIL_ROUND_CAP = RoundCap()

/** Rastro já montado de um membro: cor estável + pedaços (encaixados ou brutos). */
internal data class MemberTrail(
    val color: Color,
    val pieces: List<TrailPiece>
)

/**
 * Largura do rastro (dp inteiro) para um zoom/latitude: a largura real de uma
 * via ([TRAIL_ROAD_WIDTH_METERS]) convertida para dp e limitada a
 * [TRAIL_MIN_WIDTH_DP]..[TRAIL_MAX_WIDTH_DP]. Arredondar para dp inteiro faz o
 * derivedStateOf só notificar quando a largura realmente muda (não a cada
 * quadro de um gesto de pinça).
 */
internal fun trailWidthDpFor(zoom: Float, latitude: Double): Int {
    val metersPerDp = METERS_PER_DP_ZOOM0_EQUATOR *
        cos(Math.toRadians(latitude)) / 2.0.pow(zoom.toDouble())
    if (!metersPerDp.isFinite() || metersPerDp <= 0.0) return TRAIL_MIN_WIDTH_DP
    val dp = TRAIL_ROAD_WIDTH_METERS / metersPerDp
    if (!dp.isFinite()) return TRAIL_MAX_WIDTH_DP
    return dp.roundToInt().coerceIn(TRAIL_MIN_WIDTH_DP, TRAIL_MAX_WIDTH_DP)
}

/** Escala um padrão tracejado (Dash/Gap) pelo fator [factor]. */
private fun scalePattern(base: List<PatternItem>, factor: Float): List<PatternItem> =
    base.map { item ->
        when (item) {
            is Dash -> Dash(item.length * factor)
            is Gap -> Gap(item.length * factor)
            else -> item
        }
    }

/** Tolerância (graus, ~2 m) para considerar que dois pedaços se tocam. */
private const val TRAIL_JOIN_TOLERANCE_DEG = 2e-5

private fun samePoint(a: LatLng, b: LatLng): Boolean =
    abs(a.latitude - b.latitude) < TRAIL_JOIN_TOLERANCE_DEG &&
        abs(a.longitude - b.longitude) < TRAIL_JOIN_TOLERANCE_DEG

/**
 * Junta pedaços consecutivos do mesmo tipo que se tocam numa única linha.
 * Com preenchimento translúcido e pontas redondas, cada emenda entre dois
 * Polylines viraria uma "bolinha" mais escura (sobreposição de transparências);
 * uma linha só, com juntas redondas, fica uniforme.
 */
private fun mergeContiguousPieces(pieces: List<TrailPiece>): List<TrailPiece> {
    val out = mutableListOf<TrailPiece>()
    var currentPoints: MutableList<LatLng>? = null
    var currentOnRoad = false
    for (piece in pieces) {
        if (piece.points.size < 2) continue
        val acc = currentPoints
        if (acc != null && currentOnRoad == piece.onRoad &&
            samePoint(acc.last(), piece.points.first())
        ) {
            acc.addAll(piece.points.subList(1, piece.points.size))
        } else {
            if (acc != null) out += TrailPiece(acc, currentOnRoad)
            currentPoints = piece.points.toMutableList()
            currentOnRoad = piece.onRoad
        }
    }
    currentPoints?.let { out += TrailPiece(it, currentOnRoad) }
    return out
}

/**
 * Desenha o rastro ao vivo de todos os membros com largura proporcional à rua.
 *
 * - Trechos encaixados nas ruas: contorno escuro fino por baixo + preenchimento
 *   translúcido na cor do membro (destaque da via, nome da rua legível).
 * - Trechos de GPS bruto (provisórios): tracejados e mais transparentes, sem
 *   contorno; o tracejado escala com a largura.
 *
 * A leitura da câmera fica isolada neste composable (escopo de recomposição
 * próprio): mudar o zoom recompõe só o rastro, não os marcadores do mapa.
 *
 * @param density densidade da tela (px por dp).
 * @param provisionalBasePattern padrão tracejado desenhado para 8 px de largura.
 */
@Composable
internal fun LiveTrailPolylines(
    trails: List<MemberTrail>,
    cameraPositionState: CameraPositionState,
    density: Float,
    provisionalBasePattern: List<PatternItem>
) {
    val widthDp by remember(cameraPositionState) {
        derivedStateOf {
            val pos = cameraPositionState.position
            trailWidthDpFor(pos.zoom, pos.target.latitude)
        }
    }
    val widthPx = widthDp * density
    val outlinePx = (widthDp + 2 * TRAIL_OUTLINE_DP) * density
    val provisionalPattern = remember(widthPx, provisionalBasePattern) {
        scalePattern(provisionalBasePattern, widthPx / TRAIL_PATTERN_BASE_WIDTH_PX)
    }

    // Emendas unificadas só quando o rastro muda (não a cada mudança de zoom).
    val drawable = remember(trails) {
        trails.map { MemberTrail(it.color, mergeContiguousPieces(it.pieces)) }
    }

    drawable.forEach { trail ->
        val fillColor = trail.color.copy(alpha = TRAIL_FILL_ALPHA)
        val outlineColor = lerp(trail.color, Color.Black, 0.45f)
            .copy(alpha = TRAIL_OUTLINE_ALPHA)
        val provisionalColor = trail.color.copy(alpha = TRAIL_PROVISIONAL_ALPHA)
        trail.pieces.forEach { piece ->
            if (piece.onRoad) {
                // Contorno: um pouco mais largo e escuro, por baixo do preenchimento.
                Polyline(
                    points = piece.points,
                    color = outlineColor,
                    width = outlinePx,
                    jointType = JointType.ROUND,
                    startCap = TRAIL_ROUND_CAP,
                    endCap = TRAIL_ROUND_CAP,
                    zIndex = TRAIL_OUTLINE_Z
                )
                Polyline(
                    points = piece.points,
                    color = fillColor,
                    width = widthPx,
                    jointType = JointType.ROUND,
                    startCap = TRAIL_ROUND_CAP,
                    endCap = TRAIL_ROUND_CAP,
                    zIndex = TRAIL_FILL_Z
                )
            } else {
                Polyline(
                    points = piece.points,
                    color = provisionalColor,
                    width = widthPx,
                    jointType = JointType.ROUND,
                    startCap = TRAIL_ROUND_CAP,
                    endCap = TRAIL_ROUND_CAP,
                    pattern = provisionalPattern,
                    zIndex = TRAIL_PROVISIONAL_Z
                )
            }
        }
    }
}
