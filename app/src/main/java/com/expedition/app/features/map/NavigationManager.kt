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

// Data classes for OSRM API response
data class OsrmResponse(val code: String, val routes: List<OsrmRoute>)
data class OsrmRoute(val geometry: String, val distance: Double, val duration: Double)

// Data classes for Open-Elevation API
data class ElevationResponse(val results: List<ElevationResult>)
data class ElevationResult(val elevation: Double, val latitude: Double, val longitude: Double)

// Data classes for OpenWeatherMap API
data class WeatherResponse(val main: MainWeather, val weather: List<WeatherDescription>, val name: String)
data class MainWeather(val temp: Double, val humidity: Int)
data class WeatherDescription(val description: String, val icon: String)

/**
 * Manages navigation, route calculation, ETA, elevation, and weather
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

    private val _elevation = MutableStateFlow<Double?>(null)
    val elevation: StateFlow<Double?> = _elevation.asStateFlow()

    private val _weather = MutableStateFlow<WeatherResponse?>(null)
    val weather: StateFlow<WeatherResponse?> = _weather.asStateFlow()

    private val _savedRoutes = MutableStateFlow<List<SavedRoute>>(emptyList())
    val savedRoutes: StateFlow<List<SavedRoute>> = _savedRoutes.asStateFlow()

    private var routeOverlay: Polyline? = null
    
    private val weatherApiKey = BuildConfig.WEATHER_API_KEY

    init {
        loadSavedRoutes()
    }

    /**
     * Set a destination and calculate route, elevation, and weather
     */
    fun setDestination(currentLocation: GeoPoint, destination: GeoPoint) {
        _destination.value = destination
        
        CoroutineScope(Dispatchers.IO).launch {
            fetchAndApplyRoute(currentLocation, destination)
            fetchElevation(destination)
            fetchWeather(destination)
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
        _elevation.value = null
        _weather.value = null
    }

    /**
     * Update environmental data (elevation, weather) for current location
     */
    fun updateEnvironmentalData(location: Location) {
        val point = GeoPoint(location.latitude, location.longitude)
        CoroutineScope(Dispatchers.IO).launch {
            fetchElevation(point)
            fetchWeather(point)
        }
    }

    /**
     * Update ETA and current weather periodically
     */
    fun updateProgress(currentLocation: Location, currentSpeedKmh: Double) {
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
        if (speedMps > 1) {
            _etaSeconds.value = (remainingDistance / speedMps).toInt()
        }

        // Periodically update elevation/weather for current position if needed
        CoroutineScope(Dispatchers.IO).launch {
            fetchElevation(GeoPoint(currentLocation.latitude, currentLocation.longitude))
        }
    }

    /**
     * Draw the current route on the map
     */
    fun drawRouteOnMap(mapView: MapView) {
        routeOverlay?.let { mapView.overlays.remove(it) }

        if (_currentRoute.value.isNotEmpty()) {
            val polyline = Polyline().apply {
                setPoints(_currentRoute.value)
                color = Color.BLUE
                width = 12f
            }
            mapView.overlays.add(polyline)
            routeOverlay = polyline
            mapView.invalidate()
        }
    }

    /**
     * Save current route to local storage
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
     * Fetches elevation from Open-Elevation API (Free)
     */
    private fun fetchElevation(point: GeoPoint) {
        val urlString = "https://api.open-elevation.com/api/v1/lookup?locations=${point.latitude},${point.longitude}"
        
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            val reader = InputStreamReader(connection.inputStream)
            val response = Gson().fromJson(reader, ElevationResponse::class.java)
            reader.close()
            connection.disconnect()

            if (response.results.isNotEmpty()) {
                _elevation.value = response.results[0].elevation
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Fetches weather from OpenWeatherMap API
     */
    private fun fetchWeather(point: GeoPoint) {
        if (weatherApiKey.isEmpty()) return

        val urlString = "https://api.openweathermap.org/data/2.5/weather?" +
                "lat=${point.latitude}&lon=${point.longitude}&" +
                "appid=$weatherApiKey&units=metric"
        
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            val reader = InputStreamReader(connection.inputStream)
            val response = Gson().fromJson(reader, WeatherResponse::class.java)
            reader.close()
            connection.disconnect()

            _weather.value = response
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Fetches route from OSRM (Open Source Routing Machine) - Free, no API Key needed
     */
    private fun fetchAndApplyRoute(start: GeoPoint, end: GeoPoint) {
        // OSRM expects coordinates in Lon,Lat format
        val urlString = "https://router.project-osrm.org/route/v1/driving/" +
                "${start.longitude},${start.latitude};${end.longitude},${end.latitude}" +
                "?overview=full&geometries=polyline"

        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "ExpeditionApp")
            
            val reader = InputStreamReader(connection.inputStream)
            val response = Gson().fromJson(reader, OsrmResponse::class.java)
            reader.close()
            connection.disconnect()

            if (response.code == "Ok" && response.routes.isNotEmpty()) {
                val route = response.routes[0]
                _currentRoute.value = decodePolyline(route.geometry)
                _distanceToDestination.value = route.distance
                _etaSeconds.value = route.duration.toInt()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            clearNavigation()
        }
    }

    // Helper methods for saving/loading routes
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
    
    private fun calculateRemainingDistance(currentLocation: GeoPoint, route: List<GeoPoint>): Double {
        val closestPointIndex = route.indices.minByOrNull { currentLocation.distanceToAsDouble(route[it]) } ?: return 0.0
        var remainingDistance = 0.0
        for (i in closestPointIndex until route.size - 1) {
            remainingDistance += route[i].distanceToAsDouble(route[i + 1])
        }
        return remainingDistance
    }

    companion object {
        fun formatDistance(meters: Double?): String {
            if (meters == null) return "N/A"
            return if (meters > 1000) String.format("%.1f km", meters / 1000) else String.format("%d m", meters.toInt())
        }

        fun formatETA(seconds: Int?): String {
            if (seconds == null) return "N/A"
            return "${seconds / 60} min"
        }
        
        fun formatElevation(elevation: Double?): String {
            if (elevation == null) return "N/A"
            return "${elevation.toInt()} m"
        }
    }
}
