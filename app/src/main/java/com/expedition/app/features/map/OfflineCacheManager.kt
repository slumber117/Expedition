package com.expedition.app.features.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.io.File

/**
 * Manages offline map tile caching for use without internet connection
 */
class OfflineCacheManager(private val context: Context) {
    
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()
    
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()
    
    private val _cachedRegions = MutableStateFlow<List<CachedRegion>>(emptyList())
    val cachedRegions: StateFlow<List<CachedRegion>> = _cachedRegions.asStateFlow()
    
    private val cacheDir = File(context.filesDir, "offline_tiles")
    private val regionsFile = File(context.filesDir, "cached_regions.txt")
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        loadCachedRegions()
    }
    
    /**
     * Data class for a cached map region
     */
    data class CachedRegion(
        val name: String,
        val centerLat: Double,
        val centerLon: Double,
        val radiusKm: Double,
        val minZoom: Int,
        val maxZoom: Int,
        val tileCount: Int,
        val sizeBytes: Long,
        val downloadedAt: Long = System.currentTimeMillis()
    )
    
    /**
     * Download tiles for a region around a center point
     */
    suspend fun downloadRegion(
        mapView: MapView,
        center: GeoPoint,
        radiusKm: Double,
        name: String,
        minZoom: Int = 10,
        maxZoom: Int = 17
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            _isDownloading.value = true
            _downloadProgress.value = 0
            
            // Calculate bounding box from center and radius
            val boundingBox = calculateBoundingBox(center, radiusKm)
            
            // Create cache manager
            val cacheManager = CacheManager(mapView)
            
            // Calculate tile count for progress estimation
            val tileCount = cacheManager.possibleTilesInArea(boundingBox, minZoom, maxZoom)
            
            // Download tiles with progress callback
            var downloadedTiles = 0
            
            cacheManager.downloadAreaAsync(
                context,
                boundingBox,
                minZoom,
                maxZoom,
                object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() {
                        _downloadProgress.value = 100
                        _isDownloading.value = false
                    }
                    
                    override fun onTaskFailed(errors: Int) {
                        _isDownloading.value = false
                    }
                    
                    override fun updateProgress(
                        progress: Int,
                        currentZoomLevel: Int,
                        zoomMin: Int,
                        zoomMax: Int
                    ) {
                        downloadedTiles++
                        val progressPercent = ((downloadedTiles.toFloat() / tileCount) * 100).toInt()
                        _downloadProgress.value = progressPercent.coerceIn(0, 100)
                    }
                    
                    override fun downloadStarted() {
                        _downloadProgress.value = 0
                    }
                    
                    override fun setPossibleTilesInArea(total: Int) {
                        // Total tiles to download
                    }
                }
            )
            
            // Save region info
            val region = CachedRegion(
                name = name,
                centerLat = center.latitude,
                centerLon = center.longitude,
                radiusKm = radiusKm,
                minZoom = minZoom,
                maxZoom = maxZoom,
                tileCount = tileCount,
                sizeBytes = estimateTileSize(tileCount)
            )
            
            val regions = _cachedRegions.value.toMutableList()
            regions.add(region)
            _cachedRegions.value = regions
            saveCachedRegions()
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            _isDownloading.value = false
            false
        }
    }
    
    /**
     * Download tiles along a route for offline navigation
     */
    suspend fun downloadRouteArea(
        mapView: MapView,
        waypoints: List<GeoPoint>,
        name: String,
        bufferKm: Double = 2.0, // Buffer around route
        minZoom: Int = 12,
        maxZoom: Int = 17
    ): Boolean = withContext(Dispatchers.IO) {
        if (waypoints.isEmpty()) return@withContext false
        
        try {
            _isDownloading.value = true
            _downloadProgress.value = 0
            
            // Calculate bounding box that encompasses the entire route with buffer
            var minLat = Double.MAX_VALUE
            var maxLat = Double.MIN_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = Double.MIN_VALUE
            
            for (point in waypoints) {
                minLat = minOf(minLat, point.latitude)
                maxLat = maxOf(maxLat, point.latitude)
                minLon = minOf(minLon, point.longitude)
                maxLon = maxOf(maxLon, point.longitude)
            }
            
            // Add buffer
            val latBuffer = bufferKm / 111.0 // Approximate degrees
            val lonBuffer = bufferKm / (111.0 * kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2)))
            
            val boundingBox = BoundingBox(
                maxLat + latBuffer,
                maxLon + lonBuffer,
                minLat - latBuffer,
                minLon - lonBuffer
            )
            
            val cacheManager = CacheManager(mapView)
            val tileCount = cacheManager.possibleTilesInArea(boundingBox, minZoom, maxZoom)
            
            var downloadedTiles = 0
            
            cacheManager.downloadAreaAsync(
                context,
                boundingBox,
                minZoom,
                maxZoom,
                object : CacheManager.CacheManagerCallback {
                    override fun onTaskComplete() {
                        _downloadProgress.value = 100
                        _isDownloading.value = false
                    }
                    
                    override fun onTaskFailed(errors: Int) {
                        _isDownloading.value = false
                    }
                    
                    override fun updateProgress(
                        progress: Int,
                        currentZoomLevel: Int,
                        zoomMin: Int,
                        zoomMax: Int
                    ) {
                        downloadedTiles++
                        val progressPercent = ((downloadedTiles.toFloat() / tileCount) * 100).toInt()
                        _downloadProgress.value = progressPercent.coerceIn(0, 100)
                    }
                    
                    override fun downloadStarted() {
                        _downloadProgress.value = 0
                    }
                    
                    override fun setPossibleTilesInArea(total: Int) {}
                }
            )
            
            // Calculate center of route
            val centerLat = (minLat + maxLat) / 2
            val centerLon = (minLon + maxLon) / 2
            
            val region = CachedRegion(
                name = name,
                centerLat = centerLat,
                centerLon = centerLon,
                radiusKm = bufferKm,
                minZoom = minZoom,
                maxZoom = maxZoom,
                tileCount = tileCount,
                sizeBytes = estimateTileSize(tileCount)
            )
            
            val regions = _cachedRegions.value.toMutableList()
            regions.add(region)
            _cachedRegions.value = regions
            saveCachedRegions()
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            _isDownloading.value = false
            false
        }
    }
    
    /**
     * Clear all cached tiles
     */
    fun clearCache() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        _cachedRegions.value = emptyList()
        saveCachedRegions()
    }
    
    /**
     * Delete a specific cached region
     */
    fun deleteRegion(regionName: String) {
        val regions = _cachedRegions.value.filter { it.name != regionName }
        _cachedRegions.value = regions
        saveCachedRegions()
    }
    
    /**
     * Get total cache size in bytes
     */
    fun getTotalCacheSize(): Long {
        return _cachedRegions.value.sumOf { it.sizeBytes }
    }
    
    /**
     * Calculate bounding box from center point and radius
     */
    private fun calculateBoundingBox(center: GeoPoint, radiusKm: Double): BoundingBox {
        // Approximate conversion: 1 degree latitude ≈ 111 km
        val latDelta = radiusKm / 111.0
        // Longitude varies with latitude
        val lonDelta = radiusKm / (111.0 * kotlin.math.cos(Math.toRadians(center.latitude)))
        
        return BoundingBox(
            center.latitude + latDelta,  // North
            center.longitude + lonDelta, // East
            center.latitude - latDelta,  // South
            center.longitude - lonDelta  // West
        )
    }
    
    /**
     * Estimate tile download size (rough estimate: ~15KB per tile on average)
     */
    private fun estimateTileSize(tileCount: Int): Long {
        return tileCount * 15_000L
    }
    
    private fun saveCachedRegions() {
        try {
            val lines = _cachedRegions.value.map { region ->
                "${region.name}|${region.centerLat}|${region.centerLon}|${region.radiusKm}|${region.minZoom}|${region.maxZoom}|${region.tileCount}|${region.sizeBytes}|${region.downloadedAt}"
            }
            regionsFile.writeText(lines.joinToString("\n"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun loadCachedRegions() {
        try {
            if (regionsFile.exists()) {
                val lines = regionsFile.readLines()
                _cachedRegions.value = lines.mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 9) {
                        CachedRegion(
                            name = parts[0],
                            centerLat = parts[1].toDouble(),
                            centerLon = parts[2].toDouble(),
                            radiusKm = parts[3].toDouble(),
                            minZoom = parts[4].toInt(),
                            maxZoom = parts[5].toInt(),
                            tileCount = parts[6].toInt(),
                            sizeBytes = parts[7].toLong(),
                            downloadedAt = parts[8].toLong()
                        )
                    } else null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
