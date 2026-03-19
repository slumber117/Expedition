package com.expedition.app.features.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data class for trailheads
 */
data class Trailhead(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val description: String
)

/**
 * Manages trailhead operations via the Go Backend
 */
class TrailheadManager {

    private val BACKEND_URL = "http://10.0.2.2/api/v1/trailheads"

    /**
     * Fetch trailheads near a location using GeoHashes on the backend
     */
    suspend fun getNearbyTrailheads(lat: Double, lon: Double): List<Trailhead> = withContext(Dispatchers.IO) {
        try {
            val urlString = "$BACKEND_URL/nearby?lat=$lat&lon=$lon"
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                val results = mutableListOf<Trailhead>()
                
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    results.add(
                        Trailhead(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            lat = obj.getDouble("lat"),
                            lon = obj.getDouble("lon"),
                            description = obj.optString("description", "")
                        )
                    )
                }
                results
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
