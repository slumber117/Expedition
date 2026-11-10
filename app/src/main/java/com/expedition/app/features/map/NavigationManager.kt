package com.expedition.app.features.map

import android.content.Context
import android.graphics.Color
import android.location.Location
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

/**
 * Supported travel modes for routing.
 * HYBRID combines fast OSRM corridor routing with Curvature Sinuosity + Time/Weather safety weighting.
 */
enum class TravelMode(val osrmProfile: String, val displayLabel: String) {
    HYBRID("driving", "Smart Hybrid"),
    TWISTY("twisty", "Twisty"),
    DRIVING("driving", "Fast"),
    LOOP("driving", "Loop"),
    WALKING("foot", "Hike")
}

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

// Data classes for Backend API responses
data class OsrmResponse(val code: String, val routes: List<OsrmRoute>?)
data class OsrmRoute(val geometry: String, val distance: Double, val duration: Double)

data class GraphHopperResponse(val paths: List<GraphHopperPath>?)
data class GraphHopperPath(val points: String, val distance: Double, val time: Long)

data class RouteWeatherRequest(val start_time_unix: Long, val waypoints: List<Waypoint>)
data class Waypoint(val lat: Double, val lon: Double, val time_from_start_seconds: Int)

data class WeatherWarning(val lat: Double, val lon: Double, val warning: String, val expected_time: String?, val severity: String?)
data class WeatherWarningsResponse(val route_safe: Boolean, val warnings: List<WeatherWarning>?)

data class RouteOption(
    val geometry: List<GeoPoint>,
    val distance: Double,
    val duration: Double,
    val curvatureScore: Double = 0.0
)

data class ElevationResponse(val results: List<ElevationResult>)
data class ElevationResult(val elevation: Double, val latitude: Double, val longitude: Double)

data class WeatherResponse(val main: MainWeather, val weather: List<WeatherDescription>, val name: String)
data class MainWeather(val temp: Double, val humidity: Int)
data class WeatherDescription(val description: String, val icon: String)

/**
 * Rich Weather + Time-of-Day Rider Telemetry Model
 */
data class RiderWeatherTelemetry(
    val tempCelsius: Double,
    val feelsLikeCelsius: Double,
    val windSpeedKmh: Double,
    val precipitationMm: Double,
    val humidityPercent: Int,
    val conditionLabel: String,
    val isDaylight: Boolean,
    val localTimeFormatted: String,
    val daylightPhase: String,
    val sunsetTimeFormatted: String?,
    val roadGripAdvisory: String,
    val gripSeverity: Int, // 0 = Optimal, 1 = Caution (Wet/Wind/Dusk), 2 = Hazard (Storm/Ice)
    val etaForecastSummary: String? = null
)

// Speed Camera Models
data class SpeedCamera(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val max_speed: Int,
    val type: String
)

data class TrafficSegment(
    val start_lat: Double,
    val start_lon: Double,
    val end_lat: Double,
    val end_lon: Double,
    val severity: String,
    val speed_kmh: Int
)

data class SpeedCameraAlert(
    val camera: SpeedCamera,
    val distanceMeters: Int
)

data class CommunityPin(
    val id: String,
    val title: String,
    val category: String,
    val lat: Double,
    val lon: Double,
    val description: String,
    val created_at: Long = System.currentTimeMillis()
)

