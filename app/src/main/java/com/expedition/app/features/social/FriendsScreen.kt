package com.expedition.app.features.social

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.expedition.app.features.auth.User
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDestination: ((GeoPoint) -> Unit)? = null
) {
    val context = LocalContext.current
    val sessionManager = remember { GroupSessionManager(context) }
    val scope = rememberCoroutineScope()
    
    val friends by sessionManager.friends.collectAsState()
    val sessions by sessionManager.sessions.collectAsState()
    val currentSession by sessionManager.currentSession.collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) }
    var showCreateSessionDialog by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expedition Social") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearchDialog = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Find Friends")
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
                    Icon(Icons.Default.GroupAdd, contentDescription = "Create Session")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Friends (${friends.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Active Sessions") }
                )
            }
            
            when (selectedTab) {
                0 -> FriendsTab(
                    friends = friends,
                    onRemoveFriend = { scope.launch { sessionManager.removeFriend(it) } },
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
                    onJoinSession = { scope.launch { sessionManager.joinSession(it) } },
                    onLeaveSession = { scope.launch { sessionManager.leaveSession() } },
                    onEndSession = { scope.launch { sessionManager.endSession(it) } },
                    onNavigateToDestination = onNavigateToDestination
                )
            }
        }
    }
    
    if (showSearchDialog) {
        UserSearchDialog(
            onDismiss = { showSearchDialog = false },
            onSearch = { query -> sessionManager.searchUsers(query) },
            onAddFriend = { userId -> 
                scope.launch { 
                    sessionManager.addFriend(userId)
                    showSearchDialog = false
                }
            }
        )
    }

    if (showCreateSessionDialog) {
        CreateSessionDialog(
            friends = friends,
            onDismiss = { showCreateSessionDialog = false },
            onCreate = { name, destName, destLat, destLon, invitedIds ->
                scope.launch {
                    sessionManager.createSession(
                        name = name,
                        destination = GeoPoint(destLat, destLon),
                        destinationName = destName,
                        invitedFriendIds = invitedIds
                    )
                    showCreateSessionDialog = false
                }
            }
        )
    }
}

@Composable
fun UserSearchDialog(
    onDismiss: () -> Unit,
    onSearch: suspend (String) -> List<User>,
    onAddFriend: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<User>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find Friends") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { 
                        query = it
                        if (it.length > 2) {
                            scope.launch {
                                isSearching = true
                                results = onSearch(it)
                                isSearching = false
                            }
                        }
                    },
                    label = { Text("Search by Email or Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        if (isSearching) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (results.isEmpty() && query.length > 2 && !isSearching) {
                    Text("No users found", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(results) { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(user.displayName, fontWeight = FontWeight.Bold)
                                    Text(user.email, style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { onAddFriend(user.id) }) {
                                    Text("Add")
                                }
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun FriendsTab(
    friends: List<Friend>,
    onRemoveFriend: (String) -> Unit,
    onInviteToSession: (String) -> Unit,
    hasActiveSession: Boolean
) {
    if (friends.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.People, null, modifier = Modifier.size(64.dp), tint = Color.LightGray)
                Text("No friends yet. Try searching for someone!", color = Color.Gray)
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
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Box(
                    modifier = Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(friend.name.first().uppercase(), style = MaterialTheme.typography.titleLarge)
                }
                Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(statusColor).align(Alignment.BottomEnd))
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(friend.name, style = MaterialTheme.typography.titleMedium)
                Text(friend.status.name, style = MaterialTheme.typography.bodySmall, color = statusColor)
            }
            
            if (showInviteButton) {
                IconButton(onClick = onInvite) { Icon(Icons.Default.GroupAdd, "Invite", tint = MaterialTheme.colorScheme.primary) }
            }
            
            IconButton(onClick = onRemove) { Icon(Icons.Default.PersonRemove, "Remove", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun SessionsTab(
    sessions: List<GroupSession>,
    currentSession: GroupSession?,
    onJoinSession: (String) -> Unit,
    onLeaveSession: () -> Unit,
    onEndSession: (String) -> Unit,
    onNavigateToDestination: ((GeoPoint) -> Unit)?
) {
    LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        currentSession?.let { session ->
            item {
                Text("Your Session", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                SessionCard(session, true, {}, onLeaveSession, { onEndSession(session.id) }, { onNavigateToDestination?.invoke(session.destination) })
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        
        val otherSessions = sessions.filter { it.id != currentSession?.id }
        if (otherSessions.isNotEmpty()) {
            item { Text("Joinable Sessions", style = MaterialTheme.typography.titleSmall) }
            items(otherSessions) { session ->
                SessionCard(session, false, { onJoinSession(session.id) }, {}, {}, null)
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
        colors = if (isCurrentSession) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(session.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("To: ${session.destinationName}", style = MaterialTheme.typography.bodyMedium)
            Text("${session.memberIds.size} riders", style = MaterialTheme.typography.labelSmall)
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (isCurrentSession) {
                    if (onNavigate != null) TextButton(onClick = onNavigate) { Text("Go") }
                    TextButton(onClick = onLeave) { Text("Leave") }
                    TextButton(onClick = onEnd) { Text("End", color = Color.Red) }
                } else {
                    Button(onClick = onJoin) { Text("Join") }
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
        title = { Text("New Ride Session") },
        text = {
            Column {
                OutlinedTextField(value = sessionName, onValueChange = { sessionName = it }, label = { Text("Session Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = destinationName, onValueChange = { destinationName = it }, label = { Text("Destination") }, modifier = Modifier.fillMaxWidth())
                Row {
                    OutlinedTextField(value = destLat, onValueChange = { destLat = it }, label = { Text("Lat") }, modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(value = destLon, onValueChange = { destLon = it }, label = { Text("Lon") }, modifier = Modifier.weight(1f))
                }
                Text("Invite Friends", modifier = Modifier.padding(top = 8.dp))
                friends.forEach { friend ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = friend.id in selectedFriends, onCheckedChange = { 
                            selectedFriends = if (it) selectedFriends + friend.id else selectedFriends - friend.id 
                        })
                        Text(friend.name)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { 
                onCreate(sessionName, destinationName, destLat.toDoubleOrNull() ?: 0.0, destLon.toDoubleOrNull() ?: 0.0, selectedFriends.toList())
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
