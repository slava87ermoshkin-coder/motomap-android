
package ru.motomap.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"
private const val ROUTE_SERVER = "https://valhalla1.openstreetmap.de/route"
private const val GEOCODER = "https://nominatim.openstreetmap.org/search"
private const val PREFS = "motomap_prefs"

private data class Trip(val date: String, val km: Double, val time: String, val max: Int, val avg: Int, val fuel: Double, val elapsedSec: Long = 0L, val fuelCost: Double = 0.0, val maxAltitude: Double = 0.0, val bike: String = "Honda CB650R", val riderKg: Double = 0.0, val passengerKg: Double = 0.0, val luggageKg: Double = 0.0, val fuelL100: Double = 0.0)
private data class RideState(val riding: Boolean = false, val speedKmh: Int = 0, val distanceKm: Double = 0.0, val elapsedSec: Long = 0L, val maxSpeedKmh: Int = 0, val maxAltitude: Double = 0.0)
private data class RouteRequest(val destination: String, val mode: String)
private data class Destination(val lat: Double, val lon: Double, val name: String)
private data class RouteOption(val name: String, val description: String, val result: RouteResult?, val error: String = "")
private data class BikePreset(val name: String, val year: String, val engine: String, val power: String, val torque: String, val weight: String, val tank: String, val fuel: String)

private val bikePresets = listOf(
    BikePreset("Honda CB650R", "2022", "649 см³", "70 кВт / 95 л.с.", "63 Н·м", "202 кг", "15,4 л", "5,0 л/100 км"),
    BikePreset("Honda CBR650R", "2022", "649 см³", "70 кВт / 95 л.с.", "63 Н·м", "208 кг", "15,4 л", "5,0 л/100 км"),
    BikePreset("Yamaha MT-07", "2022", "689 см³", "54 кВт / 73,4 л.с.", "67 Н·м", "184 кг", "14 л", "4,2 л/100 км"),
    BikePreset("Kawasaki Z650", "2022", "649 см³", "50,2 кВт / 68 л.с.", "64 Н·м", "188 кг", "15 л", "4,3 л/100 км"),
    BikePreset("Suzuki SV650", "2022", "645 см³", "56 кВт / 76 л.с.", "64 Н·м", "198 кг", "14,5 л", "4,0 л/100 км"),
    BikePreset("BMW F 900 R", "2022", "895 см³", "73 кВт / 99 л.с.", "92 Н·м", "211 кг", "13 л", "4,2 л/100 км")
)

class MainActivity : ComponentActivity() {
    private var hasLocationPermission by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocationPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        hasLocationPermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        setContent { MotoMapApp(hasLocationPermission) }
    }
}

