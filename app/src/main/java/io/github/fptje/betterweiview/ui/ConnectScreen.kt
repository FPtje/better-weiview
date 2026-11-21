package io.github.fptje.betterweiview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fptje.betterweiview.R

/** The start screen: one Connect button, enabled while the camera is reachable. */
@Composable
fun ConnectScreen(enabled: Boolean, onConnect: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // About twice the default Button size: doubled padding and text (titleLarge is 22sp).
            Button(
                onClick = onConnect,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
            ) {
                Text(stringResource(R.string.connect), style = MaterialTheme.typography.displayMedium)
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
