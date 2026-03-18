package com.expedition.app.features.map

import android.Manifest
import android.preference.PreferenceManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.expedition.app.features.social.GroupSessionManager
import com.expedition.app.features.social.FriendStatus
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun MapScreen(
    onNavigateToFriends: () -> Unit,
    onNavigateToSavedRoutes: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Managers
    val sessionManager = remember { GroupSessionManager(context) }
    val locationTracker = remember { LocationTracker(context) }
    val navigationManager = remember { NavigationManager(context) }
    val searchManager = remember { PlaceSearchManager() }
    
    // Location state
    val currentLocation by locationTracker.currentLocation.collectAsState()
    val speedKmh by locationTracker.speedKmh.collectAsState()
    val isOfflineMode by locationTracker.isOfflineMode.collectAsState()
    
    // Social state
    val friends by sessionManager.friends.collectAsState()
    val currentSession by sessionManager.currentSession.collectAsState()
    
    // Navigation state
    val destination by navigationManager.destination.collectAsState()
    val etaSeconds by navigationManager.etaSeconds.collectAsState()
    val distanceToDestination by navigationManager.distanceToDestination.collectAsState()
    val currentRoute by navigationManager.currentRoute.collectAsState()
    val elevation by navigationManager.elevation.collectAsState()
    val weather by navigationManager.weather.collectAsState()
    val travelMode by navigationManager.travelMode.collectAsState()
    
    // UI state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var routeName by remember { mutableStateOf("") }
    var destinationMarker by remember { mutableStateOf<Marker?>(null) }
    val friendMarkers = remember { mutableMapOf<String, Marker>() }
    
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
    
    // Real-time location sync to Firestore
    LaunchedEffect(currentLocation, speedKmh) {
        currentLocation?.let { loc ->
            val status = if (currentSession != null) FriendStatus.IN_SESSION 
                        else if (speedKmh > 5f) FriendStatus.RIDING 
                        else FriendStatus.ONLINE
            
            sessionManager.updateMyLocation(
                location = GeoPoint(loc.latitude, loc.longitude),
                speed = speedKmh,
                status = status
            )
        }
    }
    
    // Environmental update loop (Always active)
    LaunchedEffect(Unit) {
        while(true) {
            currentLocation?.let { loc ->
                navigationManager.updateEnvironmentalData(loc)
            }
            delay(10000) // Every 10 seconds for general environment
        }
    }
    
    // ETA update loop (When navigating)
    LaunchedEffect(destination) {
        if (destination != null) {
            while (true) {
                currentLocation?.let { loc ->
                    navigationManager.updateProgress(loc, speedKmh.toDouble())
                }
                delay(5000) // Update every 5 seconds during navigation
            }
        }
    }
    
    // Search suggestions debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery.length > 2) {
            delay(500) // Debounce
            searchResults = searchManager.search(searchQuery)
        } else {
            searchResults = emptyList()
        }
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true) 
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(17.0)
            setUseDataConnection(true)
            
            val locationProvider = GpsMyLocationProvider(context)
            val myLocationOverlay = MyLocationNewOverlay(locationProvider, this)
            myLocationOverlay.enableMyLocation()
            myLocationOverlay.enableFollowLocation()
            overlays.add(myLocationOverlay)
            
            val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    p?.let { point ->
                        destinationMarker?.let { overlays.remove(it) }
                        val marker = Marker(this@apply).apply {
                            position = point
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "Destination"
                        }
                        overlays.add(marker)
                        destinationMarker = marker
                        
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
            overlays.add(0, mapEventsOverlay)
        }
    }
    
    // Update friend markers on map
    LaunchedEffect(friends) {
        // Remove old markers not in the list anymore
        val currentFriendIds = friends.map { it.id }.toSet()
        val idsToRemove = friendMarkers.keys.filter { it !in currentFriendIds }
        idsToRemove.forEach { id ->
            mapView.overlays.remove(friendMarkers[id])
            friendMarkers.remove(id)
        }
        
        // Add or update markers
        friends.forEach { friend ->
            friend.lastKnownLocation?.let { point ->
                val marker = friendMarkers.getOrPut(friend.id) {
                    Marker(mapView).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        mapView.overlays.add(this)
                    }
                }
                marker.position = point
                marker.title = "${friend.name} (${friend.status})"
            }
        }
        mapView.invalidate()
    }
    
    LaunchedEffect(currentLocation) {
        currentLocation?.let { location ->
            if (destination == null) {
                mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            }
        }
    }
    
    LaunchedEffect(currentRoute) {
        if (currentRoute.isNotEmpty()) {
            navigationManager.drawRouteOnMap(mapView)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            locationTracker.stopTracking()
            sessionManager.cleanup()
            mapView.onDetach()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )
        
        // Search Bar
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
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search destination...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        )
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                }
            }
            
            // Search Results
            if (searchResults.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column {
                        searchResults.forEach { result ->
                            ListItem(
                                headlineContent = { Text(result.displayName) },
                                supportingContent = { Text(result.type) },
                                leadingContent = { Icon(Icons.Default.Place, null) },
                                modifier = Modifier.clickable {
                                    searchQuery = ""
                                    searchResults = emptyList()
                                    
                                    destinationMarker?.let { mapView.overlays.remove(it) }
                                    val marker = Marker(mapView).apply {
                                        position = GeoPoint(result.latitude, result.longitude)
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        title = result.displayName
                                    }
                                    mapView.overlays.add(marker)
                                    destinationMarker = marker
                                    
                                    currentLocation?.let { loc ->
                                        navigationManager.setDestination(
                                            GeoPoint(loc.latitude, loc.longitude),
                                            GeoPoint(result.latitude, result.longitude)
                                        )
                                    }
                                    mapView.invalidate()
                                }
                            )
                        }
                    }
                }
            }
        }
        
        // Persistent Dashboard (Speed & Elevation)
        PersistentDashboard(
            speedKmh = speedKmh,
            elevation = elevation,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 110.dp) // Below search bar
        )
        
        // Navigation Info Overlay
        if (destination != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (etaSeconds != null) "${etaSeconds!! / 60} min" else "-- min",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = String.format("%.1f km", distanceToDestination / 1000.0),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        
                        Row {
                            IconButton(
                                onClick = { showSaveRouteDialog = true },
                                modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            ) {
                                Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = { navigationManager.clearNavigation() },
                                modifier = Modifier.background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                            ) {
                                Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }
                    
                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    // Travel Mode Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TravelModeButton(
                            mode = TravelMode.DRIVING,
                            isSelected = travelMode == TravelMode.DRIVING,
                            icon = Icons.Default.DirectionsCar,
                            onClick = { 
                                navigationManager.setTravelMode(TravelMode.DRIVING)
                                currentLocation?.let { loc ->
                                    destination?.let { dest ->
                                        navigationManager.setDestination(GeoPoint(loc.latitude, loc.longitude), dest)
                                    }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        TravelModeButton(
                            mode = TravelMode.WALKING,
                            isSelected = travelMode == TravelMode.WALKING,
                            icon = Icons.Default.DirectionsWalk,
                            onClick = { 
                                navigationManager.setTravelMode(TravelMode.WALKING)
                                currentLocation?.let { loc ->
                                    destination?.let { dest ->
                                        navigationManager.setDestination(GeoPoint(loc.latitude, loc.longitude), dest)
                                    }
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        EnvironmentalInfoItem(
                            icon = Icons.Default.Terrain,
                            label = "Elevation",
                            value = if (elevation != null) "${elevation!!.toInt()}m" else "--"
                        )
                        EnvironmentalInfoItem(
                            icon = Icons.Default.Cloud,
                            label = "Weather",
                            value = weather?.weather?.firstOrNull()?.description ?: "--"
                        )
                        EnvironmentalInfoItem(
                            icon = Icons.Default.Speed,
                            label = "Speed",
                            value = String.format("%.0f km/h", speedKmh)
                        )
                    }
                }
            }
        }
        
        // Floating Action Buttons
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp)
        ) {
            FloatingActionButton(
                onClick = onNavigateToFriends,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(Icons.Default.Group, "Friends")
            }
            Spacer(modifier = Modifier.height(16.dp))
            FloatingActionButton(
                onClick = onNavigateToSavedRoutes,
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Icon(Icons.Default.Route, "Saved Routes")
            }
            Spacer(modifier = Modifier.height(16.dp))
            FloatingActionButton(
                onClick = {
                    currentLocation?.let {
                        mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.MyLocation, "My Location")
            }
        }
        
        if (isOfflineMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 120.dp),
                color = MaterialTheme.colorScheme.error,
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.SignalWifiOff, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Offline Mode - Sensor Tracking Active", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp)
        )
    }
    
    if (showSaveRouteDialog) {
        AlertDialog(
            onDismissRequest = { showSaveRouteDialog = false },
            title = { Text("Save Route") },
            text = {
                OutlinedTextField(
                    value = routeName,
                    onValueChange = { routeName = it },
                    label = { Text("Route Name") }
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (currentRoute.isEmpty()) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Cannot save: No route calculated. Check your API key.")
                        }
                    } else {
                        navigationManager.saveCurrentRoute(routeName)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Route '$routeName' saved!")
                        }
                    }
                    showSaveRouteDialog = false
                    routeName = ""
                }) {
                    Text("Save")
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

@Composable
fun TravelModeButton(
    mode: TravelMode,
    isSelected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = mode.name,
                modifier = Modifier.size(18.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PersistentDashboard(
    speedKmh: Float,
    elevation: Double?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            DashboardItem(
                icon = Icons.Default.Speed,
                value = String.format("%.0f", speedKmh),
                unit = "km/h",
                label = "SPEED"
            )
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .width(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            DashboardItem(
                icon = Icons.Default.Terrain,
                value = if (elevation != null) "${elevation.toInt()}" else "--",
                unit = "m",
                label = "ELEVATION"
            )
        }
    }
}

@Composable
fun DashboardItem(icon: ImageVector, value: String, unit: String, label: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp).padding(bottom = 2.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

@Composable
fun EnvironmentalInfoItem(icon: ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
