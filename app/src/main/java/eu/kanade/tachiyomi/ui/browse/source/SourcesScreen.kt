package eu.kanade.tachiyomi.ui.browse.source

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.BrowseTabWrapper
import eu.kanade.presentation.util.Screen

class SourcesScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        BrowseTabWrapper(sourcesTab(), onBackPressed = navigator::pop)
    }
}
