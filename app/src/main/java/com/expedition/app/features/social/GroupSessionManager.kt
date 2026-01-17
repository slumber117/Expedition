package com.expedition.app.features.social

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.util.GeoPoint
import java.io.File
import java.util.UUID

/**
 * Represents a friend/contact in the app
 */
data class Friend(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val status: FriendStatus = FriendStatus.OFFLINE,
    val lastKnownLocation: GeoPoint? = null,
    val currentSpeed: Float = 0f,
    val lastUpdated: Long = System.currentTimeMillis()
)

enum class FriendStatus {
    ONLINE,
    RIDING,
    OFFLINE,
    IN_SESSION
}

/**
 * Represents a group riding session
 */
data class GroupSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val destination: GeoPoint,
    val destinationName: String,
    val creatorId: String,
    val memberIds: MutableList<String> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = true
)

/**
 * Manages friends and group sessions
 */
class GroupSessionManager(private val context: Context) {
    
    private val gson = Gson()
    private val friendsFile = File(context.filesDir, "friends.json")
    private val sessionsFile = File(context.filesDir, "sessions.json")
    
    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()
    
    private val _sessions = MutableStateFlow<List<GroupSession>>(emptyList())
    val sessions: StateFlow<List<GroupSession>> = _sessions.asStateFlow()
    
    private val _currentSession = MutableStateFlow<GroupSession?>(null)
    val currentSession: StateFlow<GroupSession?> = _currentSession.asStateFlow()
    
    // Current user ID (in production, this would come from auth)
    private val currentUserId = "current_user"
    
    init {
        loadFriends()
        loadSessions()
        
        // Add some demo friends if empty
        if (_friends.value.isEmpty()) {
            addDemoFriends()
        }
    }
    
    /**
     * Add a new friend
     */
    fun addFriend(name: String): Friend {
        val friend = Friend(
            name = name,
            status = FriendStatus.OFFLINE
        )
        
        val updated = _friends.value.toMutableList()
        updated.add(friend)
        _friends.value = updated
        saveFriends()
        
        return friend
    }
    
    /**
     * Remove a friend
     */
    fun removeFriend(friendId: String) {
        val updated = _friends.value.filter { it.id != friendId }
        _friends.value = updated
        saveFriends()
    }
    
    /**
     * Update friend's location and status (would be called via real-time sync in production)
     */
    fun updateFriendLocation(friendId: String, location: GeoPoint, speed: Float, status: FriendStatus) {
        val updated = _friends.value.map { friend ->
            if (friend.id == friendId) {
                friend.copy(
                    lastKnownLocation = location,
                    currentSpeed = speed,
                    status = status,
                    lastUpdated = System.currentTimeMillis()
                )
            } else friend
        }
        _friends.value = updated
    }
    
    /**
     * Create a new group session
     */
    fun createSession(
        name: String,
        destination: GeoPoint,
        destinationName: String,
        invitedFriendIds: List<String> = emptyList()
    ): GroupSession {
        val session = GroupSession(
            name = name,
            destination = destination,
            destinationName = destinationName,
            creatorId = currentUserId,
            memberIds = (listOf(currentUserId) + invitedFriendIds).toMutableList()
        )
        
        val updated = _sessions.value.toMutableList()
        updated.add(session)
        _sessions.value = updated
        _currentSession.value = session
        
        // Update friend statuses
        invitedFriendIds.forEach { friendId ->
            updateFriendStatus(friendId, FriendStatus.IN_SESSION)
        }
        
        saveSessions()
        return session
    }
    
    /**
     * Join an existing session
     */
    fun joinSession(sessionId: String): Boolean {
        val session = _sessions.value.find { it.id == sessionId } ?: return false
        
        if (!session.memberIds.contains(currentUserId)) {
            session.memberIds.add(currentUserId)
        }
        
        _currentSession.value = session
        saveSessions()
        return true
    }
    
    /**
     * Leave current session
     */
    fun leaveSession() {
        _currentSession.value?.let { session ->
            session.memberIds.remove(currentUserId)
            
            // If no members left, deactivate session
            if (session.memberIds.isEmpty()) {
                val updated = _sessions.value.map {
                    if (it.id == session.id) it.copy(isActive = false) else it
                }
                _sessions.value = updated
            }
            
            _currentSession.value = null
            saveSessions()
        }
    }
    
    /**
     * End session (creator only)
     */
    fun endSession(sessionId: String) {
        val updated = _sessions.value.map { session ->
            if (session.id == sessionId && session.creatorId == currentUserId) {
                session.copy(isActive = false)
            } else session
        }
        _sessions.value = updated
        
        if (_currentSession.value?.id == sessionId) {
            _currentSession.value = null
        }
        
        saveSessions()
    }
    
