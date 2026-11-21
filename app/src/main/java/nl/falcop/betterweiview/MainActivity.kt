package nl.falcop.betterweiview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.falcop.betterweiview.net.CameraNetworkMonitor
import nl.falcop.betterweiview.ui.AppTheme
import nl.falcop.betterweiview.ui.ConnectScreen
import nl.falcop.betterweiview.ui.VideoScreen

class MainActivity : ComponentActivity() {
    private lateinit var monitor: CameraNetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        monitor = CameraNetworkMonitor(applicationContext).also { it.start() }
        setContent {
            AppTheme {
                val network by monitor.cameraNetwork.collectAsStateWithLifecycle()
                var streaming by rememberSaveable { mutableStateOf(false) }
                val current = network
                if (streaming && current != null) {
                    VideoScreen(current, onExit = { streaming = false })
                } else {
                    ConnectScreen(enabled = current != null, onConnect = { streaming = true })
                }
            }
        }
    }

    override fun onDestroy() {
        monitor.stop()
        super.onDestroy()
    }
}
