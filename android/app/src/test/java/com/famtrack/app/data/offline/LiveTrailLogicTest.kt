package com.famtrack.app.data.offline

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTrailLogicTest {

    // Haversine: no JVM puro não há Location.distanceBetween. Para os
    // deslocamentos curtos dos testes a diferença para WGS84 é desprezível.
    private val haversine: TrailDistance = { a, b ->
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2).pow(2)
        (2 * r * asin(sqrt(h))).toFloat()
    }

    // ~1 m de latitude em graus.
    private val meter = 1.0 / 111_195.0
    private val baseLat = -23.55
    private val baseLon = -46.63
    private val minute = 60_000L

    /** Ponto [northM] metros ao norte da base, [t] ms após o início. */
    private fun pt(northM: Double, t: Long, user: String = "u1") =
        TrailPoint(user, baseLat + northM * meter, baseLon, t, accuracy = 10f, speed = null)

    // ---------------------------------------------------------------- gate

    @Test
    fun `tremor de GPS parado nao gera trecho`() {
        val points = (0 until 20).map { i -> pt(northM = (i % 5).toDouble() * 4, t = i * 10_000L) }
        val result = LiveTrailLogic.applyMovementGate(points, haversine)
        assertTrue(result.kept.isEmpty())
        assertEquals(MotionState.STATIONARY, result.state)
        assertEquals(points.first(), result.anchor)
    }

    @Test
    fun `cruzar o limiar inicia trecho a partir da ancora`() {
        val anchor = pt(0.0, 0)
        val jitter = pt(10.0, 10_000)
        val moved = pt(40.0, 20_000)
        val further = pt(80.0, 30_000)
        val result = LiveTrailLogic.applyMovementGate(listOf(anchor, jitter, moved, further), haversine)
        assertEquals(listOf(anchor, moved, further), result.kept)
        assertEquals(MotionState.MOVING, result.state)
    }

    @Test
    fun `entrada fora de ordem e reordenada por recordedAt`() {
        val anchor = pt(0.0, 0)
        val moved = pt(40.0, 20_000)
        val result = LiveTrailLogic.applyMovementGate(listOf(moved, anchor), haversine)
        assertEquals(listOf(anchor, moved), result.kept)
    }

    @Test
    fun `parada confirmada apos 2 min dentro do raio`() {
        val walk = listOf(pt(0.0, 0), pt(40.0, 20_000), pt(100.0, 40_000))
        // Fica a menos de 30 m do 1º fix da janela (100 m) por 2 min.
        val stop = listOf(pt(105.0, 40_000 + minute), pt(110.0, 40_000 + 2 * minute))
        val result = LiveTrailLogic.applyMovementGate(walk + stop, haversine)
        assertEquals(MotionState.STATIONARY, result.state)
        assertEquals(stop.last(), result.anchor)
        assertEquals(walk + stop, result.kept)
    }

    @Test
    fun `pausa curta no transito nao confirma parada`() {
        val walk = listOf(pt(0.0, 0), pt(40.0, 20_000), pt(100.0, 40_000))
        val pause = pt(105.0, 40_000 + minute)
        val resume = pt(200.0, 40_000 + minute + 20_000)
        val result = LiveTrailLogic.applyMovementGate(walk + pause + resume, haversine)
        assertEquals(MotionState.MOVING, result.state)
        assertEquals(walk + pause + resume, result.kept)
    }

    @Test
    fun `trecho concluido some apos 5 min parado e novo trecho parte da nova ancora`() {
        val walk = listOf(pt(0.0, 0), pt(40.0, 20_000), pt(100.0, 40_000))
        val stopAt = 40_000 + 2 * minute
        val stop = listOf(pt(105.0, 40_000 + minute), pt(110.0, stopAt))
        val stillThere = pt(112.0, stopAt + 6 * minute)
        val leaveAgain = pt(160.0, stopAt + 7 * minute)
        val result = LiveTrailLogic.applyMovementGate(walk + stop + stillThere + leaveAgain, haversine)
        // O trecho anterior foi descartado; o novo começa na âncora da parada.
        assertEquals(listOf(stop.last(), leaveAgain), result.kept)
        assertEquals(MotionState.MOVING, result.state)
    }

    @Test
    fun `lista vazia nao tem ancora`() {
        val result = LiveTrailLogic.applyMovementGate(emptyList(), haversine)
        assertTrue(result.kept.isEmpty())
        assertNull(result.anchor)
    }

    // ------------------------------------------------------------ isTrailGap

    @Test
    fun `passo normal nao e lacuna`() {
        assertFalse(LiveTrailLogic.isTrailGap(pt(0.0, 0), pt(50.0, 10_000), haversine))
    }

    @Test
    fun `timestamp regressivo e lacuna`() {
        assertTrue(LiveTrailLogic.isTrailGap(pt(0.0, 10_000), pt(10.0, 0), haversine))
    }

    @Test
    fun `velocidade implicita acima de 180 kmh e lacuna`() {
        // 1 km em 10 s = 360 km/h.
        assertTrue(LiveTrailLogic.isTrailGap(pt(0.0, 0), pt(1_000.0, 10_000), haversine))
    }

    @Test
    fun `fixes quase simultaneos nao passam pelo teste de velocidade`() {
        assertFalse(LiveTrailLogic.isTrailGap(pt(0.0, 0), pt(100.0, 500), haversine))
    }

    @Test
    fun `lacuna longa com deriva curta mantem o traco`() {
        assertFalse(LiveTrailLogic.isTrailGap(pt(0.0, 0), pt(20.0, 10 * minute), haversine))
    }

    @Test
    fun `lacuna longa com salto grande quebra o traco`() {
        // 600 m em 10 min = 3,6 km/h: plausível em velocidade, mas coleta cega.
        assertTrue(LiveTrailLogic.isTrailGap(pt(0.0, 0), pt(600.0, 10 * minute), haversine))
    }

    // --------------------------------------------------------------- segment

    @Test
    fun `segmenta no salto e descarta trechos de um ponto so`() {
        val a = listOf(pt(0.0, 0), pt(50.0, 10_000), pt(100.0, 20_000))
        val jump = pt(5_000.0, 30_000)
        val b = listOf(pt(5_050.0, 40_000), pt(5_100.0, 50_000))
        val outlier = pt(50_000.0, 60_000)
        val segments = LiveTrailLogic.segment(a + jump + b + outlier, haversine)
        assertEquals(listOf(a, listOf(jump) + b), segments)
    }

    @Test
    fun `segmento com menos de dois pontos volta como esta`() {
        val single = listOf(pt(0.0, 0))
        assertEquals(listOf(single), LiveTrailLogic.segment(single, haversine))
    }

    // -------------------------------------------------------------- decimate

    @Test
    fun `decimacao remove pontos a menos de 5 m do ultimo mantido`() {
        val points = listOf(pt(0.0, 0), pt(2.0, 1), pt(4.0, 2), pt(6.0, 3), pt(8.0, 4), pt(12.0, 5))
        val out = LiveTrailLogic.decimate(points, haversine)
        assertEquals(listOf(points[0], points[3], points[5]), out)
    }

    @Test
    fun `decimacao aplica o teto preservando os mais recentes`() {
        val max = RouteQuality.TRAIL_MAX_POINTS_PER_MEMBER
        val points = (0 until max + 50).map { i -> pt(i * 10.0, i.toLong()) }
        val out = LiveTrailLogic.decimate(points, haversine)
        assertEquals(max, out.size)
        assertEquals(points.last(), out.last())
        assertEquals(points[50], out.first())
    }

    // --------------------------------------------------------------- quality

    @Test
    fun `precisao desconhecida ou dentro do limite e aceita`() {
        assertTrue(RouteQuality.isAccurate(null))
        assertTrue(RouteQuality.isAccurate(0f))
        assertTrue(RouteQuality.isAccurate(RouteQuality.TRAIL_MAX_ACCURACY_M))
        assertFalse(RouteQuality.isAccurate(RouteQuality.TRAIL_MAX_ACCURACY_M + 0.1f))
    }
}
