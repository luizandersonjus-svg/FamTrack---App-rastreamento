package com.famtrack.app.feature.sos

import java.util.concurrent.atomic.AtomicReference

/**
 * Ponte entre o clique da notificação SOS e a Home: a notificação abre a
 * MainActivity com extras sos_lat/sos_lng; a MainActivity publica aqui e a
 * Home consome (uma única vez) para centralizar o mapa no alerta.
 */
object SosDeepLink {

    private val pending = AtomicReference<Pair<Double, Double>?>(null)

    fun publish(lat: Double, lng: Double) {
        pending.set(lat to lng)
    }

    fun consume(): Pair<Double, Double>? = pending.getAndSet(null)
}