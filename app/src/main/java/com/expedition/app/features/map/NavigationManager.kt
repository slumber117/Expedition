package com.expedition.app.features.map

import android.content.Context
import android.graphics.Color
import android.location.Location
import com.expedition.app.BuildConfig
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * Data class representing a saved route
 */
data class SavedRoute(
    val id: String,
    val name: String,
    val waypoints: List<GeoPoint>,
    val totalDistanceMeters: Double,
    val createdAt: Long = System.currentTimeMillis()
)

// Data classes for parsing Google Directions API response
data class DirectionsResponse(val routes: List<Route>)
data class Route(val overview_polyline: OverviewPolyline, val legs: List<Leg>)
data class OverviewPolyline(val points: String)
data class Leg(val distance: Distance, val duration: Duration)
data class Distance(val value: Int)
data class Duration(val value: Int)


/**
 * Manages navigation, route calculation, and ETA
 */
class NavigationManager(private val context: Context) {

    private val _destination = MutableStateFlow<GeoPoint?>(null)
    val destination: StateFlow<GeoPoint?> = _destination.asStateFlow()

    private val _currentRoute = MutableStateFlow<List<GeoPoint>>(emptyList())
    val currentRoute: StateFlow<List<GeoPoint>> = _currentRoute.asStateFlow()

    private val _distanceToDestination = MutableStateFlow(0.0)
    val distanceToDestination: StateFlow<Double> = _distanceToDestination.asStateFlow()

    private val _etaSeconds = MutableStateFlow<Int?>(null)
    val etaSeconds: StateFlow<Int?> = _etaSeconds.asStateFlow()

    private val _savedRoutes = MutableStateFlow<List<SavedRoute>>(emptyList())
    val savedRoutes: StateFlow<List<SavedRoute>> = _savedRoutes.asStateFlow()

    private var routeOverlay: Polyline? = null
    
    private val apiKey = BuildConfig.MAPS_API_KEY

    init {
        loadSavedRoutes()
    }

    /**
     * Set a destination and calculate route from a routing API
     */
    fun setDestination(currentLocation: GeoPoint, destination: GeoPoint) {
        _destination.value = destination
        
        // Launch a coroutine to fetch the route from the API
        CoroutineScope(Dispatchers.IO).launch {
            fetchAndApplyRoute(currentLocation, destination)
        }
    }

    /**
     * Clear current navigation
     */
    fun clearNavigation() {
        _destination.value = null
        _currentRoute.value = emptyList()
        _distanceToDestination.value = 0.0
        _etaSeconds.value = null
    }

    /**
     * Update ETA based on current speed and remaining distance
     */
    fun updateETA(currentLocation: Location, currentSpeedKmh: Double) {
        val route = _currentRoute.value
        if (route.isEmpty() || destination.value == null) {
            _etaSeconds.value = null
            return
        }

        val remainingDistance = calculateRemainingDistance(
            GeoPoint(currentLocation.latitude, currentLocation.longitude),
            route
        )
        _distanceToDestination.value = remainingDistance

        val speedMps = currentSpeedKmh * 1000 / 3600
        if (speedMps > 1) { // Only update if moving
            _etaSeconds.value = (remainingDistance / speedMps).toInt()
        }
    }

    /**
     * Draw the current route on the map
     */
    fun drawRouteOnMap(mapView: MapView) {
        // Remove old route overlay
        routeOverlay?.let {
            mapView.overlays.remove(it)
        }

        // Add new route overlay
        if (_currentRoute.value.isNotEmpty()) {
            val polyline = Polyline().apply {
                setPoints(_currentRoute.value)
                color = Color.BLUE
                width = 12f
            }
            mapView.overlays.add(polyline)
            routeOverlay = polyline
            mapView.invalidate() // Refresh the map
        }
    }

