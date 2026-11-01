package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.navigation.AdminNavDestination
import com.example.ui.theme.InstitutionalGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScaffold(
    currentDestination: AdminNavDestination,
    onNavigate: (AdminNavDestination) -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    isFirebaseReady: Boolean,
    adminEmail: String,
    content: @Composable () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Handle back press if drawer is open
    if (drawerState.isOpen) {
        BackHandler {
            scope.launch { drawerState.close() }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 720.dp

        if (isWideScreen) {
            // Tablet / Desktop Canonical Layout with NavigationRail
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    header = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(InstitutionalGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AdminPanelSettings,
                                    contentDescription = "PV Admin Logo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "PV Admin",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = InstitutionalGreen
                            )
                        }
                    }
                ) {
                    AdminNavDestination.mainNavItems.forEach { item ->
                        NavigationRailItem(
                            selected = currentDestination.route == item.route,
                            onClick = { onNavigate(item) },
                            icon = {
                                item.icon?.let {
                                    Icon(imageVector = it, contentDescription = item.title)
                                }
                            },
                            label = { Text(item.title, maxLines = 1) },
                            modifier = Modifier.testTag("nav_rail_${item.route}")
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    NavigationRailItem(
                        selected = false,
                        onClick = onLogout,
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Sign Out"
                            )
                        },
                        label = { Text("Logout") },
                        modifier = Modifier.testTag("nav_rail_logout")
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Main Content
                Scaffold(
                    topBar = {
                        AdminTopBar(
                            title = currentDestination.title,
                            adminEmail = adminEmail,
                            isFirebaseReady = isFirebaseReady,
                            showMenuButton = false,
                            onMenuClick = {},
                            onRefresh = onRefresh,
                            onLogout = onLogout
                        )
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        content()
                    }
                }
            }
        } else {
            // Mobile Layout with Navigation Drawer + Bottom Bar
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(300.dp),
                        drawerContainerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(InstitutionalGreen)
                                .padding(24.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AdminPanelSettings,
                                    contentDescription = null,
                                    tint = InstitutionalGreen,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Prophecy Verse Admin",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Text(
                                text = adminEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        AdminNavDestination.mainNavItems.forEach { item ->
                            NavigationDrawerItem(
                                label = { Text(item.title) },
                                icon = {
                                    item.icon?.let { Icon(imageVector = it, contentDescription = item.title) }
                                },
                                selected = currentDestination.route == item.route,
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    onNavigate(item)
                                },
                                modifier = Modifier
                                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                                    .testTag("nav_drawer_${item.route}")
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        NavigationDrawerItem(
                            label = { Text("Logout") },
                            icon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = "Sign Out"
                                )
                            },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                onLogout()
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            ) {
                Scaffold(
                    topBar = {
                        AdminTopBar(
                            title = currentDestination.title,
                            adminEmail = adminEmail,
                            isFirebaseReady = isFirebaseReady,
                            showMenuButton = true,
                            onMenuClick = { scope.launch { drawerState.open() } },
                            onRefresh = onRefresh,
                            onLogout = onLogout
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 4.dp
                        ) {
                            // Primary 4 items in bottom bar for quick mobile navigation
                            listOf(
                                AdminNavDestination.Dashboard,
                                AdminNavDestination.UserManagement,
                                AdminNavDestination.NoticeManagement,
                                AdminNavDestination.VersionControl
                            ).forEach { item ->
                                NavigationBarItem(
                                    selected = currentDestination.route == item.route,
                                    onClick = { onNavigate(item) },
                                    icon = {
                                        item.icon?.let { Icon(imageVector = it, contentDescription = item.title) }
                                    },
                                    label = { Text(item.title, maxLines = 1) },
                                    modifier = Modifier.testTag("nav_bottom_${item.route}")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        content()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminTopBar(
    title: String,
    adminEmail: String,
    isFirebaseReady: Boolean,
    showMenuButton: Boolean,
    onMenuClick: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isFirebaseReady) InstitutionalGreen else MaterialTheme.colorScheme.tertiary)
                    )
                    Text(
                        text = if (isFirebaseReady) "Live Firestore" else "REST / Ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        navigationIcon = {
            if (showMenuButton) {
                IconButton(onClick = onMenuClick, modifier = Modifier.testTag("topbar_menu_button")) {
                    Icon(imageVector = Icons.Default.Menu, contentDescription = "Open Navigation Menu")
                }
            }
        },
        actions = {
            IconButton(onClick = onRefresh, modifier = Modifier.testTag("topbar_refresh_button")) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Data")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}
