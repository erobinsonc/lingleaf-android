package app.lingleaf

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.lingleaf.ui.LingleafApp
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
        setContent { LingleafApp(container) }
    }
}
