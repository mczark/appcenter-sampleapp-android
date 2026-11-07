package pl.radar.spolek

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import pl.radar.spolek.ui.DetailScreen
import pl.radar.spolek.ui.HomeScreen
import pl.radar.spolek.ui.MainViewModel
import pl.radar.spolek.ui.SettingsScreen
import pl.radar.spolek.ui.TrendMeterScreen
import pl.radar.spolek.ui.theme.RadarTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            RadarTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            vm = vm,
                            onOpen = { nav.navigate("detail/$it") },
                            onSettings = { nav.navigate("settings") },
                            onTrendMeter = { nav.navigate("trendmeter") },
                        )
                    }
                    composable(
                        "detail/{symbol}",
                        arguments = listOf(navArgument("symbol") { type = NavType.StringType }),
                    ) { entry ->
                        DetailScreen(vm, entry.arguments?.getString("symbol").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("trendmeter") {
                        TrendMeterScreen(vm, onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(vm, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
