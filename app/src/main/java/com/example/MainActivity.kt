package com.example

import android.app.Application
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.navigation.Screen
import com.example.ui.screens.DetailScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.PlayerScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.ProphecyVerseTheme
import com.example.ui.theme.SpaceDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.SurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.viewmodels.DetailViewModel
import com.example.ui.viewmodels.DownloadsViewModel
import com.example.ui.viewmodels.HomeViewModel
import com.example.ui.viewmodels.LibraryViewModel
import com.example.ui.viewmodels.PlayerViewModel
import com.example.ui.viewmodels.ProfileViewModel
import com.example.ui.viewmodels.SearchViewModel

import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import com.example.data.notice.NoticeManager
import com.example.ui.components.BilingualNoticeDialog
import kotlinx.coroutines.launch

private const val OPTIONAL_UPDATE_REMINDER_INTERVAL_MS = 24 * 60 * 60 * 1000L

class MainActivity : ComponentActivity() {
    var isInPipMode by mutableStateOf(false)
        private set

    var isPlayerActive by mutableStateOf(false)
    var isVideoPlaying by mutableStateOf(false)
    var pendingNotificationRoute by mutableStateOf<String?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingNotificationRoute = notificationRouteFrom(intent)
        com.example.data.firebase.ProphecyMessagingService.ensureNotificationChannel(applicationContext)

        lifecycleScope.launch {
            NoticeManager.getInstance(applicationContext).checkAndPrepareNotice(forceShow = false)
            com.example.data.firebase.AuthManager.getInstance(applicationContext).ensureGuestSignIn()
            com.example.data.firebase.InstallationManager.getInstance(applicationContext).initializeAndSync()
            com.example.data.firebase.AppAnalytics.getInstance(applicationContext).logEvent("app_open")
            com.example.data.firebase.CloudHistoryRepository.getInstance(applicationContext).pullCloudHistoryAndWatchlist()
        }

