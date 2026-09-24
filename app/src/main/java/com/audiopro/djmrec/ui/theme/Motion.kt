package com.audiopro.djmrec.ui.theme

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * Shared motion tokens. Durations are chosen per distance and complexity (skill rules:
 * duration-timing / motion-consistency): micro feedback is instant-feeling, container
 * replacement is brief, and exits run ~60% of entrances so leaving always feels responsive
 * (exit-faster-than-enter). Slides arrive on springs -- natural deceleration and inherently
 * interruptible/cancellable (spring-physics, interruptible, cancellable-state-transitions);
 * nothing here blocks input, and every fade completes fully (opacity-threshold).
 *
 * All builders take `reduced` so callers can collapse to a brief crossfade when the user
 * has animations disabled ([rememberReducedMotion]).
 */
object DjmRecMotion {

    /** Micro state flips and press feedback. */
    const val FAST = 150

    /** Enter side of container / page replacement. */
    const val BASE = 300

    /** Exit side -- deliberately shorter than the enter. */
    const val EXIT = 180

    private val arrive = spring<IntOffset>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    /**
     * Page/step replacement with directional continuity (navigation-direction): forward
     * enters a quarter-slide from the right (or below when [vertical]), backward mirrors it.
     * The quarter distance keeps it modern and subtle rather than a full-screen carousel.
     */
    fun pageTransform(
        forward: Boolean,
        vertical: Boolean = false,
        reduced: Boolean = false
    ): ContentTransform {
        if (reduced) return fadeIn(tween(FAST)) togetherWith fadeOut(tween(FAST))
        val from = if (forward) 1 else -1
        val enter = fadeIn(tween(BASE)) + if (vertical) {
            slideInVertically(arrive) { from * it / 4 }
        } else {
            slideInHorizontally(arrive) { from * it / 4 }
        }
        val exit = fadeOut(tween(EXIT)) + if (vertical) {
            slideOutVertically(tween(EXIT)) { -from * it / 4 }
        } else {
            slideOutHorizontally(tween(EXIT)) { -from * it / 4 }
        }
        return enter togetherWith exit
    }

    /** Content swap with no spatial implication (overlays, transport rows). */
    fun fadeTransform(reduced: Boolean = false): ContentTransform {
        val enter = if (reduced) FAST else BASE
        return fadeIn(tween(enter)) togetherWith fadeOut(tween(EXIT))
    }
}
