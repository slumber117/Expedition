package com.expedition.app.features.map

import android.content.Context
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import android.graphics.Color
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.math.*

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

/**
 * Manages navigation, routes, and ETA calculations
 */
class NavigationManager(private val context: Context) {
    
    private val gson = Gson()
    private val routesFile = File(context.filesDir, "saved_routes.json")
    
    // Current navigation state
    private val _destination = MutableStateFlow<GeoPoint?>(null)
    val destination: StateFlow<GeoPoint?> = _destination.asStateFlow()
    
    private val _currentRoute = MutableStateFlow<List<GeoPoint>>(emptyList())
    val currentRoute: StateFlow<List<GeoPoint>> = _currentRoute.asStateFlow()
    
    private val _distanceToDestination = MutableStateFlow(0.0) // meters
    val distanceToDestination: StateFlow<Double> = _distanceToDestination.asStateFlow()
    
    private val _etaSeconds = MutableStateFlow<Long?>(null)
    val etaSeconds: StateFlow<Long?> = _etaSeconds.asStateFlow()
    
    private val _savedRoutes = MutableStateFlow<List<SavedRoute>>(emptyList())
    val savedRoutes: StateFlow<List<SavedRoute>> = _savedRoutes.asStateFlow()
    
    private var routeOverlay: Polyline? = null
    
    init {
        loadSavedRoutes()
    }
    
    /**
     * Set a destination and calculate route from current location
     */
    fun setDestination(currentLocation: GeoPoint, destination: GeoPoint) {
        _destination.value = destination
        
        // Generate route waypoints (simple straight-line for now, could integrate with routing API)
        val waypoints = generateRouteWaypoints(currentLocation, destination)
        _currentRoute.value = waypoints
        
        // Calculate total distance
        val distance = calculateTotalDistance(waypoints)
        _distanceToDestination.value = distance
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
     * Should be called every 500ms for real-time updates
     */
    fun updateETA(currentLocation: Location, speedKmh: Float) {
        val dest = _destination.value ?: return
        
        // Calculate remaining distance from current position to destination
        val remainingDistance = calculateDistance(
            currentLocation.latitude, currentLocation.longitude,
            dest.latitude, dest.longitude
        )
        _distanceToDestination.value = remainingDistance
        
        // Calculate ETA
        if (speedKmh > 1f) { // Only calculate if moving
            val speedMs = speedKmh / 3.6f // Convert km/h to m/s
            val etaSec = (remainingDistance / speedMs).toLong()
            _etaSeconds.value = etaSec
        } else {
            // If stopped, keep the last ETA or show null
            // Don't update to prevent flickering
        }
    }
    
    /**
     * Draw route on map
     */
    fun drawRouteOnMap(mapView: MapView) {
        // Remove old route
        routeOverlay?.let { mapView.overlays.remove(it) }
        
        val waypoints = _currentRoute.value
        if (waypoints.isEmpty()) return
        
        routeOverlay = Polyline().apply {
            setPoints(waypoints)
            outlinePaint.color = Color.parseColor("#2196F3") // Material Blue
            outlinePaint.strokeWidth = 12f
        }
        
        mapView.overlays.add(routeOverlay)
        mapView.invalidate()
    }
    
    /**
     * Save current route for offline use
     */
    fun saveCurrentRoute(name: String): SavedRoute? {
        val waypoints = _currentRoute.value
        if (waypoints.isEmpty()) return null
        
        val route = SavedRoute(
            id = System.currentTimeMillis().toString(),
            name = name,
            waypoints = waypoints,
            totalDistanceMeters = _distanceToDestination.value
        )
        
        val routes = _savedRoutes.value.toMutableList()
        routes.add(route)
        _savedRoutes.value = routes
        
        persistRoutes(routes)
        return route
    }
    
    /**
     * Load a saved route
     */
    fun loadRoute(routeId: String): SavedRoute? {
        val route = _savedRoutes.value.find { it.id == routeId }
        route?.let {
            _currentRoute.value = it.waypoints
            _distanceToDestination.value = it.totalDistanceMeters
            if (it.waypoints.isNotEmpty()) {
                _destination.value = it.waypoints.last()
            }
        }
        return route
    }
    
    /**
     * Delete a saved route
     */
    fun deleteRoute(routeId: String) {
        val routes = _savedRoutes.value.filter { it.id != routeId }
        _savedRoutes.value = routes
        persistRoutes(routes)
    }
    
    /**
     * Generate route waypoints between two points
     * This is a simplified version - in production, you'd use a routing API like OSRM or GraphHopper
     */
    private fun generateRouteWaypoints(start: GeoPoint, end: GeoPoint): List<GeoPoint> {
        val waypoints = mutableListOf<GeoPoint>()
        
        // Add start
        waypoints.add(start)
        
        // Add intermediate points for smoother visualization
        val steps = 10
        for (i in 1 until steps) {
            val fraction = i.toDouble() / steps
            val lat = start.latitude + (end.latitude - start.latitude) * fraction
            val lon = start.longitude + (end.longitude - start.longitude) * fraction
            waypoints.add(GeoPoint(lat, lon))
        }
        
        // Add end
        waypoints.add(end)
        
        return waypoints
    }
    
    /**
     * Calculate total distance of a route in meters
     */
    private fun calculateTotalDistance(waypoints: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 0 until waypoints.size - 1) {
            total += calculateDistance(
                waypoints[i].latitude, waypoints[i].longitude,
                waypoints[i + 1].latitude, waypoints[i + 1].longitude
            )
        }
        return total
    }
    
    /**
     * Calculate distance between two points using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return earthRadius * c
    }
    
    /**
     * Save routes to file for persistence
     */
    private fun persistRoutes(routes: List<SavedRoute>) {
        try {
            // Convert GeoPoints to serializable format
            val serializableRoutes = routes.map { route ->
                mapOf(
                    "id" to route.id,
                    "name" to route.name,
                    "waypoints" to route.waypoints.map { listOf(it.latitude, it.longitude) },
                    "totalDistanceMeters" to route.totalDistanceMeters,
                    "createdAt" to route.createdAt
                )
            }
            routesFile.writeText(gson.toJson(serializableRoutes))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Load routes from file
     */
    private fun loadSavedRoutes() {
        try {
            if (routesFile.exists()) {
                val json = routesFile.readText()
                val type = object : TypeToken<List<Map<String, Any>>>() {}.type
                val rawRoutes: List<Map<String, Any>> = gson.fromJson(json, type)
                
                _savedRoutes.value = rawRoutes.map { raw ->
                    @Suppress("UNCHECKED_CAST")
                    val waypointsList = raw["waypoints"] as List<List<Double>>
                    SavedRoute(
                        id = raw["id"] as String,
                        name = raw["name"] as String,
                        waypoints = waypointsList.map { GeoPoint(it[0], it[1]) },
                        totalDistanceMeters = (raw["totalDistanceMeters"] as Number).toDouble(),
                        createdAt = (raw["createdAt"] as Number).toLong()
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    companion object {
        /**
         * Format ETA seconds to human-readable string
         */
        fun formatETA(seconds: Long): String {
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> {
                    val mins = seconds / 60
                    val secs = seconds % 60
                    "${mins}m ${secs}s"
                }
                else -> {
                    val hours = seconds / 3600
                    val mins = (seconds % 3600) / 60
                    "${hours}h ${mins}m"
                }
            }
        }
        
        /**
         * Format distance to human-readable string
         */
        fun formatDistance(meters: Double): String {
            return when {
                meters < 1000 -> "${meters.toInt()}m"
                else -> String.format("%.1fkm", meters / 1000)
            }
        }
    }
}