@Composable
private fun MotoMapApp(hasLocationPermission: Boolean) {
    var tab by remember { mutableIntStateOf(0) }
    var riding by remember { mutableStateOf(false) }
    var rideState by remember { mutableStateOf(RideState()) }
    var routeRequest by remember { mutableStateOf<RouteRequest?>(null) }
    var selectedBike by remember { mutableStateOf(bikePresets.first()) }
    var activeFuelL100 by remember { mutableStateOf(5.0) }
    var activeFuelPrice by remember { mutableStateOf(0.0) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val trips = remember { loadTrips(prefs) }

    val toggleRide: () -> Unit = {
        if (riding) {
            if (rideState.elapsedSec > 0L || rideState.distanceKm > 0.0) {
                val avg = if (rideState.elapsedSec > 0L) (rideState.distanceKm / (rideState.elapsedSec / 3600.0)).roundToInt() else 0
                val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
                val fuel = rideState.distanceKm * activeFuelL100 / 100.0
                val riderKg = prefs.getString("rider_weight", "0")?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                val passengerKg = prefs.getString("passenger_weight", "0")?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                val luggageKg = prefs.getString("luggage_weight", "0")?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                trips.add(Trip(date, rideState.distanceKm, formatDuration(rideState.elapsedSec), rideState.maxSpeedKmh, avg, fuel, rideState.elapsedSec, fuel * activeFuelPrice, rideState.maxAltitude, selectedBike.name, riderKg, passengerKg, luggageKg, activeFuelL100))
                saveTrips(prefs, trips)
            }
            riding = false
            rideState = RideState()
        } else {
            val fallback = selectedBike.fuel.replace(",", ".").substringBefore(" ").toDoubleOrNull() ?: 5.0
            activeFuelL100 = prefs.getString("fuel_consumption", null)?.replace(",", ".")?.toDoubleOrNull() ?: fallback
            activeFuelPrice = prefs.getString("fuel_price", "0")?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
            riding = true
            rideState = RideState(riding = true)
        }
    }

    MaterialTheme {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("🗺️" to "Карта", "🏍️" to "Покататься", "🛣️" to "Маршруты", "📊" to "Статистика", "⚙️" to "Настройки")
                    .forEachIndexed { i, item ->
                        NavigationBarItem(tab == i, { tab = i }, icon = { Text(item.first) }, label = { Text(item.second) })
                    }
            }
        }) { pad ->
            when (tab) {
                0 -> MapScreen(riding, rideState, hasLocationPermission, routeRequest,
                    toggleRide,
                    { rideState = it }, pad)
                1 -> RideScreen(rideState,
                    { toggleRide },
                    { rideState = it },
                    { request -> routeRequest = request; tab = 0 },
                    pad)
                2 -> RoutesScreen(routeRequest, { routeRequest = it; tab = 0 }, pad)
                3 -> StatisticsScreen(trips, pad)
                else -> SettingsScreen(selectedBike, { selectedBike = it }, pad)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun MapScreen(
    riding: Boolean,
    rideState: RideState,
    hasLocationPermission: Boolean,
    routeRequest: RouteRequest?,
    onToggleRide: () -> Unit,
    onRideStateChanged: (RideState) -> Unit,
    pad: PaddingValues
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var destination by remember { mutableStateOf(routeRequest?.destination ?: "") }
    var status by remember { mutableStateOf("") }
    var searchRequest by remember { mutableStateOf<RouteRequest?>(routeRequest) }

    LaunchedEffect(routeRequest) {
        if (routeRequest != null) {
            destination = routeRequest.destination
            searchRequest = routeRequest
        }
    }

    DisposableEffect(lifecycleOwner, mapViewRef) {
        val mapView = mapViewRef ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // MapScreen is often created while the Activity is already RESUMED.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            runCatching { mapView.onStart() }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            runCatching { mapView.onResume() }
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onPause() }
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }

    LaunchedEffect(mapRef, hasLocationPermission) {
        val map = mapRef ?: return@LaunchedEffect
        if (hasLocationPermission) map.style?.let { enableLocationIfAllowed(context, map, true, it) }
    }

    LaunchedEffect(searchRequest, mapRef) {
        val request = searchRequest ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (request.destination.isBlank()) return@LaunchedEffect

        if (request.mode == "Кольцевой") {
            val targetKm = request.destination.removePrefix("loop:").toDoubleOrNull()
            if (targetKm == null || targetKm <= 0.0) return@LaunchedEffect
            status = "Строю кольцевой маршрут…"
            val origin = currentLocation(context)
            if (origin == null) { status = "Не удалось определить GPS-позицию."; return@LaunchedEffect }
            val loop = requestLoopRoute(origin.latitude, origin.longitude, targetKm)
            if (loop == null) status = "Кольцевой маршрут не построен. Проверьте интернет."
            else {
                drawRoute(map, loop)
                status = "Кольцо: " + loop.distanceKm + " км • " + loop.minutes + " мин"
                map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(origin.latitude, origin.longitude), 10.5), 500)
            }
            return@LaunchedEffect
        }
        status = "Ищу пункт назначения…"
        val target = geocode(request.destination)
        if (target == null) {
            status = "Ничего не найдено. Уточните город или адрес."
            return@LaunchedEffect
        }

        map.cameraPosition = CameraPosition.Builder().target(LatLng(target.lat, target.lon)).zoom(12.5).build()
        val origin = currentLocation(context)
        if (origin == null) {
            status = "Найдено: " + target.name + ". Для маршрута включите GPS."
            return@LaunchedEffect
        }

        status = "Строю маршрут: " + request.mode + "…"
        val route = requestRoute(origin.latitude, origin.longitude, target.lat, target.lon, request.mode)
        if (route == null) {
            status = "Маршрут не построен. Проверьте интернет и повторите."
        } else {
            drawRoute(map, route)
            status = request.mode + ": " + route.distanceKm + " км • " + route.minutes + " мин"
            map.cameraPosition = CameraPosition.Builder().target(LatLng(target.lat, target.lon)).zoom(10.5).build()
        }
    }

    Box(Modifier.fillMaxSize().padding(pad)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                MapView(ctx).also { view ->
                    mapViewRef = view
                    view.onCreate(null)
                    view.getMapAsync { map ->
                        mapRef = map
                        map.setStyle(MAP_STYLE) { style ->
                            enableLocationIfAllowed(ctx, map, hasLocationPermission, style)
                        }
                    }
                }
            },
            update = {}
        )

        FloatingActionButton(
            onClick = {
                if (!hasLocationPermission) return@FloatingActionButton
                scope.launch {
                    val location = lastKnownLocation(context) ?: currentLocation(context)
                    val map = mapRef
                    if (location != null && map != null) {
                        runCatching {
                            val style = map.style
                            if (style != null) {
                                enableLocationIfAllowed(context, map, true, style)
                                val component = map.locationComponent
                                if (component.isLocationComponentActivated && component.isLocationComponentEnabled) {
                                    component.forceLocationUpdate(location)
                                    component.cameraMode = CameraMode.TRACKING
                                    map.animateCamera(
                                        org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                                            LatLng(location.latitude, location.longitude), 16.0
                                        ),
                                        500
                                    )
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 150.dp, end = 12.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text("⌾", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }

        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), tonalElevation = 5.dp) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { destination = it },
                        placeholder = { Text("Куда едем?") },
                        label = { Text("Город или адрес") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                            if (destination.isNotBlank()) searchRequest = RouteRequest(destination.trim(), routeRequest?.mode ?: "Мото")
                        }, onDone = {
                            if (destination.isNotBlank()) searchRequest = RouteRequest(destination.trim(), routeRequest?.mode ?: "Мото")
                        }),
                        trailingIcon = {
                            TextButton(
                                enabled = destination.isNotBlank(),
                                onClick = { searchRequest = RouteRequest(destination.trim(), routeRequest?.mode ?: "Мото") }
                            ) { Text("Найти") }
                        }
                    )
                    if (routeRequest != null) Text("Режим: " + routeRequest.mode, fontWeight = FontWeight.Bold)
                    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (riding) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(rideState.speedKmh.toString() + " км/ч", fontWeight = FontWeight.Bold)
                    Text("%.1f км".format(Locale.US, rideState.distanceKm))
                    Text(formatDuration(rideState.elapsedSec))
                }
                Button(onClick = onToggleRide, Modifier.fillMaxWidth().height(54.dp)) {
                    Text(if (riding) "ЗАВЕРШИТЬ ПОЕЗДКУ" else "НАЧАТЬ ПОЕЗДКУ", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Keep the trip timer running independently of GPS callbacks.
    val latestRideState by rememberUpdatedState(rideState)
    val latestOnRideStateChanged by rememberUpdatedState(onRideStateChanged)
    LaunchedEffect(riding) {
        if (riding) {
            while (true) {
                delay(1000L)
                val current = latestRideState
                if (current.riding) {
                    latestOnRideStateChanged(current.copy(elapsedSec = current.elapsedSec + 1L))
                }
            }
        }
    }

    RideLocationTracker(context, riding, rideState, onRideStateChanged, mapRef)
}

@SuppressLint("MissingPermission")
private fun enableLocationIfAllowed(context: Context, map: MapLibreMap, allowed: Boolean, style: Style) {
    if (!allowed) return
    runCatching {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) {
            val options = LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(LocationComponentOptions.builder(context).pulseEnabled(true).build())
                .useDefaultLocationEngine(true)
                .build()
            component.activateLocationComponent(options)
        }
        component.isLocationComponentEnabled = true
        component.setMaxAnimationFps(60)
        component.cameraMode = CameraMode.TRACKING
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun RideLocationTracker(
    context: Context,
    riding: Boolean,
    state: RideState,
    onChanged: (RideState) -> Unit,
    map: MapLibreMap?
) {
    val manager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    DisposableEffect(riding) {
        if (!riding) return@DisposableEffect onDispose {}

        var last: Location? = null
        var distanceMeters = state.distanceKm * 1000.0
        var maxSpeed = state.maxSpeedKmh
        var maxAltitude = state.maxAltitude
        val start = System.currentTimeMillis() - state.elapsedSec * 1000L

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                map?.let { m ->
                    runCatching {
                        m.locationComponent.forceLocationUpdate(location)
                        m.locationComponent.cameraMode = CameraMode.TRACKING
                    }
                }

                val previous = last
                if (previous != null && location.accuracy <= 35f && previous.accuracy <= 35f) {
                    val d = previous.distanceTo(location).toDouble()
                    if (d in 0.5..300.0) distanceMeters += d
                }

                val speed = when {
                    location.hasSpeed() && location.speed >= 0f -> location.speed * 3.6f
                    previous != null -> {
                        val dt = (location.time - previous.time).coerceAtLeast(250L)
                        previous.distanceTo(location) / dt * 3.6f
                    }
                    else -> 0f
                }.roundToInt().coerceAtLeast(0)

                maxSpeed = maxOf(maxSpeed, speed)
                if (location.hasAltitude()) maxAltitude = maxOf(maxAltitude, location.altitude)
                onChanged(
                    RideState(
                        riding = true,
                        speedKmh = speed,
                        distanceKm = distanceMeters / 1000.0,
                        elapsedSec = (System.currentTimeMillis() - start) / 1000L,
                        maxSpeedKmh = maxSpeed,
                        maxAltitude = maxAltitude
                    )
                )
                last = location
            }
        }

        var registered = false
        runCatching {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0.5f, listener)
                registered = true
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, listener)
                registered = true
            }
        }

        if (!registered) onChanged(state.copy(riding = true))
        onDispose { runCatching { manager.removeUpdates(listener) } }
    }
}