    /**
     * Save the current route to a file
     */
    fun saveCurrentRoute(routeName: String) {
        val route = _currentRoute.value
        if (route.isEmpty() || routeName.isBlank()) return

        val newRoute = SavedRoute(
            id = "route_${System.currentTimeMillis()}",
            name = routeName,
            waypoints = route,
            totalDistanceMeters = _distanceToDestination.value
        )

        val updatedRoutes = _savedRoutes.value.toMutableList().apply { add(newRoute) }
        _savedRoutes.value = updatedRoutes
        saveRoutesToFile()
    }

    /**
     * Delete a saved route
     */
    fun deleteSavedRoute(routeId: String) {
        val updatedRoutes = _savedRoutes.value.filter { it.id != routeId }
        _savedRoutes.value = updatedRoutes
        saveRoutesToFile()
    }

    private fun loadSavedRoutes() {
        try {
            val file = File(context.filesDir, "saved_routes.json")
            if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<List<SavedRoute>>() {}.type
                _savedRoutes.value = Gson().fromJson(json, type) ?: emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveRoutesToFile() {
        try {
            val file = File(context.filesDir, "saved_routes.json")
            val json = Gson().toJson(_savedRoutes.value)
            file.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Fetches route from Google Directions API and updates state
     */
    private fun fetchAndApplyRoute(start: GeoPoint, end: GeoPoint) {
        val urlString = "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=${start.latitude},${start.longitude}&" +
                "destination=${end.latitude},${end.longitude}&" +
                "key=$apiKey"

        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        try {
            val reader = InputStreamReader(connection.inputStream)
            val response = Gson().fromJson(reader, DirectionsResponse::class.java)
            reader.close()

            if (response.routes.isNotEmpty()) {
                val route = response.routes[0]
                
                // Decode polyline and update current route
                _currentRoute.value = decodePolyline(route.overview_polyline.points)

                // Update distance and ETA from the API response
                val totalDistance = route.legs.sumOf { it.distance.value }.toDouble()
                val totalDuration = route.legs.sumOf { it.duration.value }
                _distanceToDestination.value = totalDistance
                _etaSeconds.value = totalDuration

            } else {
                // Handle no route found
                clearNavigation()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            clearNavigation()
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Decode polyline string from Google Maps API to a list of GeoPoints
     *
     * Courtesy of: https://stackoverflow.com/questions/39851243/android-ios-decode-google-maps-encoded-polyline-string
     */
    private fun decodePolyline(encoded: String): List<GeoPoint> {
        val poly = ArrayList<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0

        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat

            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng

            val p = GeoPoint(lat.toDouble() / 1E5, lng.toDouble() / 1E5)
            poly.add(p)
        }

        return poly
    }

    /**
     * Calculate total distance of a route in meters
     */
    private fun calculateTotalDistance(waypoints: List<GeoPoint>): Double {
        var totalDistance = 0.0
        for (i in 0 until waypoints.size - 1) {
            totalDistance += waypoints[i].distanceToAsDouble(waypoints[i + 1])
        }
        return totalDistance
    }

    /**
     * Calculate remaining distance from a point on the route
     */
    private fun calculateRemainingDistance(currentLocation: GeoPoint, route: List<GeoPoint>): Double {
        // Find the closest point on the route
        val closestPointIndex = route.indices.minByOrNull {
            currentLocation.distanceToAsDouble(route[it])
        } ?: return 0.0

        // Calculate distance from the closest point to the end of the route
        var remainingDistance = 0.0
        for (i in closestPointIndex until route.size - 1) {
            remainingDistance += route[i].distanceToAsDouble(route[i + 1])
        }

        return remainingDistance
    }

    companion object {
        fun formatDistance(meters: Double?): String {
            if (meters == null) return "N/A"
            return if (meters > 1000) {
                String.format("%.1f km", meters / 1000)
            } else {
                String.format("%d m", meters.toInt())
            }
        }

        fun formatETA(seconds: Int?): String {
            if (seconds == null) return "N/A"
            val minutes = seconds / 60
            return "$minutes min"
        }
    }
}
