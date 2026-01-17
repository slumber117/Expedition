package com.expedition.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.osmdroid.util.GeoPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDestination: ((GeoPoint) -> Unit)? = null
) {
    val context = LocalContext.current
    val sessionManager = remember { GroupSessionManager(context) }
    
    val friends by sessionManager.friends.collectAsState()
    val sessions by sessionManager.sessions.collectAsState()
    val currentSession by sessionManager.currentSession.collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) }
    var showCreateSessionDialog by remember { mutableStateOf(false) }
    var showAddFriendDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Friends & Sessions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddFriendDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Friend")
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedTab == 1 && currentSession == null) {
                FloatingActionButton(
                    onClick = { showCreateSessionDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create Session")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Friends") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Sessions") }
                )
            }
            
            when (selectedTab) {
                0 -> FriendsTab(
                    friends = friends,
                    onRemoveFriend = { sessionManager.removeFriend(it) },
                    onInviteToSession = { 
                        if (currentSession != null) {
                            sessionManager.inviteFriendToSession(it)
                        }
                    },
                    hasActiveSession = currentSession != null
                )
                1 -> SessionsTab(
                    sessions = sessions.filter { it.isActive },
                    currentSession = currentSession,
                    onJoinSession = { sessionManager.joinSession(it) },
                    onLeaveSession = { sessionManager.leaveSession() },
                    onEndSession = { sessionManager.endSession(it) },
                    onNavigateToDestination = onNavigateToDestination
                )
            }
        }
    }
    
    // Create Session Dialog
    if (showCreateSessionDialog) {
        CreateSessionDialog(
            friends = friends,
            onDismiss = { showCreateSessionDialog = false },
            onCreate = { name, destName, destLat, destLon, invitedIds ->
                sessionManager.createSession(
                    name = name,
                    destination = GeoPoint(destLat, destLon),
                    destinationName = destName,
                    invitedFriendIds = invitedIds
                )
                showCreateSessionDialog = false
            }
        )
    }
    
    // Add Friend Dialog
    if (showAddFriendDialog) {
        AddFriendDialog(
            onDismiss = { showAddFriendDialog = false },
            onAdd = { name ->
                sessionManager.addFriend(name)
                showAddFriendDialog = false
            }
        )
    }
}

@Composable
fun FriendsTab(
    friends: List<Friend>,
    onRemoveFriend: (String) -> Unit,
    onInviteToSession: (String) -> Unit,
    hasActiveSession: Boolean
) {
    if (friends.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No friends yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Tap + to add friends",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(friends) { friend ->
                FriendCard(
                    friend = friend,
                    onRemove = { onRemoveFriend(friend.id) },
                    onInvite = { onInviteToSession(friend.id) },
                    showInviteButton = hasActiveSession && friend.status != FriendStatus.IN_SESSION
                )
            }
        }
    }
}

