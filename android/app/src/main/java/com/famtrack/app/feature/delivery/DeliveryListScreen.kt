package com.famtrack.app.feature.delivery

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.famtrack.app.feature.common.ErrorMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Lista das paradas, com ordenação (rota, nº do pacote, endereço, tipo) e
 * filtro (todos, residência, comércio). Tocar numa linha abre as ações do
 * pacote. "Otimizar a rota" reordena as pendentes para rodar menos.
 * "Encerrar a rota" tira todos os pacotes do mapa e da lista e os guarda no
 * Histórico (nada é apagado).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeliveryListScreen(
    onNavigateBack: () -> Unit,
    onAddPackage: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DeliveryRepository() }

    var familyId by remember { mutableStateOf<String?>(null) }
    var deliveries by remember { mutableStateOf<List<Delivery>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isBusy by remember { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(DeliverySort.ROTA.name) }
    var filterName by rememberSaveable { mutableStateOf(DeliveryFilter.TODOS.name) }
    var actionsForId by remember { mutableStateOf<String?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }

    val sort = DeliverySort.entries.firstOrNull { it.name == sortName } ?: DeliverySort.ROTA
    val show = DeliveryFilter.entries.firstOrNull { it.name == filterName } ?: DeliveryFilter.TODOS

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    suspend fun reload() {
        val fid = familyId ?: repository.currentFamilyId()
        familyId = fid
        if (fid != null) deliveries = repository.list(fid)
    }

    /** Executa uma alteração e recarrega a lista; avisa se falhar. */
    fun runChange(detail: String, block: suspend () -> Unit) {
        if (isBusy) return
        isBusy = true
        scope.launch {
            try {
                block()
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorMessages.logSafe(e, detail)
                toast("Não foi possível salvar. Verifique a internet.")
            } finally {
                isBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            reload()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorMessages.logSafe(e, "carregar lista de entregas")
            toast("Não foi possível carregar as entregas.")
        } finally {
            isLoading = false
        }
    }

    val routeOrdered = deliveries.inRouteOrder()
    val stopNumberById = routeOrdered.mapIndexedNotNull { index, d -> d.id?.let { it to index + 1 } }.toMap()
    val nextPendingId = routeOrdered.firstOrNull { !it.delivered }?.id
    val total = routeOrdered.size
    val doneCount = routeOrdered.count { it.delivered }
    val visible = deliveries.sortedAndFiltered(sort, show)

    fun optimize() {
        if (isBusy) return
        isBusy = true
        scope.launch {
            try {
                val start = lastKnownPosition(context)
                val fid = familyId ?: repository.currentFamilyId()
                    ?: throw IllegalStateException("sem família")
                val optimized = optimizeDeliveryRoute(deliveries, start)
                repository.saveOrder(fid, optimized.orderById)
                reload()
                sortName = DeliverySort.ROTA.name
                toast(
                    if (optimized.byRoads) "Rota otimizada."
                    else "Servidor de rotas indisponível: ordem aproximada, em linha reta."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorMessages.logSafe(e, "otimizar rota")
                toast("Não foi possível otimizar agora.")
            } finally {
                isBusy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Rota de entregas") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar ao mapa")
                    }
                },
                actions = {
                    IconButton(onClick = onAddPackage) {
                        Icon(Icons.Filled.Add, contentDescription = "Adicionar pacote")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "Histórico de rotas")
                    }
                    IconButton(onClick = { confirmArchive = true }, enabled = total > 0 && !isBusy) {
                        Icon(Icons.Filled.Archive, contentDescription = "Encerrar a rota")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = "$doneCount de $total entregues",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            if (total > doneCount) {
                                Text(
                                    text = if (total - doneCount == 1) "falta 1" else "faltam ${total - doneCount}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else doneCount.toFloat() / total },
                            color = DeliveredGreen,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                        )
                    }
                }

                ChipRow(title = "Ordenar por") {
                    DeliverySort.entries.forEach { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { sortName = option.name },
                            label = { Text(option.label) },
                            modifier = Modifier.height(44.dp)
                        )
                    }
                }
                ChipRow(title = "Mostrar") {
                    DeliveryFilter.entries.forEach { option ->
                        FilterChip(
                            selected = show == option,
                            onClick = { filterName = option.name },
                            label = { Text(option.label) },
                            modifier = Modifier.height(44.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                    total == 0 -> Text(
                        text = "Nenhum pacote na rota. Toque em + para adicionar.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )

                    visible.isEmpty() -> Text(
                        text = "Nenhum pacote deste tipo na rota.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        items(visible, key = { it.id ?: it.package_number + it.street }) { delivery ->
                            DeliveryRow(
                                delivery = delivery,
                                stopNumber = delivery.id?.let { stopNumberById[it] },
                                isNext = delivery.id != null && delivery.id == nextPendingId,
                                onClick = { actionsForId = delivery.id }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { optimize() },
                enabled = !isBusy && total - doneCount >= 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(48.dp)
            ) {
                Icon(Icons.Filled.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (isBusy) "Aguarde…" else "Otimizar a rota")
            }
        }
    }

    // Ações do pacote tocado.
    val actionTarget = deliveries.firstOrNull { it.id != null && it.id == actionsForId }
    if (actionTarget != null) {
        val targetId = actionTarget.id.orEmpty()
        AlertDialog(
            onDismissRequest = { actionsForId = null },
            title = { Text("Pacote ${actionTarget.package_number}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(actionTarget.addressLine)
                    val note = actionTarget.note?.trim().orEmpty()
                    if (note.isNotEmpty()) {
                        Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    actionsForId = null
                    val newValue = !actionTarget.delivered
                    runChange("marcar entrega") { repository.setDelivered(targetId, newValue) }
                }) {
                    Text(if (actionTarget.delivered) "Desfazer entrega" else "Marcar como entregue")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        actionsForId = null
                        runChange("excluir entrega") { repository.delete(targetId) }
                    }) {
                        Text("Excluir", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = {
                        actionsForId = null
                        if (!openDeliveryNavigation(context, actionTarget)) {
                            toast("Nenhum app de mapas encontrado.")
                        }
                    }) {
                        Text("Navegar")
                    }
                }
            }
        )
    }

    // Confirmação antes de encerrar a rota (arquiva tudo, sem apagar).
    if (confirmArchive) {
        val pendingCount = total - doneCount
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text("Encerrar a rota?") },
            text = {
                Text(
                    "Os $total pacotes saem do mapa e da lista nos dois aparelhos e ficam " +
                        "guardados no Histórico." +
                        when (pendingCount) {
                            0 -> ""
                            1 -> " 1 pacote ainda não foi entregue; ele vai para o Histórico como não entregue."
                            else -> " $pendingCount pacotes ainda não foram entregues; eles vão para o " +
                                "Histórico como não entregues."
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmArchive = false
                    val fid = familyId
                    if (fid != null) {
                        runChange("encerrar rota") { repository.archiveActive(fid) }
                    }
                }) {
                    Text("Encerrar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmArchive = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun ChipRow(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun DeliveryRow(
    delivery: Delivery,
    stopNumber: Int?,
    isNext: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = if (delivery.isCommerce) Icons.Filled.Storefront else Icons.Filled.Home,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = delivery.placeTypeLabel + " · " + when {
                        delivery.delivered -> "Entregue"
                        isNext -> "Próxima parada"
                        else -> "Pendente"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        delivery.delivered -> DeliveredGreen
                        isNext -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        if (stopNumber != null) {
            Text(
                text = "${stopNumber}ª parada",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
