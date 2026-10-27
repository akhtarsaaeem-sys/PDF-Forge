package com.pdforge.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdforge.app.data.models.ToolRegistry
import com.pdforge.app.data.repository.RecentFilesRepository
import com.pdforge.app.storage.FileManager
import com.pdforge.app.ui.components.*
import com.pdforge.app.ui.screens.openRecentFile
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    recentFilesRepository: RecentFilesRepository,
    onToolClick: (String) -> Unit,
    onCategoryClick: (String) -> Unit,
    onOpenFile: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val searchResults = remember(searchQuery) { ToolRegistry.searchTools(searchQuery) }
    val recentFiles by recentFilesRepository.recentFiles.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PDF Forge",
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Search bar
            item {
                ToolSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    searchResults = searchResults,
                    onToolClick = onToolClick
                )
            }

            // Categories
            item {
                Text(
                    text = "Tools",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            items(ToolRegistry.categories) { category ->
                CategoryCard(
                    name = category.name,
                    description = category.description,
                    toolCount = category.tools.size,
                    color = category.color,
                    icon = category.icon,
                    onClick = { onCategoryClick(category.id) }
                )
            }

            // Recent Files
            if (recentFiles.isNotEmpty()) {
                item {
                    Text(
                        text = "Recent Files",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                items(recentFiles.take(5)) { file ->
                    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                    val dateStr = dateFormat.format(Date(file.lastModified))

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openRecentFile(context, file, onOpenFile)
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "$dateStr • ${FileManager.formatFileSize(file.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}
