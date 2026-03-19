package com.expedition.app.features.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * Manages location tracking with GPS and sensor-based fallback for offline use.
 * Utilizes Accelerometer, Gyroscope, and Barometer for dead reckoning and environmental awareness.
 */
class LocationTracker(private val context: Context) : SensorEventListener, LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val _currentLocation = MutableStateFlow<Location?>(null)
    val currentLocation: StateFlow<Location?> = _currentLocation.asStateFlow()
    
    private val _speedKmh = MutableStateFlow(0f)
    val speedKmh: StateFlow<Float> = _speedKmh.asStateFlow()
    
    private val _isOfflineMode = MutableStateFlow(false)
    val isOfflineMode: StateFlow<Boolean> = _isOfflineMode.asStateFlow()

    private val _steps = MutableStateFlow(0)
    val steps: StateFlow<Int> = _steps.asStateFlow()

    private val _estimatedHeading = MutableStateFlow(0f) // Degrees from North
    val estimatedHeading: StateFlow<Float> = _estimatedHeading.asStateFlow()

    private val _barometricAltitude = MutableStateFlow<Double?>(null)
    val barometricAltitude: StateFlow<Double?> = _barometricAltitude.asStateFlow()
    
    // For sensor-based velocity estimation
    private var lastUpdateTime = 0L
    private var velocityX = 0f
    private var velocityY = 0f
    private var velocityZ = 0f
    private val gravity = FloatArray(3)
    private val linearAcceleration = FloatArray(3)
    private val alpha = 0.8f // Low-pass filter constant
    
    // For Heading estimation
    private val magnetValues = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastGyroTime = 0L

    // For Step Detection
    private var accelPeakThreshold = 12.0f // m/s^2
    private var stepCooldown = 300L // ms
    private var lastStepTime = 0L

    private var lastKnownLocation: Location? = null
    private var isTracking = false

    fun startTracking() {
        if (isTracking) return
        isTracking = true
        
        // Check permissions
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
            == PackageManager.PERMISSION_GRANTED) {
            
            // Request GPS updates
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, // Update every 1 second
                    1f,    // Or every 1 meter
                    this
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Also try network provider as backup
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    1f,
                    this
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Get last known location immediately
            try {
                val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                
                val bestLocation = when {
                    lastGps != null && lastNetwork != null -> 
                        if (lastGps.time > lastNetwork.time) lastGps else lastNetwork
                    lastGps != null -> lastGps
                    else -> lastNetwork
                }
                
                bestLocation?.let {
                    _currentLocation.value = it
                    lastKnownLocation = it
                    updateSpeedFromLocation(it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        startSensorTracking()
    }
    
    private fun startSensorTracking() {
        val sensors = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE
        )
        
        sensors.forEach { type ->
            sensorManager.getDefaultSensor(type)?.let { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }
    
    fun stopTracking() {
        isTracking = false
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        
        // Reset state
        velocityX = 0f
        velocityY = 0f
        velocityZ = 0f
        lastUpdateTime = 0L
        lastGyroTime = 0L
    }
    
    // LocationListener callbacks
    override fun onLocationChanged(location: Location) {
        _currentLocation.value = location
        lastKnownLocation = location
        _isOfflineMode.value = false
        
        updateSpeedFromLocation(location)
        
        // Reset sensor-based velocity/dead reckoning offset when we get a real GPS fix
        velocityX = 0f
        velocityY = 0f
        velocityZ = 0f
        
        if (location.hasBearing()) {
            _estimatedHeading.value = location.bearing
        }
    }
    
    override fun onProviderEnabled(provider: String) {
        _isOfflineMode.value = false
    }
    
    override fun onProviderDisabled(provider: String) {
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gpsEnabled && !networkEnabled) {
            _isOfflineMode.value = true
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    
    // SensorEventListener callbacks
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val currentTime = System.currentTimeMillis()

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                handleAccelerometer(event, currentTime)
            }
            Sensor.TYPE_GYROSCOPE -> {
                handleGyroscope(event, currentTime)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetValues, 0, 3)
                updateOrientation()
            }
            Sensor.TYPE_PRESSURE -> {
                handlePressure(event)
            }
        }
    }

    private fun handleAccelerometer(event: SensorEvent, currentTime: Long) {
        // Low-pass filter to isolate gravity
        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
        
        // Remove gravity to get linear acceleration
        linearAcceleration[0] = event.values[0] - gravity[0]
        linearAcceleration[1] = event.values[1] - gravity[1]
        linearAcceleration[2] = event.values[2] - gravity[2]

        val mag = sqrt(event.values[0].pow(2) + event.values[1].pow(2) + event.values[2].pow(2))
        
        // Step detection
        if (mag > accelPeakThreshold && currentTime - lastStepTime > stepCooldown) {
            _steps.value += 1
            lastStepTime = currentTime
        }

        // Dead Reckoning (Position Update)
        if (lastUpdateTime != 0L && _isOfflineMode.value) {
            val deltaTime = (currentTime - lastUpdateTime) / 1000f
            
            // Estimate speed from linear acceleration
            val damping = 0.95f
            velocityX = (velocityX + linearAcceleration[0] * deltaTime) * damping
            velocityY = (velocityY + linearAcceleration[1] * deltaTime) * damping
            velocityZ = (velocityZ + linearAcceleration[2] * deltaTime) * damping
            
            val speedMs = sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ)
            val filteredSpeedMs = if (speedMs < 0.3f) 0f else speedMs
            _speedKmh.value = filteredSpeedMs * 3.6f

            // Update virtual location based on speed and heading
            if (filteredSpeedMs > 0) {
                performDeadReckoningUpdate(filteredSpeedMs, deltaTime)
            }
        }
        lastUpdateTime = currentTime
    }

    private fun handleGyroscope(event: SensorEvent, currentTime: Long) {
        if (lastGyroTime != 0L) {
            val dt = (currentTime - lastGyroTime) / 1000f
            // Simple integration of Z-axis rotation for heading refinement
            val rotationZ = Math.toDegrees(event.values[2].toDouble()).toFloat() * dt
            _estimatedHeading.value = (_estimatedHeading.value - rotationZ + 360) % 360
        }
        lastGyroTime = currentTime
    }

    private fun handlePressure(event: SensorEvent) {
        val pressure = event.values[0]
        // Standard formula for altitude from pressure: h = 44330 * (1 - (p/p0)^(1/5.255))
        val altitude = 44330.0 * (1.0 - (pressure / 1013.25).pow(1.0 / 5.255))
        _barometricAltitude.value = altitude
    }

    private fun updateOrientation() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetValues)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            // azimuth is orientationAngles[0] in radians
            val azimuthDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            // We use a complementary-like approach: trust GPS/Gyro, but drift-correct with Mag
            val alphaMag = 0.05f // Low weight for magnetometer to avoid jitter
            _estimatedHeading.value = (1 - alphaMag) * _estimatedHeading.value + alphaMag * ((azimuthDegrees + 360) % 360)
        }
    }

    private fun performDeadReckoningUpdate(speedMs: Float, dt: Float) {
        val currentLoc = _currentLocation.value ?: return
        val headingRad = Math.toRadians(_estimatedHeading.value.toDouble())
        
        val distance = speedMs * dt
        val earthRadius = 6371000.0 // meters

        val deltaLat = (distance * cos(headingRad)) / earthRadius
        val deltaLon = (distance * sin(headingRad)) / (earthRadius * cos(Math.toRadians(currentLoc.latitude)))

        val newLocation = Location("dead_reckoning").apply {
            latitude = currentLoc.latitude + Math.toDegrees(deltaLat)
            longitude = currentLoc.longitude + Math.toDegrees(deltaLon)
            time = System.currentTimeMillis()
            speed = speedMs
            bearing = _estimatedHeading.value
            accuracy = 50f // Lower accuracy for estimated positions
        }
        _currentLocation.value = newLocation
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    
    private fun updateSpeedFromLocation(location: Location) {
        if (location.hasSpeed()) {
            _speedKmh.value = location.speed * 3.6f
        }
    }
}
