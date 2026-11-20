package com.nuvio.app.features.home.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.features.home.EmptyScreenAction
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.home_empty_add_source
import nuvio.composeapp.generated.resources.home_empty_connect_server
import nuvio.composeapp.generated.resources.home_empty_open_downloads
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HomeEmptyStateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    actions: List<EmptyScreenAction> = emptyList(),
    onEmptyAction: (EmptyScreenAction) -> Unit = {},
) {
    NuvioSurfaceCard(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            actions.forEachIndexed { index, action ->
                val label = emptyActionLabel(action)
                if (index == 0) {
                    NuvioPrimaryButton(
                        text = label,
                        onClick = { onEmptyAction(action) },
                    )
                } else {
                    TextButton(onClick = { onEmptyAction(action) }) {
                        Text(text = label)
                    }
                }
            }
        } else if (actionLabel != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(16.dp))
            NuvioPrimaryButton(
                text = actionLabel,
                onClick = onActionClick,
            )
        }
    }
}

@Composable
private fun emptyActionLabel(action: EmptyScreenAction): String = when (action) {
    EmptyScreenAction.AddSource -> stringResource(Res.string.home_empty_add_source)
    EmptyScreenAction.ConnectServer -> stringResource(Res.string.home_empty_connect_server)
    EmptyScreenAction.OpenDownloads -> stringResource(Res.string.home_empty_open_downloads)
    EmptyScreenAction.Retry -> stringResource(Res.string.action_retry)
}
