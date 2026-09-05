package com.famtrack.app.feature.healthsnap

import kotlinx.serialization.Serializable

/**
 * Resumo de batimentos do dia por usuário (coleção nova `health_snapshots`).
 * Só é gravado quando o usuário ativa "Compartilhar dados de saúde" (F7).
 */
@Serializable
data class HealthSnapshot(
    val id: String? = null,
    val user_id: String,
    val family_id: String,
    val date: String,
    val bpm_media: Int? = null,
    val bpm_min: Int? = null,
    val bpm_max: Int? = null,
    val samples: Int? = null,
    val created_at: String? = null
)