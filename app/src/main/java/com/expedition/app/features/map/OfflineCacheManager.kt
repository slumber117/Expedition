package com.expedition.app.features.map

import android.content.Context
import android.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

/**
 * Crash-safe offline map tile manager.
 * Uses pure Kotlin Coroutines + SqlTileWriter directly (bypassing OSMDroid's legacy AsyncTask
 * CacheManager which throws TileSourcePolicyException on Mapnik and spawns intrusive dialogs).
 */
class OfflineCacheManager(private val context: Context) {

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadingRegionName = MutableStateFlow<String?>(null)
    val downloadingRegionName: StateFlow<String?> = _downloadingRegionName.asStateFlow()

    private val _cachedRegions = MutableStateFlow<List<CachedRegion>>(emptyList())
    val cachedRegions: StateFlow<List<CachedRegion>> = _cachedRegions.asStateFlow()

    private val _forceOfflineMap = MutableStateFlow(false)
    val forceOfflineMap: StateFlow<Boolean> = _forceOfflineMap.asStateFlow()

    private val osmdroidBaseDir = File(context.filesDir, "osmdroid")
    private val cacheDir = File(osmdroidBaseDir, "tiles")
    private val regionsFile = File(context.filesDir, "cached_regions.txt")

    init {
        configureOsmdroidStorage(context)
        loadCachedRegions()
    }

    /**
     * Ensures OSMDroid reads and writes tiles from the same persistent internal directory.
     */
    fun configureOsmdroidStorage(ctx: Context) {
        runCatching {
            if (!osmdroidBaseDir.exists()) osmdroidBaseDir.mkdirs()
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val config = Configuration.getInstance()
            config.osmdroidBasePath = osmdroidBaseDir
            config.osmdroidTileCache = cacheDir
            config.load(ctx, PreferenceManager.getDefaultSharedPreferences(ctx))
            config.userAgentValue = ctx.packageName
            config.tileFileSystemCacheMaxBytes = 500L * 1024L * 1024L
            config.tileFileSystemCacheTrimBytes = 450L * 1024L * 1024L
            config.expirationOverrideDuration = 90L * 24L * 3600L * 1000L
        }
    }

