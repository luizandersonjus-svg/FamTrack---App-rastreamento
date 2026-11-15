package com.famtrack.app.feature.delivery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory

/** Verde da bandeira de pacote entregue (inteiramente verde). */
internal val DeliveredGreen = Color(0xFF1B7F3B)
private val DeliveredGreenBorder = Color(0xFF14522A)
private val FlagInk = Color(0xFF111111)

private const val FLAG_COLS = 5
private const val FLAG_ROWS = 3

/**
 * Bandeira para listas e painéis: quadriculada (estilo corrida) enquanto o
 * pacote está pendente, inteiramente verde depois de entregue. O número do
 * pacote fica no centro; na quadriculada, sobre uma plaquinha branca.
 */
@Composable
fun DeliveryFlagChip(
    number: String,
    delivered: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .background(FlagInk)
        )
        Box(
            modifier = Modifier
                .size(width = 50.dp, height = 30.dp)
                .clip(shape)
                .drawBehind {
                    if (delivered) {
                        drawRect(DeliveredGreen)
                    } else {
                        drawRect(Color.White)
                        val cellW = size.width / FLAG_COLS
                        val cellH = size.height / FLAG_ROWS
                        for (row in 0 until FLAG_ROWS) {
                            for (col in 0 until FLAG_COLS) {
                                if ((row + col) % 2 == 0) {
                                    drawRect(
                                        color = FlagInk,
                                        topLeft = Offset(col * cellW, row * cellH),
                                        size = Size(cellW, cellH)
                                    )
                                }
                            }
                        }
                    }
                }
                .border(1.dp, if (delivered) DeliveredGreenBorder else FlagInk, shape),
            contentAlignment = Alignment.Center
        ) {
            val plate = if (delivered) Modifier else Modifier
                .background(Color.White, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp)
            Text(
                text = number,
                modifier = plate,
                color = if (delivered) Color.White else FlagInk,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
    }
}

/** Amostra pequena da bandeira, para legendas. */
@Composable
fun DeliveryFlagSwatch(delivered: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 20.dp, height = 12.dp)
            .drawBehind {
                if (delivered) {
                    drawRect(DeliveredGreen)
                } else {
                    drawRect(Color.White)
                    val cellW = size.width / FLAG_COLS
                    val cellH = size.height / FLAG_ROWS
                    for (row in 0 until FLAG_ROWS) {
                        for (col in 0 until FLAG_COLS) {
                            if ((row + col) % 2 == 0) {
                                drawRect(
                                    color = FlagInk,
                                    topLeft = Offset(col * cellW, row * cellH),
                                    size = Size(cellW, cellH)
                                )
                            }
                        }
                    }
                }
            }
            .border(1.dp, if (delivered) DeliveredGreenBorder else FlagInk)
    )
}

/** Ícone do marcador + âncora (fração do bitmap onde fica o pé do mastro). */
internal class DeliveryFlagIcon(val descriptor: BitmapDescriptor, val anchor: Offset)

/**
 * Marcador do mapa: mastro com a bandeira no alto (número do pacote) e, no
 * pé do mastro, o texto "casa 312" / "comércio 1240" com contorno branco.
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
    // Unidade = 1dp (um pouco maior quando o marcador está selecionado).
    val u = density * (if (selected) 1.25f else 1f)

    val pad = 6f * u
    val poleW = 3f * u
    val poleH = 44f * u
    val flagW = 50f * u
    val flagH = 30f * u
    val poleX = pad
    val top = pad
    val flagLeft = poleX + poleW
    val baseY = top + poleH

    val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * u
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        color = if (delivered) android.graphics.Color.WHITE else 0xFF111111.toInt()
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * u
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = 0xFF1F1F1F.toInt()
    }
    val labelHalo = Paint(labelPaint).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * u
        strokeJoin = Paint.Join.ROUND
        color = android.graphics.Color.WHITE
    }

    val labelX = flagLeft + 5f * u
    val labelWidth = if (label.isEmpty()) 0f else labelPaint.measureText(label)
    val width = (maxOf(flagLeft + flagW, labelX + labelWidth) + pad).toInt().coerceAtLeast(1)
    val height = (baseY + 12f * u + pad).toInt().coerceAtLeast(1)

    val bitmap = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    } catch (e: Throwable) {
        return null
    }
    val canvas = Canvas(bitmap)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt() }

    // Mastro e ponto de apoio no chão.
    canvas.drawRoundRect(RectF(poleX, top, poleX + poleW, baseY), u, u, ink)
    canvas.drawCircle(poleX + poleW / 2f, baseY, 4.5f * u, ink)

    // Bandeira.
    val flagRect = RectF(flagLeft, top, flagLeft + flagW, top + flagH)
    if (delivered) {
        canvas.drawRect(flagRect, Paint().apply { color = 0xFF1B7F3B.toInt() })
    } else {
        canvas.drawRect(flagRect, Paint().apply { color = android.graphics.Color.WHITE })
        val cellW = flagW / FLAG_COLS
        val cellH = flagH / FLAG_ROWS
        for (row in 0 until FLAG_ROWS) {
            for (col in 0 until FLAG_COLS) {
                if ((row + col) % 2 == 0) {
                    canvas.drawRect(
                        flagLeft + col * cellW,
                        top + row * cellH,
                        flagLeft + (col + 1) * cellW,
                        top + (row + 1) * cellH,
                        ink
                    )
                }
            }
        }
    }
    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (if (selected) 2.5f else 1f) * u
        color = if (delivered) 0xFF14522A.toInt() else 0xFF111111.toInt()
    }
    canvas.drawRect(flagRect, border)

    // Número do pacote (na quadriculada, sobre plaquinha branca).
    val centerX = flagRect.centerX()
    val textMetrics = numberPaint.fontMetrics
    val baseline = flagRect.centerY() - (textMetrics.ascent + textMetrics.descent) / 2f
    if (!delivered) {
        val plateHalfW = numberPaint.measureText(number) / 2f + 4f * u
        val plateHalfH = 9f * u
        canvas.drawRoundRect(
            RectF(
                centerX - plateHalfW,
                flagRect.centerY() - plateHalfH,
                centerX + plateHalfW,
                flagRect.centerY() + plateHalfH
            ),
            4f * u, 4f * u,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        )
    }
    canvas.drawText(number, centerX, baseline, numberPaint)

    // "casa 312" / "comércio 1240" no pé do mastro.
    if (label.isNotEmpty()) {
        val labelBaseline = baseY + 5f * u
        canvas.drawText(label, labelX, labelBaseline, labelHalo)
        canvas.drawText(label, labelX, labelBaseline, labelPaint)
    }

    val anchor = Offset((poleX + poleW / 2f) / width, baseY / height)
    return bitmap to anchor
}
