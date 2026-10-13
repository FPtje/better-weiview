package nl.falcop.betterweiview

import android.net.Network
import android.os.Bundle
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import nl.falcop.betterweiview.icap.IcapClient
import nl.falcop.betterweiview.icap.IcapException
import nl.falcop.betterweiview.icap.VideoFrame
import nl.falcop.betterweiview.net.CameraNetworkMonitor
import nl.falcop.betterweiview.video.MjpegRenderer

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

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        android.os.Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun ConnectScreen(enabled: Boolean, onConnect: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = onConnect, enabled = enabled) {
                Text(stringResource(R.string.connect), style = MaterialTheme.typography.titleLarge)
            }
            Text(
                text = stringResource(if (enabled) R.string.hint_ready else R.string.hint_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun VideoScreen(network: Network, onExit: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val renderer = remember { MjpegRenderer() }
    var firstFrame by remember { mutableStateOf(false) }

    BackHandler(onBack = onExit)

    DisposableEffect(view) {
        val window = (view.context as ComponentActivity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(network) {
        IcapClient(network).frames()
            .map { frame ->
                if (frame.codec != VideoFrame.CODEC_MJPEG) {
                    throw IcapException("unsupported video codec ${frame.codec}")
                }
                renderer.render(frame.data)
            }
            .flowOn(Dispatchers.Default)
            .catch { e ->
                Toast.makeText(context, context.getString(R.string.error_stream, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                onExit()
            }
            .collect { drawn -> if (drawn) firstFrame = true }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(renderer) } },
            modifier = Modifier.fillMaxSize(),
        )
        if (!firstFrame) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color.White)
        }
    }
}
