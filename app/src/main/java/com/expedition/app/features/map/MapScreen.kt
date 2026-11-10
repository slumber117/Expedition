package com.expedition.app.features.map

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.expedition.app.features.auth.AuthManager
import com.expedition.app.features.social.FriendStatus
import com.expedition.app.features.social.GroupSessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun MapScreen(
    navigationManager: NavigationManager,
    searchManager: PlaceSearchManager,
    sessionManager: GroupSessionManager,
    offlineCacheManager: OfflineCacheManager? = null,
    onNavigateToFriends: () -> Unit,
    onNavigateToSavedRoutes: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val cacheManager = offlineCacheManager ?: remember { OfflineCacheManager(context) }
    val locationTracker = remember { LocationTracker(context) }

    // Location & Telemetry state
    val currentLocation by locationTracker.currentLocation.collectAsState()
    val speedKmh by locationTracker.speedKmh.collectAsState()
    val isOfflineMode by locationTracker.isOfflineMode.collectAsState()
    val heading by locationTracker.estimatedHeading.collectAsState()
    val baroAltitude by locationTracker.barometricAltitude.collectAsState()
    val leanAngleDegrees by locationTracker.leanAngleDegrees.collectAsState()
    val maxLeanAngleDegrees by locationTracker.maxLeanAngleDegrees.collectAsState()
    val crashDetectedEvent by locationTracker.crashDetectedEvent.collectAsState()
    val powerProfile by locationTracker.powerProfile.collectAsState()

    // Offline cache state
    val forceOfflineMap by cacheManager.forceOfflineMap.collectAsState()
    val cachedRegions by cacheManager.cachedRegions.collectAsState()

    // Social state
    val friends by sessionManager.friends.collectAsState()
    val currentSession by sessionManager.currentSession.collectAsState()
    val activePttBeacon by sessionManager.activePttBeacon.collectAsState()

    // Navigation & Weather+Time state
    val destination by navigationManager.destination.collectAsState()
    val etaSeconds by navigationManager.etaSeconds.collectAsState()
    val distanceToDestination by navigationManager.distanceToDestination.collectAsState()
    val currentRoute by navigationManager.currentRoute.collectAsState()
    val elevation by navigationManager.elevation.collectAsState()
    val riderWeather by navigationManager.riderWeather.collectAsState()
    val routeCurvatureScore by navigationManager.routeCurvatureScore.collectAsState()
    val activeRoutingAlgorithm by navigationManager.activeRoutingAlgorithm.collectAsState()
    val travelMode by navigationManager.travelMode.collectAsState()
    val weatherWarnings by navigationManager.weatherWarnings.collectAsState()
    val requiresProUpgrade by navigationManager.requiresProUpgrade.collectAsState()
    val speedCameras by navigationManager.speedCameras.collectAsState()
    val trafficSegments by navigationManager.trafficSegments.collectAsState()
    val speedCameraAlert by navigationManager.speedCameraAlert.collectAsState()
    val communityPins by navigationManager.communityPins.collectAsState()
    val savedRoutes by navigationManager.savedRoutes.collectAsState()

    val authManager = remember { AuthManager(context) }
    val currentUser by authManager.currentUser.collectAsState()

    // Rider Ergonomics: Left-hand (clutch hand) mode enabled by default for motorcycle riders
    var isLeftHandedMode by remember { mutableStateOf(true) }
    var showAccountDialog by remember { mutableStateOf(false) }

    // UI state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchErrorMessage by remember { mutableStateOf<String?>(null) }
    var showSaveRouteDialog by remember { mutableStateOf(false) }
    var showLoopDialog by remember { mutableStateOf(false) }
    var showAddPinDialog by remember { mutableStateOf(false) }
    var clickedGeoPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var routeName by remember { mutableStateOf("") }
    var destinationMarker by remember { mutableStateOf<Marker?>(null) }
    val friendMarkers = remember { mutableMapOf<String, Marker>() }
    val cameraMarkers = remember { mutableListOf<Marker>() }
    val trafficOverlays = remember { mutableListOf<Polyline>() }
    val communityPinMarkers = remember { mutableListOf<Marker>() }
    var sosCountdown by remember { mutableStateOf(30) }

    // Battery optimization state for Firestore sync throttling
    var lastFirestoreSyncTime by remember { mutableStateOf(0L) }
    var lastFirestoreSyncLocation by remember { mutableStateOf<Location?>(null) }

    // Permission state
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (hasLocationPermission) {
            locationTracker.startTracking()
        }
    }

    // Initialize unified OSMDroid offline storage and permissions
    LaunchedEffect(Unit) {
        cacheManager.configureOsmdroidStorage(context)

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

    // Battery-Optimized Firestore Location Sync:
    // Only transmits over cellular radio if >= 10 seconds elapsed OR rider moved >= 50 meters
    LaunchedEffect(currentLocation, speedKmh) {
        currentLocation?.let { loc ->
            val now = System.currentTimeMillis()
            val distMoved = lastFirestoreSyncLocation?.distanceTo(loc) ?: Float.MAX_VALUE
            if (!isOfflineMode && (now - lastFirestoreSyncTime >= 10_000L || distMoved >= 50f)) {
                lastFirestoreSyncTime = now
                lastFirestoreSyncLocation = loc
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
    }

    // Battery-Optimized Environmental & Time Update Loop
    LaunchedEffect(currentLocation) {
        currentLocation?.let { loc ->
            navigationManager.updateEnvironmentalData(loc)
            if (communityPins.isEmpty()) {
                navigationManager.fetchCommunityPins(GeoPoint(loc.latitude, loc.longitude))
            }
        }
    }

    // Periodic clock & weather refresh every 60 seconds (NavigationManager internally throttles network to 10 mins / 1.5km)
    LaunchedEffect(Unit) {
        while (true) {
            currentLocation?.let { loc ->
                navigationManager.updateEnvironmentalData(loc)
            }
            delay(60_000L)
        }
    }

    // ETA & Speed Camera proximity loop when navigating
    LaunchedEffect(destination) {
        if (destination != null) {
            while (true) {
                currentLocation?.let { loc ->
                    navigationManager.updateProgress(loc, speedKmh.toDouble())
                }
                delay(3000L)
            }
        }
    }

    // Show PTT Voice Beacon snackbar when received
    LaunchedEffect(activePttBeacon) {
        activePttBeacon?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            sessionManager.dismissPttBeacon()
        }
    }

    // Search suggestions debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery.length > 2) {
            isSearching = true
            searchErrorMessage = null
            delay(450)
            try {
                searchResults = searchManager.search(searchQuery)
                if (searchResults.isEmpty()) {
                    searchErrorMessage = "No results found for '$searchQuery'"
                }
            } catch (e: Exception) {
                searchErrorMessage = "Offline / Network error. Try loading a Saved Offline Route."
            } finally {
                isSearching = false
            }
        } else {
            searchResults = emptyList()
            searchErrorMessage = null
        }
    }

    val mapView = remember {
        cacheManager.configureOsmdroidStorage(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setUseDataConnection(!forceOfflineMap)
            controller.setZoom(14.5)
            // Set a valid initial land center until GPS acquires a fix (avoids blank 0.0,0.0 ocean tiles)
            controller.setCenter(GeoPoint(51.5074, -0.1278))

            val locationProvider = GpsMyLocationProvider(context)
            val myLocationOverlay = MyLocationNewOverlay(locationProvider, this)
            myLocationOverlay.enableMyLocation()
            myLocationOverlay.enableFollowLocation()
            overlays.add(myLocationOverlay)

            val mapEventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

                override fun longPressHelper(p: GeoPoint?): Boolean {
                    p?.let { point ->
                        clickedGeoPoint = point
                        destinationMarker?.let { overlays.remove(it) }
                        val marker = Marker(this@apply).apply {
                            position = point
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "Destination"
                        }
                        overlays.add(marker)
                        destinationMarker = marker

                        val startPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                            ?: GeoPoint(mapCenter.latitude, mapCenter.longitude)
                        navigationManager.setDestination(startPoint, point)
                        invalidate()
                    }
                    return true
                }
            })
            overlays.add(0, mapEventsOverlay)
            onResume()
        }
    }

    // Sync offline tile data connection flag dynamically
    LaunchedEffect(forceOfflineMap) {
        mapView.setUseDataConnection(!forceOfflineMap)
        mapView.invalidate()
    }

    // Update friend markers on map
    LaunchedEffect(friends) {
        val currentFriendIds = friends.map { it.id }.toSet()
        val idsToRemove = friendMarkers.keys.filter { it !in currentFriendIds }
        idsToRemove.forEach { id ->
            mapView.overlays.remove(friendMarkers[id])
            friendMarkers.remove(id)
        }

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
            if (destination == null && currentRoute.isEmpty()) {
                mapView.controller.animateTo(GeoPoint(location.latitude, location.longitude))
            }
        }
    }

    // Draw route and zoom/pan to route corridor when route is calculated or loaded from SavedRoutesScreen
    LaunchedEffect(currentRoute) {
        navigationManager.drawRouteOnMap(mapView)
        val routeSnapshot = currentRoute.toList()
        val firstPt = routeSnapshot.firstOrNull()
        val endPt = routeSnapshot.lastOrNull()
        if (firstPt != null && endPt != null) {
            destinationMarker?.let { mapView.overlays.remove(it) }
            val marker = Marker(mapView).apply {
                position = endPt
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Destination"
            }
            mapView.overlays.add(marker)
            destinationMarker = marker

            if (routeSnapshot.size >= 2) {
                try {
                    val bbox = BoundingBox.fromGeoPoints(routeSnapshot)
                    mapView.post {
                        try {
                            mapView.zoomToBoundingBox(bbox.increaseByScale(1.25f), true)
                        } catch (_: Exception) {
                            mapView.controller.animateTo(firstPt)
                        }
                    }
                } catch (_: Exception) {
                    mapView.controller.animateTo(firstPt)
                }
            }
            mapView.invalidate()

            // Automatically cache map tiles along this route while the user has mobile data / Wi-Fi
            val autoRegionKey = "AutoRoute_${firstPt.latitude}_${endPt.latitude}"
            if (cachedRegions.none { it.name == autoRegionKey }) {
                cacheManager.downloadRouteArea(mapView, routeSnapshot, autoRegionKey)
            }
        } else {
            destinationMarker?.let { mapView.overlays.remove(it) }
            destinationMarker = null
            mapView.invalidate()
        }
    }

    LaunchedEffect(speedCameras) {
        cameraMarkers.forEach { mapView.overlays.remove(it) }
        cameraMarkers.clear()

        speedCameras.forEach { cam ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(cam.lat, cam.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Speed Camera (${cam.max_speed} km/h)"
            }
            mapView.overlays.add(marker)
            cameraMarkers.add(marker)
        }
        mapView.invalidate()
    }

    LaunchedEffect(trafficSegments) {
        trafficOverlays.forEach { mapView.overlays.remove(it) }
        trafficOverlays.clear()

        trafficSegments.forEach { segment ->
            val polyline = Polyline().apply {
                addPoint(GeoPoint(segment.start_lat, segment.start_lon))
                addPoint(GeoPoint(segment.end_lat, segment.end_lon))
                color = if (segment.severity == "red") android.graphics.Color.RED else android.graphics.Color.YELLOW
                width = 16f
            }
            mapView.overlays.add(polyline)
            trafficOverlays.add(polyline)
        }
        mapView.invalidate()
    }

    LaunchedEffect(communityPins) {
        communityPinMarkers.forEach { mapView.overlays.remove(it) }
        communityPinMarkers.clear()

        communityPins.forEach { pin ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(pin.lat, pin.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "[${pin.category.uppercase()}] ${pin.title}"
                snippet = pin.description
            }
            mapView.overlays.add(marker)
            communityPinMarkers.add(marker)
        }
        mapView.invalidate()
    }

    LaunchedEffect(crashDetectedEvent) {
        if (crashDetectedEvent) {
            sosCountdown = 30
            while (sosCountdown > 0) {
                delay(1000)
                sosCountdown--
            }
        }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            locationTracker.stopTracking()
            sessionManager.cleanup()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    val controlRailAlignment = if (isLeftHandedMode) Alignment.CenterStart else Alignment.CenterEnd
    val speedLeanAlignment = if (isLeftHandedMode) Alignment.CenterEnd else Alignment.CenterStart
    val isRouteLoaded = currentRoute.isNotEmpty() || destination != null

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                mapView.onResume()
                mapView
            },
            update = { view ->
                view.invalidate()
            },
            modifier = Modifier.fillMaxSize()
        )

        // TOP HUD: Search Bar (automatically shrinks to half height when a route is loaded) + Weather & Time-of-Day Bar
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 36.dp, start = 12.dp, end = 12.dp)
                .zIndex(2f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. Search Bar with Left-Handed Quick Clear & Ergonomic Hand Toggle (half-height when route is loaded)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isRouteLoaded) 32.dp else 56.dp),
                shape = RoundedCornerShape(if (isRouteLoaded) 10.dp else 14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left-side Search / Clear button for easy left-thumb access
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(if (isRouteLoaded) 24.dp else 36.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(if (isRouteLoaded) 16.dp else 22.dp)
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .size(if (isRouteLoaded) 16.dp else 22.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = if (isRouteLoaded) "Search destination..." else "Search destination or trailhead...",
                                style = if (isRouteLoaded) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = if (isRouteLoaded) 12.sp else 15.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (isRouteLoaded) 16.dp else 22.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Handedness toggle chip (defaults to LEFT / Clutch-Hand Mode)
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { isLeftHandedMode = !isLeftHandedMode },
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = if (isRouteLoaded) 6.dp else 8.dp,
                                vertical = if (isRouteLoaded) 2.dp else 5.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.PanTool,
                                contentDescription = "Toggle Left/Right Hand UI",
                                modifier = Modifier.size(if (isRouteLoaded) 11.dp else 14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isLeftHandedMode) "L-HAND" else "R-HAND",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = if (isRouteLoaded) 9.sp else 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Verified Account / Hardware Developer Badge
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showAccountDialog = true },
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = if (isRouteLoaded) 6.dp else 8.dp,
                                vertical = if (isRouteLoaded) 2.dp else 5.dp
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.VerifiedUser,
                                contentDescription = "Verified Account Status",
                                modifier = Modifier.size(if (isRouteLoaded) 11.dp else 14.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (currentUser?.accountType?.name == "DEVELOPER") "DEV✓" else "ACC✓",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = if (isRouteLoaded) 9.sp else 11.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            // Search Results Dropdown
            if (searchResults.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 10.dp)
                ) {
                    Column {
                        searchResults.forEach { result ->
                            ListItem(
                                headlineContent = { Text(result.displayName, fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text(result.type) },
                                leadingContent = { Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.clickable {
                                    searchQuery = ""
                                    searchResults = emptyList()
                                    val destPoint = GeoPoint(result.latitude, result.longitude)
                                    val startPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                                        ?: GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
                                    navigationManager.setDestination(startPoint, destPoint, result.displayName)
                                }
                            )
                        }
                    }
                }
            } else if (searchErrorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                ) {
                    Text(
                        text = searchErrorMessage!!,
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 2. Weather + Time-of-Day + Road Grip Bar (Always visible & updated for current time + forecast)
            riderWeather?.let { wx ->
                WeatherAndTimeHudBanner(
                    weather = wx,
                    isOffline = isOfflineMode,
                    savedRoutesCount = savedRoutes.size,
                    onRefreshWeather = {
                        currentLocation?.let { navigationManager.updateEnvironmentalData(it, forceRefresh = true) }
                    }
                )
            }
        }

        // RIGHT-HAND SIDE SPEED & LEAN ANGLE TELEMETRY ICON POD (Power & HDG removed as requested)
        RightSideSpeedAndLeanPod(
            speedKmh = speedKmh,
            leanAngleDegrees = leanAngleDegrees,
            maxLeanAngleDegrees = maxLeanAngleDegrees,
            modifier = Modifier
                .align(speedLeanAlignment)
                .padding(top = 120.dp, start = 12.dp, end = 12.dp)
                .zIndex(2f)
        )

        // LEFT-HANDED RIDER CONTROL RAIL ("Clutch-Hand Ergonomic Dock")
        // Positioned on the LEFT edge by default and shifted down well below the Weather HUD banner!
        Column(
            modifier = Modifier
                .align(controlRailAlignment)
                .padding(top = 120.dp, start = 12.dp, end = 12.dp)
                .zIndex(2f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RiderDockButton(
                icon = Icons.Default.MyLocation,
                label = "CENTER",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = {
                    val target = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                        ?: currentRoute.firstOrNull()
                    target?.let { mapView.controller.animateTo(it) }
                }
            )

            RiderDockButton(
                icon = Icons.Default.ReportProblem,
                label = "HAZARD",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                onClick = {
                    clickedGeoPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                        ?: GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
                    showAddPinDialog = true
                }
            )

            RiderDockButton(
                icon = Icons.Default.Route,
                label = "ROUTES",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onNavigateToSavedRoutes
            )

            RiderDockButton(
                icon = Icons.Default.Group,
                label = "PACK",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onNavigateToFriends
            )

            if (currentSession != null) {
                RiderDockButton(
                    icon = Icons.Default.Mic,
                    label = "PTT",
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = Color.White,
                    onClick = {
                        sessionManager.sendPttVoiceBeacon("Road ahead clear! Maintain pace.")
                    }
                )
            }
        }

        // Speed Camera Proximity Alert + Weather Route Warnings (Stacked cleanly above bottom card)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = if (isLeftHandedMode) 84.dp else 16.dp,
                    end = if (isLeftHandedMode) 16.dp else 84.dp,
                    bottom = if (destination != null) 235.dp else 24.dp
                )
                .zIndex(3f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            speedCameraAlert?.let { alert ->
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Speed,
                            contentDescription = "Speed Camera",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "SPEED CAMERA IN ${alert.distanceMeters}m",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "Limit: ${alert.camera.max_speed} km/h • Current: ${speedKmh.roundToInt()} km/h",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            weatherWarnings.firstOrNull()?.let { firstWarning ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = firstWarning.warning,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // BOTTOM NAVIGATION HUD CARD (Ergonomically ordered with primary buttons on the LEFT for clutch-hand reach)
        if (destination != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth()
                    .zIndex(2f),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Top Row: Left-Hand Action Buttons + ETA / Distance / Algorithm Telemetry
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left-Handed Primary Controls on the LEFT side of the card!
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Large Stop/Clear Navigation Button on far left
                            Button(
                                onClick = { navigationManager.clearNavigation() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "End Route", tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("END", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onErrorContainer)
                            }

                            // Save & Offline Cache Route Button right next to it
                            FilledTonalButton(
                                onClick = { showSaveRouteDialog = true },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Save & Cache Route")
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SAVE", fontWeight = FontWeight.Bold)
                            }
                        }

                        // ETA & Distance Readout
                        Column(horizontalAlignment = Alignment.End) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = NavigationManager.formatETA(etaSeconds),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = NavigationManager.formatDistance(distanceToDestination),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "Curve ${String.format(Locale.US, "%.1f", routeCurvatureScore)}/10 • $activeRoutingAlgorithm",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Projected Weather at ETA if available
                    riderWeather?.etaForecastSummary?.let { etaWeather ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Schedule,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = etaWeather,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = riderWeather?.roadGripAdvisory ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Glove-Friendly Travel Mode Selector (Scrollable horizontally, starting from left)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TravelModeButton(
                            mode = TravelMode.HYBRID,
                            isSelected = travelMode == TravelMode.HYBRID,
                            icon = Icons.Default.AutoAwesome,
                            onClick = {
                                navigationManager.setTravelMode(TravelMode.HYBRID)
                                val start = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                                    ?: currentRoute.firstOrNull()
                                if (start != null && destination != null) {
                                    navigationManager.setDestination(start, destination!!)
                                }
                            }
                        )
                        TravelModeButton(
                            mode = TravelMode.TWISTY,
                            isSelected = travelMode == TravelMode.TWISTY,
                            icon = Icons.Default.Route,
                            onClick = {
                                navigationManager.setTravelMode(TravelMode.TWISTY)
                                val start = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                                    ?: currentRoute.firstOrNull()
                                if (start != null && destination != null) {
                                    navigationManager.setDestination(start, destination!!)
                                }
                            }
                        )
                        TravelModeButton(
                            mode = TravelMode.DRIVING,
                            isSelected = travelMode == TravelMode.DRIVING,
                            icon = Icons.Default.Speed,
                            onClick = {
                                navigationManager.setTravelMode(TravelMode.DRIVING)
                                val start = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                                    ?: currentRoute.firstOrNull()
                                if (start != null && destination != null) {
                                    navigationManager.setDestination(start, destination!!)
                                }
                            }
                        )
                        TravelModeButton(
                            mode = TravelMode.LOOP,
                            isSelected = travelMode == TravelMode.LOOP,
                            icon = Icons.Default.Autorenew,
                            onClick = {
                                navigationManager.setTravelMode(TravelMode.LOOP)
                                showLoopDialog = true
                            }
                        )
                        TravelModeButton(
                            mode = TravelMode.WALKING,
                            isSelected = travelMode == TravelMode.WALKING,
                            icon = Icons.Default.DirectionsWalk,
                            onClick = {
                                navigationManager.setTravelMode(TravelMode.WALKING)
                                val start = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                                    ?: currentRoute.firstOrNull()
                                if (start != null && destination != null) {
                                    navigationManager.setDestination(start, destination!!)
                                }
                            }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (destination != null) 210.dp else 32.dp)
                .zIndex(4f)
        )
    }

    if (showSaveRouteDialog) {
        AlertDialog(
            onDismissRequest = { showSaveRouteDialog = false },
            title = { Text("Name Saved Expedition Route") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Routes calculated while you have mobile data are automatically saved and cached for offline use. Give this route a custom name:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = routeName,
                        onValueChange = { routeName = it },
                        label = { Text("Route Name (e.g., Pyrenees Pass)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val finalName = routeName.ifBlank {
                        "Route ${SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()).format(java.util.Date())}"
                    }
                    if (currentRoute.isEmpty()) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Cannot save: No route calculated.")
                        }
                    } else {
                        navigationManager.saveCurrentRoute(finalName)
                        val routePointsCopy = currentRoute.toList()
                        coroutineScope.launch {
                            cacheManager.downloadRouteArea(mapView, routePointsCopy, finalName)
                            snackbarHostState.showSnackbar("Route '$finalName' saved to your Routes list!")
                        }
                    }
                    showSaveRouteDialog = false
                    routeName = ""
                }) {
                    Text("Save Route")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveRouteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (requiresProUpgrade) {
        AlertDialog(
            onDismissRequest = { navigationManager.dismissUpgradeDialog() },
            title = { Text("Pro Feature Required") },
            text = {
                Text(
                    "The GraphHopper Extreme Curvature Engine is an Expedition Pro feature (Smart Hybrid mode remains 100% free!).\n\n" +
                        "Since this is an active developer environment, upgrading is £0. Would you like to provision a Pro account now?"
                )
            },
            confirmButton = {
                Button(onClick = {
                    coroutineScope.launch {
                        val authManager = AuthManager(context)
                        val result = authManager.upgradeToPro()
                        navigationManager.dismissUpgradeDialog()
                        when (result) {
                            is com.expedition.app.features.auth.AuthResult.Success -> {
                                snackbarHostState.showSnackbar("Upgraded to Pro! Try calculating your Twisty route again.")
                            }
                            is com.expedition.app.features.auth.AuthResult.VerificationRequired -> {
                                snackbarHostState.showSnackbar(result.message)
                            }
                            is com.expedition.app.features.auth.AuthResult.Error -> {
                                snackbarHostState.showSnackbar(result.message)
                            }
                        }
                    }
                }) {
                    Text("Upgrade for Free")
                }
            },
            dismissButton = {
                TextButton(onClick = { navigationManager.dismissUpgradeDialog() }) {
                    Text("Use Smart Hybrid (Free)")
                }
            }
        )
    }

    if (crashDetectedEvent) {
        AlertDialog(
            onDismissRequest = { locationTracker.dismissCrashAlert() },
            containerColor = MaterialTheme.colorScheme.errorContainer,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("CRASH DETECTED!", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "High-speed riding impact (>6.5G) + bike laydown detected. Sending Emergency SOS alert with GPS coordinates in $sosCountdown seconds...",
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            },
            confirmButton = {
                Button(
                    onClick = { locationTracker.dismissCrashAlert() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("I'M OKAY - CANCEL SOS", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showLoopDialog) {
        AlertDialog(
            onDismissRequest = { showLoopDialog = false },
            title = { Text("Generate Round-Trip Curvature Loop") },
            text = {
                Column {
                    Text("Select target ride distance starting and ending at your current location:")
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(30.0, 50.0, 100.0, 150.0).forEach { distKm ->
                            Button(
                                onClick = {
                                    showLoopDialog = false
                                    val startPt = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                                        ?: GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
                                    navigationManager.generateRoundTripLoop(startPt, distKm)
                                }
                            ) {
                                Text("${distKm.toInt()} km")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLoopDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddPinDialog && clickedGeoPoint != null) {
        var pinTitle by remember { mutableStateOf("") }
        var pinCategory by remember { mutableStateOf("gravel") }
        var pinDesc by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddPinDialog = false },
            title = { Text("Report Rider Hazard / Pin") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        listOf("gravel", "hazard", "scenic", "camp").forEach { cat ->
                            OutlinedButton(
                                onClick = { pinCategory = cat },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (pinCategory == cat) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(cat.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = pinTitle,
                        onValueChange = { pinTitle = it },
                        label = { Text("Title (optional - defaults to category)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pinDesc,
                        onValueChange = { pinDesc = it },
                        label = { Text("Details (e.g., Diesel spill / Gravel on apex)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAddPinDialog = false
                        clickedGeoPoint?.let { pt ->
                            navigationManager.addCommunityPin(
                                title = if (pinTitle.isBlank()) "${pinCategory.replaceFirstChar { it.uppercase() }} Alert" else pinTitle,
                                category = pinCategory,
                                lat = pt.latitude,
                                lon = pt.longitude,
                                description = pinDesc.ifBlank { "Reported by rider" }
                            )
                        }
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("DROP PIN", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPinDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAccountDialog) {
        val fp = remember { authManager.getDeviceFingerprintPreview() }
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            icon = {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Verified Rider Identity", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Name: ${currentUser?.displayName ?: "Developer"}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Email: ${currentUser?.email ?: "Device-Bound Key"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Role: ${currentUser?.accountType?.name ?: "DEVELOPER"} • Pro: ${if (currentUser?.isPro == true) "ACTIVE" else "STANDARD"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Device Hardware Keystore Binding",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = currentUser?.hardwareKeyFingerprint ?: fp,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAccountDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sign Out", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccountDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

/**
 * Large 58dp Glove-Friendly Dock Button for the Left-Handed Rider Rail
 */
@Composable
fun RiderDockButton(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(width = 62.dp, height = 58.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = containerColor.copy(alpha = 0.96f),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

/**
 * Weather + Time-of-Day + Road Surface Grip HUD Banner
 */
@Composable
fun WeatherAndTimeHudBanner(
    weather: RiderWeatherTelemetry,
    isOffline: Boolean,
    savedRoutesCount: Int,
    onRefreshWeather: () -> Unit
) {
    val badgeColor = when (weather.gripSeverity) {
        2 -> MaterialTheme.colorScheme.errorContainer
        1 -> Color(0xFF4E342E)
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val badgeTextColor = when (weather.gripSeverity) {
        2 -> MaterialTheme.colorScheme.onErrorContainer
        1 -> Color(0xFFFFD54F)
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onRefreshWeather() },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Time of Day + Daylight / Sunset status + Weather Temp & Wind
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (weather.isDaylight) Icons.Default.WbSunny else Icons.Default.NightsStay,
                    contentDescription = null,
                    tint = if (weather.isDaylight) Color(0xFFFFB300) else Color(0xFF90CAF9),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${weather.localTimeFormatted} • ${weather.daylightPhase}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (weather.sunsetTimeFormatted != null && weather.isDaylight) {
                            Text(
                                text = " (Sunset ${weather.sunsetTimeFormatted})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        text = "${weather.tempCelsius.roundToInt()}°C • ${weather.conditionLabel} • Wind ${weather.windSpeedKmh.roundToInt()} km/h",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Right: Road Surface Grip Advisory + Offline Readiness Pill
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    color = badgeColor,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = weather.roadGripAdvisory,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Black,
                        color = badgeTextColor
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isOffline) "NO SIGNAL • SAVED ROUTES READY" else "$savedRoutesCount Routes Saved on Device",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = if (isOffline) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
            .height(42.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = mode.displayLabel,
                modifier = Modifier.size(18.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = mode.displayLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Compact Right-Hand Side Speed & Lean Angle Telemetry Icon Pod
 * Replaces the full-width top speed bar and removes POWER and HDG for a cleaner rider view.
 */
@Composable
fun RightSideSpeedAndLeanPod(
    speedKmh: Float,
    leanAngleDegrees: Float,
    maxLeanAngleDegrees: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.width(74.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Speed Icon Pod
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = "Speed",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = String.format(Locale.US, "%.0f", speedKmh),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "KM/H",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Divider(
                modifier = Modifier.padding(horizontal = 6.dp),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // Lean Angle alongside Speed
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.TwoWheeler,
                    contentDescription = "Lean Angle",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = String.format(Locale.US, "%.0f°", leanAngleDegrees),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "LEAN (${maxLeanAngleDegrees.toInt()}°)",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