        setContent {
            ProphecyVerseTheme {
                MainAppNav(
                    isInPipMode = isInPipMode,
                    notificationRoute = pendingNotificationRoute,
                    onNotificationRouteConsumed = { pendingNotificationRoute = null },
                    onEnterPip = { enterPipMode() },
                    onPlayerStateChanged = { active, playing ->
                        isPlayerActive = active
                        isVideoPlaying = playing
                        updateAutoEnterPip(active && playing)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNotificationRoute = notificationRouteFrom(intent)
    }

    private fun notificationRouteFrom(intent: android.content.Intent?): String? {
        if (intent == null) return null
        val animeId = intent.getStringExtra("animeId") ?: intent.getStringExtra("anime_id")
        val episodeId = intent.getStringExtra("episodeId") ?: intent.getStringExtra("episode_id")
        if (animeId.isNullOrBlank()) return null
        val safeAnimeId = android.net.Uri.encode(animeId)
        return if (!episodeId.isNullOrBlank()) {
            Screen.Player.createRoute(safeAnimeId, android.net.Uri.encode(episodeId))
        } else {
            Screen.Detail.createRoute(safeAnimeId)
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.data.firebase.InstallationManager.getInstance(applicationContext).recordHeartbeat()
        com.example.data.firebase.VersionControlManager.getInstance(applicationContext).checkForUpdates()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isPlayerActive && isVideoPlaying && !isInPipMode) {
                enterPipMode()
            }
        }
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateAutoEnterPip(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(enabled)
                    .build()
                setPictureInPictureParams(params)
            } catch (_: Exception) {
            }
        }
    }
}

@Composable
fun MainAppNav(
    isInPipMode: Boolean = false,
    notificationRoute: String? = null,
    onNotificationRouteConsumed: () -> Unit = {},
    onEnterPip: () -> Unit = {},
    onPlayerStateChanged: (isActive: Boolean, isPlaying: Boolean) -> Unit = { _, _ -> }
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    androidx.compose.runtime.LaunchedEffect(notificationRoute) {
        val target = notificationRoute ?: return@LaunchedEffect
        navController.navigate(target) { launchSingleTop = true }
        onNotificationRouteConsumed()
    }
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val noticeManager = remember { NoticeManager.getInstance(context) }
    val isNoticeVisible by noticeManager.isNoticeVisible.collectAsStateWithLifecycle()
    val noticeContent by noticeManager.noticeContent.collectAsStateWithLifecycle()

    val versionControlManager = remember { com.example.data.firebase.VersionControlManager.getInstance(context) }
    val versionState by versionControlManager.versionState.collectAsStateWithLifecycle()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                if (isGranted) {
                    com.example.data.firebase.AppAnalytics.getInstance(context).logEvent("notification_permission_granted")
                }
            }
        )
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val updatePrefs = remember { context.getSharedPreferences("version_control_ui", android.content.Context.MODE_PRIVATE) }
    var dismissedOptionalVersionCode by remember {
        mutableStateOf(updatePrefs.getInt("dismissed_optional_version_code", -1))
    }
    var dismissedOptionalAtMs by remember {
        mutableStateOf(updatePrefs.getLong("dismissed_optional_at_ms", 0L))
    }
    val dismissOptionalUpdate: (Int) -> Unit = { versionCode ->
        val dismissedAt = System.currentTimeMillis()
        updatePrefs.edit()
            .putInt("dismissed_optional_version_code", versionCode)
            .putLong("dismissed_optional_at_ms", dismissedAt)
            .apply()
        dismissedOptionalVersionCode = versionCode
        dismissedOptionalAtMs = dismissedAt
    }

    when (val vState = versionState) {
        is com.example.data.firebase.VersionCheckState.ForceUpdate -> {
            com.example.ui.screens.ForceUpdateScreen(versionState = vState)
            return
        }
        is com.example.data.firebase.VersionCheckState.VerificationFailed -> {
            com.example.ui.screens.ForceUpdateScreen(versionState = vState)
            return
        }
        else -> {}
    }

    // Hide bottom bar on player screen or when in PIP mode
    val shouldShowBottomBar = !isInPipMode && currentRoute?.startsWith("player/") != true

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(SpaceDark),
        containerColor = SpaceDark,
        contentWindowInsets = androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (shouldShowBottomBar) {
                BottomNavBar(navController = navController, currentRoute = currentRoute)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isInPipMode || currentRoute?.startsWith("player/") == true) PaddingValues(0.dp) else innerPadding)
        ) {
            composable(Screen.Home.route) {
                val homeViewModel: HomeViewModel = viewModel()
                HomeScreen(
                    viewModel = homeViewModel,
                    onAnimeClick = { animeId ->
                        navController.navigate(Screen.Detail.createRoute(animeId))
                    },
                    onPlayHistoryClick = { animeId, episodeId ->
                        navController.navigate(Screen.Player.createRoute(animeId, episodeId))
                    }
                )
            }

            composable(Screen.Search.route) {
                val searchViewModel: SearchViewModel = viewModel()
                SearchScreen(
                    viewModel = searchViewModel,
                    onAnimeClick = { animeId ->
                        navController.navigate(Screen.Detail.createRoute(animeId))
                    }
                )
            }

            composable(Screen.Downloads.route) {
                val downloadsViewModel: DownloadsViewModel = viewModel()
                DownloadsScreen(
                    viewModel = downloadsViewModel,
                    onPlayOffline = { aId, eId ->
                        navController.navigate(Screen.Player.createRoute(aId, eId))
                    },
                    onAnimeClick = { aId ->
                        navController.navigate(Screen.Detail.createRoute(aId))
                    }
                )
            }

            composable(Screen.Library.route) {
                val libraryViewModel: LibraryViewModel = viewModel()
                LibraryScreen(
                    viewModel = libraryViewModel,
                    onAnimeClick = { animeId ->
                        navController.navigate(Screen.Detail.createRoute(animeId))
                    },
                    onPlayHistoryClick = { animeId, episodeId ->
                        navController.navigate(Screen.Player.createRoute(animeId, episodeId))
                    }
                )
            }

            composable(Screen.History.route) {
                val libraryViewModel: LibraryViewModel = viewModel()
                HistoryScreen(
                    viewModel = libraryViewModel,
                    onAnimeClick = { animeId ->
                        navController.navigate(Screen.Detail.createRoute(animeId))
                    },
                    onPlayHistoryClick = { animeId, episodeId ->
                        navController.navigate(Screen.Player.createRoute(animeId, episodeId))
                    }
                )
            }

            composable(Screen.Profile.route) {
                val profileViewModel: ProfileViewModel = viewModel()
                ProfileScreen(
                    viewModel = profileViewModel,
                    onNavigateToDownloads = { navController.navigate(Screen.Downloads.route) },
                    onNavigateToHistory = { navController.navigate(Screen.History.route) },
                    onNavigateToLibrary = { navController.navigate(Screen.Library.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onPlayHistoryItem = { aId, eId ->
                        navController.navigate(Screen.Player.createRoute(aId, eId))
                    }
                )
            }

            composable(Screen.Settings.route) {
                val libraryViewModel: LibraryViewModel = viewModel()
                SettingsScreen(viewModel = libraryViewModel)
            }

            composable(
                route = Screen.Detail.route,
                arguments = listOf(navArgument("animeId") { type = NavType.StringType })
            ) { backStackEntry ->
                val animeId = backStackEntry.arguments?.getString("animeId") ?: return@composable
                val detailViewModel = viewModel<DetailViewModel>(
                    key = "detail_$animeId",
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return DetailViewModel(app, animeId) as T
                        }
                    }
                )
                DetailScreen(
                    viewModel = detailViewModel,
                    onBackClick = { navController.popBackStack() },
                    onPlayEpisode = { aId, eId, sourceId ->
                        navController.navigate(Screen.Player.createRoute(aId, eId, sourceId))
                    }
                )
            }

            composable(
                route = Screen.Player.route,
                arguments = listOf(
                    navArgument("animeId") { type = NavType.StringType },
                    navArgument("episodeId") { type = NavType.StringType },
                    navArgument("preferredSourceId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val animeId = backStackEntry.arguments?.getString("animeId") ?: return@composable
                val episodeId = backStackEntry.arguments?.getString("episodeId") ?: return@composable
                val preferredSourceId = backStackEntry.arguments?.getString("preferredSourceId")

                val playerViewModel = viewModel<PlayerViewModel>(
                    key = "player_${animeId}_$episodeId",
                    factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                            return PlayerViewModel(app, animeId, episodeId, preferredSourceId) as T
                        }
                    }
                )
                PlayerScreen(
                    viewModel = playerViewModel,
                    isInPipMode = isInPipMode,
                    onEnterPip = onEnterPip,
                    onPlayerStateChanged = onPlayerStateChanged,
                    onBackClick = { navController.popBackStack() },
                    onNavigateToDetail = { targetAnimeId ->
                        navController.navigate(Screen.Detail.createRoute(targetAnimeId))
                    }
                )
            }
        }

        BilingualNoticeDialog(
            noticeContent = noticeContent,
            isVisible = isNoticeVisible && !isInPipMode && currentRoute?.startsWith("player/") != true,
            onDismiss = { noticeManager.dismissNotice() },
            onRetry = { noticeManager.retryNotices() },
            onAction = { url -> noticeManager.openNoticeAction(url, context) }
        )

        val optionalUpdate = versionState as? com.example.data.firebase.VersionCheckState.OptionalUpdate
        val reminderExpired = dismissedOptionalAtMs <= 0L ||
            System.currentTimeMillis() - dismissedOptionalAtMs >= OPTIONAL_UPDATE_REMINDER_INTERVAL_MS
        if (optionalUpdate != null &&
            dismissedOptionalVersionCode != optionalUpdate.config.latestVersionCode &&
            !isInPipMode && currentRoute?.startsWith("player/") != true) {
            com.example.ui.screens.ForceUpdateScreen(
                versionState = optionalUpdate,
                onDismissOptional = { dismissOptionalUpdate(optionalUpdate.config.latestVersionCode) }
            )
        } else if (optionalUpdate != null &&
            dismissedOptionalVersionCode == optionalUpdate.config.latestVersionCode &&
            reminderExpired &&
            !isInPipMode && currentRoute?.startsWith("player/") != true) {
            com.example.ui.screens.ForceUpdateScreen(
                versionState = optionalUpdate,
                onDismissOptional = { dismissOptionalUpdate(optionalUpdate.config.latestVersionCode) }
            )
        }
    }

}

@Composable
fun BottomNavBar(
    navController: NavHostController,
    currentRoute: String?
) {
    val items = listOf(
        NavigationItem(Screen.Home.route, "Discover", Icons.Default.Explore),
        NavigationItem(Screen.Search.route, "Search", Icons.Default.Search),
        NavigationItem(Screen.Downloads.route, "Downloads", Icons.Default.Download),
        NavigationItem(Screen.History.route, "History", Icons.Default.History),
        NavigationItem(Screen.Profile.route, "Profile", Icons.Default.Person)
    )

    NavigationBar(
        containerColor = SurfaceDark,
        tonalElevation = 8.dp
    ) {
        items.forEach { item ->
            val isSelected = currentRoute == item.route
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label
                    )
                },
                label = { Text(text = item.label, fontSize = 11.sp) },
                selected = isSelected,
                onClick = {
                    if (currentRoute != item.route) {
                        navController.navigate(item.route) {
                            popUpTo(Screen.Home.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = AccentCyan,
                    indicatorColor = AccentIndigo,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted
                ),
                modifier = Modifier.testTag("nav_item_${item.route}")
            )
        }
    }
}

data class NavigationItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
