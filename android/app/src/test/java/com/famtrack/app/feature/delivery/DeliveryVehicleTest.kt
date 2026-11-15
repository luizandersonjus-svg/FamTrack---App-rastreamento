package com.famtrack.app.feature.delivery

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeliveryVehicleTest {

    private val m = 1.0 / 111_195.0
    private val origin = GeoPt(0.0, 0.0)

    @Test
    fun `em movimento usa o rumo do GPS`() {
        assertEquals(135f, nextHeading(10f, gpsBearing = 135f, speedMps = 8f, from = origin, to = origin))
    }

    @Test
    fun `devagar ignora o rumo do GPS e usa o deslocamento`() {
        val h = nextHeading(null, gpsBearing = 300f, speedMps = 0.5f, from = origin, to = GeoPt(0.0, 20.0 * m))!!
        assertTrue(abs(h - 90f) < 0.5f, "veio $h")
    }

    @Test
    fun `parado mantem o ultimo rumo`() {
        assertEquals(42f, nextHeading(42f, gpsBearing = 200f, speedMps = 0.2f, from = origin, to = GeoPt(1.0 * m, 0.0)))
        assertNull(nextHeading(null, null, null, from = null, to = origin))
    }
}
