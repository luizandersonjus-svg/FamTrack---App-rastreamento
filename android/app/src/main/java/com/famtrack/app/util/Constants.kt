package com.famtrack.app.util

object Constants {
    // Supabase
    const val SUPABASE_URL = "https://YOUR_PROJECT.supabase.co"
    const val SUPABASE_ANON_KEY = "YOUR_ANON_KEY"

    // Google Maps
    const val GOOGLE_MAPS_API_KEY = "YOUR_API_KEY"

    // Location
    const val LOCATION_UPDATE_INTERVAL = 10000L // 10 segundos
    const val LOCATION_FASTEST_INTERVAL = 5000L // 5 segundos
    const val LOCATION_MIN_DISTANCE = 10f // 10 metros

    // Notification
    const val NOTIFICATION_CHANNEL_ID = "famtrack_location"
    const val NOTIFICATION_CHANNEL_NAME = "Localização"
    const val NOTIFICATION_ID = 1

    // Family
    const val MIN_FAMILY_MEMBERS = 2
    const val MAX_FAMILY_MEMBERS = 20

    // Geofence
    const val DEFAULT_GEOFENCE_RADIUS = 100.0 // metros
    const val MIN_GEOFENCE_RADIUS = 50.0 // metros
    const val MAX_GEOFENCE_RADIUS = 1000.0 // metros
}
