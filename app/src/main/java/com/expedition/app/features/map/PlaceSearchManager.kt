package com.expedition.app.features.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Data class for search results
 */
data class SearchResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val type: String = ""
)

/**
 * Manages place search using Nominatim (OpenStreetMap Geocoding)
 */
class PlaceSearchManager {
    
    /**
     * Search for a place by query string
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "https://nominatim.openstreetmap.org/search?format=json&q=$encodedQuery&limit=5"
            val url = URL(urlString)
            
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ExpeditionApp")
                doInput = true
            }
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                val results = mutableListOf<SearchResult>()
                
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    results.add(
                        SearchResult(
                            displayName = obj.getString("display_name"),
                            latitude = obj.getDouble("lat"),
                            longitude = obj.getDouble("lon"),
                            type = obj.optString("type", "")
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
