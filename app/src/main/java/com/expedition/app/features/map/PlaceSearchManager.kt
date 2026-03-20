package com.expedition.app.features.map

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

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
 * Manages place search using the Go Backend proxy.
 */
class PlaceSearchManager {

    private val BACKEND_SEARCH_URL = "${BackendConfig.BASE_URL}/search" 
    
    /**
     * Search for a place by query string via Go Backend
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val urlString = "$BACKEND_SEARCH_URL?q=$encodedQuery"
            Log.d("PlaceSearch", "Searching: $urlString")
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "ExpeditionApp")
                connectTimeout = 10000 // 10 second timeout
                readTimeout = 10000
                doInput = true
            }
            
            val responseCode = connection.responseCode
            Log.d("PlaceSearch", "Response Code: $responseCode")
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d("PlaceSearch", "Response: $response")
                
                val jsonArray = JSONArray(response)
                val results = mutableListOf<SearchResult>()
                
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val lat = obj.optString("lat").toDoubleOrNull() ?: 0.0
                    val lon = obj.optString("lon").toDoubleOrNull() ?: 0.0
                    
                    results.add(
                        SearchResult(
                            displayName = obj.optString("display_name", "Unknown"),
                            latitude = lat,
                            longitude = lon,
                            type = obj.optString("type", "location")
                        )
                    )
                }
                results
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e("PlaceSearch", "Error Response: $error")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("PlaceSearch", "Exception during search", e)
            emptyList()
        }
    }
}
