package io.github.munetoshi.slidecompose.player.presenter

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.components.LocalAssetDirectory
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideRenderer

/**
 * A small picture of [frame], with its slide's number, as the slide bar shows it under the mouse.
 * The picture is drawn once into [pictures], and fades in when it comes.
 */
@Composable
internal fun SlidePreview(controller: PresentationController, renderer: SlideRenderer, frame: Frame, pictures: SnapshotStateMap<Frame, ImageBitmap>) {
    val width = with(LocalDensity.current) { PreviewWidth.roundToPx() }
    val assets = LocalAssetDirectory.current
    LaunchedEffect(frame, width) {
        if (frame !in pictures) renderer.thumbnail(controller, frame, width, assets)?.let { pictures[frame] = it }
    }
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier.padding(bottom = PreviewGap).shadow(8.dp, shape).clip(shape).background(colors.panel).border(1.dp, colors.upcoming, shape).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(PreviewWidth).aspectRatio(controller.presentation.size.aspectRatio).clip(RoundedCornerShape(4.dp)).background(colors.background)) {
            val picture = pictures[frame]
            // A picture there from the start shows as it is.
            val alpha by animateFloatAsState(if (picture != null) 1f else 0f, tween(SlideListMillis), label = "preview")
            picture?.let { Image(it, contentDescription = null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentScale = ContentScale.Fit) }
        }
        Text("${frame.slide + 1}", color = colors.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

private val PreviewWidth: Dp = 176.dp

/** Room between the preview and the bar under it. */
private val PreviewGap: Dp = 6.dp
