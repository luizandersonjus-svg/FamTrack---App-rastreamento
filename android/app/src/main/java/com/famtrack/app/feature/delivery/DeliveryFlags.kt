package com.famtrack.app.feature.delivery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Verde da bandeira de pacote entregue (inteiramente verde). */
internal val DeliveredGreen = Color(0xFF1B7F3B)

// Quadriculado: 6 colunas x 4 linhas, como uma bandeira de chegada.
private const val FLAG_COLS = 6
private const val FLAG_ROWS = 4
// Subdivisões por coluna para as bordas da onda saírem curvas.
private const val WAVE_STEPS = 4

private const val INK = 0xFF161616.toInt()
private const val INK_LIGHT = 0xFF5A5A5A.toInt()
private const val PAPER = 0xFFFFFFFF.toInt()
private const val PAPER_SHADE = 0xFFB9B9B9.toInt()
private const val GREEN = 0xFF1B7F3B.toInt()
private const val GREEN_LIGHT = 0xFF34A862.toInt()
private const val GREEN_DARK = 0xFF0E4A22.toInt()
private const val POLE = 0xFFE9E9E9.toInt()
private const val POLE_EDGE = 0xFF8A8A8A.toInt()
private const val GOLD = 0xFFE0A83A.toInt()
private const val GOLD_DARK = 0xFF9A6514.toInt()

/**
 * Geometria da bandeira ondulada, em coordenadas locais com o pé do mastro
 * em (0, 0). [u] = tamanho de 1dp em pixels (já com a escala de seleção).
 *
 * O mastro sobe inclinado para a direita; o tecido sai do topo do mastro e
 * ondula para a direita, com a ponta solta caindo um pouco — o mesmo
 * desenho da bandeira de chegada.
 */
private class FlagGeometry(val u: Float) {
    private val tilt = Math.toRadians(16.0)
    val poleLength = 56f * u
    val poleWidth = 3.2f * u
    val flagWidth = 54f * u
    val flagHeight = 36f * u
    private val amplitude = 4.2f * u
    private val droop = 6f * u

    val poleTop = PointF(
        (sin(tilt) * poleLength).toFloat(),
        (-cos(tilt) * poleLength).toFloat()
    )
    // O tecido começa um pouco abaixo da ponteira.
    private val attach = PointF(
        poleTop.x - (sin(tilt) * 4f * u).toFloat(),
        poleTop.y + (cos(tilt) * 4f * u).toFloat()
    )

    /** Ponto do tecido: [s] ao longo do comprimento (0 = mastro), [t] na altura. */
    fun point(s: Float, t: Float): PointF {
        // Borda presa ao mastro: desce acompanhando a inclinação.
        val edgeX = attach.x - (sin(tilt) * t * flagHeight).toFloat()
        val edgeY = attach.y + (cos(tilt) * t * flagHeight).toFloat()
        // Onda: nula no mastro, cresce para a ponta solta.
        val wave = amplitude * sin(2.0 * PI * (1.05 * s) + 0.35).toFloat() * (0.2f + 0.8f * s)
        // O tecido "encolhe" um pouco na horizontal nas dobras.
        val x = edgeX + s * flagWidth - 2.5f * u * s * s
        val y = edgeY + wave + droop * s * s + 3f * u * s * t
        return PointF(x, y)
    }

    /** Inclinação da onda em [s]: >0 dobra virada para a luz, <0 na sombra. */
    fun slope(s: Float): Float = cos(2.0 * PI * (1.05 * s) + 0.35).toFloat()

    /** Caixa que contém mastro, ponteira e tecido. */
    fun bounds(): RectF {
        val r = RectF(-6f * u, poleTop.y - 6f * u, 6f * u, 6f * u)
        for (i in 0..12) {
            for (j in 0..4) {
                val p = point(i / 12f, j / 4f)
                r.union(p.x, p.y)
            }
        }
        r.inset(-2f * u, -2f * u)
        return r
    }
}

