package io.github.munetoshi.slidecompose.player.presenter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.munetoshi.slidecompose.deck.Frame
import io.github.munetoshi.slidecompose.deck.Slide
import io.github.munetoshi.slidecompose.deck.components.LocalAssetDirectory
import io.github.munetoshi.slidecompose.deck.theme.with
import io.github.munetoshi.slidecompose.generated.resources.*
import io.github.munetoshi.slidecompose.player.stage.AppForeground
import io.github.munetoshi.slidecompose.player.stage.PresentationController
import io.github.munetoshi.slidecompose.player.stage.SlideRenderer
import io.github.munetoshi.slidecompose.player.stage.keepsNoFocus
import org.jetbrains.compose.resources.stringResource

/**
 * A toggle under the values of the bars, and when open, the slides, each as its representative
 * step (see [Slide.representativeStep]): the current one, the next ones and the one before, as far
 * as there is room (see [focusScroll]), the others a scroll away. Clicking one goes to it. The list
 * unfolds as it opens, while its pictures are drawn, which fade in as they come.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SlideList(controller: PresentationController, renderer: SlideRenderer, open: Boolean, onOpenChange: (Boolean) -> Unit) {
    Row {
        Box(Modifier.width(ValueWidth), contentAlignment = Alignment.TopEnd) {
            TooltipArea(tooltip = { Tooltip(stringResource(if (open) Res.string.tooltip_hide_slide_list else Res.string.tooltip_show_slide_list)) }) {
                Text(
                    stringResource(Res.string.caption_slides) + if (open) " ▾" else " ▸",
                    Modifier.clip(RoundedCornerShape(4.dp)).keepsNoFocus().clickable { onOpenChange(!open) }.padding(horizontal = 4.dp, vertical = 2.dp),
                    color = colors.dim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        AnimatedVisibility(
            open,
            Modifier.weight(1f),
            enter = expandVertically(tween(SlideListMillis), expandFrom = Alignment.Top) + fadeIn(tween(SlideListMillis)),
            exit = shrinkVertically(tween(SlideListMillis), shrinkTowards = Alignment.Top) + fadeOut(tween(SlideListMillis)),
        ) {
            Thumbnails(controller, renderer, Modifier.fillMaxWidth())
        }
    }
}

/** How long the slide list takes to open or close, and a picture in it to fade in. */
internal const val SlideListMillis = 250

@Composable
private fun Thumbnails(controller: PresentationController, renderer: SlideRenderer, modifier: Modifier) {
    val presentation = controller.presentation
    val current = controller.frame.slide
    val width = with(LocalDensity.current) { ThumbnailWidth.roundToPx() }
    val assets = LocalAssetDirectory.current
    // Drawn again each time the list opens, so they follow the deck's state, e.g. its language.
    val pictures = remember(presentation, width) { mutableStateMapOf<Int, ImageBitmap>() }
    LaunchedEffect(presentation, width) {
        // The ones in view first: the current slide, the one before and those after.
        val inView = sequenceOf(current) + (current - 1 downTo 0).take(1) + (current + 1 until presentation.slides.size)
        for (index in inView + (current - 2 downTo 0)) {
            val frame = Frame(index, presentation.slides[index].representativeStep)
            renderer.thumbnail(controller, frame, width, assets)?.let { pictures[index] = it }
        }
    }
    val stride = width + with(LocalDensity.current) { ThumbnailGap.roundToPx() }
    BoxWithConstraints(modifier) {
        val viewport = constraints.maxWidth
        val count = presentation.slides.size
        // Past the last slide, the list stops: the last one is at the right edge.
        val start = thumbnailScroll(current, count, stride, width, viewport)
        val list = rememberLazyListState(start / stride, start % stride)
        LaunchedEffect(current, viewport) {
            val target = thumbnailScroll(current, count, stride, width, viewport)
            list.animateScrollToItem(target / stride, target % stride)
        }
        LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(ThumbnailGap)) {
            items(presentation.slides.size) { index ->
                Thumbnail(index + 1, index == current, pictures[index], presentation.size.aspectRatio) {
                    if (!AppForeground.justRaised()) controller.goToSlide(index)
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(number: Int, isCurrent: Boolean, picture: ImageBitmap?, aspectRatio: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Column(
        Modifier.width(ThumbnailWidth).clip(RoundedCornerShape(6.dp)).pointerHoverIcon(PointerIcon.Hand).keepsNoFocus().clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspectRatio).clip(shape).background(colors.panel)
                .border(if (isCurrent) 2.dp else 1.dp, if (isCurrent) colors.accent else colors.upcoming, shape),
        ) {
            // A picture there from the start shows as it is.
            val alpha by animateFloatAsState(if (picture != null) 1f else 0f, tween(SlideListMillis), label = "thumbnail")
            picture?.let { Image(it, contentDescription = null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentScale = ContentScale.Fit) }
        }
        Text("$number", color = if (isCurrent) colors.text else colors.dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

private val ThumbnailWidth: Dp = 128.dp
private val ThumbnailGap: Dp = 12.dp