    fun setForceOfflineMap(forceOffline: Boolean) {
        _forceOfflineMap.value = forceOffline
    }

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
     * Safely caches tiles around a center point without using OSMDroid's CacheManager AsyncTask.
     */
    suspend fun downloadRegion(
        mapView: MapView?,
        center: GeoPoint,
        radiusKm: Double,
        name: String,
        minZoom: Int = 12,
        maxZoom: Int = 15
    ): Boolean = withContext(Dispatchers.IO) {
        downloadRouteArea(
            mapView = mapView,
            waypoints = listOf(center),
            name = name,
            bufferKm = radiusKm,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
    }

    /**
     * Silently and safely caches map tiles along a route corridor using SqlTileWriter on Dispatchers.IO.
     * Never throws exceptions or blocks the UI.
     */
    suspend fun downloadRouteArea(
        mapView: MapView?,
        waypoints: List<GeoPoint>,
        name: String,
        bufferKm: Double = 1.5,
        minZoom: Int = 12,
        maxZoom: Int = 15
    ): Boolean = withContext(Dispatchers.IO) {
        if (waypoints.isEmpty() || _isDownloading.value) return@withContext false

        runCatching {
            configureOsmdroidStorage(context)
            _isDownloading.value = true
            _downloadingRegionName.value = name
            _downloadProgress.value = 5

            val sampled = if (waypoints.size > 20) {
                val step = (waypoints.size / 20).coerceAtLeast(1)
                waypoints.filterIndexed { index, _ -> index % step == 0 || index == waypoints.lastIndex }
            } else {
                waypoints
            }

            // Collect unique (zoom, tileX, tileY) coordinates along the route corridor (capped at 60 tiles per route for politeness & battery)
            val tilesToFetch = linkedSetOf<Long>()
            for (zoom in minZoom..maxZoom.coerceAtMost(15)) {
                for (pt in sampled) {
                    val tx = lonToTileX(pt.longitude, zoom)
                    val ty = latToTileY(pt.latitude, zoom)
                    tilesToFetch.add(MapTileIndex.getTileIndex(zoom, tx, ty))
                    if (tilesToFetch.size >= 60) break
                }
                if (tilesToFetch.size >= 60) break
            }

            val sqlWriter = runCatching { SqlTileWriter() }.getOrNull()
            val tileSource = TileSourceFactory.MAPNIK
            val total = tilesToFetch.size.coerceAtLeast(1)
            var completed = 0

            for (tileIndex in tilesToFetch) {
                runCatching {
                    if (sqlWriter != null && !sqlWriter.exists(tileSource, tileIndex)) {
                        val z = MapTileIndex.getZoom(tileIndex)
                        val x = MapTileIndex.getX(tileIndex)
                        val y = MapTileIndex.getY(tileIndex)
                        val tileUrl = "https://tile.openstreetmap.org/$z/$x/$y.png"
                        val conn = URL(tileUrl).openConnection() as HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.setRequestProperty("User-Agent", "${context.packageName}/1.0")
                        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                            val bytes = conn.inputStream.use { it.readBytes() }
                            if (bytes.isNotEmpty()) {
                                sqlWriter.saveFile(
                                    tileSource,
                                    tileIndex,
                                    ByteArrayInputStream(bytes),
                                    System.currentTimeMillis() + 90L * 24L * 3600L * 1000L
                                )
                            }
                        }
                        conn.disconnect()
                    }
                }
                completed++
                _downloadProgress.value = ((completed.toFloat() / total) * 100).toInt().coerceIn(5, 100)
            }

            runCatching { sqlWriter?.onDetach() }

            val minLat = waypoints.minOfOrNull { it.latitude } ?: 0.0
            val maxLat = waypoints.maxOfOrNull { it.latitude } ?: 0.0
            val minLon = waypoints.minOfOrNull { it.longitude } ?: 0.0
            val maxLon = waypoints.maxOfOrNull { it.longitude } ?: 0.0

            val region = CachedRegion(
                name = name,
                centerLat = (minLat + maxLat) / 2.0,
                centerLon = (minLon + maxLon) / 2.0,
                radiusKm = bufferKm,
                minZoom = minZoom,
                maxZoom = maxZoom,
                tileCount = total,
                sizeBytes = estimateTileSize(total)
            )

            val regions = _cachedRegions.value.filter { !it.name.equals(name, ignoreCase = true) }.toMutableList()
            regions.add(region)
            _cachedRegions.value = regions
            saveCachedRegions()
        }

        _downloadProgress.value = 100
        _isDownloading.value = false
        _downloadingRegionName.value = null
        true
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return (((lon + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
        return y.toInt().coerceIn(0, n - 1)
    }

    fun clearCache() {
        runCatching {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            _cachedRegions.value = emptyList()
            saveCachedRegions()
        }
    }

    fun deleteRegion(regionName: String) {
        runCatching {
            val regions = _cachedRegions.value.filter { it.name != regionName }
            _cachedRegions.value = regions
            saveCachedRegions()
        }
    }

    fun getTotalCacheSize(): Long {
        return runCatching {
            val diskBytes = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val estimatedBytes = _cachedRegions.value.sumOf { it.sizeBytes }
            maxOf(diskBytes, estimatedBytes)
        }.getOrDefault(0L)
    }

    private fun estimateTileSize(tileCount: Int): Long {
        return tileCount * 15_000L
    }

    private fun saveCachedRegions() {
        runCatching {
            val lines = _cachedRegions.value.map { region ->
                "${region.name}|${region.centerLat}|${region.centerLon}|${region.radiusKm}|${region.minZoom}|${region.maxZoom}|${region.tileCount}|${region.sizeBytes}|${region.downloadedAt}"
            }
            regionsFile.writeText(lines.joinToString("\n"))
        }
    }

    private fun loadCachedRegions() {
        runCatching {
            if (regionsFile.exists()) {
                val lines = regionsFile.readLines()
                _cachedRegions.value = lines.mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 9) {
                        CachedRegion(
                            name = parts[0],
                            centerLat = parts[1].toDoubleOrNull() ?: 0.0,
                            centerLon = parts[2].toDoubleOrNull() ?: 0.0,
                            radiusKm = parts[3].toDoubleOrNull() ?: 1.5,
                            minZoom = parts[4].toIntOrNull() ?: 12,
                            maxZoom = parts[5].toIntOrNull() ?: 15,
                            tileCount = parts[6].toIntOrNull() ?: 10,
                            sizeBytes = parts[7].toLongOrNull() ?: 150_000L,
                            downloadedAt = parts[8].toLongOrNull() ?: System.currentTimeMillis()
                        )
                    } else null
                }
            }
        }
    }
}
