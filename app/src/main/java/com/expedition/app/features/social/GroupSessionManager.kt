package com.expedition.app.features.social

import android.content.Context
import com.expedition.app.features.auth.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.util.GeoPoint
import java.util.UUID

/**
 * Represents a friend/contact in the app (UI Model)
 */
data class Friend(
    val id: String = "",
    val name: String = "",
    val status: FriendStatus = FriendStatus.OFFLINE,
    val lastKnownLat: Double? = null,
    val lastKnownLon: Double? = null,
    val currentSpeed: Float = 0f,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    val lastKnownLocation: GeoPoint? get() = if (lastKnownLat != null && lastKnownLon != null) GeoPoint(lastKnownLat, lastKnownLon) else null
}

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
    val id: String = "",
    val name: String = "",
    val destLat: Double = 0.0,
    val destLon: Double = 0.0,
    val destinationName: String = "",
    val creatorId: String = "",
    val memberIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    @field:JvmField
    val isActive: Boolean = true
) {
    val destination: GeoPoint get() = GeoPoint(destLat, destLon)
}

/**
 * Manages friends and group sessions using Firebase Firestore
 */
class GroupSessionManager(private val context: Context) {
    
    private val db = Firebase.firestore
    private val auth = FirebaseAuth.getInstance()
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()
    
    private val _sessions = MutableStateFlow<List<GroupSession>>(emptyList())
    val sessions: StateFlow<List<GroupSession>> = _sessions.asStateFlow()
    
    private val _currentSession = MutableStateFlow<GroupSession?>(null)
    val currentSession: StateFlow<GroupSession?> = _currentSession.asStateFlow()
    
    private var friendsListener: ListenerRegistration? = null
    private var sessionsListener: ListenerRegistration? = null
    
    init {
        observeFriends()
        observeSessions()
    }
    
    private fun observeFriends() {
        val currentUser = auth.currentUser ?: return
        
        // Listen to current user's document to get their friendIds
        db.collection("users").document(currentUser.uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                
                val user = snapshot.toObject<User>()
                val friendIds = user?.friendIds ?: emptyList()
                
                if (friendIds.isEmpty()) {
                    _friends.value = emptyList()
                    return@addSnapshotListener
                }
                
                // Fetch details for all friends
                // Note: Firestore 'in' query supports up to 30 items.
                db.collection("users")
                    .whereIn("id", friendIds)
                    .addSnapshotListener { friendsSnapshot, fe ->
                        if (fe != null) return@addSnapshotListener
                        
                        val friendsList = friendsSnapshot?.documents?.mapNotNull { doc ->
                            val friendUser = doc.toObject<User>()
                            friendUser?.let {
                                Friend(
                                    id = doc.id,
                                    name = it.displayName,
                                    status = try { FriendStatus.valueOf(it.status) } catch(e: Exception) { FriendStatus.OFFLINE },
                                    lastKnownLat = it.lastKnownLat,
                                    lastKnownLon = it.lastKnownLon,
                                    currentSpeed = it.currentSpeed,
                                    lastUpdated = System.currentTimeMillis()
                                )
                            }
                        } ?: emptyList()
                        
                        _friends.value = friendsList
                    }
            }
    }
    
