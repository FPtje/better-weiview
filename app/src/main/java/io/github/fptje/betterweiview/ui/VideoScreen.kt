package io.github.fptje.betterweiview.ui

import android.content.Context
import android.net.Network
import android.util.Log
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import io.github.fptje.betterweiview.R
import io.github.fptje.betterweiview.icap.IcapClient
import io.github.fptje.betterweiview.icap.IcapException
import io.github.fptje.betterweiview.icap.VideoFrame
import io.github.fptje.betterweiview.video.MjpegRenderer
import io.github.fptje.betterweiview.video.TiltEstimator

/** Full-screen live view with the auto-rotate toggle. Calls [onExit] on back or a fatal error. */
@Composable
fun VideoScreen(network: Network, onExit: () -> Unit) {
    val context = LocalContext.current
    val renderer = remember { MjpegRenderer() }
    var streaming by remember { mutableStateOf(false) }

    BackHandler(onBack = onExit)
    ImmersiveMode()

    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var autoRotate by remember { mutableStateOf(prefs.getBoolean(PREF_AUTO_ROTATE, true)) }
    val currentAutoRotate by rememberUpdatedState(autoRotate)

    LaunchedEffect(network) {
        val error = streamUntilFailure(network, renderer, { currentAutoRotate }, { streaming = it })
        val message = context.getString(R.string.error_stream, error.message ?: error.javaClass.simpleName)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        onExit()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(renderer) } },
                modifier = Modifier.fillMaxSize(),
            )
            if (!streaming) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color.White)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = autoRotate, role = Role.Checkbox, onValueChange = {
                    autoRotate = it
                    prefs.edit { putBoolean(PREF_AUTO_ROTATE, it) }
                })
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Checkbox(
                checked = autoRotate,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    uncheckedColor = Color.White,
                    checkmarkColor = Color.Black,
                    checkedColor = Color.White,
                ),
            )
            Text(
                text = stringResource(R.string.auto_rotate),
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Hides the system bars and keeps the screen on while in composition. */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
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
}

/**
 * Streams frames to [renderer], reconnecting after drops (Wi-Fi hiccups, camera resets)
 * like the vendor app does. [onStreaming] reports whether frames are being drawn.
 * Returns the error that ended streaming for good; cancellation ends it silently.
 */
private suspend fun streamUntilFailure(
    network: Network,
    renderer: MjpegRenderer,
    autoRotate: () -> Boolean,
    onStreaming: (Boolean) -> Unit,
): Exception {
    val tilt = TiltEstimator()
    var failures = 0
    while (true) {
        val error: Exception = try {
            IcapClient(network).frames()
                .map { frame ->
                    if (frame.codec != VideoFrame.CODEC_MJPEG) {
                        throw UnsupportedOperationException("unsupported video codec ${frame.codec}")
                    }
                    // Track tilt even while disabled so enabling it is instant and smooth.
                    val angle = frame.accel?.let { tilt.update(it.x, it.y, it.z) }
                    renderer.render(frame.data, rotation = angle?.takeIf { autoRotate() })
                }
                .flowOn(Dispatchers.Default)
                .collect { drawn ->
                    if (drawn) {
                        onStreaming(true)
                        failures = 0
                    }
                }
            IcapException("stream ended")
        } catch (e: IOException) {
            e
        } catch (e: UnsupportedOperationException) {
            Log.e(TAG, "giving up", e)
            return e
        }
        failures++
        Log.w(TAG, "stream dropped, reconnect attempt $failures/$MAX_RECONNECTS", error)
        if (failures > MAX_RECONNECTS) return error
        onStreaming(false)
        delay(RECONNECT_DELAY_MS)
    }
}

private const val TAG = "BetterWeiview"
private const val MAX_RECONNECTS = 3
private const val RECONNECT_DELAY_MS = 1_000L
private const val PREFS = "settings"
private const val PREF_AUTO_ROTATE = "auto_rotate"
