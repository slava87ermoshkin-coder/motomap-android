package ru.motomap.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Trip(val date: String, val km: Double, val time: String, val max: Int, val avg: Int, val fuel: Double)

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        setContent { MotoMapApp() }
    }
}

@Composable
private fun MotoMapApp() {
    var tab by remember { mutableIntStateOf(0) }
    var riding by remember { mutableStateOf(false) }
    val trips = remember { mutableStateListOf(Trip("24.09.2026", 286.4, "4:17", 143, 67, 14.9), Trip("19.09.2026", 174.2, "2:51", 128, 61, 8.9)) }

    MaterialTheme {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("🗺️" to "Карта", "🏍️" to "Поездка", "🛣️" to "Маршруты", "📊" to "Статистика", "⚙️" to "Настройки").forEachIndexed { i, item ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(item.first) }, label = { Text(item.second) })
                }
            }
        }) { pad ->
            when (tab) {
                0 -> MapScreen(riding, { riding = !riding }, pad)
                1 -> RideScreen(riding, { riding = !riding }, pad)
                2 -> RoutesScreen(pad)
                3 -> StatisticsScreen(trips, pad)
                else -> SettingsScreen(pad)
            }
        }
    }
}

@Composable
private fun MapScreen(riding: Boolean, toggle: () -> Unit, pad: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(pad)) {
        OutlinedTextField("", {}, placeholder = { Text("Куда едем?") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFE9E9E9)), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗺️", fontSize = 64.sp)
                Text("Картографический движок подключается следующим этапом")
                Text("GPS • маршруты • камеры • красивые дороги")
            }
        }
        Button(toggle, Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (riding) "ЗАВЕРШИТЬ ПОЕЗДКУ" else "НАЧАТЬ ПОЕЗДКУ", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RideScreen(riding: Boolean, toggle: () -> Unit, pad: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Текущая поездка", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Stat("Скорость", "0 км/ч"); Stat("Расстояние", "0,0 км"); Stat("Время движения", "00:00"); Stat("Максимальная скорость", "0 км/ч"); Stat("Средняя скорость", "0 км/ч"); Stat("Расход", "5,2 л/100 км")
        Button(toggle, Modifier.fillMaxWidth().height(56.dp)) { Text(if (riding) "Завершить" else "Начать") }
    }
}

@Composable private fun Stat(title: String, value: String) { Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(17.dp), Arrangement.SpaceBetween) { Text(title); Text(value, fontWeight = FontWeight.Bold) } } }

@Composable
private fun RoutesScreen(pad: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Маршрут", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField("", {}, placeholder = { Text("Введите пункт назначения") }, modifier = Modifier.fillMaxWidth())
        Route("Быстрый", "190 км • 2:20"); Route("Мото", "212 км • 2:48"); Route("Красивый", "248 км • 3:35"); Route("Повороты", "263 км • 3:52")
    }
}
@Composable private fun Route(title: String, details: String) { Card(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(18.dp), Arrangement.SpaceBetween) { Text(title, fontWeight = FontWeight.Bold); Text(details) } } }

@Composable
private fun StatisticsScreen(trips: List<Trip>, pad: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize().padding(pad).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Статистика", fontSize = 28.sp, fontWeight = FontWeight.Bold); Stat("Поездок", trips.size.toString()); Stat("Расстояние", "${"%.1f".format(trips.sumOf { it.km })} км"); Stat("Топливо", "${"%.1f".format(trips.sumOf { it.fuel })} л"); Text("История", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        items(trips) { t -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(t.date, fontWeight = FontWeight.Bold); Text("${t.km} км • ${t.time}"); Text("Средняя ${t.avg} км/ч • максимум ${t.max} км/ч"); Text("Топливо ${t.fuel} л") } } }
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
            Field("Мотоцикл", bike) { bike = it }; Field("Расход, л/100 км", fuel) { fuel = it }; Field("Вес водителя, кг", rider) { rider = it }; Field("Вес пассажира, кг", passenger) { passenger = it }; Field("Вес багажа, кг", luggage) { luggage = it }
        }
    }
}
@Composable private fun Field(label: String, value: String, change: (String) -> Unit) { OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
