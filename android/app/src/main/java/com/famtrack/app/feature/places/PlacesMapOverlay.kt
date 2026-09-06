package com.famtrack.app.feature.places

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.platform.LocalDensity
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState

/**
 * Camada dos locais salvos (F3) para desenhar dentro do mapa da tela inicial:
 * círculo da geofence + marcador com o ícone coerente do local (badge redondo
 * branco com o ícone colorido). Os círculos sobrepõem-se às geofences
 * existentes sem conflito.
 */
@Composable
fun PlacesMapOverlay(places: List<Place>) {
    val density = LocalDensity.current.density
    val pinColor = MaterialTheme.colorScheme.primary.toArgb()
    places.forEach { place ->
        val center = LatLng(place.center_lat, place.center_lon)
        Circle(
            center = center,
            radius = place.radius_meters.toDouble(),
            strokeColor = MaterialTheme.colorScheme.secondary,
            fillColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
            strokeWidth = 2f
        )
        val descriptor = placeIconDescriptor(
            name = place.name,
            type = place.type,
            tint = pinColor,
            densityFloat = density
        )
        if (descriptor != null) {
            Marker(
                state = MarkerState(position = center),
                title = place.name,
                icon = descriptor,
                anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                zIndex = 5f
            )
        }
    }
}

/**
 * Descritor do pin-marcador com o ícone coerente do lugar (badge branco
 * circular + ícone colorido). Reaproveitado na edição de local para o marcador
 * do mapa refletir o tipo/nome atual.
 */
@Composable
internal fun placeIconDescriptor(
    name: String,
    type: String,
    tint: Int,
    densityFloat: Float
): BitmapDescriptor? {
    val vector = resolvePlaceIcon(name, type)
    return remember(name, type, tint, densityFloat, vector) {
        buildPlaceBadgeBitmap(vector, densityFloat, tint)
            ?.let { BitmapDescriptorFactory.fromBitmap(it) }
    }
}

/** Renderiza o ImageVector num badge redondo branco via Android Canvas. */
private fun buildPlaceBadgeBitmap(
    vector: ImageVector,
    densityFloat: Float,
    tint: Int
): Bitmap? {
    val sizePx = (34f * densityFloat).toInt().coerceAtLeast(30)
    val bitmap = try {
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    } catch (e: Exception) {
        return null
    }
    val canvas = android.graphics.Canvas(bitmap)
    val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 1f, whitePaint)

    val path = try {
        flattenPathData(vector).toPath().asAndroidPath()
    } catch (e: Exception) {
        return bitmap
    }
    val bounds = RectF()
    path.computeBounds(bounds, true)
    val pathW = bounds.width()
    val pathH = bounds.height()
    if (pathW <= 0f || pathH <= 0f) return bitmap

    val iconSize = sizePx * 0.62f
    val scale = kotlin.math.min(iconSize / pathW, iconSize / pathH)

    val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.FILL
    }
    canvas.save()
    canvas.concat(
        Matrix().apply {
            postScale(scale, scale)
            postTranslate(
                -bounds.left * scale + (sizePx - pathW * scale) / 2f,
                -bounds.top * scale + (sizePx - pathH * scale) / 2f
            )
        }
    )
    canvas.drawPath(path, tintPaint)
    canvas.restore()
    return bitmap
}

/** Junta os PathNodes de todos os VectorPath do ImageVector (raiz e subgrupos). */
private fun flattenPathData(vector: ImageVector): List<PathNode> {
    val nodes = mutableListOf<PathNode>()
    fun collect(children: Iterable<VectorNode>) {
        for (child in children) {
            when (child) {
                is VectorPath -> nodes += child.pathData
                is VectorGroup -> collect(child)
            }
        }
    }
    nodes += vector.root.clipPathData
    collect(vector.root)
    return nodes
}