@Composable
fun FriendCard(
    friend: Friend,
    onRemove: () -> Unit,
    onInvite: () -> Unit,
    showInviteButton: Boolean
) {
    val statusColor = when (friend.status) {
        FriendStatus.ONLINE -> Color(0xFF4CAF50)
        FriendStatus.RIDING -> Color(0xFF2196F3)
        FriendStatus.IN_SESSION -> Color(0xFFFF9800)
        FriendStatus.OFFLINE -> Color.Gray
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar with status indicator
            Box {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = friend.name.first().uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                // Status dot
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                        .align(Alignment.BottomEnd)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = when (friend.status) {
                        FriendStatus.RIDING -> "Riding • ${friend.currentSpeed.toInt()} KMH"
                        FriendStatus.IN_SESSION -> "In Group Session"
                        FriendStatus.ONLINE -> "Online"
                        FriendStatus.OFFLINE -> "Offline"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }
            
            if (showInviteButton) {
                TextButton(onClick = onInvite) {
                    Text("Invite")
                }
            }
            
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun SessionsTab(
    sessions: List<GroupSession>,
    currentSession: GroupSession?,
    onJoinSession: (String) -> Boolean,
    onLeaveSession: () -> Unit,
    onEndSession: (String) -> Unit,
    onNavigateToDestination: ((GeoPoint) -> Unit)?
) {
    LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Current session (if any)
        currentSession?.let { session ->
            item {
                Text(
                    "Your Active Session",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                SessionCard(
                    session = session,
                    isCurrentSession = true,
                    onJoin = {},
                    onLeave = onLeaveSession,
                    onEnd = { onEndSession(session.id) },
                    onNavigate = { onNavigateToDestination?.invoke(session.destination) }
                )
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
        
        // Other available sessions
        val otherSessions = sessions.filter { it.id != currentSession?.id }
        if (otherSessions.isNotEmpty()) {
            item {
                Text(
                    "Available Sessions",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            
            items(otherSessions) { session ->
                SessionCard(
                    session = session,
                    isCurrentSession = false,
                    onJoin = { onJoinSession(session.id) },
                    onLeave = {},
                    onEnd = {},
                    onNavigate = null
                )
            }
        }
        
        if (sessions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No active sessions",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Create one to ride together!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SessionCard(
    session: GroupSession,
    isCurrentSession: Boolean,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onNavigate: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isCurrentSession) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF4CAF50))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = session.destinationName,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            Text(
                text = "${session.memberIds.size} members",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (isCurrentSession) {
                    if (onNavigate != null) {
                        Button(onClick = onNavigate) {
                            Icon(Icons.Default.LocationOn, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Navigate")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    TextButton(onClick = onLeave) {
                        Text("Leave")
                    }
                    TextButton(onClick = onEnd) {
                        Text("End Session", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Button(onClick = onJoin) {
                        Text("Join")
                    }
                }
            }
        }
    }
}

@Composable
fun CreateSessionDialog(
    friends: List<Friend>,
    onDismiss: () -> Unit,
    onCreate: (name: String, destName: String, destLat: Double, destLon: Double, invitedIds: List<String>) -> Unit
) {
    var sessionName by remember { mutableStateOf("") }
    var destinationName by remember { mutableStateOf("") }
    var destLat by remember { mutableStateOf("") }
    var destLon by remember { mutableStateOf("") }
    var selectedFriends by remember { mutableStateOf(setOf<String>()) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Group Session") },
        text = {
            Column {
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = { sessionName = it },
                    label = { Text("Session Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = destinationName,
                    onValueChange = { destinationName = it },
                    label = { Text("Destination Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row {
                    OutlinedTextField(
                        value = destLat,
                        onValueChange = { destLat = it },
                        label = { Text("Latitude") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = destLon,
                        onValueChange = { destLon = it },
                        label = { Text("Longitude") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Invite Friends", style = MaterialTheme.typography.labelLarge)
                
                friends.filter { it.status != FriendStatus.OFFLINE }.forEach { friend ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedFriends = if (friend.id in selectedFriends) {
                                    selectedFriends - friend.id
                                } else {
                                    selectedFriends + friend.id
                                }
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = friend.id in selectedFriends,
                            onCheckedChange = {
                                selectedFriends = if (it) {
                                    selectedFriends + friend.id
                                } else {
                                    selectedFriends - friend.id
                                }
                            }
                        )
                        Text(friend.name)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val lat = destLat.toDoubleOrNull() ?: 0.0
                    val lon = destLon.toDoubleOrNull() ?: 0.0
                    if (sessionName.isNotBlank() && destinationName.isNotBlank()) {
                        onCreate(sessionName, destinationName, lat, lon, selectedFriends.toList())
                    }
                },
                enabled = sessionName.isNotBlank() && destinationName.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AddFriendDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var friendName by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Friend") },
        text = {
            OutlinedTextField(
                value = friendName,
                onValueChange = { friendName = it },
                label = { Text("Friend's Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = { onAdd(friendName) },
                enabled = friendName.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
