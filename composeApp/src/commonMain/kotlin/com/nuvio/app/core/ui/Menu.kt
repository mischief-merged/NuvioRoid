package com.nuvio.app.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.nuvio.app.controller.ControllerFocusEngine
import com.nuvio.app.controller.LocalControllerModalOverlayToken

private val MenuShape = RoundedCornerShape(NuvioTokens.Space.s20)

private val MenuMinWidth = 180.dp

@Composable
fun Menu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(NuvioTokens.Space.none, NuvioTokens.Space.s6),
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val menuOverlayToken = remember { Any() }
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    if (expanded) {
        DisposableEffect(menuOverlayToken) {
            ControllerFocusEngine.pushModalOverlay(
                token = menuOverlayToken,
                onBack = {
                    currentOnDismiss()
                    true
                },
            )
            onDispose { ControllerFocusEngine.popModalOverlay(menuOverlayToken) }
        }
        PlatformBackHandler(enabled = true) {
            onDismissRequest()
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.platformTouchScroll().widthIn(min = MenuMinWidth),
        offset = offset,
        shape = MenuShape,
        containerColor = tokens.colors.surfacePopover,
        tonalElevation = tokens.elevation.flat,
        shadowElevation = tokens.elevation.overlay,
        border = BorderStroke(tokens.borders.thin, SurfaceEdge),
    ) {
        val menuFocusManager = LocalFocusManager.current
        DisposableEffect(menuFocusManager) {
            ComposePopupFocus.push(menuFocusManager)
            onDispose { ComposePopupFocus.pop(menuFocusManager) }
        }
        CompositionLocalProvider(LocalControllerModalOverlayToken provides menuOverlayToken) {
            Column(
                modifier = Modifier.onFocusChanged {
                    ComposePopupFocus.setFocused(menuFocusManager, it.hasFocus)
                },
                content = content,
            )
        }
    }
}

@Composable
fun MenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTokens.Space.s8)
            .clip(tokens.shapes.compactCard)
            .background(if (selected) tokens.colors.accent.copy(alpha = tokens.opacity.hover) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(NuvioTokens.Space.s12),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            color = if (enabled) tokens.colors.textPrimary else tokens.colors.textDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier.size(tokens.icons.md),
            )
        }
    }
}
