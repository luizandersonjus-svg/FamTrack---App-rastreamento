package com.famtrack.app.feature.places

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle

/**
 * Camada de círculos dos locais salvos (F3) para desenhar dentro do mapa da
 * tela inicial, sobrepostos às geofences existentes.
 */
@Composable
fun PlacesMapOverlay(places: List<Place>) {
    places.forEach { place ->
        Circle(
            center = LatLng(place.center_lat, place.center_lon),
            radius = place.radius_meters.toDouble(),
            strokeColor = MaterialTheme.colorScheme.secondary,
            fillColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
            strokeWidth = 2f
        )
    }
}