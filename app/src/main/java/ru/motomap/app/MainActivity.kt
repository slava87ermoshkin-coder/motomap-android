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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.Locale
import kotlin.math.roundToInt

private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

private data class Trip(val date: String, val km: Double, val time: String, val max: Int, val avg: Int, val fuel: Double)
private data class RideState(val riding: Boolean = false, val speedKmh: Int = 0, val distanceKm: Double = 0.0, val elapsedSec: Long = 0L, val maxSpeedKmh: Int = 0)

class MainActivity : ComponentActivity() {
    private var hasLocationPermission by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocationPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        hasLocationPermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        setContent { MotoMapApp(hasLocationPermission) }
    }
}

@Composable
private fun MotoMapApp(hasLocationPermission: Boolean) {
    var tab by remember { mutableIntStateOf(0) }
    var riding by remember { mutableStateOf(false) }
    var rideState by remember { mutableStateOf(RideState()) }
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
                0 -> MapScreen(riding, rideState, hasLocationPermission, {
                    riding = !riding
                    rideState = if (!riding) RideState() else RideState(riding = true)
                }, { rideState = it }, pad)
                1 -> RideScreen(rideState, {
                    riding = !riding
                    rideState = if (!riding) RideState() else RideState(riding = true)
                }, pad)
                2 -> RoutesScreen(pad)
                3 -> StatisticsScreen(trips, pad)
                else -> SettingsScreen(pad)
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
    onToggleRide: () -> Unit,
    onRideStateChanged: (RideState) -> Unit,
    pad: PaddingValues
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

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
            Surface(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp), tonalElevation = 4.dp) {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    placeholder = { Text("Куда едем?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (!hasLocationPermission) {
                Card(Modifier.fillMaxWidth()) {
                    Text("Доступ к геолокации не разрешён. Положение мотоцикла не будет показано.", Modifier.padding(14.dp))
                }
            }
        }

        Card(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            RoundedCornerShape(18.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (riding) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("\${rideState.speedKmh} км/ч", fontWeight = FontWeight.Bold)
                        Text("%.1f км".format(Locale.US, rideState.distanceKm))
                        Text(formatDuration(rideState.elapsedSec))
                    }
                }
                Button(onClick = onToggleRide, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (riding) "ЗАВЕРШИТЬ ПОЕЗДКУ" else "НАЧАТЬ ПОЕЗДКУ", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    RideLocationTracker(context, riding, rideState, onRideStateChanged)
}

@SuppressLint("MissingPermission")
private fun enableLocationIfAllowed(context: Context, map: MapLibreMap, allowed: Boolean, style: org.maplibre.android.maps.Style) {
    if (!allowed) return
    val component = map.locationComponent
    if (!component.isLocationComponentActivated) {
        val options = LocationComponentActivationOptions.builder(context, style)
            .locationComponentOptions(LocationComponentOptions.builder(context).pulseEnabled(true).build())
            .useDefaultLocationEngine(true)
            .build()
        component.activateLocationComponent(options)
    }
    component.isLocationComponentEnabled = true
    component.cameraMode = CameraMode.TRACKING
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
                val distance = current.distanceKm + segment / 1000.0
                val elapsed = ((System.currentTimeMillis() - startTime) / 1000L).coerceAtLeast(0L)
                onChanged(current.copy(
                    riding = true,
                    speedKmh = speed,
                    distanceKm = distance,
                    elapsedSec = elapsed,
                    maxSpeedKmh = maxOf(current.maxSpeedKmh, speed)
                ))
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
        Stat("Скорость", "\${state.speedKmh} км/ч")
        Stat("Расстояние", "%.1f км".format(Locale.US, state.distanceKm))
        Stat("Время движения", formatDuration(state.elapsedSec))
        Stat("Максимальная скорость", "\${state.maxSpeedKmh} км/ч")
        Stat("Средняя скорость", if (state.elapsedSec > 0) "\${(state.distanceKm / (state.elapsedSec / 3600.0)).roundToInt()} км/ч" else "0 км/ч")
        Stat("Расход", "5,2 л/100 км")
        Button(toggle, Modifier.fillMaxWidth().height(56.dp)) { Text(if (state.riding) "Завершить" else "Начать") }
    }
}

@Composable
private fun Stat(title: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(17.dp), Arrangement.SpaceBetween) {
            Text(title)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RoutesScreen(pad: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Маршрут", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField("", {}, placeholder = { Text("Введите пункт назначения") }, modifier = Modifier.fillMaxWidth())
        Route("Быстрый", "Расчёт подключим следующим этапом")
        Route("Мото", "Максимум подходящих мото-дорог")
        Route("Красивый", "Живописные дороги")
        Route("Повороты", "Приоритет извилистых дорог")
    }
}

@Composable
private fun Route(title: String, details: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.Bold); Text(details) } }
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
                Column(Modifier.padding(16.dp)) {
                    Text(t.date, fontWeight = FontWeight.Bold)
                    Text("\${t.km} км • \${t.time}")
                    Text("Средняя \${t.avg} км/ч • максимум \${t.max} км/ч")
                    Text("Топливо \${t.fuel} л")
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(pad: PaddingValues) {
    var bike by remember { mutableStateOf("Honda CB650R") }
    var fuel by remember { mutableStateOf("5,2") }
    var rider by remember { mutableStateOf("80") }
    var passenger by remember { mutableStateOf("0") }
    var luggage by remember { mutableStateOf("0") }

    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Мотоцикл", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Field("Мотоцикл", bike) { bike = it }
            Field("Расход, л/100 км", fuel) { fuel = it }
            Field("Вес водителя, кг", rider) { rider = it }
            Field("Вес пассажира, кг", passenger) { passenger = it }
            Field("Вес багажа, кг", luggage) { luggage = it }
        }
    }
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}
