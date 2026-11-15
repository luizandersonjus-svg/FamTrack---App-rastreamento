package com.famtrack.app.feature.delivery

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famtrack.app.feature.common.ErrorMessages
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

/** Uma rota encerrada: todos os pacotes arquivados no mesmo momento. */
private class ClosedRoute(val closedAt: String, val packages: List<Delivery>)

/** Linhas da lista: cabeçalho da rota encerrada ou um pacote dela. */
private sealed class HistoryItem {
    data class Header(val key: String, val title: String, val summary: String) : HistoryItem()
    data class Pack(val key: String, val delivery: Delivery) : HistoryItem()
}

/**
 * Histórico das rotas encerradas. Cada vez que a rota é encerrada, os
 * pacotes daquele momento formam um bloco aqui, com quantos foram entregues
 * e quais ficaram sem entregar. Só leitura.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryHistoryScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { DeliveryRepository() }

    var rows by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val familyId = repository.currentFamilyId()
            if (familyId != null) {
                rows = buildHistoryItems(repository.listArchived(familyId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorMessages.logSafe(e, "carregar histórico de entregas")
            Toast.makeText(context, "Não foi possível carregar o histórico.", Toast.LENGTH_SHORT).show()
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Histórico de rotas") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                rows.isEmpty() -> Text(
                    text = "Nenhuma rota encerrada ainda. Ao encerrar uma rota na lista, " +
                        "os pacotes dela aparecem aqui.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(
                        rows,
                        key = { item ->
                            when (item) {
                                is HistoryItem.Header -> item.key
                                is HistoryItem.Pack -> item.key
                            }
                        }
                    ) { item ->
                        when (item) {
                            is HistoryItem.Header -> HistoryHeader(item)
                            is HistoryItem.Pack -> {
                                HistoryPackRow(item.delivery)
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildHistoryItems(archived: List<Delivery>): List<HistoryItem> {
    // A lista já vem do mais recente ao mais antigo; groupBy preserva a ordem.
    val routes = archived
        .groupBy { it.archived_at ?: "" }
        .map { (closedAt, packages) -> ClosedRoute(closedAt, packages.inRouteOrder()) }
    val result = ArrayList<HistoryItem>()
    routes.forEachIndexed { routeIndex, route ->
        val total = route.packages.size
        val done = route.packages.count { it.delivered }
        val missed = total - done
        val summary = "$done de $total entregues" + when (missed) {
            0 -> ""
            1 -> " · 1 não entregue"
            else -> " · $missed não entregues"
        }
        result.add(
            HistoryItem.Header(
                key = "h-$routeIndex-${route.closedAt}",
                title = "Rota encerrada em ${formatClosedAt(route.closedAt)}",
                summary = summary
            )
        )
        route.packages.forEachIndexed { index, delivery ->
            result.add(HistoryItem.Pack(key = delivery.id ?: "p-$routeIndex-$index", delivery = delivery))
        }
    }
    return result
}

/** "15/11/2026 às 10:45" no fuso do aparelho; devolve o texto cru se falhar. */
private fun formatClosedAt(iso: String): String {
    if (iso.isBlank()) return "data desconhecida"
    return try {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm"))
    } catch (e: Exception) {
        iso
    }
}

@Composable
private fun HistoryHeader(header: HistoryItem.Header) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = header.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = header.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HistoryPackRow(delivery: Delivery) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DeliveryFlagChip(number = delivery.package_number, delivered = delivery.delivered)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = delivery.addressLine, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = delivery.placeTypeLabel + " · " +
                    (if (delivery.delivered) "Entregue" else "Não entregue"),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (delivery.delivered) FontWeight.Normal else FontWeight.Bold,
                color = if (delivery.delivered) DeliveredGreen else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
