/*
 * Small-cover presentation shared by the portrait and landscape players:
 * one card size and one unsharp mask, so the source no longer decides the
 * layout — a telegram file and a tier-4 file render through exactly the
 * same two states (card when the source is under PLAYER_MIN_PX, the full
 * region when it is not).
 */
package com.extrumz.app.ui.player

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Unsharp mask over the *already upscaled* pixels: a 1 px high-pass against
 * a 4-tap blur, clamped back into range. This is the only thing that makes a
 * small source read above its own resolution — bilinear upscaling can only
 * ever soften, and softness is what reads as "low quality".
 *
 * Neighbours sampling to transparent black (outside the image, at its edges)
 * fall back to the centre pixel so the border cannot darken.
 */
private const val UNSHARP_MASK = """
uniform shader content;

half4 main(float2 p) {
    half4 c = content.eval(p);
    half4 e = content.eval(p + float2(1.0, 0.0));
    half4 w = content.eval(p - float2(1.0, 0.0));
    half4 s = content.eval(p + float2(0.0, 1.0));
    half4 n = content.eval(p - float2(0.0, 1.0));
    half3 blur = (
        (e.a > 0.0 ? e.rgb : c.rgb) +
        (w.a > 0.0 ? w.rgb : c.rgb) +
        (s.a > 0.0 ? s.rgb : c.rgb) +
        (n.a > 0.0 ? n.rgb : c.rgb)
    ) * 0.25;
    half3 sharp = clamp(c.rgb + (c.rgb - blur) * 0.75, half3(0.0), half3(1.0));
    return half4(sharp, c.a);
}
"""

/** Fraction of the screen's shorter side — the small-cover card, both orientations. */
private const val SMALL_CARD_FRACTION = 0.45f

/**
 * The side of the card a too-small cover is presented at. Deliberately the
 * same value in portrait and landscape (screen shorter side, not the local
 * region): the card must look like one design decision, not two.
 */
@Composable
fun smallArtCardSize(): Dp {
    val config = LocalConfiguration.current
    return minOf(config.screenWidthDp, config.screenHeightDp).dp * SMALL_CARD_FRACTION
}

/**
 * Runs [UNSHARP_MASK] over this node's rendered pixels. `active` should be
 * true only while this node is drawing a source smaller than its region;
 * a cover already at region size pays nothing.
 *
 * RuntimeShader is API 33+; below that this is a no-op and the plain
 * bilinear result ships instead.
 */
@Composable
fun Modifier.sharpenUpscale(active: Boolean): Modifier {
    if (!active || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val shader = remember { RuntimeShader(UNSHARP_MASK) }
    val effect = remember(shader) {
        // createShaderEffect would leave `content` unbound — it is for plain
        // Shaders that paint on their own, not for a RuntimeShader that
        // SAMPLES the incoming layer. An unbound child makes the whole effect
        // fail at draw time with no exception, which is how every small cover
        // rendered as an empty card. The name here must match the uniform.
        AndroidRenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
    return graphicsLayer { renderEffect = effect }
}