/**
 * Manages navigation, Hybrid route scoring (Fast + Curvature + Time-Projected Weather),
 * offline routing fallback, elevation, weather+time display, speed cameras, and traffic.
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

    private val _riderWeather = MutableStateFlow<RiderWeatherTelemetry?>(null)
    val riderWeather: StateFlow<RiderWeatherTelemetry?> = _riderWeather.asStateFlow()

    private val _routeCurvatureScore = MutableStateFlow(0.0)
    val routeCurvatureScore: StateFlow<Double> = _routeCurvatureScore.asStateFlow()

    private val _activeRoutingAlgorithm = MutableStateFlow("Hybrid A* + Curvature-Weather")
    val activeRoutingAlgorithm: StateFlow<String> = _activeRoutingAlgorithm.asStateFlow()

    private val _weatherWarnings = MutableStateFlow<List<WeatherWarning>>(emptyList())
    val weatherWarnings: StateFlow<List<WeatherWarning>> = _weatherWarnings.asStateFlow()

    private val _speedCameras = MutableStateFlow<List<SpeedCamera>>(emptyList())
    val speedCameras: StateFlow<List<SpeedCamera>> = _speedCameras.asStateFlow()

    private val _trafficSegments = MutableStateFlow<List<TrafficSegment>>(emptyList())
    val trafficSegments: StateFlow<List<TrafficSegment>> = _trafficSegments.asStateFlow()

    private val _speedCameraAlert = MutableStateFlow<SpeedCameraAlert?>(null)
    val speedCameraAlert: StateFlow<SpeedCameraAlert?> = _speedCameraAlert.asStateFlow()

    private val _communityPins = MutableStateFlow<List<CommunityPin>>(emptyList())
    val communityPins: StateFlow<List<CommunityPin>> = _communityPins.asStateFlow()

    private val _routeColor = MutableStateFlow(Color.parseColor("#FF6B35"))
    val routeColor: StateFlow<Int> = _routeColor.asStateFlow()

    private val _requiresProUpgrade = MutableStateFlow(false)
    val requiresProUpgrade: StateFlow<Boolean> = _requiresProUpgrade.asStateFlow()

    private val _savedRoutes = MutableStateFlow<List<SavedRoute>>(emptyList())
    val savedRoutes: StateFlow<List<SavedRoute>> = _savedRoutes.asStateFlow()

    private val _travelMode = MutableStateFlow(TravelMode.HYBRID)
    val travelMode: StateFlow<TravelMode> = _travelMode.asStateFlow()

    private var routeOverlay: Polyline? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })

    // Battery optimization: throttle environmental network fetches
    private var lastEnvFetchTimeMs = 0L
    private var lastEnvFetchLocation: GeoPoint? = null
    private var lastClosestRouteIndex = 0
    private var lastDestinationLabel: String? = null

    init {
        loadSavedRoutes()
        // Populate initial time & local baseline weather state immediately
        _riderWeather.value = buildDefaultTimeTelemetry()
    }

    fun setDestination(currentLocation: GeoPoint, destination: GeoPoint, destinationLabel: String? = null) {
        _destination.value = destination
        lastClosestRouteIndex = 0
        if (!destinationLabel.isNullOrBlank()) {
            lastDestinationLabel = destinationLabel.substringBefore(",").trim()
        } else if (lastDestinationLabel == null) {
            lastDestinationLabel = String.format(Locale.US, "Route to %.3f, %.3f", destination.latitude, destination.longitude)
        }
        ioScope.launch {
            fetchAndApplyRoute(currentLocation, destination)
            fetchElevation(destination)
            fetchWeatherAndTime(destination, isDestinationFetch = true)
        }
    }

    fun loadSavedRoute(route: SavedRoute, currentLocation: GeoPoint?) {
        val pts = route.waypoints
        if (pts.isEmpty()) return
        lastClosestRouteIndex = 0
        _currentRoute.value = pts
        _destination.value = pts.lastOrNull()
        _distanceToDestination.value = route.totalDistanceMeters
        _routeCurvatureScore.value = calculateCurvatureScore(pts, route.totalDistanceMeters)
        _activeRoutingAlgorithm.value = "Offline Cached Corridor"
        // Estimate initial ETA at 60 km/h (16.67 m/s)
        _etaSeconds.value = (route.totalDistanceMeters / 16.67).toInt()
        val firstPt = pts.firstOrNull()
        val lastPt = pts.lastOrNull()
        if (firstPt != null && lastPt != null) {
            ioScope.launch {
                fetchSpeedCamerasForRoute(pts)
                fetchTrafficSegmentsForRoute(firstPt, lastPt)
            }
        }
    }

    fun clearNavigation() {
        _destination.value = null
        _currentRoute.value = emptyList()
        _distanceToDestination.value = 0.0
        _etaSeconds.value = null
        _routeCurvatureScore.value = 0.0
        _weatherWarnings.value = emptyList()
        _speedCameras.value = emptyList()
        _trafficSegments.value = emptyList()
        _speedCameraAlert.value = null
        lastClosestRouteIndex = 0
    }

    fun dismissUpgradeDialog() {
        _requiresProUpgrade.value = false
    }

    /**
     * Battery-optimized environmental update:
     * Only triggers network calls if >= 10 minutes have elapsed OR rider moved >= 1.5 km,
     * while always keeping the local time / solar phase fresh.
     */
    fun updateEnvironmentalData(location: Location, forceRefresh: Boolean = false) {
        val point = GeoPoint(location.latitude, location.longitude)
        val now = System.currentTimeMillis()
        val movedMeters = lastEnvFetchLocation?.let {
            calculateDistanceMeters(it.latitude, it.longitude, point.latitude, point.longitude)
        } ?: Double.MAX_VALUE

        // Always refresh local time string in HUD without network
        _riderWeather.value = _riderWeather.value?.copy(
            localTimeFormatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)),
            daylightPhase = computeDaylightPhase(Calendar.getInstance())
        ) ?: buildDefaultTimeTelemetry()

        if (!forceRefresh && (now - lastEnvFetchTimeMs < 600_000L) && movedMeters < 1500.0) {
            return
        }

        lastEnvFetchTimeMs = now
        lastEnvFetchLocation = point

        CoroutineScope(Dispatchers.IO).launch {
            fetchElevation(point)
            fetchWeatherAndTime(point, isDestinationFetch = false)
        }
    }

    fun updateProgress(currentLocation: Location, currentSpeedKmh: Double) {
        val route = _currentRoute.value
        if (route.isEmpty() || destination.value == null) {
            _etaSeconds.value = null
            _speedCameraAlert.value = null
            return
        }

        val remainingDistance = calculateRemainingDistanceWindowed(
            GeoPoint(currentLocation.latitude, currentLocation.longitude),
            route
        )
        _distanceToDestination.value = remainingDistance

        val speedMps = currentSpeedKmh * 1000.0 / 3600.0
        val effectiveSpeedMps = if (speedMps > 2.0) speedMps else 15.0 // Fallback to ~54 km/h average when stopped at light
        _etaSeconds.value = (remainingDistance / effectiveSpeedMps).toInt()

        checkSpeedCameraProximity(currentLocation)
    }

    private fun checkSpeedCameraProximity(currentLocation: Location) {
        val cameras = _speedCameras.value
        if (cameras.isEmpty()) {
            _speedCameraAlert.value = null
            return
        }

        var closestCamera: SpeedCamera? = null
        var minDistance = Double.MAX_VALUE

        for (cam in cameras) {
            val dist = calculateDistanceMeters(currentLocation.latitude, currentLocation.longitude, cam.lat, cam.lon)
            if (dist < minDistance) {
                minDistance = dist
                closestCamera = cam
            }
        }

        if (closestCamera != null && minDistance <= 600.0) {
            _speedCameraAlert.value = SpeedCameraAlert(closestCamera, minDistance.toInt())
        } else {
            _speedCameraAlert.value = null
        }
    }

    fun generateRoundTripLoop(startPoint: GeoPoint, targetDistanceKm: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            val radiusMeters = (targetDistanceKm * 1000.0) / (2 * Math.PI)
            val radiusDegrees = radiusMeters / 111000.0

            val waypoints = mutableListOf<GeoPoint>()
            val numPoints = 12
            for (i in 0 until numPoints) {
                val angle = 2 * Math.PI * i / numPoints
                val dLat = radiusDegrees * sin(angle)
                val dLon = radiusDegrees * cos(angle) / cos(Math.toRadians(startPoint.latitude)).coerceAtLeast(0.1)
                waypoints.add(GeoPoint(startPoint.latitude + dLat, startPoint.longitude + dLon))
            }
            waypoints.add(waypoints.first())

            _currentRoute.value = waypoints
            _destination.value = waypoints.last()
            _distanceToDestination.value = targetDistanceKm * 1000.0
            _etaSeconds.value = ((targetDistanceKm / 55.0) * 3600).toInt()
            _routeCurvatureScore.value = calculateCurvatureScore(waypoints, targetDistanceKm * 1000.0)
            _activeRoutingAlgorithm.value = "Curvature Loop Generator"
            autoPersistRouteWhileOnline(
                routeName = "${targetDistanceKm.toInt()} km Round-Trip Loop",
                waypoints = waypoints,
                distanceMeters = targetDistanceKm * 1000.0
            )
            fetchSpeedCamerasForRoute(waypoints)
            fetchTrafficSegmentsForRoute(waypoints.first(), waypoints[numPoints / 2])
        }
    }

    fun fetchCommunityPins(location: GeoPoint) {
        CoroutineScope(Dispatchers.IO).launch {
            val urlString = "${BackendConfig.BASE_URL}/community-pins?lat=${location.latitude}&lon=${location.longitude}"
            try {
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "ExpeditionApp/1.0")
                val reader = InputStreamReader(connection.inputStream)
                val type = object : TypeToken<List<CommunityPin>>() {}.type
                val pins: List<CommunityPin>? = Gson().fromJson(reader, type)
                reader.close()
                connection.disconnect()
                _communityPins.value = pins ?: emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addCommunityPin(title: String, category: String, lat: Double, lon: Double, description: String) {
        val newPin = CommunityPin(
            id = "pin_${System.currentTimeMillis()}",
            title = title,
            category = category,
            lat = lat,
            lon = lon,
            description = description
        )
        _communityPins.value = _communityPins.value + newPin

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val urlString = "${BackendConfig.BASE_URL}/community-pins"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                val json = Gson().toJson(newPin)
                connection.outputStream.use { os -> os.write(json.toByteArray()) }
                connection.responseCode
                connection.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    fun setTravelMode(mode: TravelMode) {
        if (_travelMode.value == mode) return
        _travelMode.value = mode
    }

    fun drawRouteOnMap(mapView: MapView) {
        routeOverlay?.let { mapView.overlays.remove(it) }

        if (_currentRoute.value.isNotEmpty()) {
            val polyline = Polyline().apply {
                setPoints(_currentRoute.value)
                color = _routeColor.value
                width = 14f
            }
            mapView.overlays.add(polyline)
            routeOverlay = polyline
            mapView.invalidate()
        }
    }

    fun saveCurrentRoute(routeName: String) {
        val route = _currentRoute.value
        if (route.isEmpty() || routeName.isBlank()) return

        autoPersistRouteWhileOnline(
            routeName = routeName.trim(),
            waypoints = route,
            distanceMeters = _distanceToDestination.value
        )
    }

    /**
     * Automatically saves any route calculated while the user has mobile data / Wi-Fi
     * so if they lose signal later, it is already in their Saved Routes list ready to load offline.
     */
    private fun autoPersistRouteWhileOnline(
        routeName: String,
        waypoints: List<GeoPoint>,
        distanceMeters: Double
    ) {
        if (waypoints.isEmpty()) return
        val existing = _savedRoutes.value.toMutableList()
        // Avoid duplicating identical destination routes; update in-place or prepend
        val endPt = waypoints.last()
        val matchIdx = existing.indexOfFirst {
            it.name.equals(routeName, ignoreCase = true) ||
                (it.waypoints.isNotEmpty() &&
                    calculateDistanceMeters(
                        it.waypoints.last().latitude,
                        it.waypoints.last().longitude,
                        endPt.latitude,
                        endPt.longitude
                    ) < 250.0)
        }

        val savedRoute = SavedRoute(
            id = if (matchIdx >= 0) existing[matchIdx].id else "route_${System.currentTimeMillis()}",
            name = routeName,
            waypoints = waypoints,
            totalDistanceMeters = distanceMeters,
            createdAt = System.currentTimeMillis()
        )

        if (matchIdx >= 0) {
            existing[matchIdx] = savedRoute
        } else {
            existing.add(0, savedRoute)
        }
        _savedRoutes.value = existing
        saveRoutesToFile()
    }

    private fun fetchElevation(point: GeoPoint) {
        val urlString = "${BackendConfig.BASE_URL}/elevation?lat=${point.latitude}&lon=${point.longitude}"
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
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
     * Fetches live Weather + Time + Solar + Hourly Forecast from Open-Meteo (free, zero-key)
     * and enriches with Backend OpenWeatherMap if available.
     * Computes motorcycle-specific Road Grip Advisory based on precipitation, wind, temperature, and time of day.
     */
    private fun fetchWeatherAndTime(point: GeoPoint, isDestinationFetch: Boolean) {
        val nowCal = Calendar.getInstance()
        val timeFormatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(nowCal.time)
        val daylightPhase = computeDaylightPhase(nowCal)

        try {
            val openMeteoUrl = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${point.latitude}&longitude=${point.longitude}" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,wind_speed_10m" +
                "&daily=sunrise,sunset&timezone=auto&forecast_days=2"

            val connection = URL(openMeteoUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("User-Agent", "ExpeditionApp/1.0")

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val root = JSONObject(body)
                val current = root.optJSONObject("current")
                val daily = root.optJSONObject("daily")
                val hourly = root.optJSONObject("hourly")

                if (current != null) {
                    val temp = current.optDouble("temperature_2m", 16.0)
                    val feelsLike = current.optDouble("apparent_temperature", temp)
                    val humidity = current.optInt("relative_humidity_2m", 55)
                    val precip = current.optDouble("precipitation", 0.0)
                    val windKmh = current.optDouble("wind_speed_10m", 10.0)
                    val weatherCode = current.optInt("weather_code", 0)
                    val isDay = current.optInt("is_day", 1) == 1

                    val sunsetStr = daily?.optJSONArray("sunset")?.optString(0)?.substringAfter("T")
                    val conditionLabel = mapWmoWeatherCode(weatherCode)

                    // Calculate ETA weather forecast using time offset if navigating
                    var etaSummary: String? = _riderWeather.value?.etaForecastSummary
                    val etaSec = _etaSeconds.value ?: 0
                    if (hourly != null && (isDestinationFetch || etaSec > 0)) {
                        val hoursAhead = ((etaSec / 3600.0).roundToInt()).coerceIn(1, 24)
                        val currentHourOfDay = nowCal.get(Calendar.HOUR_OF_DAY)
                        val targetIndex = (currentHourOfDay + hoursAhead).coerceAtMost(47)
                        val hourlyTemps = hourly.optJSONArray("temperature_2m")
                        val hourlyPrecipProb = hourly.optJSONArray("precipitation_probability")
                        val hourlyCodes = hourly.optJSONArray("weather_code")
                        if (hourlyTemps != null && targetIndex < hourlyTemps.length()) {
                            val etaTemp = hourlyTemps.optDouble(targetIndex, temp).roundToInt()
                            val etaRainProb = hourlyPrecipProb?.optInt(targetIndex, 0) ?: 0
                            val etaCond = mapWmoWeatherCode(hourlyCodes?.optInt(targetIndex, weatherCode) ?: weatherCode)
                            val arrivalCal = (nowCal.clone() as Calendar).apply { add(Calendar.SECOND, etaSec.coerceAtLeast(1800)) }
                            val arrivalTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(arrivalCal.time)
                            etaSummary = "ETA ($arrivalTimeStr): $etaTemp°C, $etaCond (${etaRainProb}% rain)"
                        }
                    }

                    // Determine Motorcycle Road Grip & Visibility Advisory
                    val (advisory, severity) = when {
                        temp <= 3.0 && precip > 0.0 -> "BLACK ICE / SLEET RISK" to 2
                        temp <= 3.0 -> "FROST RISK (<3°C) • COLD TIRES" to 2
                        precip >= 2.5 || weatherCode >= 95 -> "HEAVY RAIN • REDUCE LEAN" to 2
                        windKmh >= 42.0 -> "STRONG CROSSWIND (${windKmh.roundToInt()} km/h)" to 2
                        precip > 0.1 || weatherCode in 51..82 -> "WET SURFACE • SMOOTH THROTTLE" to 1
                        !isDay -> "NIGHT RIDE • HIGH-CONTRAST HUD" to 1
                        daylightPhase.contains("Glare") -> "LOW SUN GLARE • VISOR CAUTION" to 1
                        windKmh >= 25.0 -> "BREEZY (${windKmh.roundToInt()} km/h) • GOOD GRIP" to 0
                        else -> "DRY OPTIMAL GRIP" to 0
                    }

                    _riderWeather.value = RiderWeatherTelemetry(
                        tempCelsius = temp,
                        feelsLikeCelsius = feelsLike,
                        windSpeedKmh = windKmh,
                        precipitationMm = precip,
                        humidityPercent = humidity,
                        conditionLabel = conditionLabel,
                        isDaylight = isDay,
                        localTimeFormatted = timeFormatted,
                        daylightPhase = daylightPhase,
                        sunsetTimeFormatted = sunsetStr,
                        roadGripAdvisory = advisory,
                        gripSeverity = severity,
                        etaForecastSummary = etaSummary
                    )

                    // Also keep legacy _weather populated for compatibility
                    _weather.value = WeatherResponse(
                        main = MainWeather(temp = temp, humidity = humidity),
                        weather = listOf(WeatherDescription(description = "$conditionLabel • ${temp.roundToInt()}°C", icon = "")),
                        name = daylightPhase
                    )
                    return
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to Backend /weather proxy if Open-Meteo could not be reached
        val urlString = "${BackendConfig.BASE_URL}/weather?lat=${point.latitude}&lon=${point.longitude}"
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val reader = InputStreamReader(connection.inputStream)
            val response = Gson().fromJson(reader, WeatherResponse::class.java)
            reader.close()
            connection.disconnect()
            if (response?.main != null) {
                _weather.value = response
                val desc = response.weather.firstOrNull()?.description?.replaceFirstChar { it.uppercase() } ?: "Clear"
                _riderWeather.value = RiderWeatherTelemetry(
                    tempCelsius = response.main.temp,
                    feelsLikeCelsius = response.main.temp,
                    windSpeedKmh = 12.0,
                    precipitationMm = 0.0,
                    humidityPercent = response.main.humidity,
                    conditionLabel = desc,
                    isDaylight = nowCal.get(Calendar.HOUR_OF_DAY) in 6..19,
                    localTimeFormatted = timeFormatted,
                    daylightPhase = daylightPhase,
                    sunsetTimeFormatted = null,
                    roadGripAdvisory = if (response.main.temp <= 3.0) "FROST RISK (<3°C)" else "DRY OPTIMAL GRIP",
                    gripSeverity = if (response.main.temp <= 3.0) 2 else 0
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildDefaultTimeTelemetry(): RiderWeatherTelemetry {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isDay = hour in 7..19
        val phase = computeDaylightPhase(cal)
        return RiderWeatherTelemetry(
            tempCelsius = 18.0,
            feelsLikeCelsius = 17.5,
            windSpeedKmh = 11.0,
            precipitationMm = 0.0,
            humidityPercent = 50,
            conditionLabel = if (isDay) "Clear Sky" else "Clear Night",
            isDaylight = isDay,
            localTimeFormatted = SimpleDateFormat("HH:mm", Locale.getDefault()).format(cal.time),
            daylightPhase = phase,
            sunsetTimeFormatted = "19:45",
            roadGripAdvisory = if (isDay) "DRY OPTIMAL GRIP" else "NIGHT RIDE • HIGH-CONTRAST HUD",
            gripSeverity = if (isDay) 0 else 1
        )
    }

    private fun computeDaylightPhase(cal: Calendar): String {
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..6 -> "Dawn • Low Light"
            in 7..16 -> "Daylight"
            in 17..19 -> "Golden Hour • Glare"
            else -> "Night Ride"
        }
    }

    private fun mapWmoWeatherCode(code: Int): String = when (code) {
        0 -> "Clear Sky"
        1, 2 -> "Partly Cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Light Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63 -> "Rain"
        65 -> "Heavy Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Fair"
    }

    private fun fetchSpeedCamerasForRoute(route: List<GeoPoint>) {
        if (route.isEmpty()) return
        val minLat = (route.minOfOrNull { it.latitude } ?: return) - 0.01
        val maxLat = (route.maxOfOrNull { it.latitude } ?: return) + 0.01
        val minLon = (route.minOfOrNull { it.longitude } ?: return) - 0.01
        val maxLon = (route.maxOfOrNull { it.longitude } ?: return) + 0.01

        val urlString = "${BackendConfig.BASE_URL}/speed-cameras/corridor?min_lat=$minLat&min_lon=$minLon&max_lat=$maxLat&max_lon=$maxLon"
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.setRequestProperty("User-Agent", "ExpeditionApp/1.0")
            val reader = InputStreamReader(connection.inputStream)
            val type = object : TypeToken<List<SpeedCamera>>() {}.type
            val cameras: List<SpeedCamera>? = Gson().fromJson(reader, type)
            reader.close()
            connection.disconnect()
            _speedCameras.value = cameras ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fetchTrafficSegmentsForRoute(start: GeoPoint, end: GeoPoint) {
        val urlString = "${BackendConfig.BASE_URL}/traffic/corridor?start=${start.latitude},${start.longitude}&end=${end.latitude},${end.longitude}"
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "ExpeditionApp/1.0")
            val reader = InputStreamReader(connection.inputStream)
            val type = object : TypeToken<List<TrafficSegment>>() {}.type
            val segments: List<TrafficSegment>? = Gson().fromJson(reader, type)
            reader.close()
            connection.disconnect()
            _trafficSegments.value = segments ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Hybrid Routing Engine:
     * Fetches candidate routes, calculates Curvature Sinuosity Index, and scores routes using a
     * hybrid multi-objective function of Travel Time + Curvature + Time-Projected Weather & Daylight.
     * Falls back to Offline Corridor Routing if no internet connection is available.
     */
    private fun fetchAndApplyRoute(start: GeoPoint, end: GeoPoint) {
        val mode = _travelMode.value
        val profile = mode.osrmProfile
        val urlString = "${BackendConfig.BASE_URL}/route?profile=$profile" +
            "&start=${start.longitude},${start.latitude}&end=${end.longitude},${end.latitude}&alternatives=true"
        try {
            val user = FirebaseAuth.getInstance().currentUser
            val tokenTask = user?.getIdToken(false)
            val token = if (tokenTask != null) {
                try { Tasks.await(tokenTask)?.token ?: "" } catch (_: Exception) { "" }
            } else ""

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.setRequestProperty("User-Agent", "ExpeditionApp/1.0")
            if (token.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }

            val responseCode = connection.responseCode
            if (responseCode == 402 || responseCode == 401) {
                _requiresProUpgrade.value = true
                // Fall back to Hybrid/OSRM so rider isn't stranded
                if (profile == "twisty") {
                    _travelMode.value = TravelMode.HYBRID
                    fetchAndApplyRoute(start, end)
                }
                return
            }

            val reader = InputStreamReader(connection.inputStream)
            val possibleRoutes = mutableListOf<RouteOption>()

            if (profile == "twisty") {
                val response = Gson().fromJson(reader, GraphHopperResponse::class.java)
                response?.paths?.forEach { path ->
                    val geo = decodePolyline(path.points)
                    val curv = calculateCurvatureScore(geo, path.distance)
                    possibleRoutes.add(RouteOption(geo, path.distance, path.time.toDouble() / 1000.0, curv))
                }
            } else {
                val response = Gson().fromJson(reader, OsrmResponse::class.java)
                if (response?.code == "Ok" && response.routes != null) {
                    response.routes.forEach { route ->
                        val geo = decodePolyline(route.geometry)
                        val curv = calculateCurvatureScore(geo, route.distance)
                        possibleRoutes.add(RouteOption(geo, route.distance, route.duration, curv))
                    }
                }
            }
            reader.close()
            connection.disconnect()

            if (possibleRoutes.isNotEmpty()) {
                evaluateRoutesWithHybridAlgorithm(possibleRoutes, token, mode)
                fetchSpeedCamerasForRoute(possibleRoutes[0].geometry)
                fetchTrafficSegmentsForRoute(start, end)
            } else {
                applyOfflineFallbackRoute(start, end)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Direct OSRM fallback or Offline Corridor fallback so routing works even if backend is offline
            if (!tryDirectOsrmFallback(start, end)) {
                applyOfflineFallbackRoute(start, end)
            }
        }
    }

    private fun tryDirectOsrmFallback(start: GeoPoint, end: GeoPoint): Boolean {
        return try {
            val osrmUrl = "https://router.project-osrm.org/route/v1/driving/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=full&geometries=polyline&alternatives=true"
            val conn = URL(osrmUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "ExpeditionApp/1.0")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = InputStreamReader(conn.inputStream)
                val response = Gson().fromJson(reader, OsrmResponse::class.java)
                reader.close()
                conn.disconnect()
                val routes = response?.routes?.map {
                    val geo = decodePolyline(it.geometry)
                    RouteOption(geo, it.distance, it.duration, calculateCurvatureScore(geo, it.distance))
                } ?: emptyList()
                if (routes.isNotEmpty()) {
                    evaluateRoutesWithHybridAlgorithm(routes, "", _travelMode.value)
                    true
                } else false
            } else {
                conn.disconnect()
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Offline Corridor Fallback:
     * 1. Checks if a saved route already covers this destination.
     * 2. Otherwise synthesizes an interpolated multi-waypoint offline bearing corridor so offline navigation works.
     */
    private fun applyOfflineFallbackRoute(start: GeoPoint, end: GeoPoint) {
        val matchingSaved = _savedRoutes.value.firstOrNull { saved ->
            saved.waypoints.isNotEmpty() &&
                calculateDistanceMeters(
                    saved.waypoints.last().latitude,
                    saved.waypoints.last().longitude,
                    end.latitude,
                    end.longitude
                ) < 1000.0
        }

        if (matchingSaved != null) {
            loadSavedRoute(matchingSaved, start)
            return
        }

        // Synthesize a 20-segment offline bearing corridor
        val points = mutableListOf<GeoPoint>()
        val steps = 20
        for (i in 0..steps) {
            val t = i.toDouble() / steps
            val lat = start.latitude + (end.latitude - start.latitude) * t
            val lon = start.longitude + (end.longitude - start.longitude) * t
            points.add(GeoPoint(lat, lon))
        }
        val directDist = calculateDistanceMeters(start.latitude, start.longitude, end.latitude, end.longitude) * 1.18
        _currentRoute.value = points
        _distanceToDestination.value = directDist
        _etaSeconds.value = (directDist / 15.0).toInt()
        _routeCurvatureScore.value = calculateCurvatureScore(points, directDist)
        _activeRoutingAlgorithm.value = "Offline Dead-Reckoning Vector"
        _routeColor.value = Color.parseColor("#FF8A5C")
    }

    /**
     * Calculates a motorcycle Curvature Score (0.0 to 10.0) by measuring cumulative
     * bearing deflection angles per kilometer and segment sinuosity.
     */
    private fun calculateCurvatureScore(geometry: List<GeoPoint>, totalDistanceMeters: Double): Double {
        if (geometry.size < 3 || totalDistanceMeters < 200.0) return 1.0
        var totalTurnDegrees = 0.0
        val step = (geometry.size / 120).coerceAtLeast(1)
        var i = step
        while (i < geometry.size - step) {
            val prev = geometry[i - step]
            val curr = geometry[i]
            val next = geometry[i + step]
            val b1 = prev.bearingTo(curr)
            val b2 = curr.bearingTo(next)
            var diff = abs(b2 - b1)
            if (diff > 180.0) diff = 360.0 - diff
            if (diff > 6.0) { // Filter out straight-road GPS quantization noise
                totalTurnDegrees += diff
            }
            i += step
        }
        val km = (totalDistanceMeters / 1000.0).coerceAtLeast(0.5)
        val degPerKm = totalTurnDegrees / km
        return (degPerKm / 35.0).coerceIn(1.0, 10.0)
    }

    /**
     * Hybrid Multi-Objective Route Selection:
     * Combines A-Star / Contraction Hierarchies fastest travel time with Curvature Sinuosity and Time-Projected Weather/Night Risk.
     * - In DRY + DAYLIGHT conditions: rewards enjoyable twisty alternatives in HYBRID/TWISTY mode.
     * - In WET / HIGH WIND / FROST / NIGHT conditions: shifts weight to safer, faster, well-lit corridors.
     */
    private fun evaluateRoutesWithHybridAlgorithm(
        routes: List<RouteOption>,
        token: String,
        mode: TravelMode
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val currentWeather = _riderWeather.value ?: buildDefaultTimeTelemetry()
            val isAdverseCondition = currentWeather.gripSeverity >= 1 || !currentWeather.isDaylight
            val minDuration = routes.minOf { it.duration }.coerceAtLeast(1.0)

            var bestRoute = routes.first()
            var bestWarnings = emptyList<WeatherWarning>()
            var bestSeverityLevel = 2
            var bestCompositeScore = -Double.MAX_VALUE

            for (route in routes) {
                val warnings = fetchWeatherForRoute(route.geometry, route.duration, token)
                val severityLevel = when {
                    warnings.any { it.severity == "red" } -> 2
                    warnings.any { it.severity == "yellow" } -> 1
                    else -> 0
                }

                // Normalized efficiency (1.0 is fastest)
                val timeEfficiency = minDuration / route.duration.coerceAtLeast(1.0)
                // Normalized curvature enjoyment (0.1 to 1.0)
                val curvatureFactor = route.curvatureScore / 10.0
                // Weather & Night penalty
                val weatherPenalty = severityLevel * 0.45

                val compositeScore = when {
                    mode == TravelMode.DRIVING -> (1.0 * timeEfficiency) - weatherPenalty
                    mode == TravelMode.TWISTY && !isAdverseCondition -> (0.35 * timeEfficiency) + (0.65 * curvatureFactor) - weatherPenalty
                    // HYBRID mode (or TWISTY during rain/night): dynamically adapts weights to weather & time!
                    isAdverseCondition -> (0.80 * timeEfficiency) - (0.20 * curvatureFactor) - (weatherPenalty * 1.3)
                    else -> (0.55 * timeEfficiency) + (0.45 * curvatureFactor) - weatherPenalty
                }

                if (compositeScore > bestCompositeScore) {
                    bestCompositeScore = compositeScore
                    bestRoute = route
                    bestWarnings = warnings
                    bestSeverityLevel = severityLevel
                }
            }

            _currentRoute.value = bestRoute.geometry
            _distanceToDestination.value = bestRoute.distance
            _etaSeconds.value = bestRoute.duration.toInt()
            _routeCurvatureScore.value = bestRoute.curvatureScore
            _weatherWarnings.value = bestWarnings

            // Automatically save this route while the user has mobile data so it stays available if signal drops!
            val autoName = lastDestinationLabel
                ?: String.format(Locale.US, "Route (%.1f km)", bestRoute.distance / 1000.0)
            autoPersistRouteWhileOnline(
                routeName = autoName,
                waypoints = bestRoute.geometry,
                distanceMeters = bestRoute.distance
            )

            _activeRoutingAlgorithm.value = when {
                mode == TravelMode.HYBRID && isAdverseCondition -> "Hybrid Safety (Wet/Night Adapted)"
                mode == TravelMode.HYBRID -> "Hybrid Curvature + Fast A*"
                mode == TravelMode.TWISTY -> "GraphHopper Curvature Engine"
                else -> "OSRM Contraction Hierarchies"
            }

            _routeColor.value = when (bestSeverityLevel) {
                2 -> Color.RED
                1 -> Color.YELLOW
                else -> Color.parseColor("#FF6B35")
            }
        }
    }

    /**
     * Sends time-projected waypoints (`time_from_start_seconds` interpolated along the route duration)
     * so weather warnings reflect the forecast at the actual hour the rider will reach each segment.
     */
    private fun fetchWeatherForRoute(
        route: List<GeoPoint>,
        totalDurationSeconds: Double,
        token: String
    ): List<WeatherWarning> {
        if (route.isEmpty()) return emptyList()
        try {
            val step = (route.size / 8).coerceAtLeast(1)
            val waypoints = route.mapIndexedNotNull { index, pt ->
                if (index % step == 0 || index == route.lastIndex) {
                    val fraction = index.toDouble() / route.lastIndex.coerceAtLeast(1)
                    val timeOffsetSec = (fraction * totalDurationSeconds).toInt()
                    Waypoint(pt.latitude, pt.longitude, timeOffsetSec)
                } else null
            }
            val request = RouteWeatherRequest(System.currentTimeMillis() / 1000, waypoints)

            val urlString = "${BackendConfig.BASE_URL}/weather/predict-route"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (token.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.doOutput = true

            val jsonRequest = Gson().toJson(request)
            connection.outputStream.use { os ->
                os.write(jsonRequest.toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode == 402) {
                return emptyList()
            }

            if (responseCode == 200) {
                val reader = InputStreamReader(connection.inputStream)
                val response = Gson().fromJson(reader, WeatherWarningsResponse::class.java)
                reader.close()
                connection.disconnect()
                return response.warnings ?: emptyList()
            }
            connection.disconnect()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return emptyList()
    }

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
                val loaded: List<SavedRoute>? = Gson().fromJson(json, type)
                if (!loaded.isNullOrEmpty()) {
                    _savedRoutes.value = loaded
                    return
                }
            }
            // Pre-populate starter offline routes so the user always has loadable routes even without Wi-Fi/data
            _savedRoutes.value = buildDefaultStarterRoutes()
            saveRoutesToFile()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildDefaultStarterRoutes(): List<SavedRoute> {
        return listOf(
            SavedRoute(
                id = "route_starter_snake_pass",
                name = "Snake Pass Mountain Run (A57)",
                waypoints = listOf(
                    GeoPoint(53.4430, -1.9540),
                    GeoPoint(53.4395, -1.9120),
                    GeoPoint(53.4325, -1.8690),
                    GeoPoint(53.4180, -1.8250),
                    GeoPoint(53.4010, -1.7710),
                    GeoPoint(53.3890, -1.7190)
                ),
                totalDistanceMeters = 18_400.0
            ),
            SavedRoute(
                id = "route_starter_box_hill",
                name = "Box Hill Zig-Zag Switchbacks",
                waypoints = listOf(
                    GeoPoint(51.2460, -0.3280),
                    GeoPoint(51.2510, -0.3210),
                    GeoPoint(51.2545, -0.3130),
                    GeoPoint(51.2570, -0.3085),
                    GeoPoint(51.2625, -0.3020)
                ),
                totalDistanceMeters = 6_200.0
            ),
            SavedRoute(
                id = "route_starter_thames",
                name = "London Embankment Express",
                waypoints = listOf(
                    GeoPoint(51.5007, -0.1246),
                    GeoPoint(51.5074, -0.1220),
                    GeoPoint(51.5105, -0.1140),
                    GeoPoint(51.5112, -0.1010),
                    GeoPoint(51.5081, -0.0759)
                ),
                totalDistanceMeters = 4_800.0
            )
        )
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

    /**
     * Windowed O(k) forward search instead of full O(N) scan on every GPS tick
     */
    private fun calculateRemainingDistanceWindowed(currentLocation: GeoPoint, route: List<GeoPoint>): Double {
        if (route.isEmpty()) return 0.0
        val searchStart = (lastClosestRouteIndex - 5).coerceAtLeast(0)
        val searchEnd = (lastClosestRouteIndex + 40).coerceAtMost(route.lastIndex)

        var closestIdx = searchStart
        var minDist = Double.MAX_VALUE
        for (i in searchStart..searchEnd) {
            val d = currentLocation.distanceToAsDouble(route[i])
            if (d < minDist) {
                minDist = d
                closestIdx = i
            }
        }
        // Fallback to full scan if rider deviated far from the search window
        if (minDist > 500.0) {
            closestIdx = route.indices.minByOrNull { currentLocation.distanceToAsDouble(route[it]) } ?: 0
        }
        lastClosestRouteIndex = closestIdx

        var remainingDistance = 0.0
        for (i in closestIdx until route.size - 1) {
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
            val hours = seconds / 3600
            val mins = (seconds % 3600) / 60
            return if (hours > 0) "${hours}h ${mins}m" else "$mins min"
        }
        fun formatElevation(elevation: Double?): String {
            if (elevation == null) return "N/A"
            return "${elevation.toInt()} m"
        }
    }
}
