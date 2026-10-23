package com.nlx.music.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nlx.music.data.artwork.ArtworkNormalizer

/**
 * Renders static album cover art in a full-bleed top-anchored hero mode.
 *
 * Characteristics:
 * - Uses the high-resolution master image (Spotify/Apple Music/Tidal/Deezer) or upscaled YouTube art.
 * - Anchors top-aligned and crops to fill full viewport width down to the playback controls.
 * - Applies a hardware-accelerated bottom alpha gradient fade (BlendMode.DstIn) to smoothly melt
 *   the artwork into the dark scrim and fluid background behind the player controls.
 * - Hardware-accelerated alpha transition when switching between player tabs.
 * - Smooth 350ms crossfade between consecutive tracks.
 */
@Composable
fun StaticArtworkHero(
    name: String,
    artist: String,
    embeddedUrl: String?,
    resolvedUrl: String?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    bottomFade: Float = 0.42f,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.TopCenter,
) {
    val context = LocalContext.current

    val highResUrl = resolvedUrl?.takeIf { it.isNotBlank() }
    val displayUrl = if (highResUrl != null) {
        highResUrl
    } else if (ArtworkNormalizer.isRealImage(embeddedUrl)) {
        ArtworkNormalizer.upscaleYoutubeArtwork(embeddedUrl)
    } else {
        null
    }

    if (displayUrl.isNullOrBlank()) return

    val model = remember(displayUrl, context) {
        ImageRequest.Builder(context)
            .data(displayUrl)
            .crossfade(350)
            .allowHardware(true)
            .build()
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                if (alpha <= 0.001f) return@drawWithContent
                drawContent()
                if (bottomFade > 0.001f) {
                    val fadeFrac = bottomFade.coerceIn(0f, 1f)
                    val fadeStartY = size.height * (1f - fadeFrac)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Black,
                            (fadeStartY / size.height) to Color.Black,
                            1f to Color.Transparent,
                            startY = 0f,
                            endY = size.height,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            alignment = alignment,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
