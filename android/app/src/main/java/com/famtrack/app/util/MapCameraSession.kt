package com.famtrack.app.util

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado da câmera do mapa que precisa sobreviver à navegação entre telas e
 * acompanhar o ciclo de vida do app (alimentado pela MainActivity):
 *
 * - "abertura pendente": o app acabou de abrir (processo novo) ou voltou do
 *   background depois de um tempo. A Home centraliza no próprio usuário, no
 *   zoom de rua padrão, assim que tiver uma posição — e marca como feito.
 *   Navegar entre telas do app (Configurações, Histórico...) NÃO conta como
 *   abrir o app: a câmera continua onde o usuário deixou.
 * - pulso de foreground: a cada onResume, para a Home consumir deep links
 *   (ex.: clique na notificação de SOS com o app já aberto).
 */
object MapCameraSession {

    /**
     * Tempo mínimo fora da tela para tratar a volta como "abrir o app de novo".
     * Trocas rápidas (responder uma mensagem, girar a tela) não mexem no mapa.
     */
    private const val REOPEN_AFTER_BACKGROUND_MS = 30_000L

    // Processo novo = app aberto: já começa pendente.
    private val openCentering = MutableStateFlow(true)
    val openCenteringPending: StateFlow<Boolean> = openCentering.asStateFlow()

    private val foreground = MutableStateFlow(0L)
    val foregroundTicks: StateFlow<Long> = foreground.asStateFlow()

    @Volatile
    private var stoppedAtElapsed = 0L

    fun isOpenCenteringPending(): Boolean = openCentering.value

    fun markOpenCenteringDone() {
        openCentering.value = false
    }

    /** MainActivity.onStop: o app saiu da tela (background, tela apagada...). */
    fun onAppStopped() {
        stoppedAtElapsed = SystemClock.elapsedRealtime()
    }

    /** MainActivity.onResume: o app está visível de novo. */
    fun onAppResumed() {
        val stoppedAt = stoppedAtElapsed
        stoppedAtElapsed = 0L
        if (stoppedAt != 0L &&
            SystemClock.elapsedRealtime() - stoppedAt >= REOPEN_AFTER_BACKGROUND_MS
        ) {
            openCentering.value = true
        }
        foreground.value = foreground.value + 1
    }
}