private fun mix(a: Int, b: Int, f: Float): Int {
    val k = f.coerceIn(0f, 1f)
    fun ch(shift: Int) =
        (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).toInt() and 0xFF
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * Desenha a bandeira com o pé do mastro em (0, 0) do [canvas]. [number]
 * vazio = sem plaquinha (legenda). [label] ("casa 312") vai no pé do mastro.
 */
private fun drawWavingFlag(
    canvas: Canvas,
    g: FlagGeometry,
    number: String,
    label: String,
    delivered: Boolean,
    selected: Boolean
) {
    val u = g.u
    val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    // Sombra no chão e mastro.
    fill.color = 0x33000000
    canvas.drawOval(RectF(-6f * u, -2f * u, 6f * u, 2.5f * u), fill)
    val pole = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    pole.strokeWidth = g.poleWidth + 1.6f * u
    pole.color = POLE_EDGE
    canvas.drawLine(0f, 0f, g.poleTop.x, g.poleTop.y, pole)
    pole.strokeWidth = g.poleWidth
    pole.color = POLE
    canvas.drawLine(0f, 0f, g.poleTop.x, g.poleTop.y, pole)

    // Tecido: célula a célula, com as bordas seguindo a onda.
    val cell = Path()
    for (col in 0 until FLAG_COLS) {
        val s0 = col.toFloat() / FLAG_COLS
        val s1 = (col + 1).toFloat() / FLAG_COLS
        val light = g.slope((s0 + s1) / 2f)
        for (row in 0 until FLAG_ROWS) {
            val t0 = row.toFloat() / FLAG_ROWS
            val t1 = (row + 1).toFloat() / FLAG_ROWS
            cell.reset()
            for (k in 0..WAVE_STEPS) {
                val p = g.point(s0 + (s1 - s0) * k / WAVE_STEPS, t0)
                if (k == 0) cell.moveTo(p.x, p.y) else cell.lineTo(p.x, p.y)
            }
            for (k in WAVE_STEPS downTo 0) {
                val p = g.point(s0 + (s1 - s0) * k / WAVE_STEPS, t1)
                cell.lineTo(p.x, p.y)
            }
            cell.close()
            fill.color = when {
                delivered -> if (light >= 0) mix(GREEN, GREEN_LIGHT, light * 0.8f)
                else mix(GREEN, GREEN_DARK, -light * 0.8f)
                (row + col) % 2 == 0 -> mix(INK, INK_LIGHT, light.coerceAtLeast(0f) * 0.55f)
                else -> mix(PAPER, PAPER_SHADE, (-light).coerceAtLeast(0f) * 0.75f + 0.05f)
            }
            canvas.drawPath(cell, fill)
        }
    }

    // Contorno do tecido.
    val outline = Path()
    for (i in 0..24) {
        val p = g.point(i / 24f, 0f)
        if (i == 0) outline.moveTo(p.x, p.y) else outline.lineTo(p.x, p.y)
    }
    for (j in 1..8) g.point(1f, j / 8f).let { outline.lineTo(it.x, it.y) }
    for (i in 23 downTo 0) g.point(i / 24f, 1f).let { outline.lineTo(it.x, it.y) }
    outline.close()
    canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = (if (selected) 2.4f else 1f) * u
        color = if (selected) 0xFFFFC107.toInt() else if (delivered) GREEN_DARK else INK
    })

    // Ponteira dourada.
    val tip = g.poleTop
    val r = 3.6f * u
    canvas.drawCircle(tip.x, tip.y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            tip.x - r * 0.4f, tip.y - r * 0.4f, r * 1.4f,
            intArrayOf(0xFFFFE6A6.toInt(), GOLD, GOLD_DARK),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    })

    // Número do pacote numa plaquinha reta (legível mesmo com a onda).
    if (number.isNotEmpty()) {
        val center = g.point(0.5f, 0.5f)
        val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13f * u
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = if (delivered) PAPER else INK
        }
        val halfW = numberPaint.measureText(number) / 2f + 4f * u
        val halfH = 8.5f * u
        val plate = RectF(center.x - halfW, center.y - halfH, center.x + halfW, center.y + halfH)
        canvas.drawRoundRect(plate, 4f * u, 4f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (delivered) GREEN_DARK else PAPER
        })
        canvas.drawRoundRect(plate, 4f * u, 4f * u, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.8f * u
            color = if (delivered) GREEN_DARK else INK
        })
        val m = numberPaint.fontMetrics
        canvas.drawText(number, center.x, center.y - (m.ascent + m.descent) / 2f, numberPaint)
    }

    // "casa 312" / "comércio 1240" logo abaixo do pé do mastro.
    if (label.isNotEmpty()) {
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12.5f * u
            typeface = Typeface.DEFAULT_BOLD
            color = 0xFF1F1F1F.toInt()
        }
        val halo = Paint(labelPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * u
            strokeJoin = Paint.Join.ROUND
            color = PAPER
        }
        // Logo abaixo do pé do mastro, sem cobrir o mastro.
        val x = 5f * u
        val y = 15f * u
        canvas.drawText(label, x, y, halo)
        canvas.drawText(label, x, y, labelPaint)
    }
}