private fun loadTrips(prefs: android.content.SharedPreferences): androidx.compose.runtime.snapshots.SnapshotStateList<Trip> {
    val list = mutableStateListOf<Trip>()
    val raw = prefs.getString("trips", null) ?: return list
    runCatching {
        val a = JSONArray(raw)
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            list.add(Trip(o.getString("date"), o.getDouble("km"), o.getString("time"), o.getInt("max"), o.getInt("avg"), o.getDouble("fuel"), o.optLong("elapsedSec", 0L), o.optDouble("fuelCost", 0.0), o.optDouble("maxAltitude", 0.0), o.optString("bike", "Honda CB650R"), o.optDouble("riderKg", 0.0), o.optDouble("passengerKg", 0.0), o.optDouble("luggageKg", 0.0), o.optDouble("fuelL100", 0.0)))
        }
    }
    return list
}

private fun saveTrips(prefs: android.content.SharedPreferences, trips: List<Trip>) {
    val a = JSONArray()
    trips.take(100).forEach {
        a.put(JSONObject().put("date", it.date).put("km", it.km).put("time", it.time).put("max", it.max).put("avg", it.avg).put("fuel", it.fuel).put("elapsedSec", it.elapsedSec).put("fuelCost", it.fuelCost).put("maxAltitude", it.maxAltitude).put("bike", it.bike).put("riderKg", it.riderKg).put("passengerKg", it.passengerKg).put("luggageKg", it.luggageKg).put("fuelL100", it.fuelL100))
    }
    prefs.edit().putString("trips", a.toString()).apply()
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun RideScreen(state: RideState, toggle: () -> Unit, onChanged: (RideState) -> Unit, onBuildLoop: (RouteRequest) -> Unit, pad: PaddingValues) {
    var mode by remember { mutableStateOf("Время") }
    var value by remember { mutableStateOf("1") }
    val timeOptions = listOf("1","2","3","4","5","6","8")
    val distanceOptions = listOf("50","100","150","200","300","400","500")
    Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Покататься", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Выберите цель поездки", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == "Время", onClick = { mode = "Время"; value = "1" }, label = { Text("⏱ Время") })
            FilterChip(selected = mode == "Расстояние", onClick = { mode = "Расстояние"; value = "200" }, label = { Text("📏 Расстояние") })
        }
        val options = if (mode == "Время") timeOptions else distanceOptions
        LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { v ->
                Card(Modifier.fillMaxWidth().selectable(selected = value == v, onClick = { value = v }, role = Role.RadioButton)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == v, onClick = { value = v })
                        Text(if (mode == "Время") v + " ч" else v + " км", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        OutlinedTextField(value, { value = it.filter { c -> c.isDigit() }.take(4) }, label = { Text(if (mode == "Время") "Свое время, часов" else "Свое расстояние, км") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Старт: 📍 текущее местоположение\nМаршрут: A → B → C → D → A")
        Button(onClick = {
            val n = value.toDoubleOrNull() ?: return@Button
            val km = if (mode == "Время") n * 50.0 else n
            onBuildLoop(RouteRequest("loop:" + "%.1f".format(Locale.US, km), "Кольцевой"))
        }, enabled = value.toDoubleOrNull()?.let { it > 0 } == true, modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text("НАЙТИ МАРШРУТ", fontWeight = FontWeight.Bold)
        }
        Text("Маршрут строится от текущей позиции и возвращается в точку старта. Фактические километры и время показываются на карте.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
private fun Stat(title: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(17.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}
@Composable
private fun RoutesScreen(routeRequest: RouteRequest?, onBuild: (RouteRequest) -> Unit, pad: PaddingValues) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var destination by remember { mutableStateOf(routeRequest?.destination ?: "") }
    var selectedMode by remember { mutableStateOf(routeRequest?.mode ?: "Мото") }
    var calculateKey by remember { mutableIntStateOf(0) }
    var calculating by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<List<RouteOption>>(emptyList()) }
    val modes = listOf(
        "Быстрый" to "Минимальное время",
        "Мото" to "Баланс скорости и мото-дорог",
        "Извилистый" to "Больше второстепенных дорог"
    )
    LaunchedEffect(calculateKey) {
        if (calculateKey == 0 || destination.isBlank()) return@LaunchedEffect
        calculating = true
        options = emptyList()
        val target = geocode(destination.trim())
        val origin = currentLocation(context)
        if (target == null || origin == null) {
            options = modes.map { RouteOption(it.first, it.second, null, if (target == null) "Адрес не найден" else "GPS недоступен") }
            calculating = false
            return@LaunchedEffect
        }
        options = modes.map { (name, description) ->
            val route = requestRoute(origin.latitude, origin.longitude, target.lat, target.lon, name)
            RouteOption(name, description, route, if (route == null) "Не удалось построить" else "")
        }
        calculating = false
    }
    Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Маршрут", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = destination, onValueChange = { destination = it }, placeholder = { Text("Введите город или адрес") }, label = { Text("Пункт назначения") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (destination.isNotBlank()) calculateKey++ }, onDone = { if (destination.isNotBlank()) calculateKey++ }))
        Button(onClick = { calculateKey++ }, enabled = destination.trim().isNotEmpty() && !calculating, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(if (calculating) "РАССЧИТЫВАЮ…" else "РАССЧИТАТЬ МАРШРУТЫ")
        }
        if (options.isNotEmpty()) Text("Время и расстояние по каждому типу", fontWeight = FontWeight.Bold)
        options.forEach { option ->
            val selected = selectedMode == option.name
            Card(Modifier.fillMaxWidth().clickable { selectedMode = option.name }, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(option.name, fontWeight = FontWeight.Bold)
                    Text(option.description, style = MaterialTheme.typography.bodySmall)
                    if (option.result != null) {
                        Text("Расстояние: " + option.result.distanceKm + " км", fontWeight = FontWeight.Bold)
                        Text("Время: " + option.result.minutes + " мин", fontWeight = FontWeight.Bold)
                    } else Text(option.error)
                    if (selected && option.result != null) Button(onClick = { onBuild(RouteRequest(destination.trim(), option.name)) }, Modifier.fillMaxWidth()) { Text("ПОКАЗАТЬ НА КАРТЕ") }
                }
            }
        }
    }
}
@Composable
private fun StatisticsScreen(trips: androidx.compose.runtime.snapshots.SnapshotStateList<Trip>, pad: PaddingValues) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var refresh by remember { mutableIntStateOf(0) }
    val now = remember(refresh) { System.currentTimeMillis() }
    var historyOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Trip?>(null) }
    fun millis(date: String) = runCatching { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).parse(date)?.time ?: 0L }.getOrDefault(0L)
    fun period(days: Long) = trips.filter { millis(it.date) >= now - days * 24L * 60L * 60L * 1000L }
    fun km(x: List<Trip>) = x.sumOf { it.km }
    fun hours(x: List<Trip>) = x.sumOf { it.elapsedSec } / 3600.0
    fun fuel(x: List<Trip>) = x.sumOf { it.fuel }
    fun cost(x: List<Trip>) = x.sumOf { it.fuelCost }
    fun avgFuel(x: List<Trip>) = if (km(x) > 0) fuel(x) * 100.0 / km(x) else 0.0
    @Composable fun Block(title: String, x: List<Trip>) {
        var open by remember(title) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth().clickable { open = !open }) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(if (open) "▲" else "▼", fontWeight = FontWeight.Bold)
                }
                Text("%.1f км • %d поездок".format(Locale.US, km(x), x.size), fontWeight = FontWeight.Bold)
                if (open) {
                    Stat("Часы", "%.1f ч".format(Locale.US, hours(x)))
                    Stat("Средний расход", "%.2f л/100 км".format(Locale.US, avgFuel(x)))
                    Stat("Топливные затраты", "%.2f ₽".format(Locale.US, cost(x)))
                    Stat("Максимальная скорость", (x.maxOfOrNull { it.max } ?: 0).toString() + " км/ч")
                    Stat("Максимальная высота", (x.maxOfOrNull { it.maxAltitude } ?: 0.0).roundToInt().toString() + " м")
                    Stat("Самый длинный маршрут", "%.1f км".format(Locale.US, x.maxOfOrNull { it.km } ?: 0.0))
                }
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Статистика", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Block("За неделю", period(7))
            Block("За месяц", period(30))
            Block("За сезон", period(180))
            Block("Всего", trips)
            Card(Modifier.fillMaxWidth().clickable { historyOpen = !historyOpen }) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("История разовых поездок", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(if (historyOpen) "▲" else "▼", fontWeight = FontWeight.Bold)
                }
            }
            if (historyOpen) {
                if (trips.isEmpty()) Text("История пока пуста.")
                trips.forEach { t ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Дата и время: " + t.date, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(t.bike + " • %.1f км • %s".format(Locale.US, t.km, t.time), fontSize = 13.sp)
                            Text("Водитель: %.0f кг • пассажир: %.0f кг • багаж: %.0f кг".format(Locale.US, t.riderKg, t.passengerKg, t.luggageKg), fontSize = 12.sp)
                            Text("Расход: %.2f л/100 км • топливо: %.2f л • %.2f ₽".format(Locale.US, t.fuelL100, t.fuel, t.fuelCost), fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { editing = t }) { Text("Редактировать") }
                                TextButton(onClick = { trips.remove(t); saveTrips(prefs, trips); refresh++ }) { Text("Удалить") }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { trips.clear(); saveTrips(prefs, trips); refresh++ }, Modifier.fillMaxWidth()) { Text("УДАЛИТЬ ВСЮ ИСТОРИЮ") }
            }
        }
    }
    editing?.let { trip -> TripEditorDialog(trip, { editing = null }) { updated ->
        val index = trips.indexOfFirst { it === trip }
        if (index >= 0) trips[index] = updated
        saveTrips(prefs, trips)
        refresh++
        editing = null
    } }
}
@Composable
private fun TripEditorDialog(trip: Trip, onDismiss: () -> Unit, onSave: (Trip) -> Unit) {
    var km by remember { mutableStateOf(trip.km.toString()) }
    var max by remember { mutableStateOf(trip.max.toString()) }
    var bike by remember { mutableStateOf(trip.bike) }
    var rider by remember { mutableStateOf(trip.riderKg.toString()) }
    var passenger by remember { mutableStateOf(trip.passengerKg.toString()) }
    var luggage by remember { mutableStateOf(trip.luggageKg.toString()) }
    var fuelL100 by remember { mutableStateOf(trip.fuelL100.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Редактирование поездки") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Field("Байк", bike) { bike = it }
            Field("Километры", km) { km = it }
            Field("Максимальная скорость", max) { max = it }
            Field("Водитель, кг", rider) { rider = it }
            Field("Пассажир, кг", passenger) { passenger = it }
            Field("Багаж, кг", luggage) { luggage = it }
            Field("Расход, л/100 км", fuelL100) { fuelL100 = it }
        } },
        confirmButton = { TextButton(onClick = {
            val newKm = km.replace(",", ".").toDoubleOrNull() ?: trip.km
            val newFuel = fuelL100.replace(",", ".").toDoubleOrNull() ?: trip.fuelL100
            val newFuelLiters = newKm * newFuel / 100.0
            onSave(trip.copy(km = newKm, max = max.toIntOrNull() ?: trip.max, avg = if (trip.elapsedSec > 0) (newKm / (trip.elapsedSec / 3600.0)).roundToInt() else trip.avg, fuel = newFuelLiters, fuelL100 = newFuel, fuelCost = if (trip.fuel > 0) trip.fuelCost * (newFuelLiters / trip.fuel) else trip.fuelCost, bike = bike, riderKg = rider.replace(",", ".").toDoubleOrNull() ?: trip.riderKg, passengerKg = passenger.replace(",", ".").toDoubleOrNull() ?: trip.passengerKg, luggageKg = luggage.replace(",", ".").toDoubleOrNull() ?: trip.luggageKg))
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
@Composable
private fun SettingsScreen(selectedBike: BikePreset, onBikeSelected: (BikePreset) -> Unit, pad: PaddingValues) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var expanded by remember { mutableStateOf(false) }
    var rider by remember { mutableStateOf(prefs.getString("rider_weight", "80") ?: "80") }
    var passenger by remember { mutableStateOf(prefs.getString("passenger_weight", "0") ?: "0") }
    var luggage by remember { mutableStateOf(prefs.getString("luggage_weight", "0") ?: "0") }
    var fuel by remember { mutableStateOf(prefs.getString("fuel_consumption", null) ?: selectedBike.fuel.replace(",", ".").substringBefore(" ")) }
    var price by remember { mutableStateOf(prefs.getString("fuel_price", "0") ?: "0") }
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Настройки", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Параметры считываются заново при каждом нажатии НАЧАТЬ ПОЕЗДКУ. Уже начатая поездка использует свой снимок параметров.", style = MaterialTheme.typography.bodySmall)
            Text("Мотоцикл", fontWeight = FontWeight.Bold)
            Box {
                OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) { Text(selectedBike.name + " • " + selectedBike.year) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    bikePresets.forEach { bike ->
                        DropdownMenuItem(text = { Text(bike.name + " • " + bike.year) }, onClick = { onBikeSelected(bike); expanded = false })
                    }
                }
            }
            Spec("Двигатель", selectedBike.engine)
            Spec("Мощность", selectedBike.power)
            Spec("Крутящий момент", selectedBike.torque)
            Spec("Снаряжённая масса", selectedBike.weight)
            Spec("Бак", selectedBike.tank)
            Spec("Заводской расход", selectedBike.fuel)
            Text("Параметры следующей поездки", fontWeight = FontWeight.Bold)
            Field("Вес водителя, кг", rider) { rider = it; prefs.edit().putString("rider_weight", it).apply() }
            Field("Вес пассажира, кг", passenger) { passenger = it; prefs.edit().putString("passenger_weight", it).apply() }
            Field("Вес багажа, кг", luggage) { luggage = it; prefs.edit().putString("luggage_weight", it).apply() }
            Field("Расход, л/100 км", fuel) { fuel = it; prefs.edit().putString("fuel_consumption", it).apply() }
            Field("Цена топлива, ₽/л", price) { price = it; prefs.edit().putString("fuel_price", it).apply() }
            OutlinedButton(onClick = {
                prefs.edit().putString("rider_weight", rider).putString("passenger_weight", passenger).putString("luggage_weight", luggage).putString("fuel_consumption", fuel).putString("fuel_price", price).apply()
            }, Modifier.fillMaxWidth()) { Text("СОХРАНИТЬ ПАРАМЕТРЫ") }
            Text("Если расход не задан, при старте используется заводской расход выбранного мотоцикла. Старые значения другой поездки не переносятся.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
private fun Spec(title: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

private data class RouteResult(val points: List<LatLng>, val distanceKm: String, val minutes: String)

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    val cached = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { System.currentTimeMillis() - it.time < 120000L }
        .maxByOrNull { it.time }
    if (cached != null) return cached
    if (providers.isEmpty()) return null
    return kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            var best: Location? = null
            var done = false
            lateinit var listener: LocationListener
            fun finish(loc: Location?) {
                if (done) return
                done = true
                runCatching { manager.removeUpdates(listener) }
                if (cont.isActive) cont.resume(loc)
            }
            listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (best == null || location.accuracy < best!!.accuracy) best = location
                    if (location.accuracy <= 50f) finish(location)
                }
            }
            runCatching { providers.forEach { manager.requestLocationUpdates(it, 1000L, 1f, listener) } }
                .onFailure { finish(null) }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish(best) }, 10000L)
            cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
        }
    }
}

