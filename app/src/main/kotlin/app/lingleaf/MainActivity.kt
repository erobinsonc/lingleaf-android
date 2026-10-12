package app.lingleaf

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.lingleaf.ui.LingleafApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LingleafApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.create(this)
        container.appScope.launch {
            container.library.load()
            container.library.seedIfNeeded(container.bundledStories())
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as LingleafApplication).container
        if (isDebuggable() && (intent.getBooleanExtra(DUE_WORD, false) || intent.getStringExtra(DUE_WORD) == "true")) {
            seedDueWord(container)
        }
        setContent { LingleafApp(container) }
    }

    private fun isDebuggable() = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Debug builds only, for the screenshot tour: a word waiting for review, since new
     * words are first due tomorrow (SPEC S7). Runs once the library has loaded.
     */
    private fun seedDueWord(container: AppContainer) {
        container.appScope.launch {
            container.library.state.first { it.books.isNotEmpty() }
            val sentence = "Tom lives in a small house by the sea."
            container.library.saveWord("sea", learned = false, context = sentence, source = "The Red Boat")
            // "Again" makes it due now.
            container.library.answerReview("sea", correct = false)
        }
    }

    private companion object {
        const val DUE_WORD = "dueWord"
    }
}
