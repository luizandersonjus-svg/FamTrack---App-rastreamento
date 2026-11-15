package com.famtrack.app.data.remote

import com.famtrack.app.data.model.Geofence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * SEC-1: o update de geofence usava Map<String, Any?>, que não é serializável
 * e fazia a edição de Locais falhar. O payload agora é um JsonObject.
 */
class GeofenceRepositoryPayloadTest {

    private val geofence = Geofence(
        id = "g1",
        family_id = "f1",
        name = "Casa",
        center_lat = -23.55,
        center_lon = -46.63,
        radius_meters = 150.0,
        color = "#00AA88",
        active = false,
        placeId = "p1"
    )

    @Test
    fun `payload contem so as colunas editaveis`() {
        val payload = GeofenceRepository.updatePayload(geofence)
        assertEquals(
            setOf("name", "center_lat", "center_lon", "radius_meters", "color", "active", "place_id"),
            payload.keys
        )
        // id, family_id e created_at nunca vão no update.
        assertFalse("family_id" in payload)
    }

    @Test
    fun `payload preserva tipos e valores`() {
        val payload = GeofenceRepository.updatePayload(geofence)
        assertEquals("Casa", payload.getValue("name").jsonPrimitive.content)
        assertEquals(-23.55, payload.getValue("center_lat").jsonPrimitive.double)
        assertEquals(150.0, payload.getValue("radius_meters").jsonPrimitive.double)
        assertEquals(false, payload.getValue("active").jsonPrimitive.boolean)
        assertEquals("p1", payload.getValue("place_id").jsonPrimitive.content)
    }

    @Test
    fun `place_id ausente nao e enviado`() {
        val payload = GeofenceRepository.updatePayload(geofence.copy(placeId = null))
        assertFalse("place_id" in payload)
    }

    @Test
    fun `payload serializa e volta igual`() {
        val payload = GeofenceRepository.updatePayload(geofence)
        val encoded = Json.encodeToString(JsonObject.serializer(), payload)
        assertEquals(payload, Json.decodeFromString(JsonObject.serializer(), encoded))
    }
}
