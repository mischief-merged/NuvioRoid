package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.isTraySupported
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_tray_show
import nuvio.composeapp.generated.resources.downloads_tray_pause_all
import nuvio.composeapp.generated.resources.downloads_tray_resume_all
import nuvio.composeapp.generated.resources.quit_downloads_quit
import org.jetbrains.compose.resources.stringResource

/** A small progress ring stays legible in either menu bar appearance. Details are in its menu and tooltip. */
private class DownloadTrayPainter(private val percent: Int?, private val running: Boolean) : Painter() {
    override val intrinsicSize = Size(22f, 22f)

    override fun DrawScope.onDraw() {
        val diameter = size.minDimension - 6f
        val offset = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        drawCircle(Color(0xFF808080), radius = diameter / 2f, style = Stroke(2f))
        drawArc(
            color = if (running) Color(0xFF36A9E1) else Color(0xFF808080),
            startAngle = -90f,
            sweepAngle = (percent ?: if (running) 25 else 0) * 3.6f,
            useCenter = false,
            topLeft = offset,
            size = Size(diameter, diameter),
            style = Stroke(3f),
        )
    }
}

@Composable
internal fun ApplicationScope.DesktopDownloadTray(
    progress: DownloadTrayProgress,
    onShow: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onQuit: () -> Unit,
) {
    if (!isTraySupported) return
    var text by remember { mutableStateOf("") }
    LaunchedEffect(progress) { text = downloadTrayText(progress) }
    val painter = remember(progress.percent, progress.running > 0) {
        DownloadTrayPainter(progress.percent, progress.running > 0)
    }
    Tray(icon = painter, tooltip = text, onAction = onShow) {
        Item(text, enabled = false, onClick = {})
        Separator()
        Item(stringResource(Res.string.downloads_tray_show), onClick = onShow)
        Item(stringResource(Res.string.downloads_tray_pause_all), enabled = progress.running > 0, onClick = onPause)
        Item(stringResource(Res.string.downloads_tray_resume_all), enabled = progress.paused > 0, onClick = onResume)
        Separator()
        Item(stringResource(Res.string.quit_downloads_quit), onClick = onQuit)
    }
}
