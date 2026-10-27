package com.pdforge.app.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.pdforge.app.data.repository.RecentFilesRepository
import com.pdforge.app.settings.SettingsRepository
import com.pdforge.app.ui.screens.*

@Composable
fun AppNavigation(
    settingsRepository: SettingsRepository,
    recentFilesRepository: RecentFilesRepository,
    modifier: Modifier = Modifier,
    navController: NavHostController
) {
    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = NavRoutes.Splash.route
        ) {
            composable(NavRoutes.Splash.route) {
                SplashScreen(
                    onNavigateToHome = {
                        navController.navigate(NavRoutes.Home.route) {
                            popUpTo(NavRoutes.Splash.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(NavRoutes.Home.route) {
                HomeScreen(
                    recentFilesRepository = recentFilesRepository,
                    onToolClick = { toolId ->
                        // launchSingleTop prevents a double-tap from pushing the same
                        // destination twice (which made tools feel like they needed
                        // two or three taps before the previous fix).
                        when (toolId) {
                            "scan_to_pdf" -> navController.navigate(NavRoutes.Scanner.route) { launchSingleTop = true }
                            "edit_pdf" -> navController.navigate(NavRoutes.EditPdf.createRoute()) { launchSingleTop = true }
                            "pdf_forms" -> navController.navigate(NavRoutes.PdfForms.route) { launchSingleTop = true }
                            "redact_pdf" -> navController.navigate(NavRoutes.RedactPdf.createRoute()) { launchSingleTop = true }
                            "sign_pdf" -> navController.navigate(NavRoutes.SignPdf.createRoute()) { launchSingleTop = true }
                            "compare_pdf" -> navController.navigate(NavRoutes.ComparePdf.route) { launchSingleTop = true }
                            "organize_pdf" -> navController.navigate(NavRoutes.Organize.route) { launchSingleTop = true }
                            else -> navController.navigate(NavRoutes.Tool.createRoute(toolId)) { launchSingleTop = true }
                        }
                    },
                    onCategoryClick = { categoryId ->
                        navController.navigate(NavRoutes.Category.createRoute(categoryId))
                    },
                    onOpenFile = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }

            composable(NavRoutes.Files.route) {
                FilesScreen(
                    recentFilesRepository = recentFilesRepository,
                    onOpenFile = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }

            composable(NavRoutes.Settings.route) {
                SettingsScreen(
                    settingsRepository = settingsRepository
                )
            }

            composable(
                route = NavRoutes.Category.route,
                arguments = listOf(
                    navArgument("categoryId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val categoryId = backStackEntry.arguments?.getString("categoryId") ?: ""
                CategoryScreen(
                    categoryId = categoryId,
                    onToolClick = { toolId ->
                        when (toolId) {
                            "scan_to_pdf" -> navController.navigate(NavRoutes.Scanner.route) { launchSingleTop = true }
                            "edit_pdf" -> navController.navigate(NavRoutes.EditPdf.createRoute()) { launchSingleTop = true }
                            "pdf_forms" -> navController.navigate(NavRoutes.PdfForms.route) { launchSingleTop = true }
                            "redact_pdf" -> navController.navigate(NavRoutes.RedactPdf.createRoute()) { launchSingleTop = true }
                            "sign_pdf" -> navController.navigate(NavRoutes.SignPdf.createRoute()) { launchSingleTop = true }
                            "compare_pdf" -> navController.navigate(NavRoutes.ComparePdf.route) { launchSingleTop = true }
                            "organize_pdf" -> navController.navigate(NavRoutes.Organize.route) { launchSingleTop = true }
                            else -> navController.navigate(NavRoutes.Tool.createRoute(toolId)) { launchSingleTop = true }
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = NavRoutes.Tool.route,
                arguments = listOf(
                    navArgument("toolId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val toolId = backStackEntry.arguments?.getString("toolId") ?: ""
                ToolScreen(
                    toolId = toolId,
                    onBack = { navController.popBackStack() },
                    onResult = { result ->
                        // Refresh recent files after processing
                        recentFilesRepository.refresh()
                    },
                    onOpenInApp = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }

            composable(
                route = NavRoutes.Viewer.route,
                arguments = listOf(
                    navArgument("filePath") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val encoded = backStackEntry.arguments?.getString("filePath") ?: ""
                // Route argument is URL-safe Base64 (see NavRoutes.Viewer.createRoute).
                // Do NOT Uri.decode() here — navigation already decodes string arguments,
                // and decoding again corrupts references containing '+' or '%xx'.
                val input = try {
                    String(
                        java.util.Base64.getUrlDecoder().decode(encoded),
                        Charsets.UTF_8
                    )
                } catch (e: Exception) {
                    ""
                }
                PdfViewerScreen(
                    input = input,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(NavRoutes.Organize.route) {
                OrganizePdfScreen(
                    onBack = { navController.popBackStack() },
                    onResult = { result ->
                        recentFilesRepository.refresh()
                        navController.popBackStack()
                    },
                    onOpenInApp = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }

            composable(NavRoutes.Scanner.route) {
                ScannerScreen(
                    onBack = { navController.popBackStack() },
                    onResult = { result ->
                        recentFilesRepository.refresh()
                        navController.popBackStack()
                    },
                    onOpenInApp = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }

            composable(
                route = NavRoutes.EditPdf.route,
                arguments = listOf(
                    navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                    navArgument("path") { type = NavType.StringType; defaultValue = "" }
                )
            ) { backStackEntry ->
                val uriStr = backStackEntry.arguments?.getString("uri")?.takeIf { it.isNotEmpty() }
                val path = backStackEntry.arguments?.getString("path")?.takeIf { it.isNotEmpty() }
                EditPdfScreen(
                    inputUri = uriStr?.let { try { Uri.parse(it) } catch (_: Exception) { null } },
                    inputPath = path,
                    onBack = { navController.popBackStack() },
                    onResult = { result ->
                        recentFilesRepository.refresh()
                        navController.popBackStack()
                    },
                    onOpenInApp = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }

            composable(NavRoutes.PdfForms.route) {
                PdfFormsScreen(
                    onBack = { navController.popBackStack() },
                    onResult = { result ->
                        recentFilesRepository.refresh()
                        navController.popBackStack()
                    },
                    onOpenInApp = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }

            composable(
                route = NavRoutes.RedactPdf.route,
                arguments = listOf(
                    navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                    navArgument("path") { type = NavType.StringType; defaultValue = "" }
                )
            ) { backStackEntry ->
                val uriStr = backStackEntry.arguments?.getString("uri")?.takeIf { it.isNotEmpty() }
                val path = backStackEntry.arguments?.getString("path")?.takeIf { it.isNotEmpty() }
                RedactPdfScreen(
                    inputUri = uriStr?.let { try { Uri.parse(it) } catch (_: Exception) { null } },
                    inputPath = path,
                    onBack = { navController.popBackStack() },
                    onResult = { result ->
                        recentFilesRepository.refresh()
                        navController.popBackStack()
                    },
                    onOpenInApp = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }

            composable(NavRoutes.ComparePdf.route) {
                ComparePdfScreen(
                    onBack = { navController.popBackStack() },
                    onOpenInApp = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }

            composable(
                route = NavRoutes.SignPdf.route,
                arguments = listOf(
                    navArgument("uri") { type = NavType.StringType; defaultValue = "" },
                    navArgument("path") { type = NavType.StringType; defaultValue = "" }
                )
            ) { backStackEntry ->
                val uriStr = backStackEntry.arguments?.getString("uri")?.takeIf { it.isNotEmpty() }
                val path = backStackEntry.arguments?.getString("path")?.takeIf { it.isNotEmpty() }
                SignPdfScreen(
                    inputUri = uriStr?.let { try { Uri.parse(it) } catch (_: Exception) { null } },
                    inputPath = path,
                    onBack = { navController.popBackStack() },
                    onResult = { result ->
                        recentFilesRepository.refresh()
                        navController.popBackStack()
                    },
                    onOpenInApp = { input ->
                        navController.navigate(NavRoutes.Viewer.createRoute(input))
                    }
                )
            }
        }
    }
}
