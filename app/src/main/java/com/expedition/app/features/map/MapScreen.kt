package com.expedition.app.features.map

import android.Manifest
import android.content.Context
import android.location.Location
import android.preference.PreferenceManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.expedition.app.features.auth.AuthManager
import com.expedition.app.ui.theme.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.view.MotionEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun MapScreen(
    onNavigateToFriends: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Managers
    val authManager = remember { AuthManager(context) }
    val locationTracker = remember { LocationTracker(context) }
    val navigationManager = remember { NavigationManager(context) }
    val offlineCacheManager = remember { OfflineCacheManager(context) }
    val searchManager = remember { PlaceSearchManager() }
    
    // Location state
    val currentUser by authManager.currentUser.collectAsState()
    val currentLocation by locationTracker.currentLocation.collectAsState()
    val speedKmh by locationTracker.speedKmh.collectAsState()
    val isOfflineMode by locationTracker.isOfflineMode.collectAsState()
    
    // Navigation state
    val destination by navigationManager.destination.collectAsState()
    val etaSeconds by navigationManager.etaSeconds.collectAsState()
    val distanceToDestination by navigationManager.distanceToDestination.collectAsState()
    val currentRoute by navigationManager.currentRoute.collectAsState()
    
    // UI state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var routeName by remember { mutableStateOf("") }
    var destinationMarker by remember { mutableStateOf<Marker?>(null) }
    
    // Permission state
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == 
                PackageManager.PERMISSION_GRANTED
        )
    }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                               permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        
        if (hasLocationPermission) {
            locationTracker.startTracking()
        }
    }
    
    // Initialize OSMDroid configuration
    LaunchedEffect(Unit) {
        Configuration.getInstance().load(context, PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = "ExpeditionApp"
        
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            locationTracker.startTracking()
        }
    }
    
    // ETA update loop - runs every 500ms
    LaunchedEffect(destination) {
        if (destination != null) {
            while (true) {
                currentLocation?.let { loc ->
                    navigationManager.updateETA(loc, speedKmh)
                }
                delay(500) // Update every 500ms
            }
        }
    }
    
    // Search suggestions debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery.length > 2) {
            isSearching = true
            delay(500) // Debounce
            searchResults = searchManager.search(searchQuery)
            isSearching = false
        } else {
            searchResults = emptyList()
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
            setUseDataConnection(true)
            
            // Add my location overlay
            val locationProvider = GpsMyLocationProvider(context)
            val myLocationOverlay = MyLocationNewOverlay(locationProvider, this)
            myLocationOverlay.enableMyLocation()
            myLocationOverlay.enableFollowLocation()
            overlays.add(myLocationOverlay)
            
            // Add map events overlay for long-press destination selection
            val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    return false
                }
                
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    p?.let { point ->
                        // Remove old destination marker
                        destinationMarker?.let { overlays.remove(it) }
                        
                        // Add new destination marker
                        val marker = Marker(this@apply).apply {
                            position = point
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "Destination"
                        }
                        overlays.add(marker)
                        destinationMarker = marker
                        
                        // Set navigation destination
                        currentLocation?.let { loc ->
                            navigationManager.setDestination(
                                GeoPoint(loc.latitude, loc.longitude),
                                point
                            )
                            navigationManager.drawRouteOnMap(this@apply)
                        }
                        
                        invalidate()
                    }
                    return true
                }
            })
            overlays.add(0, mapEventsOverlay) // Add at bottom of overlay stack
        }
    }
    
    // Update map center when location changes
    LaunchedEffect(currentLocation) {
        currentLocation?.let { location ->
            if (destination == null) {
                mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            }
        }
    }
    
    // Update route on map when route changes
    LaunchedEffect(currentRoute) {
        if (currentRoute.isNotEmpty()) {
            navigationManager.drawRouteOnMap(mapView)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            locationTracker.stopTracking()
            mapView.onDetach()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )
        
        // Search Bar - Top Center
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 48.dp)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search for a destination...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        )
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                }
            }
            
            // Search Results Dropdown
            if (searchResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column {
                        searchResults.forEach { result ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val point = GeoPoint(result.latitude, result.longitude)
                                        
                                        // Set destination
                                        currentLocation?.let { loc ->
                                            navigationManager.setDestination(
                                                GeoPoint(loc.latitude, loc.longitude),
                                                point
                                            )
                                        }
                                        
                                        // Clear results
                                        searchQuery = ""
                                        searchResults = emptyList()
                                        
                                        // Animate map
                                        mapView.controller.animateTo(point)
                                        
                                        // Add marker
                                        destinationMarker?.let { mapView.overlays.remove(it) }
                                        val marker = Marker(mapView).apply {
                                            position = point
                                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                            title = result.displayName
                                        }
                                        mapView.overlays.add(marker)
                                        destinationMarker = marker
                                        mapView.invalidate()
                                    }
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = result.displayName.split(",").first(),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = result.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
        
        // Turn-by-Turn Guidance Card - Top Right area (replaces normal indicators when turn is near)
        if (destination != null && etaSeconds != null) {
            val distance = (distanceToDestination ?: 0.0)
            val instruction = if (distance < 500) "Turn Ahead" else "Continue Straight"
            
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 110.dp) // Below search bar
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = instruction,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "In ${NavigationManager.formatDistance(distanceToDestination)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    
                    // ETA mini summary
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = NavigationManager.formatETA(etaSeconds!!),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "ETA",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
        
        // Speed indicator (Floating)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp)
                .padding(bottom = 100.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "${speedKmh.toInt()}",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = "KMH",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
        
        // Profile/Logout button - Top Left
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .padding(top = 48.dp) // Match search bar height
        ) {
            IconButton(
                onClick = { 
                    authManager.logout()
                    onLogout()
                },
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .size(48.dp)
            ) {
                Icon(
                    Icons.Default.ExitToApp,
                    contentDescription = "Logout",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        
        // Offline mode indicator
        if (isOfflineMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .padding(top = 88.dp) // Below logout button
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(StatusWarning.copy(alpha = 0.9f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "OFFLINE SENSORS",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black
                )
            }
        }
        
        // Navigation info bar (shown when navigating)
        if (destination != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .padding(bottom = 100.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Navigating to destination",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = NavigationManager.formatDistance(distanceToDestination) + " remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Save route button
                TextButton(onClick = { showSaveRouteDialog = true }) {
                    Text("Save")
                }
                
                // Clear navigation button
                IconButton(onClick = { 
                    navigationManager.clearNavigation()
                    destinationMarker?.let { mapView.overlays.remove(it) }
                    destinationMarker = null
                    mapView.invalidate()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Clear navigation")
                }
            }
        }

        // One-handed controls overlay
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .padding(bottom = 32.dp)
        ) {
            FloatingActionButton(
                onClick = { mapView.controller.zoomIn() },
                modifier = Modifier.size(64.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In")
            }
            
            Spacer(modifier = Modifier.size(16.dp))

            FloatingActionButton(
                onClick = { mapView.controller.zoomOut() },
                modifier = Modifier.size(64.dp),
                containerColor = MaterialTheme.colorScheme.secondary
            ) {
                Box(modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.onSecondary))
            }

            Spacer(modifier = Modifier.size(16.dp))
            
            // Center on my location button
            FloatingActionButton(
                onClick = { 
                    currentLocation?.let { location ->
                        mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
                    }
                },
                modifier = Modifier.size(64.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text("📍", fontSize = 24.sp)
            }

            Spacer(modifier = Modifier.size(16.dp))

            FloatingActionButton(
                onClick = onNavigateToFriends,
                modifier = Modifier.size(64.dp),
                containerColor = MaterialTheme.colorScheme.tertiary
            ) {
                Icon(Icons.Default.Person, contentDescription = "Friends")
            }
        }
        
        // Instruction text for long-press
        if (destination == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .padding(bottom = 100.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Long-press to set destination",
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }
    }
    
    // Save route dialog
    if (showSaveRouteDialog) {
        AlertDialog(
            onDismissRequest = { showSaveRouteDialog = false },
            title = { Text("Save Route") },
            text = {
                OutlinedTextField(
                    value = routeName,
                    onValueChange = { routeName = it },
                    label = { Text("Route name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (routeName.isNotBlank()) {
                            navigationManager.saveCurrentRoute(routeName)
                            // Also cache tiles along the route
                            coroutineScope.launch {
                                offlineCacheManager.downloadRouteArea(
                                    mapView,
                                    currentRoute,
                                    routeName
                                )
                            }
                            showSaveRouteDialog = false
                            routeName = ""
                        }
                    }
                ) {
                    Text("Save & Cache Offline")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveRouteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
