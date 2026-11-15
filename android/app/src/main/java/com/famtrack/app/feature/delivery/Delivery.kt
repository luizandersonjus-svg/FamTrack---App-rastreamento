package com.famtrack.app.feature.delivery

import kotlinx.serialization.Serializable

const val PLACE_RESIDENCIA = "RESIDENCIA"
const val PLACE_COMERCIO = "COMERCIO"

/**
 * Pacote a entregar (tabela `deliveries`, sql/13_deliveries.sql).
 *
 * [package_number] é o número que aparece na bandeira do mapa;
 * [house_number] é o número do imóvel, sempre exibido com a palavra
 * "casa" ou "comércio" na frente para não ser confundido com o do pacote.
 */
@Serializable
data class Delivery(
    val id: String? = null,
    val family_id: String,
    val package_number: String,
    val street: String,
    val house_number: String = "",
    val place_type: String = PLACE_RESIDENCIA,
    val note: String? = null,
    val latitude: Double,
    val longitude: Double,
    val route_order: Int = 0,
    val delivered: Boolean = false,
    val delivered_at: String? = null,
    /** true = rota encerrada; o pacote só aparece no Histórico. */
    val archived: Boolean = false,
    val archived_at: String? = null,
    val created_at: String? = null
)

val Delivery.isCommerce: Boolean
    get() = place_type == PLACE_COMERCIO

/** "casa 85" / "comércio 1240"; vazio quando não há número cadastrado. */
val Delivery.numberLabel: String
    get() = if (house_number.isBlank()) "" else
        (if (isCommerce) "comércio " else "casa ") + house_number.trim()

/** "Rua das Acácias, casa 85". */
val Delivery.addressLine: String
    get() = if (numberLabel.isEmpty()) street else "$street, $numberLabel"

val Delivery.placeTypeLabel: String
    get() = if (isCommerce) "Comércio" else "Residência"

/** Ordenação da lista de paradas. */
enum class DeliverySort(val label: String) {
    ROTA("Rota"),
    PACOTE("Nº do pacote"),
    ENDERECO("Endereço"),
    TIPO("Tipo")
}

/** Filtro da lista de paradas. */
enum class DeliveryFilter(val label: String) {
    TODOS("Todos"),
    RESIDENCIA("Residência"),
    COMERCIO("Comércio")
}

/**
 * Aplica filtro e ordenação. Números de pacote são comparados como número
 * quando possível (27 antes de 105), com o texto como desempate.
 */
fun List<Delivery>.sortedAndFiltered(sort: DeliverySort, show: DeliveryFilter): List<Delivery> {
    val filtered = when (show) {
        DeliveryFilter.TODOS -> this
        DeliveryFilter.RESIDENCIA -> filter { !it.isCommerce }
        DeliveryFilter.COMERCIO -> filter { it.isCommerce }
    }
    val byRoute = compareBy<Delivery> { it.route_order }.thenBy { it.created_at ?: "" }
    return when (sort) {
        DeliverySort.ROTA -> filtered.sortedWith(byRoute)
        DeliverySort.PACOTE -> filtered.sortedWith(
            compareBy<Delivery> { it.package_number.trim().toLongOrNull() ?: Long.MAX_VALUE }
                .thenBy { it.package_number.trim().lowercase() }
        )
        DeliverySort.ENDERECO -> filtered.sortedWith(
            compareBy<Delivery> { it.street.trim().lowercase() }
                .thenBy { it.house_number.trim().toLongOrNull() ?: Long.MAX_VALUE }
                .thenBy { it.house_number.trim() }
        )
        DeliverySort.TIPO -> filtered.sortedWith(
            compareBy<Delivery> { if (it.isCommerce) 1 else 0 }.then(byRoute)
        )
    }
}

/** Lista na ordem da rota (base para "3ª parada" e "próxima parada"). */
fun List<Delivery>.inRouteOrder(): List<Delivery> =
    sortedWith(compareBy<Delivery> { it.route_order }.thenBy { it.created_at ?: "" })
