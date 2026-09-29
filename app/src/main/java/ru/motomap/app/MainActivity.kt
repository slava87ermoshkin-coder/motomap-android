
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
import kotlinx.coroutines.withContext
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
import kotlin.math.roundToInt

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/bright"
private const val ROUTE_SERVER = "https://valhalla1.openstreetmap.de/route"
private const val GEOCODER = "https://nominatim.openstreetmap.org/search"

private data class Trip(val date: String, val km: Double, val time: String, val max: Int, val avg: Int, val fuel: Double)
private data class RideState(val riding: Boolean = false, val speedKmh: Int = 0, val distanceKm: Double = 0.0, val elapsedSec: Long = 0L, val maxSpeedKmh: Int = 0)
private data class RouteRequest(val destination: String, val mode: String)
private data class Destination(val lat: Double, val lon: Double, val name: String)
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

    val trips = remember { mutableStateListOf(
        Trip("24.09.2026", 286.4, "4:17", 143, 67, 14.9),
        Trip("19.09.2026", 174.2, "2:51", 128, 61, 8.9)
    ) }

    MaterialTheme {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("🗺️" to "Карта", "🏍️" to "Поездка", "🛣️" to "Маршруты", "📊" to "Статистика", "⚙️" to "Настройки")
                    .forEachIndexed { i, item ->
                        NavigationBarItem(tab == i, { tab = i }, icon = { Text(item.first) }, label = { Text(item.second) })
                    }
            }
        }) { pad ->
            when (tab) {
                0 -> MapScreen(riding, rideState, hasLocationPermission, routeRequest,
                    { riding = !riding; rideState = if (riding) RideState(riding = true) else RideState() },
                    { rideState = it }, pad)
                1 -> RideScreen(rideState,
                    { riding = !riding; rideState = if (riding) RideState(riding = true) else RideState() }, pad)
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
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(searchRequest, mapRef) {
        val request = searchRequest ?: return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        if (request.destination.isBlank()) return@LaunchedEffect

        status = "Ищу пункт назначения…"
        val target = geocode(request.destination)
        if (target == null) {
            status = "Ничего не найдено. Уточните город или адрес."
            return@LaunchedEffect
        }

        map.animateCamera(CameraPosition.Builder().target(LatLng(target.lat, target.lon)).zoom(12.5).build(), 800)
        val origin = lastKnownLocation(context)
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
            map.animateCamera(CameraPosition.Builder().target(LatLng(target.lat, target.lon)).zoom(10.5).build(), 700)
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

    RideLocationTracker(context, riding, rideState, onRideStateChanged)
}

@SuppressLint("MissingPermission")
private fun enableLocationIfAllowed(context: Context, map: MapLibreMap, allowed: Boolean, style: Style) {
    if (!allowed) return
    runCatching {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) {
            val options = LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(LocationComponentOptions.builder(context).pulseEnabled(true).build())
                .useDefaultLocationEngine(true).build()
            component.activateLocationComponent(options)
        }
        component.isLocationComponentEnabled = true
        component.cameraMode = CameraMode.TRACKING
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun RideLocationTracker(context: Context, riding: Boolean, current: RideState, onChanged: (RideState) -> Unit) {
    val manager = remember(context) { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var startTime by remember { mutableLongStateOf(0L) }

    DisposableEffect(riding) {
        if (!riding) {
            lastLocation = null
            startTime = 0L
            return@DisposableEffect onDispose {}
        }
        startTime = System.currentTimeMillis()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val previous = lastLocation
                val segment = if (previous != null && location.accuracy <= 35f) previous.distanceTo(location).toDouble() else 0.0
                val speed = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt() else 0
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000L).coerceAtLeast(0L)
                onChanged(current.copy(riding = true, speedKmh = speed, distanceKm = current.distanceKm + segment / 1000.0, elapsedSec = elapsed, maxSpeedKmh = maxOf(current.maxSpeedKmh, speed)))
                lastLocation = location
            }
        }
        runCatching { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2f, listener) }
        onDispose { runCatching { manager.removeUpdates(listener) } }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun RideScreen(state: RideState, toggle: () -> Unit, pad: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Текущая поездка", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Stat("Скорость", state.speedKmh.toString() + " км/ч")
        Stat("Расстояние", "%.1f км".format(Locale.US, state.distanceKm))
        Stat("Время движения", formatDuration(state.elapsedSec))
        Stat("Максимальная скорость", state.maxSpeedKmh.toString() + " км/ч")
        Stat("Средняя скорость", if (state.elapsedSec > 0) ((state.distanceKm / (state.elapsedSec / 3600.0)).roundToInt().toString() + " км/ч") else "0 км/ч")
        Button(onClick = toggle, Modifier.fillMaxWidth().height(56.dp)) { Text(if (state.riding) "Завершить поездку" else "Начать поездку") }
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
    var destination by remember { mutableStateOf(routeRequest?.destination ?: "") }
    var selectedMode by remember { mutableStateOf(routeRequest?.mode ?: "Мото") }
    val modes = listOf(
        "Быстрый" to "Минимальное время",
        "Мото" to "Баланс скорости и мото-дорог",
        "Извилистый" to "Больше второстепенных дорог",
        "Красивый" to "Приоритет живописных дорог"
    )

    Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Маршрут", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = destination,
            onValueChange = { destination = it },
            placeholder = { Text("Введите город или адрес") },
            label = { Text("Пункт назначения") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text("Тип маршрута", fontWeight = FontWeight.Bold)

        modes.forEach { (name, description) ->
            Card(Modifier.fillMaxWidth().selectable(selected = selectedMode == name, onClick = { selectedMode = name }, role = Role.RadioButton)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selectedMode == name, onClick = { selectedMode = name })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(name, fontWeight = FontWeight.Bold)
                        Text(description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text("Выбрано: " + selectedMode, fontWeight = FontWeight.Bold)
        Button(
            onClick = { onBuild(RouteRequest(destination.trim(), selectedMode)) },
            enabled = destination.trim().isNotEmpty(),
            Modifier.fillMaxWidth().height(56.dp)
        ) { Text("ПОСТРОИТЬ МАРШРУТ") }
    }
}

@Composable
private fun StatisticsScreen(trips: List<Trip>, pad: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Статистика", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Stat("Поездок", trips.size.toString())
            Stat("Расстояние", "%.1f км".format(Locale.US, trips.sumOf { it.km }))
            Stat("Топливо", "%.1f л".format(Locale.US, trips.sumOf { it.fuel }))
            Text("История", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        items(trips) { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(t.date, fontWeight = FontWeight.Bold)
                    Text("%.1f км • %s".format(Locale.US, t.km, t.time))
                    Text("Средняя %d км/ч • максимум %d км/ч".format(t.avg, t.max))
                    Text("Топливо %.1f л".format(Locale.US, t.fuel))
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(selectedBike: BikePreset, onBikeSelected: (BikePreset) -> Unit, pad: PaddingValues) {
    var expanded by remember { mutableStateOf(false) }
    var rider by remember { mutableStateOf("80") }
    var passenger by remember { mutableStateOf("0") }
    var luggage by remember { mutableStateOf("0") }

    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Мотоцикл", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Выберите модель — заводские характеристики заполнятся автоматически.", style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
                    Text(selectedBike.name + " • " + selectedBike.year)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    bikePresets.forEach { bike ->
                        DropdownMenuItem(
                            text = { Text(bike.name + " • " + bike.year) },
                            onClick = { onBikeSelected(bike); expanded = false }
                        )
                    }
                }
            }
            Spec("Двигатель", selectedBike.engine)
            Spec("Мощность", selectedBike.power)
            Spec("Крутящий момент", selectedBike.torque)
            Spec("Снаряжённая масса", selectedBike.weight)
            Spec("Бак", selectedBike.tank)
            Spec("Заводской расход", selectedBike.fuel)
            Spacer(Modifier.height(8.dp))
            Text("Параметры поездки", fontWeight = FontWeight.Bold)
            Field("Вес водителя, кг", rider) { rider = it }
            Field("Вес пассажира, кг", passenger) { passenger = it }
            Field("Вес багажа, кг", luggage) { luggage = it }
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
private fun lastKnownLocation(context: Context): Location? {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return runCatching {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()
}

private suspend fun geocode(query: String): Destination? = withContext(Dispatchers.IO) {
    runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = URL(GEOCODER + "?format=jsonv2&limit=1&q=" + encoded)
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", "MotoMap/0.1 Android navigation app")
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.inputStream.use { input ->
            val body = BufferedReader(InputStreamReader(input)).readText()
            val item = JSONArray(body).optJSONObject(0) ?: return@withContext null
            Destination(item.optDouble("lat"), item.optDouble("lon"), item.optString("display_name", query))
        }
    }.getOrNull()
}

private suspend fun requestRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, mode: String): RouteResult? = withContext(Dispatchers.IO) {
    runCatching {
        val options = when (mode) {
            "Быстрый" -> "\"use_highways\":true,\"shortest\":false"
            "Извилистый" -> "\"use_highways\":false,\"shortest\":false,\"top_speed\":70"
            "Красивый" -> "\"use_highways\":false,\"shortest\":false,\"top_speed\":60"
            else -> "\"use_highways\":false,\"shortest\":false"
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
    val feature = com.mapbox.geojson.Feature.fromGeometry(
        com.mapbox.geojson.LineString.fromLngLats(
            result.points.map { com.mapbox.geojson.Point.fromLngLat(it.longitude, it.latitude) }
        )
    )
    style.removeLayer("motomap-route-line")
    style.removeSource("motomap-route-source")
    style.addSource(GeoJsonSource("motomap-route-source", feature))
    style.addLayer(LineLayer("motomap-route-line", "motomap-route-source").withProperties(lineColor("#1976D2"), lineWidth(6f)))
}
