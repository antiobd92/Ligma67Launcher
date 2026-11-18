package com.ligma67.launcher.anim

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import com.ligma67.launcher.data.Curve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.sqrt
import androidx.compose.animation.core.animate as animateValue

/**
 * An interruptible animated float.
 *
 * - Reads of [value] inside graphicsLayer/draw lambdas only invalidate that layer — zero
 *   recomposition per frame.
 * - Retargeting mid-flight starts from the current value AND current velocity, so spamming
 *   open/close never snaps or restarts; springs hand momentum over seamlessly.
 */
@Stable
class Motion(initial: Float) {
    var value by mutableFloatStateOf(initial)
        private set
    var velocity = 0f
        private set
    var target = initial
        private set
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun snap(v: Float) {
        job?.cancel()
        job = null
        value = v
        target = v
        velocity = 0f
    }

    fun animateTo(
        scope: CoroutineScope,
        to: Float,
        spec: AnimationSpec<Float>,
        context: CoroutineContext = EmptyCoroutineContext,
        initialVelocity: Float? = null,
        onFrame: ((Float) -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
    ): Job {
        job?.cancel()
        target = to
        val from = value
        val v0 = initialVelocity ?: velocity
        val j = scope.launch(context) {
            animateValue(from, to, v0, spec) { v, vel ->
                value = v
                velocity = vel
                onFrame?.invoke(v)
            }
            velocity = 0f
            onEnd?.invoke()
        }
        job = j
        return j
    }
}

/** Speeds animations up/down globally (and optionally ignores the system animator scale). */
class FixedMotionScale(override val scaleFactor: Float) : MotionDurationScale

object Specs {
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    val Overshoot: Easing = Easing { t ->
        val tension = 1.4f
        val x = t - 1f
        x * x * ((tension + 1f) * x + tension) + 1f
    }

    fun easing(curve: Curve): Easing = when (curve) {
        Curve.SPRING, Curve.EMPHASIZED -> Emphasized
        Curve.STANDARD -> FastOutSlowInEasing
        Curve.DECELERATE -> Decelerate
        Curve.OVERSHOOT -> Overshoot
        Curve.LINEAR -> LinearEasing
    }

    fun <T> of(curve: Curve, durationMs: Int, damping: Float, stiffness: Float): AnimationSpec<T> =
        if (curve == Curve.SPRING) spring(dampingRatio = damping, stiffness = stiffness)
        else tween(durationMillis = durationMs.coerceAtLeast(0), easing = easing(curve))

    /** Rough visual settle time of a spring, used to size the matching window animation. */
    fun springDurationMs(damping: Float, stiffness: Float): Int {
        val omega = sqrt(stiffness.coerceAtLeast(1f))
        val zeta = damping.coerceIn(0.1f, 1.5f)
        return (3.2f / (zeta * omega) * 1000f).toInt().coerceIn(120, 900)
    }
}
