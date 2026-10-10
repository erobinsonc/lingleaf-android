package app.lingleaf.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.lingleaf.AppContainer
import app.lingleaf.R

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No AppContainer provided") }

private enum class Tab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    Library("library", R.string.tab_library, Icons.AutoMirrored.Filled.MenuBook),
    Discover("discover", R.string.tab_discover, Icons.Filled.Explore),
    Words("words", R.string.tab_words, Icons.Filled.Style),
    Settings("settings", R.string.tab_settings, Icons.Filled.Tune),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LingleafApp(container: AppContainer) {
    CompositionLocalProvider(LocalContainer provides container) {
        LingleafTheme {
            val navController = rememberNavController()
            val backStack by navController.currentBackStackEntryAsState()
            val route = backStack?.destination?.route
            val state by container.library.state.collectAsStateWithLifecycle()
            val due = remember(state.words) { container.library.dueWords().size }

            Scaffold(
                // Exposes Compose test tags as resource ids so UI automation (Maestro) can find them.
                modifier = Modifier.semantics { testTagsAsResourceId = true },
                contentWindowInsets = WindowInsets(0),
                bottomBar = {
                    if (Tab.entries.any { it.route == route }) {
                        NavigationBar {
                            Tab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = route == tab.route,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            popUpTo(Tab.Library.route) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        if (tab == Tab.Words && due > 0) {
                                            BadgedBox(badge = { Badge { Text("$due") } }) { Icon(tab.icon, contentDescription = null) }
                                        } else {
                                            Icon(tab.icon, contentDescription = null)
                                        }
                                    },
                                    label = { Text(stringResource(tab.label)) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                NavHost(navController, startDestination = Tab.Library.route, modifier = Modifier.padding(padding)) {
                    composable(Tab.Library.route) { LibraryScreen(onOpenBook = { navController.navigate("reader/$it") }) }
                    composable(Tab.Discover.route) { DiscoverScreen() }
                    composable(Tab.Words.route) { WordsScreen(onReview = { navController.navigate("review") }) }
                    composable(Tab.Settings.route) { SettingsScreen() }
                    composable("reader/{bookId}") { entry ->
                        ReaderScreen(
                            bookId = entry.arguments?.getString("bookId").orEmpty(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("review") { ReviewScreen(onBack = { navController.popBackStack() }) }
                }
            }
        }
    }
}
