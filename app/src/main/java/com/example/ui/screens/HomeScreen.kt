package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.data.firebase.AuthManager
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.AnimeEntry
import com.example.data.local.PlaybackHistory
import com.example.ui.components.AnimeCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.ShimmerCard
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.PlayableGreen
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.SurfaceVariantDark
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodels.HomeViewModel

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onAnimeClick: (String) -> Unit,
    onPlayHistoryClick: (String, String) -> Unit
) {
    val listState = rememberLazyListState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.checkAndRefreshOnResume()
    }

    val trending by viewModel.trending.collectAsStateWithLifecycle()
    val seasonal by viewModel.seasonal.collectAsStateWithLifecycle()
    val popular by viewModel.popular.collectAsStateWithLifecycle()
    val kdramas by viewModel.kdramas.collectAsStateWithLifecycle()
    val movies by viewModel.movies.collectAsStateWithLifecycle()
    val webSeries by viewModel.webSeries.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val continueWatching by viewModel.continueWatching.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    // Confirmed available only on Home Screen
    val playableTrending: List<AnimeEntry> = remember(trending) { trending.filter { it.isPlayable } }
    val playableSeasonal: List<AnimeEntry> = remember(seasonal) { seasonal.filter { it.isPlayable } }
    val playablePopular: List<AnimeEntry> = remember(popular) { popular.filter { it.isPlayable } }
    val playableKdramas: List<AnimeEntry> = remember(kdramas) { kdramas.filter { it.isPlayable } }
    val playableMovies: List<AnimeEntry> = remember(movies) { movies.filter { it.isPlayable } }
    val playableWebSeries: List<AnimeEntry> = remember(webSeries) { webSeries.filter { it.isPlayable } }

    val featuredAnime: AnimeEntry? = when (selectedCategory) {
        "KDRAMA" -> playableKdramas.firstOrNull()
        "MOVIES" -> playableMovies.firstOrNull()
        "SERIES" -> playableWebSeries.firstOrNull()
        else -> playableTrending.firstOrNull { entry: AnimeEntry -> !entry.bannerUrl.isNullOrBlank() }
            ?: playableTrending.firstOrNull() ?: playableSeasonal.firstOrNull()
    }

    val context = LocalContext.current
    val authManager = remember { AuthManager.getInstance(context) }
    val authState by authManager.authState.collectAsStateWithLifecycle()
    val isGuest = authManager.isGuest
    var showAccountDialog by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceDark)
            .testTag("home_screen"),
        contentPadding = PaddingValues(bottom = 90.dp)
    ) {
        // App Bar / Brand Header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(listOf(AccentIndigo, AccentCyan))
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Logo",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "ProphecyVerse",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Verified Streaming & Catalog",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentCyan,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val context = LocalContext.current

                    // Prominent Auth / Profile Entry Point
                    if (isGuest) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = AccentCyan.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.7f)),
                            modifier = Modifier
                                .clickable { showAccountDialog = true }
                                .testTag("topbar_login_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = "Log In",
                                    tint = AccentCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "Log In",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentCyan
                                )
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .clickable { showAccountDialog = true }
                                .testTag("topbar_account_button")
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = AccentIndigo,
                                border = BorderStroke(1.5.dp, PlayableGreen),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = (authManager.currentUser?.email?.firstOrNull()?.uppercase() ?: "U"),
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(PlayableGreen)
                                    .align(Alignment.BottomEnd)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = { com.example.data.notice.NoticeManager.getInstance(context).showNotice() },
                        modifier = Modifier.testTag("open_notice_button")
                    ) {
                        Text(text = "📢", fontSize = 16.sp)
                    }

                    IconButton(
                        onClick = { viewModel.refreshHome() },
                        modifier = Modifier.testTag("refresh_home_button")
                    ) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AccentCyan,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh",
                                tint = TextSecondary
                            )
                        }
                    }
                }
            }
        }

        // Category Filter Chips (All, Anime, K-Drama, Movies, Web Series)
        item {
            val categories = listOf(
                Triple("ALL", "All", Icons.Default.AutoAwesome),
                Triple("ANIME", "Anime", Icons.Default.PlayArrow),
                Triple("KDRAMA", "K-Drama", Icons.Default.Favorite),
                Triple("MOVIES", "Movies", Icons.Default.Movie),
                Triple("SERIES", "Web Series", Icons.Default.Tv)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { (key, label, icon) ->
                    val isSelected = selectedCategory == key
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) AccentIndigo else SurfaceVariantDark,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) AccentCyan.copy(alpha = 0.8f) else SurfaceHighlight
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { viewModel.selectCategory(key) }
                            .testTag("chip_category_$key")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) Color.White else TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Non-blocking error/offline notification if any
        if (errorMessage != null) {
            item {
                Surface(
                    color = SurfaceHighlight,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = errorMessage!!,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }

        // Hero Featured Item
        if (featuredAnime != null) {
            item {
                HeroBanner(
                    anime = featuredAnime,
                    onClick = { onAnimeClick(featuredAnime.id) }
                )
            }
        }

        // Continue Watching rail (if any history items)
        if (continueWatching.isNotEmpty()) {
            item {
                SectionHeader(title = "Continue Watching", subtitle = "Jump back in")
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

        // 1. ANIME RAILS (Trending & Seasonal)
        if (selectedCategory == "ALL" || selectedCategory == "ANIME") {
            item {
                SectionHeader(title = "Trending Anime", subtitle = "Confirmed available to play")
                if (playableTrending.isEmpty() && isRefreshing) {
                    LoadingRail()
                } else if (playableTrending.isNotEmpty()) {
                    AnimeRail(
                        animes = playableTrending,
                        onAnimeClick = onAnimeClick,
                        testTagPrefix = "trending"
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                SectionHeader(title = "This Season", subtitle = "Top currently airing anime releases")
                if (playableSeasonal.isEmpty() && isRefreshing) {
                    LoadingRail()
                } else if (playableSeasonal.isNotEmpty()) {
                    AnimeRail(
                        animes = playableSeasonal,
                        onAnimeClick = onAnimeClick,
                        testTagPrefix = "seasonal"
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // 2. K-DRAMA RAIL
        if (selectedCategory == "ALL" || selectedCategory == "KDRAMA") {
            item {
                SectionHeader(title = "K-Drama Hits", subtitle = "Verified Korean drama series available now")
                if (playableKdramas.isEmpty() && isRefreshing) {
                    LoadingRail()
                } else if (playableKdramas.isNotEmpty()) {
                    AnimeRail(
                        animes = playableKdramas,
                        onAnimeClick = onAnimeClick,
                        testTagPrefix = "kdrama"
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // 3. MOVIES RAIL
        if (selectedCategory == "ALL" || selectedCategory == "MOVIES") {
            item {
                SectionHeader(title = "Movies", subtitle = "Verified feature films and anime movies")
                if (playableMovies.isEmpty() && isRefreshing) {
                    LoadingRail()
                } else if (playableMovies.isNotEmpty()) {
                    AnimeRail(
                        animes = playableMovies,
                        onAnimeClick = onAnimeClick,
                        testTagPrefix = "movies"
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // 4. WEB SERIES RAIL
        if (selectedCategory == "ALL" || selectedCategory == "SERIES") {
            item {
                SectionHeader(title = "Web Series", subtitle = "Confirmed streaming live action & web series")
                if (playableWebSeries.isEmpty() && isRefreshing) {
                    LoadingRail()
                } else if (playableWebSeries.isNotEmpty()) {
                    AnimeRail(
                        animes = playableWebSeries,
                        onAnimeClick = onAnimeClick,
                        testTagPrefix = "series"
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // All Time Popular Rail
        if (selectedCategory == "ALL" || selectedCategory == "ANIME") {
            item {
                SectionHeader(title = "All-Time Popular", subtitle = "Acclaimed legends")
                if (playablePopular.isEmpty() && isRefreshing) {
                    LoadingRail()
                } else if (playablePopular.isNotEmpty()) {
                    AnimeRail(
                        animes = playablePopular,
                        onAnimeClick = onAnimeClick,
                        testTagPrefix = "popular"
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showAccountDialog) {
        AccountDialog(onDismissRequest = { showAccountDialog = false })
    }
}

@Composable
fun HeroBanner(
    anime: AnimeEntry,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .aspectRatio(16f / 10f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("hero_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val bannerUrl = anime.bannerUrl ?: anime.posterUrl
            if (!bannerUrl.isNullOrBlank()) {
                AsyncImage(
                    model = bannerUrl,
                    contentDescription = anime.preferredTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Rich gradient shading
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.4f),
                                Color.Black.copy(alpha = 0.95f)
                            )
                        )
                    )
            )

            // Content
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (anime.isPlayable) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = PlayableGreen
                        ) {
                            Text(
                                text = "PLAYABLE",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "${anime.format ?: "ANIME"} • ${anime.year ?: ""}",
                        color = AccentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = anime.preferredTitle,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!anime.synopsis.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = anime.synopsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onClick,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Explore Details",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun AnimeRail(
    animes: List<AnimeEntry>,
    onAnimeClick: (String) -> Unit,
    testTagPrefix: String
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("${testTagPrefix}_rail")
    ) {
        items(animes, key = { it.id }) { anime ->
            AnimeCard(
                anime = anime,
                modifier = Modifier.width(135.dp),
                onClick = { onAnimeClick(anime.id) }
            )
        }
    }
}

@Composable
fun ContinueWatchingCard(
    history: PlaybackHistory,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("continue_card_${history.id}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = AccentIndigo.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "S${history.seasonNumber} : E${history.episodeNumber}",
                        color = AccentIndigo,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = AccentCyan,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = history.episodeTitle ?: "Episode ${history.episodeNumber}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Progress bar
            val progress = if (history.durationMs > 0) {
                (history.positionMs.toFloat() / history.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SurfaceHighlight)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(AccentCyan)
                )
            }
        }
    }
}

@Composable
fun LoadingRail() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(4) {
            ShimmerCard(
                modifier = Modifier
                    .width(135.dp)
                    .height(200.dp)
            )
        }
    }
}