private fun labelBounds(label: String, u: Float): RectF? {
    if (label.isEmpty()) return null
    val paint = Paint().apply {
        textSize = 12.5f * u
        typeface = Typeface.DEFAULT_BOLD
    }
    return RectF(5f * u, 0f, 5f * u + paint.measureText(label) + 3f * u, 19f * u)
}

/**
 * Bandeira para listas e painéis: quadriculada enquanto o pacote está
 * pendente, inteiramente verde depois de entregue, com o número no centro.
 */
@Composable
fun DeliveryFlagChip(
    number: String,
    delivered: Boolean,
    modifier: Modifier = Modifier
) {
    ComposeCanvas(modifier = modifier.size(width = 64.dp, height = 56.dp)) {
        drawIntoCanvas { c -> drawFlagFitted(c.nativeCanvas, size.width, size.height, number, delivered) }
    }
}

/** Amostra pequena da bandeira, para legendas. */
@Composable
fun DeliveryFlagSwatch(delivered: Boolean, modifier: Modifier = Modifier) {
    ComposeCanvas(modifier = modifier.size(width = 24.dp, height = 22.dp)) {
        drawIntoCanvas { c -> drawFlagFitted(c.nativeCanvas, size.width, size.height, "", delivered) }
    }
}

/** Desenha a bandeira inteira (sem rótulo) ajustada a uma área w x h. */
private fun drawFlagFitted(canvas: Canvas, w: Float, h: Float, number: String, delivered: Boolean) {
    val base = FlagGeometry(1f).bounds()
    val scale = minOf(w / base.width(), h / base.height())
    val g = FlagGeometry(scale)
    val b = g.bounds()
    canvas.save()
    canvas.translate(-b.left + (w - b.width()) / 2f, -b.top + (h - b.height()) / 2f)
    drawWavingFlag(canvas, g, number, "", delivered, selected = false)
    canvas.restore()
}

/** Ícone do marcador + âncora (fração do bitmap onde fica o pé do mastro). */
internal class DeliveryFlagIcon(val descriptor: BitmapDescriptor, val anchor: Offset)

/**
 * Marcador do mapa: bandeira ondulada no mastro (número do pacote) e, no pé
 * do mastro, o texto "casa 312" / "comércio 1240" com contorno branco.
 * Deve ser chamado dentro do conteúdo do GoogleMap (o SDK já inicializado).
 */
@Composable
internal fun rememberDeliveryFlagIcon(
    number: String,
    label: String,
    delivered: Boolean,
    selected: Boolean,
    density: Float
): DeliveryFlagIcon? = remember(number, label, delivered, selected, density) {
    try {
        buildDeliveryFlagBitmap(number, label, delivered, selected, density)
            ?.let { (bitmap, anchor) ->
                DeliveryFlagIcon(BitmapDescriptorFactory.fromBitmap(bitmap), anchor)
            }
    } catch (e: Exception) {
        null
    }
}

private fun buildDeliveryFlagBitmap(
    number: String,
    label: String,
    delivered: Boolean,
    selected: Boolean,
    density: Float
): Pair<Bitmap, Offset>? {
    // Unidade = 1dp (maior quando o marcador está selecionado).
    val u = density * (if (selected) 1.3f else 1f)
    val g = FlagGeometry(u)
    val bounds = g.bounds()
    labelBounds(label, u)?.let { bounds.union(it) }

    val width = bounds.width().toInt().coerceAtLeast(1)
    val height = bounds.height().toInt().coerceAtLeast(1)
    val bitmap = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) {
        return null
    }
    val canvas = Canvas(bitmap)
    canvas.translate(-bounds.left, -bounds.top)
    drawWavingFlag(canvas, g, number, label, delivered, selected)

    val anchor = Offset(-bounds.left / width, -bounds.top / height)
    return bitmap to anchor
}
