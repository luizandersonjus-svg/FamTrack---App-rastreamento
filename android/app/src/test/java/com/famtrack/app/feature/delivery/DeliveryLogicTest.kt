package com.famtrack.app.feature.delivery

import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryLogicTest {

    private fun d(
        id: String,
        pkg: String = id,
        street: String = "Rua A",
        house: String = "",
        type: String = PLACE_RESIDENCIA,
        order: Int = 0,
        createdAt: String? = null
    ) = Delivery(
        id = id,
        family_id = "f",
        package_number = pkg,
        street = street,
        house_number = house,
        place_type = type,
        latitude = 0.0,
        longitude = 0.0,
        route_order = order,
        created_at = createdAt
    )

    @Test
    fun `numberLabel diferencia casa e comercio`() {
        assertEquals("casa 85", d("1", house = " 85 ").numberLabel)
        assertEquals("comércio 1240", d("1", house = "1240", type = PLACE_COMERCIO).numberLabel)
        assertEquals("", d("1", house = "  ").numberLabel)
    }

    @Test
    fun `addressLine sem numero mostra so a rua`() {
        assertEquals("Rua A", d("1").addressLine)
        assertEquals("Rua A, casa 3", d("1", house = "3").addressLine)
    }

    @Test
    fun `inRouteOrder desempata pela criacao`() {
        val list = listOf(
            d("b", order = 1, createdAt = "2026-01-02"),
            d("a", order = 1, createdAt = "2026-01-01"),
            d("c", order = 0)
        )
        assertEquals(listOf("c", "a", "b"), list.inRouteOrder().map { it.id })
    }

    @Test
    fun `ordenar por pacote compara como numero`() {
        val list = listOf(d("1", pkg = "105"), d("2", pkg = "27"), d("3", pkg = "abc"), d("4", pkg = "3"))
        val sorted = list.sortedAndFiltered(DeliverySort.PACOTE, DeliveryFilter.TODOS)
        assertEquals(listOf("3", "27", "105", "abc"), sorted.map { it.package_number })
    }

    @Test
    fun `ordenar por endereco usa rua e numero da casa`() {
        val list = listOf(
            d("1", street = "Rua B", house = "10"),
            d("2", street = "rua a", house = "100"),
            d("3", street = "Rua A", house = "20")
        )
        val sorted = list.sortedAndFiltered(DeliverySort.ENDERECO, DeliveryFilter.TODOS)
        assertEquals(listOf("3", "2", "1"), sorted.map { it.id })
    }

    @Test
    fun `filtro e ordenacao por tipo`() {
        val list = listOf(
            d("c1", type = PLACE_COMERCIO, order = 1),
            d("r1", order = 2),
            d("r0", order = 0)
        )
        assertEquals(
            listOf("c1"),
            list.sortedAndFiltered(DeliverySort.ROTA, DeliveryFilter.COMERCIO).map { it.id }
        )
        assertEquals(
            listOf("r0", "r1"),
            list.sortedAndFiltered(DeliverySort.ROTA, DeliveryFilter.RESIDENCIA).map { it.id }
        )
        assertEquals(
            listOf("r0", "r1", "c1"),
            list.sortedAndFiltered(DeliverySort.TIPO, DeliveryFilter.TODOS).map { it.id }
        )
    }
}
