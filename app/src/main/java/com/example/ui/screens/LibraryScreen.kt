package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.EmptyState
import com.example.ui.components.SectionHeader
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodels.LibraryViewModel

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onAnimeClick: (String) -> Unit,
    onPlayHistoryClick: (String, String) -> Unit
) {
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf("ALL") }

    val tabs = listOf("ALL", "WATCHING", "PLANNING", "COMPLETED")

    val filteredList = when (selectedTab) {
        "ALL" -> watchlist
        else -> watchlist.filter { it.status == selectedTab }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceDark)
            .testTag("library_screen"),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        item {
            Text(
                text = "My Library",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
            )
        }

        // Continue watching rail
        if (continueWatching.isNotEmpty()) {
            item {
                SectionHeader(title = "Continue Watching", subtitle = "Resume saved progress")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(continueWatching, key = { it.id }) { history ->
                        ContinueWatchingCard(
                            history = history,
                            onClick = { onPlayHistoryClick(history.animeId, history.episodeId) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Watchlist tabs
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = {
                            val count = if (tab == "ALL") watchlist.size else watchlist.count { it.status == tab }
                            Text(text = "$tab ($count)", fontSize = 12.sp)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = SurfaceVariantDark,
                            labelColor = TextSecondary,
                            selectedContainerColor = AccentIndigo,
                            selectedLabelColor = Color.White
                        ),
                        border = null,
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }

        if (filteredList.isEmpty()) {
            item {
                EmptyState(
                    title = "Your Watchlist is Empty",
                    message = "Browse discovery or search to add anime to your personal library."
                )
            }
        } else {
            items(filteredList, key = { it.animeId }) { item ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                    onClick = { onAnimeClick(item.animeId) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Anime #${item.animeId.take(12)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Status: ${item.status}",
                                color = AccentCyan,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
