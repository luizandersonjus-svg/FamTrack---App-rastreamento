package com.famtrack.app.feature.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeliveryProximityTest {

    // ~1 m de latitude = 1/111_195 grau.
    private val metre = 1.0 / 111_195.0

    private fun d(id: String, northMetres: Double, delivered: Boolean = false) = Delivery(
        id = id,
        family_id = "f",
        package_number = id,
        street = "Rua A",
        latitude = northMetres * metre,
        longitude = 0.0,
        delivered = delivered
    )

    private fun at(m: Double) = m * metre

    @Test
    fun `haversine confere com distancia conhecida`() {
        val d = haversineMeters(0.0, 0.0, at(100.0), 0.0)
        assertTrue(d in 99.0..101.0, "esperado ~100 m, veio $d")
    }

    @Test
    fun `dispara ao entrar no raio e so uma vez`() {
        val t = ArrivalTracker()
        val list = listOf(d("A", 0.0))
        assertNull(t.update(at(100.0), 0.0, list))
        assertEquals("A", t.update(at(30.0), 0.0, list))
        assertNull(t.update(at(10.0), 0.0, list))
        assertNull(t.update(at(35.0), 0.0, list))
    }

    @Test
    fun `dispara de novo so depois de sair do raio de saida`() {
        val t = ArrivalTracker()
        val list = listOf(d("A", 0.0))
        assertEquals("A", t.update(0.0, 0.0, list))
        assertNull(t.update(at(60.0), 0.0, list)) // ainda dentro dos 70 m
        assertNull(t.update(at(30.0), 0.0, list))
        assertNull(t.update(at(100.0), 0.0, list)) // saiu
        assertEquals("A", t.update(at(20.0), 0.0, list))
    }

    @Test
    fun `ignora entregues e escolhe o pendente mais perto`() {
        val t = ArrivalTracker()
        val list = listOf(d("A", 0.0, delivered = true), d("B", 25.0), d("C", 5.0))
        assertEquals("C", t.update(0.0, 0.0, list))
    }

    @Test
    fun `vizinho colado nao rouba o pacote com GPS oscilando`() {
        val t = ArrivalTracker()
        val list = listOf(d("A", 0.0), d("B", 16.0))
        assertEquals("A", t.update(at(6.0), 0.0, list))
        // Oscila para mais perto de B, mas a diferença é pequena.
        assertNull(t.update(at(9.0), 0.0, list))
        // Chegou de fato em B.
        assertEquals("B", t.update(at(16.0), 0.0, list))
    }

    @Test
    fun `pacote entregue libera o proximo da mesma porta`() {
        val t = ArrivalTracker()
        assertEquals("A", t.update(0.0, 0.0, listOf(d("A", 0.0), d("B", 3.0))))
        assertEquals("B", t.update(0.0, 0.0, listOf(d("A", 0.0, delivered = true), d("B", 3.0))))
    }

    @Test
    fun `formata distancia em metros e km`() {
        assertEquals("a 85 m", formatDistance(85.4))
        assertEquals("a 1,2 km", formatDistance(1234.0))
    }
}