@SuppressLint("MissingPermission")
private fun lastKnownLocation(context: Context): Location? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()
}

private suspend fun geocode(query: String): Destination? = withContext(Dispatchers.IO) {
    // Photon first; Nominatim is a fallback if Photon is temporarily unavailable.
    val photon = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL("https://photon.komoot.io/api/?limit=1&lang=ru&q=" + encoded)
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "MotoMap/0.2 Android navigation app")
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.inputStream.use { input ->
            val body = BufferedReader(InputStreamReader(input)).readText()
            val feature = JSONObject(body).getJSONArray("features").optJSONObject(0)
                ?: return@use null
            val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
            val lon = coordinates.optDouble(0)
            val lat = coordinates.optDouble(1)
            if (!lat.isFinite() || !lon.isFinite()) return@use null
            val props = feature.optJSONObject("properties")
            val name = listOf(
                props?.optString("name").orEmpty(),
                props?.optString("city").orEmpty(),
                props?.optString("state").orEmpty()
            ).filter { it.isNotBlank() }.distinct().joinToString(", ")
            Destination(lat, lon, if (name.isBlank()) query else name)
        }
    }.getOrNull()

    if (photon != null) return@withContext photon

    runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL(GEOCODER + "?format=jsonv2&limit=1&addressdetails=1&accept-language=ru&q=" + encoded)
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "MotoMap/0.2 Android navigation app")
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.inputStream.use { input ->
            val body = BufferedReader(InputStreamReader(input)).readText()
            val item = JSONArray(body).optJSONObject(0) ?: return@use null
            Destination(item.optDouble("lat"), item.optDouble("lon"), item.optString("display_name", query))
        }
    }.getOrNull()
}

