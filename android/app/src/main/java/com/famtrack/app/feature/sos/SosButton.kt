package com.famtrack.app.feature.sos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HOLD_MILLIS = 3000L

/**
 * Botão circular de SOS (F5).
 *
 * Gestos:
 *  - Segurar o dedo pressionado por 3 segundos -> dispara [onTrigger] (envio do SOS).
 *  - Toque rápido (ou arrasto para fora) antes dos 3 segundos -> cancela o
 *    timer e dispara [onTap] (dica de como acionar o SOS).
 *
 * A animação de progresso (anel) reflete os 3 segundos de pressionar.
 */
@Composable
fun SosButton(
    onTrigger: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val progress by animateFloatAsState(
        targetValue = if (pressing) 1f else 0f,
        animationSpec = if (pressing) {
            tween(durationMillis = HOLD_MILLIS.toInt())
        } else {
            tween(durationMillis = 150)
        },
        label = "sosHold"
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error)
            .drawBehind {
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = size.minDimension / 2f - 1.dp.toPx(),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "SOS. Segure por 3 segundos para acionar"
            }
            .drawBehind {
                val sweep = 360f * progress.coerceIn(0f, 1f)
                if (sweep > 0f) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.9f),
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                        size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var triggered = false

                    // Timer de 3s: dispara o SOS se o dedo continuar pressionado.
                    val holdJob = scope.launch {
                        delay(HOLD_MILLIS)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        triggered = true
                        onTrigger()
                    }

                    pressing = true

                    // Segue até soltar o dedo ou o gesto ser cancelado.
                    waitForUpOrCancellation()
                    holdJob.cancel()
                    pressing = false

                    // Toque rápido/arrasto para fora: cancela o envio e ensina o gesto.
                    if (!triggered) {
                        onTap()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "SOS",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}
