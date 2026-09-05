package com.famtrack.app.feature.sos

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import kotlinx.coroutines.delay

private const val CANCEL_WINDOW_SECONDS = 10

/**
 * Janela de CANCELAMENTO pós-envio do SOS (F5).
 * O alerta JA foi enviado quando esta janela abre. O usuario tem 10 segundos
 * para cancelar; ao fim do tempo a janela apenas fecha, SEM cancelar o SOS.
 */
@Composable
fun SosCancelWindowDialog(
    onCancel: () -> Unit,
    onWindowEnded: () -> Unit
) {
    var remaining by remember { mutableIntStateOf(CANCEL_WINDOW_SECONDS) }
    var cancelRequested by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (remaining > 0 && !cancelRequested) {
            delay(1000)
            remaining--
        }
        // Tempo encerrado: fecha a janela SEM resolver/cancelar o alerta.
        if (!cancelRequested) {
            onWindowEnded()
        }
    }

    AlertDialog(
        onDismissRequest = onWindowEnded,
        icon = {
            Icon(
                Icons.Filled.Sos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.sos_cancel_title),
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.sos_cancel_text))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.sos_cancel_window, remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    cancelRequested = true
                    onCancel()
                }
            ) {
                Text(stringResource(R.string.sos_cancel_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onWindowEnded) {
                Text(stringResource(R.string.sos_cancel_close))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    )
}