private suspend fun requestLoopRoute(fromLat: Double, fromLon: Double, targetKm: Double): RouteResult? = withContext(Dispatchers.IO) {
    runCatching {
        val radiusKm = (targetKm / (2.0 * Math.PI)).coerceIn(3.0, 120.0)
        val latScale = 111.0
        val lonScale = 111.0 * kotlin.math.cos(Math.toRadians(fromLat)).coerceAtLeast(0.15)
        fun p(angle: Double): Pair<Double, Double> {
            val r = Math.toRadians(angle)
            return Pair(fromLat + radiusKm * kotlin.math.cos(r) / latScale, fromLon + radiusKm * kotlin.math.sin(r) / lonScale)
        }
        val locs = listOf(Pair(fromLat, fromLon), p(0.0), p(90.0), p(180.0), Pair(fromLat, fromLon))
        val locations = JSONArray()
        locs.forEach { loc -> locations.put(JSONObject().put("lat", loc.first).put("lon", loc.second).put("type", "break")) }
        val json = JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("units", "kilometers")
            .put("shape_format", "polyline6")
            .put("costing_options", JSONObject().put("auto", JSONObject().put("use_highways", false).put("shortest", false)))
        val connection = URL(ROUTE_SERVER).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Client-Id", "motomap-android")
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.outputStream.use { it.write(json.toString().toByteArray()) }
        val body = BufferedReader(InputStreamReader(connection.inputStream)).readText()
        val trip = JSONObject(body).getJSONObject("trip")
        val summary = trip.getJSONObject("summary")
        val legs = trip.getJSONArray("legs")
        val points = ArrayList<LatLng>()
        for (i in 0 until legs.length()) {
            val part = decodePolyline6(legs.getJSONObject(i).getString("shape"))
            if (points.isEmpty()) points.addAll(part) else points.addAll(part.drop(1))
        }
        RouteResult(points, "%.1f".format(Locale.US, summary.getDouble("length")), (summary.getDouble("time") / 60.0).roundToInt().toString())
    }.getOrNull()
}
private suspend fun requestRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: String): RouteResult? = withContext(Dispatchers.IO) {
    runCatching {
        val options = when (mode) {
            "Быстрый" -> "\"use_highways\":true,\"shortest\":false"
            "Извилистый" -> "\"use_highways\":false,\"shortest\":false,\"top_speed\":45,\"use_tolls\":false"

            else -> "\"use_highways\":false,\"shortest\":false,\"top_speed\":90"
        }
        val json = "{\"locations\":[{\"lat\":" + fromLat + ",\"lon\":" + fromLon + ",\"type\":\"break\"},{\"lat\":" + toLat + ",\"lon\":" + toLon + ",\"type\":\"break\"}],\"costing\":\"auto\",\"units\":\"kilometers\",\"shape_format\":\"polyline6\",\"costing_options\":{\"auto\":{" + options + "}}}"
        val connection = URL(ROUTE_SERVER).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("X-Client-Id", "motomap-android")
        connection.connectTimeout = 12000
        connection.readTimeout = 15000
        connection.outputStream.use { it.write(json.toByteArray()) }
        val body = BufferedReader(InputStreamReader(connection.inputStream)).readText()
        val trip = JSONObject(body).getJSONObject("trip")
        val summary = trip.getJSONObject("summary")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        RouteResult(
            decodePolyline6(leg.getString("shape")),
            "%.1f".format(Locale.US, summary.getDouble("length")),
            (summary.getDouble("time") / 60.0).roundToInt().toString()
        )
    }.getOrNull()
}

private fun decodePolyline6(encoded: String): List<LatLng> {
    val result = ArrayList<LatLng>()
    var index = 0
    var lat = 0
    var lon = 0
    while (index < encoded.length) {
        var shift = 0
        var value = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            value = value or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if ((value and 1) != 0) -(value shr 1) - 1 else value shr 1
        shift = 0
        value = 0
        do {
            b = encoded[index++].code - 63
            value = value or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lon += if ((value and 1) != 0) -(value shr 1) - 1 else value shr 1
        result.add(LatLng(lat / 1_000_000.0, lon / 1_000_000.0))
    }
    return result
}

private fun drawRoute(map: MapLibreMap, result: RouteResult) {
    val style = map.style ?: return
    val feature = Feature.fromGeometry(
        LineString.fromLngLats(
            result.points.map { Point.fromLngLat(it.longitude, it.latitude) }
        )
    )
    style.removeLayer("motomap-route-line")
    style.removeSource("motomap-route-source")
    style.addSource(GeoJsonSource("motomap-route-source", feature))
    style.addLayer(LineLayer("motomap-route-line", "motomap-route-source").withProperties(lineColor("#1976D2"), lineWidth(6f)))
}
