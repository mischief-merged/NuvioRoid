package com.nuvio.app.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppVersionPolicy
import com.nuvio.app.core.build.UPSTREAM_DESKTOP_VERSION
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.fork_about_based_on
import nuvio.composeapp.generated.resources.fork_about_based_on_with_core
import nuvio.composeapp.generated.resources.fork_about_credit
import nuvio.composeapp.generated.resources.fork_about_open_project
import nuvio.composeapp.generated.resources.fork_about_tagline
import nuvio.composeapp.generated.resources.fork_about_version
import nuvio.composeapp.generated.resources.nuvioroid_logo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The fork's name and project links. */
internal object ForkIdentity {
    const val NAME = "NuvioRoid"

    const val PROJECT_HOST = "github.com/mischief-merged/NuvioRoid"
    const val PROJECT_URL = "https://$PROJECT_HOST"
}

/** The footer of Settings: this fork's name and version, then what it is based on and who made that. */
@Composable
internal fun ForkAttribution(
    isTablet: Boolean,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = if (isTablet) 20.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(Res.drawable.nuvioroid_logo),
                contentDescription = null,
                modifier = Modifier.size(if (isTablet) 44.dp else 38.dp).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = ForkIdentity.NAME,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(if (isTablet) 6.dp else 4.dp))
        AttributionLine(stringResource(Res.string.fork_about_tagline))
        AttributionLine(
            stringResource(
                Res.string.fork_about_version,
                AppVersionPolicy.displayVersionName,
                AppVersionPolicy.displayVersionCode,
            ),
        )
        val core = AppVersionPolicy.basedOnVersionName
        AttributionLine(
            if (core != null) {
                stringResource(Res.string.fork_about_based_on_with_core, UPSTREAM_DESKTOP_VERSION, core)
            } else {
                stringResource(Res.string.fork_about_based_on, UPSTREAM_DESKTOP_VERSION)
            },
        )
        AttributionLine(stringResource(Res.string.fork_about_credit))
        Text(
            text = ForkIdentity.PROJECT_HOST,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(Res.string.fork_about_open_project),
                ) { runCatching { uriHandler.openUri(ForkIdentity.PROJECT_URL) } }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
            textDecoration = TextDecoration.Underline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AttributionLine(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}
