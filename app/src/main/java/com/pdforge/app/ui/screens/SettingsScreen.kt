package com.pdforge.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdforge.app.settings.SettingsRepository
import com.pdforge.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val themeMode by settingsRepository.themeMode.collectAsState(initial = "system")
    val defaultCompression by settingsRepository.defaultCompressionQuality.collectAsState(initial = "recommended")
    val defaultOcrLang by settingsRepository.defaultOcrLanguage.collectAsState(initial = "eng")
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Appearance section
            item {
                SectionHeader("Appearance")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Brightness6,
                    title = "Theme",
                    subtitle = when (themeMode) {
                        "light" -> "Light"
                        "dark" -> "Dark"
                        else -> "System"
                    },
                    onClick = {
                        val nextMode = when (themeMode) {
                            "system" -> "light"
                            "light" -> "dark"
                            else -> "system"
                        }
                        scope.launch { settingsRepository.setThemeMode(nextMode) }
                    }
                )
            }

            // Files section
            item {
                SectionHeader("Files")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Folder,
                    title = "Default output location",
                    subtitle = "Internal storage",
                    onClick = { }
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Title,
                    title = "Output filename behavior",
                    subtitle = "Add suffix",
                    onClick = { }
                )
            }

            // OCR section
            item {
                SectionHeader("OCR")
            }

            item {
                val ocrLangNames = listOf("eng" to "English", "fra" to "French", "deu" to "German", "spa" to "Spanish", "ita" to "Italian", "por" to "Portuguese", "rus" to "Russian")
                val currentLangName = ocrLangNames.find { it.first == defaultOcrLang }?.second ?: "English"
                SettingsItem(
                    icon = Icons.Default.Language,
                    title = "Default OCR language",
                    subtitle = currentLangName,
                    onClick = {
                        val currentIndex = ocrLangNames.indexOfFirst { it.first == defaultOcrLang }
                        val nextIndex = (currentIndex + 1) % ocrLangNames.size
                        scope.launch { settingsRepository.setDefaultOcrLanguage(ocrLangNames[nextIndex].first) }
                    }
                )
            }

            // Processing section
            item {
                SectionHeader("Processing")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Speed,
                    title = "Default compression quality",
                    subtitle = when (defaultCompression) {
                        "high" -> "High Quality"
                        "small" -> "Smaller File"
                        else -> "Recommended"
                    },
                    onClick = {
                        val nextQuality = when (defaultCompression) {
                            "high" -> "recommended"
                            "recommended" -> "small"
                            else -> "high"
                        }
                        scope.launch { settingsRepository.setDefaultCompressionQuality(nextQuality) }
                    }
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.CleaningServices,
                    title = "Temporary file cleanup",
                    subtitle = "On exit",
                    onClick = { }
                )
            }

            // About section
            item {
                SectionHeader("About")
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "PDF Forge",
                    subtitle = "Version 1.0.0",
                    onClick = { }
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "Open-source licenses",
                    subtitle = "View dependencies",
                    onClick = { }
                )
            }

            item {
                SettingsItem(
                    icon = Icons.Default.PrivacyTip,
                    title = "Privacy information",
                    subtitle = "All processing happens locally",
                    onClick = { }
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
