package io.github.munetoshi.slidecompose.deck.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.takeOrElse
import io.github.munetoshi.slidecompose.deck.PresentationBuilder
import io.github.munetoshi.slidecompose.deck.SlideScope
import io.github.munetoshi.slidecompose.deck.theme.BulletTheme
import io.github.munetoshi.slidecompose.deck.theme.LocalSlideStyle
import io.github.munetoshi.slidecompose.deck.theme.LocalSlideTheme
import io.github.munetoshi.slidecompose.deck.theme.RevealStyle
import io.github.munetoshi.slidecompose.deck.theme.SlideTheme
import io.github.munetoshi.slidecompose.deck.theme.TitleAndContentTheme
import io.github.munetoshi.slidecompose.deck.theme.TitleSlideTheme
import io.github.munetoshi.slidecompose.deck.transition.ChangesWithSlides
import io.github.munetoshi.slidecompose.deck.transition.ElementTransitionMode
import io.github.munetoshi.slidecompose.deck.transition.LocalStepRecorder
import io.github.munetoshi.slidecompose.deck.transition.elementTransition

/** Applies [elementTransition] only when [id] is non-null. */
fun Modifier.elementTransitionIfNotNull(id: Any?, mode: ElementTransitionMode = ElementTransitionMode.Scale): Modifier =
    if (id == null) this else elementTransition(id, mode)

/** A title and an optional subtitle, centered unless the theme's [TitleSlideTheme] says otherwise. */
@Composable
fun TitleSlide(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    titleId: Any? = null,
    subtitleId: Any? = null,
) {
    val theme = LocalSlideTheme.current
    val look = theme.titleSlide
    val textAlign = look.horizontalAlignment.textAlign()
    Column(
        modifier.then(look.modifier).fillMaxSize().padding(look.padding),
        verticalArrangement = look.verticalArrangement,
        horizontalAlignment = look.horizontalAlignment,
    ) {
        SlideText(
            title,
            style = MaterialTheme.typography.displayMedium.merge(look.title),
            textAlign = textAlign,
            id = titleId,
        )
        if (subtitle != null) {
            val style = MaterialTheme.typography.titleLarge.merge(look.subtitle)
            Spacer(Modifier.height(look.spacing))
            SlideText(
                subtitle,
                Modifier.padding(start = look.subtitleIndent),
                id = subtitleId,
                style = style,
                color = style.color.takeOrElse { theme.muted },
                textAlign = textAlign,
            )
        }
    }
}

private fun Alignment.Horizontal.textAlign(): TextAlign = when (this) {
    Alignment.Start -> TextAlign.Start
    Alignment.End -> TextAlign.End
    else -> TextAlign.Center
}

/**
 * A slide with a title at the top and free-form content below it. [contentSpacing] defaults to
 * the theme's [TitleAndContentTheme].
 */
@Composable
fun TitleAndContent(
    title: String,
    modifier: Modifier = Modifier,
    titleId: Any? = null,
    contentSpacing: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val look = LocalSlideTheme.current.titleAndContent
    Column(modifier.then(look.modifier).fillMaxSize().padding(look.padding)) {
        SlideText(
            title,
            style = MaterialTheme.typography.headlineLarge.merge(look.title),
            id = titleId,
        )
        Spacer(Modifier.height(look.titleSpacing))
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(contentSpacing ?: look.contentSpacing),
            content = content,
        )
    }
}

/**
 * A bullet point. Nest with [level]. Text between backticks is shown as inline code, see
 * [slideText]. [marker] defaults to the theme's [BulletTheme].
 */
@Composable
fun Bullet(
    text: String,
    modifier: Modifier = Modifier,
    level: Int = 0,
    marker: String? = null,
    id: Any? = null,
) = Bullet(slideText(text), modifier, level, marker, id)

/**
 * A bullet point. [id] gives its text (not the marker) an [elementTransition] id, e.g. to turn an
 * agenda item into the title of the next slide.
 */
@Composable
fun Bullet(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    level: Int = 0,
    marker: String? = null,
    id: Any? = null,
) {
    val theme = LocalSlideTheme.current
    val look = theme.bullet
    val style = if (level == 0) {
        MaterialTheme.typography.bodyLarge.merge(look.text)
    } else {
        MaterialTheme.typography.bodyMedium.merge(look.nestedText)
    }
    Row(modifier.padding(start = look.indent * level).then(look.modifier)) {
        Text(
            marker ?: look.markers[level.coerceIn(0, look.markers.lastIndex)],
            style = style,
            color = look.markerColor.takeOrElse { theme.primary },
        )
        Spacer(Modifier.width(look.markerSpacing))
        SlideText(text, style = style, id = id)
    }
}

/**
 * Shows [content] from build step [atStep] onwards (up to and including [until]). Space for it
 * is reserved even while hidden, like a Keynote build, so other content does not move.
 * Slides count their build steps from these unless given `steps` explicitly.
 *
 * It fades in by default. [durationMillis], [offset] (a distance to slide up from) and [easing]
 * default to the slide's [RevealStyle].
 */
@Composable
fun SlideScope.Reveal(
    atStep: Int,
    modifier: Modifier = Modifier,
    until: Int = Int.MAX_VALUE,
    durationMillis: Int? = null,
    offset: Dp? = null,
    easing: Easing? = null,
    content: @Composable () -> Unit,
) {
    val style = LocalSlideStyle.current.reveal
    val offset = offset ?: style.offset
    LocalStepRecorder.current?.let { recorder ->
        recorder.record(atStep)
        if (until != Int.MAX_VALUE) recorder.record(until + 1)
    }
    val visible = step in atStep..until
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis ?: style.durationMillis, easing = easing ?: style.easing),
        label = "reveal",
    )
    Box(
        modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * offset.toPx()
        },
    ) {
        content()
    }
}

/**
 * "3 / 12", or "3" without [showsCount], in the theme's [SlideTheme.slideNumber] style. In a
 * [background][PresentationBuilder.background], which stays still while the slides change, the
 * number changes with them: the old one goes as the old slide does, and the new one comes as the
 * new slide does.
 */
@Composable
fun SlideScope.SlideNumber(modifier: Modifier = Modifier, showsCount: Boolean = true) {
    val theme = LocalSlideTheme.current
    val style = MaterialTheme.typography.labelLarge.merge(theme.slideNumber)
    ChangesWithSlides(modifier) { scope ->
        Text(
            if (showsCount) "${scope.slideIndex + 1} / ${scope.slideCount}" else "${scope.slideIndex + 1}",
            style = style,
            color = style.color.takeOrElse { theme.muted },
        )
    }
}
