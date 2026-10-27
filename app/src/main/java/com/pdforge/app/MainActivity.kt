package com.pdforge.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pdforge.app.data.repository.RecentFilesRepository
import com.pdforge.app.navigation.AppNavigation
import com.pdforge.app.navigation.NavRoutes
import com.pdforge.app.settings.SettingsRepository
import com.pdforge.app.ui.theme.PDFForgeTheme
import com.pdforge.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // On API 26-28, publishing to the public Documents folder requires the
        // WRITE_EXTERNAL_STORAGE runtime permission (declared with maxSdkVersion 28).
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001
                )
            }
        }

        val settingsRepository = SettingsRepository(applicationContext)
        val recentFilesRepository = RecentFilesRepository(applicationContext)

        setContent {
            val themeMode by settingsRepository.themeMode.collectAsState(initial = "system")

            PDFForgeTheme(
                themeMode = when (themeMode) {
                    "light" -> ThemeMode.LIGHT
                    "dark" -> ThemeMode.DARK
                    else -> ThemeMode.SYSTEM
                }
            ) {
                MainContent(
                    settingsRepository = settingsRepository,
                    recentFilesRepository = recentFilesRepository
                )
            }
        }
    }
}

@Composable
fun MainContent(
    settingsRepository: SettingsRepository,
    recentFilesRepository: RecentFilesRepository
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomBarScreens = listOf(
        BottomNavItem(NavRoutes.Home.route, "Home", Icons.Filled.Home),
        BottomNavItem(NavRoutes.Files.route, "Files", Icons.Filled.Folder),
        BottomNavItem(NavRoutes.Settings.route, "Settings", Icons.Filled.Settings)
    )

    val showBottomBar = currentDestination?.route in bottomBarScreens.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomBarScreens.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        AppNavigation(
            settingsRepository = settingsRepository,
            recentFilesRepository = recentFilesRepository,
            modifier = Modifier.padding(innerPadding),
            navController = navController
        )
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)
