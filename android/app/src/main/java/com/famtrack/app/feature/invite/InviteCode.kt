package com.famtrack.app.feature.invite

import kotlin.random.Random

/**
 * Gera códigos de convite com 6 caracteres sem caracteres ambíguos
 * (0, O, 1 e I são excluídos para facilitar digitação).
 */
object InviteCode {

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val LENGTH = 6

    fun generate(): String {
        val random = Random(System.nanoTime())
        return buildString {
            repeat(LENGTH) {
                append(ALPHABET[random.nextInt(ALPHABET.length)])
            }
        }
    }

    /** Normaliza o código digitado: maiúsculas e sem espaços/hífens. */
    fun sanitize(input: String): String {
        return input.uppercase().replace(Regex("[\\s-]"), "")
    }
}