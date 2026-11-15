package dev.dsh.files

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.dsh.files.ui.AppTheme
import dev.dsh.files.ui.BrowserScreen
import dev.dsh.files.ui.ConnectScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ensureNotificationPermission()
        handleShareIntent(intent)
        val prefs = Prefs(this)
        if (prefs.paired && prefs.pushEnabled) {
            PushService.start(this)
        }
        setContent {
            AppTheme {
                var connected by remember { mutableStateOf(prefs.paired) }
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (connected) {
                        BrowserScreen(
                            prefs = prefs,
                            onDisconnect = {
                                prefs.clearPairing()
                                PushService.stop(this)
                                connected = false
                            },
                        )
                    } else {
                        ConnectScreen(
                            prefs = prefs,
                            onConnected = { connected = true },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris: List<Uri> = if (action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        } else {
            listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
        if (uris.isNotEmpty()) {
            ShareHub.addAll(uris)
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 43)
        }
    }
}
