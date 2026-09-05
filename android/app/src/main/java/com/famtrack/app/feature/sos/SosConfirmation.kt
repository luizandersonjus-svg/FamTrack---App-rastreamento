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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.famtrack.app.R
import kotlinx.coroutines.delay

private const val CONFIRM_WINDOW_SECONDS = 10

/**
 * Janela de confirmação do SOS: o usuário tem 10 segundos para confirmar;
 * se não confirmar, o envio é cancelado automaticamente (F5).
 */
@Composable
fun SosConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var remaining by remember { mutableIntStateOf(CONFIRM_WINDOW_SECONDS) }

    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1000)
            remaining--
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Filled.Sos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(R.string.sos_confirm_title),
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.sos_confirm_text))
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.sos_auto_cancel, remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.sos_confirm_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.sos_cancel))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    )
}