    /**
     * Get active sessions I'm part of or invited to
     */
    fun getActiveSessions(): List<GroupSession> {
        return _sessions.value.filter { it.isActive }
    }
    
    /**
     * Get friends by status
     */
    fun getFriendsByStatus(status: FriendStatus): List<Friend> {
        return _friends.value.filter { it.status == status }
    }
    
    /**
     * Get online friends (online or riding)
     */
    fun getOnlineFriends(): List<Friend> {
        return _friends.value.filter { 
            it.status == FriendStatus.ONLINE || 
            it.status == FriendStatus.RIDING ||
            it.status == FriendStatus.IN_SESSION
        }
    }
    
    /**
     * Invite friend to current session
     */
    fun inviteFriendToSession(friendId: String) {
        _currentSession.value?.let { session ->
            if (!session.memberIds.contains(friendId)) {
                session.memberIds.add(friendId)
                updateFriendStatus(friendId, FriendStatus.IN_SESSION)
                saveSessions()
            }
        }
    }
    
    private fun updateFriendStatus(friendId: String, status: FriendStatus) {
        val updated = _friends.value.map { friend ->
            if (friend.id == friendId) friend.copy(status = status) else friend
        }
        _friends.value = updated
        saveFriends()
    }
    
    private fun addDemoFriends() {
        val demoFriends = listOf(
            Friend(name = "Alice", status = FriendStatus.RIDING),
            Friend(name = "Bob", status = FriendStatus.OFFLINE),
            Friend(name = "Charlie", status = FriendStatus.ONLINE),
            Friend(name = "Diana", status = FriendStatus.IN_SESSION)
        )
        _friends.value = demoFriends
        saveFriends()
    }
    
    private fun saveFriends() {
        try {
            val serializableFriends = _friends.value.map { friend ->
                mapOf(
                    "id" to friend.id,
                    "name" to friend.name,
                    "status" to friend.status.name,
                    "lastKnownLat" to friend.lastKnownLocation?.latitude,
                    "lastKnownLon" to friend.lastKnownLocation?.longitude,
                    "currentSpeed" to friend.currentSpeed,
                    "lastUpdated" to friend.lastUpdated
                )
            }
            friendsFile.writeText(gson.toJson(serializableFriends))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun loadFriends() {
        try {
            if (friendsFile.exists()) {
                val json = friendsFile.readText()
                val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
                val rawFriends: List<Map<String, Any?>> = gson.fromJson(json, type)
                
                _friends.value = rawFriends.map { raw ->
                    val lat = raw["lastKnownLat"] as? Double
                    val lon = raw["lastKnownLon"] as? Double
                    
                    Friend(
                        id = raw["id"] as String,
                        name = raw["name"] as String,
                        status = FriendStatus.valueOf(raw["status"] as String),
                        lastKnownLocation = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
                        currentSpeed = (raw["currentSpeed"] as? Number)?.toFloat() ?: 0f,
                        lastUpdated = (raw["lastUpdated"] as? Number)?.toLong() ?: System.currentTimeMillis()
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun saveSessions() {
        try {
            val serializableSessions = _sessions.value.map { session ->
                mapOf(
                    "id" to session.id,
                    "name" to session.name,
                    "destLat" to session.destination.latitude,
                    "destLon" to session.destination.longitude,
                    "destinationName" to session.destinationName,
                    "creatorId" to session.creatorId,
                    "memberIds" to session.memberIds,
                    "createdAt" to session.createdAt,
                    "isActive" to session.isActive
                )
            }
            sessionsFile.writeText(gson.toJson(serializableSessions))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun loadSessions() {
        try {
            if (sessionsFile.exists()) {
                val json = sessionsFile.readText()
                val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
                val rawSessions: List<Map<String, Any?>> = gson.fromJson(json, type)
                
                _sessions.value = rawSessions.map { raw ->
                    @Suppress("UNCHECKED_CAST")
                    GroupSession(
                        id = raw["id"] as String,
                        name = raw["name"] as String,
                        destination = GeoPoint(
                            raw["destLat"] as Double,
                            raw["destLon"] as Double
                        ),
                        destinationName = raw["destinationName"] as String,
                        creatorId = raw["creatorId"] as String,
                        memberIds = (raw["memberIds"] as List<String>).toMutableList(),
                        createdAt = (raw["createdAt"] as Number).toLong(),
                        isActive = raw["isActive"] as Boolean
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
