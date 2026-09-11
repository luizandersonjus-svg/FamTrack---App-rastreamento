package com.famtrack.app.ui.home

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Sinal de "app voltou ao foreground" (publicado em MainActivity.onResume).
 * A Home consome esse sinal para recarregar as tabelas raras
 * (geofences/places/flags/sos/locations) sem precisar de polling —
 * ETAPA 4 (stale/fresh). Mesmo padrão do SosDeepLink.
 */
object HomeDataRefresh {

    private val tick = MutableStateFlow(0L)

    val ticks: Flow<Long> get() = tick

    fun publish() {
        tick.value = System.currentTimeMillis()
    }

    fun consume(): Long = tick.value
}