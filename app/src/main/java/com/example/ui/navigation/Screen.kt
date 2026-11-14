package com.example.ui.navigation

sealed class Screen(val route: String, val title: String) {
    data object Home : Screen("home", "Discover")
    data object Search : Screen("search", "Search")
    data object Library : Screen("library", "Library")
    data object History : Screen("history", "History")
    data object Downloads : Screen("downloads", "Downloads")
    data object Profile : Screen("profile", "Profile")
    data object Settings : Screen("settings", "Settings")

    data object Detail : Screen("detail/{animeId}?seasonNumber={seasonNumber}", "Detail") {
        fun createRoute(animeId: String, seasonNumber: Int? = null): String =
            "detail/$animeId" + (seasonNumber?.let { "?seasonNumber=$it" } ?: "")
    }

    data object Player : Screen("player/{animeId}/{episodeId}?preferredSourceId={preferredSourceId}", "Player") {
        fun createRoute(animeId: String, episodeId: String, preferredSourceId: String? = null): String {
            val route = "player/$animeId/$episodeId"
            return if (preferredSourceId.isNullOrBlank()) route
            else "$route?preferredSourceId=${android.net.Uri.encode(preferredSourceId)}"
        }
    }
}
