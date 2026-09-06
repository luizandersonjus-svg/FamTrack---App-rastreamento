package com.famtrack.app.feature.places

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalMall
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Local salvo da família (coleção nova `places` no backend).
 * Tipos: CASA, ESCOLA, TRABALHO, IGRJA, MERCADO, RESTAURANTE, FARMACIA,
 * ACADEMIA, SHOPPING, OUTRO.
 */
@Serializable
data class Place(
    val id: String? = null,
    val family_id: String,
    val name: String,
    val type: String = "OUTRO",
    val center_lat: Double,
    val center_lon: Double,
    val radius_meters: Int = 150,
    val alert_on_enter: Boolean? = null,
    val alert_on_exit: Boolean? = null,
    val geofence_id: String? = null,
    val created_by: String,
    val created_at: String? = null
)

/**
 * Ícone coerente para o local (mapa e edição): prioriza o TIPO cadastrado
 * (CASA/ESCOLA/TRABALHO/IGRJA/MERCADO/RESTAURANTE/FARMACIA/ACADEMIA/SHOPPING)
 * e, para OUTRO, palavras-chave do nome (igreja, saúde, supermercado,
 * academia, restaurante, compras...). Pino padrão se nada combinar. O nome é
 * normalizado sem acentos antes da comparação.
 */
fun resolvePlaceIcon(placeName: String, placeType: String): ImageVector {
    return when (placeType) {
        "CASA" -> Icons.Filled.Home
        "ESCOLA" -> Icons.Filled.School
        "TRABALHO" -> Icons.Filled.Work
        "IGRJA" -> Icons.Filled.Church
        "MERCADO" -> Icons.Filled.LocalGroceryStore
        "RESTAURANTE" -> Icons.Filled.Restaurant
        "FARMACIA" -> Icons.Filled.LocalPharmacy
        "ACADEMIA" -> Icons.Filled.FitnessCenter
        "SHOPPING" -> Icons.Filled.LocalMall
        else -> matchByKeywords(normalizePlaceName(placeName))
    }
}

private fun matchByKeywords(normalized: String): ImageVector = when {
    "igreja" in normalized || "templo" in normalized || "capela" in normalized ->
        Icons.Filled.Church
    "farmacia" in normalized || "drogaria" in normalized || "medico" in normalized ||
        "clinica" in normalized || "consultorio" in normalized || "hospital" in normalized ||
        "posto de saude" in normalized || "laboratorio" in normalized ->
        Icons.Filled.LocalHospital
    "mercado" in normalized || "supermercado" in normalized || "acougue" in normalized ||
        "padaria" in normalized || "hortifruti" in normalized || "quitanda" in normalized ->
        Icons.Filled.LocalGroceryStore
    "academia" in normalized || "ginasio" in normalized || "crossfit" in normalized ->
        Icons.Filled.FitnessCenter
    "restaurante" in normalized || "pizzaria" in normalized || "lanchonete" in normalized ||
        "hamburgueria" in normalized || "churrascaria" in normalized ||
        "cafe" in normalized || "sorveteria" in normalized || "pastelaria" in normalized ->
        Icons.Filled.Restaurant
    "shopping" in normalized || "loja" in normalized || "centro comercial" in normalized ||
        "boutique" in normalized ->
        Icons.Filled.LocalMall
    "escola" in normalized || "colegio" in normalized || "creche" in normalized ||
        "faculdade" in normalized || "universidade" in normalized ->
        Icons.Filled.School
    "trabalho" in normalized || "escritorio" in normalized || "empresa" in normalized ||
        "firma" in normalized || "comercio" in normalized ->
        Icons.Filled.Work
    "casa" in normalized || "residencia" in normalized || "apartamento" in normalized ||
        "kitnet" in normalized ->
        Icons.Filled.Home
    else -> Icons.Filled.Place
}

/** Normaliza o nome: minúsculas e sem acentos, para casar palavras-chave. */
private fun normalizePlaceName(name: String): String {
    val normalized = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
    return normalized.replace(Regex("\\p{Mn}+"), "").lowercase(Locale.getDefault())
}