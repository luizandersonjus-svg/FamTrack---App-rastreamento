package com.famtrack.app.feature.sos

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import kotlinx.coroutines.launch

private const val HOLD_MILLIS = 3000L

/**
 * Botão de SOS com pressionar e segurar por 3 segundos. Ao soltar com a
 * duração mínima, dispara onTrigger (F5).
 */
@Composable
fun SosButton(
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(36.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        val pointerId = down.id
                        scope.launch { pressing = true }
                        val start = System.currentTimeMillis()
                        var released = false
                        var consumed = false
                        while (!released) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                            if (change == null) {
                                released = true
                                break
                            }
                            if (!change.pressed) {
                                released = true
                                consumed = change.isConsumed
                                break
                            }
                        }
                        scope.launch {
                            pressing = false
                            if (released && !consumed &&
                                System.currentTimeMillis() - start >= HOLD_MILLIS
                            ) {
                                onTrigger()
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Sos,
            contentDescription = stringResource(R.string.sos_cd),
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.height(28.dp)
        )
        Text(
            text = stringResource(R.string.sos_hold_hint),
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(6.dp)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.error)
            )
        }
    }
}