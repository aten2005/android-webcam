package dev.aten.webcam.ui

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.aten.webcam.data.AppState
import dev.aten.webcam.data.Settings
import dev.aten.webcam.service.BootReceiver
import dev.aten.webcam.service.StreamService

class MainActivity : ComponentActivity() {
    private var resumeRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumeRequested = savedInstanceState == null && intent.getBooleanExtra(EXTRA_RESUME_LISTENER, false)
        val settings = Settings(this)
        setContent {
            WebcamTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(settings)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        resumeRequested = intent.getBooleanExtra(EXTRA_RESUME_LISTENER, false)
    }

    override fun onResume() {
        super.onResume()
        if (!resumeRequested) return
        resumeRequested = false
        getSystemService(NotificationManager::class.java).cancel(BootReceiver.NOTIFICATION_ID)
        // Started here, with the activity in the foreground, the service keeps camera access afterwards.
        if (!AppState.status.value.running && PermissionState.read(this).camera) StreamService.start(this)
    }

    companion object {
        const val EXTRA_RESUME_LISTENER = "resume_listener"
    }
}