    private fun observeSessions() {
        val currentUser = auth.currentUser ?: return
        
        sessionsListener = db.collection("sessions")
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                
                val sessionsList = snapshot?.documents?.mapNotNull { doc ->
                    doc.toObject<GroupSession>()?.copy(id = doc.id)
                } ?: emptyList()
                
                _sessions.value = sessionsList
                _currentSession.value = sessionsList.find { it.memberIds.contains(currentUser.uid) }
            }
    }
    
    fun updateMyLocation(location: GeoPoint, speed: Float, status: FriendStatus) {
        val currentUser = auth.currentUser ?: return
        val updates = mapOf(
            "lastKnownLat" to location.latitude,
            "lastKnownLon" to location.longitude,
            "currentSpeed" to speed,
            "status" to status.name
        )
        db.collection("users").document(currentUser.uid).update(updates)
    }
    
    suspend fun createSession(
        name: String,
        destination: GeoPoint,
        destinationName: String,
        invitedFriendIds: List<String> = emptyList()
    ): GroupSession? {
        val currentUser = auth.currentUser ?: return null
        
        val session = GroupSession(
            id = UUID.randomUUID().toString(),
            name = name,
            destLat = destination.latitude,
            destLon = destination.longitude,
            destinationName = destinationName,
            creatorId = currentUser.uid,
            memberIds = listOf(currentUser.uid) + invitedFriendIds,
            isActive = true
        )
        
        return try {
            db.collection("sessions").document(session.id).set(session).await()
            updateMyLocation(GeoPoint(0.0, 0.0), 0f, FriendStatus.IN_SESSION)
            session
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun joinSession(sessionId: String): Boolean {
        val currentUser = auth.currentUser ?: return false
        return try {
            val sessionRef = db.collection("sessions").document(sessionId)
            val session = sessionRef.get().await().toObject<GroupSession>()
            
            if (session != null && !session.memberIds.contains(currentUser.uid)) {
                val updatedMembers = session.memberIds.toMutableList()
                updatedMembers.add(currentUser.uid)
                sessionRef.update("memberIds", updatedMembers).await()
                updateMyLocation(GeoPoint(0.0, 0.0), 0f, FriendStatus.IN_SESSION)
            }
            true
        } catch (e: Exception) {
            false
        }
    }
    
    suspend fun leaveSession() {
        val currentUser = auth.currentUser ?: return
        val session = _currentSession.value ?: return
        
        try {
            val sessionRef = db.collection("sessions").document(session.id)
            val updatedMembers = session.memberIds.filter { it != currentUser.uid }
            
            if (updatedMembers.isEmpty()) {
                sessionRef.update("isActive", false).await()
            } else {
                sessionRef.update("memberIds", updatedMembers).await()
            }
            updateMyLocation(GeoPoint(0.0, 0.0), 0f, FriendStatus.ONLINE)
            _currentSession.value = null
        } catch (e: Exception) { }
    }

    suspend fun endSession(sessionId: String) {
        val currentUser = auth.currentUser ?: return
        try {
            val sessionRef = db.collection("sessions").document(sessionId)
            val session = sessionRef.get().await().toObject<GroupSession>()
            if (session?.creatorId == currentUser.uid) {
                sessionRef.update("isActive", false).await()
            }
        } catch (e: Exception) { }
    }

    fun inviteFriendToSession(friendId: String) {
        val session = _currentSession.value ?: return
        if (!session.memberIds.contains(friendId)) {
            val updatedMembers = session.memberIds.toMutableList()
            updatedMembers.add(friendId)
            db.collection("sessions").document(session.id).update("memberIds", updatedMembers)
        }
    }

    /**
     * Search for users by exact email or display name
     */
    suspend fun searchUsers(query: String): List<User> {
        return try {
            val emailQuery = db.collection("users")
                .whereEqualTo("email", query)
                .get().await()
            
            val nameQuery = db.collection("users")
                .whereEqualTo("displayName", query)
                .get().await()
            
            (emailQuery.toObjects(User::class.java) + nameQuery.toObjects(User::class.java))
                .distinctBy { it.id }
                .filter { it.id != auth.currentUser?.uid }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Add a user to the current user's friend list
     */
    suspend fun addFriend(friendId: String) {
        val currentUser = auth.currentUser ?: return
        try {
            db.collection("users").document(currentUser.uid)
                .update("friendIds", FieldValue.arrayUnion(friendId))
                .await()
        } catch (e: Exception) { }
    }

    /**
     * Remove a user from the current user's friend list
     */
    suspend fun removeFriend(friendId: String) {
        val currentUser = auth.currentUser ?: return
        try {
            db.collection("users").document(currentUser.uid)
                .update("friendIds", FieldValue.arrayRemove(friendId))
                .await()
        } catch (e: Exception) { }
    }

    fun cleanup() {
        friendsListener?.remove()
        sessionsListener?.remove()
    }
}
