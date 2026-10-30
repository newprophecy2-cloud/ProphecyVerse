package com.example.ui.navigation

sealed class Screen(val route: String, val title: String) {
    data object Home : Screen("home", "Discover")
    data object Search : Screen("search", "Search")
    data object Library : Screen("library", "Library")
    data object History : Screen("history", "History")
    data object Settings : Screen("settings", "Settings")

    data object Detail : Screen("detail/{animeId}", "Detail") {
        fun createRoute(animeId: String) = "detail/$animeId"
    }

    data object Player : Screen("player/{animeId}/{episodeId}", "Player") {
        fun createRoute(animeId: String, episodeId: String) = "player/$animeId/$episodeId"
    }
}
