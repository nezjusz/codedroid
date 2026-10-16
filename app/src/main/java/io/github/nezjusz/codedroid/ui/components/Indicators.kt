package io.github.nezjusz.codedroid.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator as MaterialCircularWavyProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator as MaterialContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator as MaterialLinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator as MaterialLoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Thin wrappers over the Material 3 Expressive loading components.
 *
 * The upstream composables carry the expressive shape-polygon parameters; screens only need a
 * colour and a size, so the wrappers keep call sites readable.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    size: Dp = 18.dp,
) {
    // The default size is applied *before* the caller's modifier so an explicit size at the call
    // site wins: in Compose the later size modifier overrides the earlier one.
    MaterialLoadingIndicator(modifier = Modifier.size(size).then(modifier), color = color)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContainedLoadingIndicator(
    modifier: Modifier = Modifier,
    indicatorColor: Color = Color.Unspecified,
    containerColor: Color = Color.Unspecified,
    size: Dp = 44.dp,
) {
    MaterialContainedLoadingIndicator(
        modifier = Modifier.size(size).then(modifier),
        containerColor = containerColor,
        indicatorColor = indicatorColor,
    )
}

/**
 * Horizontal "the agent is working" bar.
 *
 * This must be the *linear* variant: the circular one squashed into a short wide box draws its
 * morphing polygons as a band of lines sweeping the full width, which reads as a glitch rather
 * than as progress.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinearWorkingBar(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    MaterialLinearWavyProgressIndicator(modifier = modifier, color = color)
}

/** The circular wavy variant, for genuinely circular slots. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CircularWorking(modifier: Modifier = Modifier, color: Color = Color.Unspecified, size: Dp = 20.dp) {
    MaterialCircularWavyProgressIndicator(modifier = Modifier.size(size).then(modifier), color = color)
}
