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
import kotlin.math.sqrt

/**
 * Manages location tracking with GPS and sensor-based fallback for offline use.
 * When GPS is unavailable, uses accelerometer for velocity estimation.
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
    
    // For sensor-based velocity estimation
    private var lastUpdateTime = 0L
    private var velocityX = 0f
    private var velocityY = 0f
    private var velocityZ = 0f
    private val gravity = FloatArray(3)
    private val linearAcceleration = FloatArray(3)
    private val alpha = 0.8f // Low-pass filter constant
    
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
        
        // Start accelerometer for offline fallback
        startSensorTracking()
    }
    
    private fun startSensorTracking() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accelerometer ->
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }
    
    fun stopTracking() {
        isTracking = false
        locationManager.removeUpdates(this)
        sensorManager.unregisterListener(this)
        
        // Reset velocity
        velocityX = 0f
        velocityY = 0f
        velocityZ = 0f
    }
    
    // LocationListener callbacks
    override fun onLocationChanged(location: Location) {
        _currentLocation.value = location
        lastKnownLocation = location
        _isOfflineMode.value = false
        
        updateSpeedFromLocation(location)
        
        // Reset sensor-based velocity when we get a real GPS fix
        velocityX = 0f
        velocityY = 0f
        velocityZ = 0f
    }
    
    override fun onProviderEnabled(provider: String) {
        _isOfflineMode.value = false
    }
    
    override fun onProviderDisabled(provider: String) {
        // Check if all providers are disabled
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        
        if (!gpsEnabled && !networkEnabled) {
            _isOfflineMode.value = true
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        // Required for older API levels
    }
    
    // SensorEventListener callbacks for offline velocity estimation
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val currentTime = System.currentTimeMillis()
            
            // Apply low-pass filter to isolate gravity
            gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
            gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
            gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
            
            // Remove gravity to get linear acceleration
            linearAcceleration[0] = event.values[0] - gravity[0]
            linearAcceleration[1] = event.values[1] - gravity[1]
            linearAcceleration[2] = event.values[2] - gravity[2]
            
            if (lastUpdateTime != 0L && _isOfflineMode.value) {
                val deltaTime = (currentTime - lastUpdateTime) / 1000f // Convert to seconds
                
                // Integrate acceleration to get velocity (simplified)
                // Apply damping to prevent drift
                val damping = 0.98f
                velocityX = (velocityX + linearAcceleration[0] * deltaTime) * damping
                velocityY = (velocityY + linearAcceleration[1] * deltaTime) * damping
                velocityZ = (velocityZ + linearAcceleration[2] * deltaTime) * damping
                
                // Calculate speed magnitude
                val speedMs = sqrt(velocityX * velocityX + velocityY * velocityY + velocityZ * velocityZ)
                
                // Filter out noise (speeds below threshold are likely noise)
                val filteredSpeedMs = if (speedMs < 0.5f) 0f else speedMs
                
                // Convert m/s to km/h
                _speedKmh.value = filteredSpeedMs * 3.6f
            }
            
            lastUpdateTime = currentTime
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for our use case
    }
    
    private fun updateSpeedFromLocation(location: Location) {
        if (location.hasSpeed()) {
            // GPS provides speed in m/s, convert to km/h
            _speedKmh.value = location.speed * 3.6f
        }
    }
}
