package com.famtrack.app.feature.delivery

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeliveryArrowsTest {

    private val m = 1.0 / 111_195.0 // ~1 m em graus de latitude no equador

    private fun north(metres: Double) = GeoPt(metres * m, 0.0)
    private fun east(metres: Double) = GeoPt(0.0, metres * m)

    private fun assertBearing(expected: Float, actual: Float) =
        assertTrue(abs(expected - actual) < 0.5f || abs(abs(expected - actual) - 360f) < 0.5f, "rumo $actual, esperado $expected")

    @Test
    fun `rumo nos quatro sentidos`() {
        val o = GeoPt(0.0, 0.0)
        assertBearing(0f, bearingDegrees(o, north(100.0)))
        assertBearing(90f, bearingDegrees(o, east(100.0)))
        assertBearing(180f, bearingDegrees(o, north(-100.0)))
        assertBearing(270f, bearingDegrees(o, east(-100.0)))
    }

    @Test
    fun `setas seguem o sentido do caminho, nao o contrario`() {
        // Rua de mão única para o norte e depois vira para o leste.
        val path = listOf(GeoPt(0.0, 0.0), north(100.0), GeoPt(100.0 * m, 100.0 * m))
        val arrows = arrowsAlong(path, spacingM = 40.0, firstAtM = 20.0)
        assertEquals(5, arrows.size) // 20, 60, 100(curva), 140, 180
        assertBearing(0f, arrows[0].bearing)
        assertBearing(0f, arrows[1].bearing)
        assertBearing(90f, arrows.last().bearing)
    }

    @Test
    fun `espacamento regular e limite de setas`() {
        val path = listOf(GeoPt(0.0, 0.0), north(1000.0))
        val arrows = arrowsAlong(path, spacingM = 100.0, firstAtM = 50.0)
        assertEquals(10, arrows.size)
        assertTrue(abs(arrows[0].lat / m - 50.0) < 0.5)
        assertTrue(abs(arrows[1].lat / m - 150.0) < 0.5)
        assertEquals(3, arrowsAlong(path, 100.0, 50.0, maxArrows = 3).size)
    }

    @Test
    fun `trecho ja percorrido some`() {
        val path = listOf(GeoPt(0.0, 0.0), north(100.0), north(200.0))
        val rest = remainingPath(path, GeoPt(150.0 * m, 3.0 * m))
        assertEquals(2, rest.size)
        assertTrue(abs(rest.first().lat / m - 150.0) < 0.5)
        assertTrue(abs(rest.first().lon) < 1e-9)
        assertEquals(north(200.0), rest.last())
    }

    @Test
    fun `distancia ate o caminho detecta desvio`() {
        val path = listOf(GeoPt(0.0, 0.0), north(200.0))
        val near = projectOnPath(path, GeoPt(100.0 * m, 10.0 * m))!!
        val far = projectOnPath(path, GeoPt(100.0 * m, 60.0 * m))!!
        assertTrue(abs(near.distanceM - 10.0) < 0.5)
        assertTrue(abs(far.distanceM - 60.0) < 0.5)
    }

    @Test
    fun `paradas por modo`() {
        fun d(id: String, pkg: String, order: Int, delivered: Boolean = false) = Delivery(
            id = id, family_id = "f", package_number = pkg, street = "R",
            latitude = 0.0, longitude = 0.0, route_order = order, delivered = delivered
        )
        val all = listOf(d("a", "30", 1), d("b", "7", 2), d("c", "12", 3, delivered = true), d("e", "9", 4))
        assertEquals(listOf("a", "b", "e"), arrowStops(ArrowMode.ROUTE, all, null).map { it.id })
        assertEquals(listOf("b", "e", "a"), arrowStops(ArrowMode.NUMBER, all, null).map { it.id })
        assertEquals(listOf("c"), arrowStops(ArrowMode.SELECTED, all, all[2]).map { it.id })
        assertEquals(emptyList(), arrowStops(ArrowMode.OFF, all, all[0]))
    }

    @Test
    fun `metros por pixel no zoom de entrega`() {
        // Zoom 18.5 no equador: ~0,42 m/px.
        val mpp = metersPerPixel(18.5f, 0.0)
        assertTrue(mpp in 0.40..0.44, "veio $mpp")
    }
}
