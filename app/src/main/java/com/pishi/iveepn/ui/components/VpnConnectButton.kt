package com.pishi.iveepn.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pishi.iveepn.ui.theme.rememberAnimationsEnabled
import java.util.Locale
import kotlin.math.exp
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Animated VPN connect button — expressive robot artwork (v2) supplied
 * by the project owner, integrated behind the existing
 * controlled-widget API:
 *
 *  - disconnected: a sleepy robot that looks around, blinks, yawns
 *    and stretches on a 9-second behaviour loop;
 *  - connecting: the robot folds into a 3x3 dot loader with a
 *    rolling wave;
 *  - connected: a happy filled robot that hops with squash and
 *    stretch, wiggles, smiles and sparkles.
 *
 * Every state is a pose function of time and the three poses are
 * blended frame by frame, so state changes morph instead of cutting.
 * The caller owns [state] and [onClick]; the label block under the
 * artwork (titles/subtitle, or the live HH:MM:SS session timer while
 * connected) is unchanged. Label colours follow the Material colour
 * scheme; the painted robot keeps its fixed palette in both themes,
 * except the loader dots and sparkles, which take the scheme's
 * on-surface colour so they stay visible on the light background
 * (the reference artwork assumed a dark screen). With system
 * animations off ([rememberAnimationsEnabled]), the frame loop stops
 * and each state shows one static pose.
 */
enum class VpnButtonState { Disconnected, Connecting, Connected }

// Robot palette, sampled from the owner's reference video.
private val MintFill = Color(0xFF50EE92)
private val OutlineGrey = Color(0xFFC6C9D4)
private val EyeGreen = Color(0xFF4CFC88)
private val ShadowGreen = Color(0xFF27865D)

private const val UNITS = 64f          // design units across the canvas, (0, 0) = robot centre
private const val PI_F = 3.1415927f
private const val TWO_PI = 6.2831855f
private const val RATE = 6.5f          // blend speed between states (per second)
private const val OFF_LOOP = 9f        // length of the idle behaviour loop (seconds)
private const val HOP_PERIOD = 0.95f   // one happy hop (seconds)

@Composable
fun VpnConnectButton(
    state: VpnButtonState,
    onClick: () -> Unit,
    connectedAtEpochMs: Long?,
    disconnectedTitle: String,
    disconnectedSubtitle: String,
    connectingTitle: String,
    connectingSubtitle: String,
    stateDesc: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    val haptics = LocalHapticFeedback.current
    // The state parameter is named stateDesc (not stateDescription) so that
    // inside the semantics {} block below, `stateDescription` still resolves
    // to the receiver's settable property instead of a shadowing val.

    val animator = remember { RobotAnimator() }
    LaunchedEffect(state, animationsEnabled) {
        if (animationsEnabled) {
            animator.setState(state.ordinal)
        } else {
            animator.snapTo(state.ordinal, staticLocalTime(state))
        }
    }
    LaunchedEffect(animator, animationsEnabled) {
        if (animationsEnabled) {
            while (true) {
                withFrameNanos { nanos -> animator.tick(nanos) }
            }
        }
    }
    val dotColor = MaterialTheme.colorScheme.onSurface

    // Session timer tick (only while connected).
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state, connectedAtEpochMs) {
        if (state == VpnButtonState.Connected && connectedAtEpochMs != null) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000)
            }
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
                .semantics {
                    role = Role.Button
                    stateDescription = stateDesc
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRobot(animator, dotColor)
            }
        }

        Spacer(modifier = Modifier.height(44.dp))

        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(animationSpec = tween(320)) +
                    slideInVertically(animationSpec = tween(320)) { (it * 0.4f).toInt() })
                    .togetherWith(fadeOut(animationSpec = tween(320)))
            },
            label = "vpnButtonLabel",
        ) { targetState ->
            when (targetState) {
                VpnButtonState.Connected -> Text(
                    text = formatSessionHhMmSs(connectedAtEpochMs, nowMs),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                VpnButtonState.Connecting -> ButtonTitleSubtitle(
                    title = connectingTitle,
                    subtitle = connectingSubtitle,
                )
                VpnButtonState.Disconnected -> ButtonTitleSubtitle(
                    title = disconnectedTitle,
                    subtitle = disconnectedSubtitle,
                )
            }
        }
    }
}

/** The frozen local time used for each state when animations are off. */
private fun staticLocalTime(state: VpnButtonState): Float = when (state) {
    VpnButtonState.Disconnected -> 0.4f  // neutral, before the first look-around
    VpnButtonState.Connecting -> 0f
    VpnButtonState.Connected -> HOP_PERIOD / 2f  // mid-hop, in the air
}

@Composable
private fun ButtonTitleSubtitle(title: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            fontSize = 30.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatSessionHhMmSs(connectedAtEpochMs: Long?, nowMs: Long): String {
    val totalSec = if (connectedAtEpochMs == null) {
        0L
    } else {
        ((nowMs - connectedAtEpochMs) / 1000).coerceAtLeast(0)
    }
    return String.format(
        Locale.US,
        "%02d:%02d:%02d",
        totalSec / 3600,
        (totalSec / 60) % 60,
        totalSec % 60,
    )
}

// ---------------------------------------------------------------------------
// Robot engine (owner-supplied v2; see the file header for the adaptations)
// ---------------------------------------------------------------------------

private val LOADER_RING = listOf(
    -1f to -1f, 0f to -1f, 1f to -1f, 1f to 0f,
    1f to 1f, 0f to 1f, -1f to 1f, -1f to 0f,
)
private val SPARKLE_POINTS = listOf(-28f to -14f, 27f to -16f, -29f to 7f, 28f to 8f)

private fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3f - 2f * t)
}

private fun window(x: Float, a1: Float, a2: Float, b1: Float, b2: Float): Float =
    smoothstep(a1, a2, x) * (1f - smoothstep(b1, b2, x))

private fun blinkCurve(c: Float, center: Float, duration: Float): Float {
    val u = (c - (center - duration / 2f)) / duration
    return if (u in 0f..1f) sin(PI_F * u) else 0f
}

private class Pose(
    val bodyY: Float, val sx: Float, val sy: Float, val rot: Float,
    val bodyAlpha: Float, val fill: Float, val faceAlpha: Float,
    val eyeOpen: Float, val eyeX: Float, val eyeY: Float, val happy: Float,
    val mouthCy: Float, val mouthRx: Float, val mouthRy: Float, val mouthFill: Float,
    val shadowAlpha: Float, val shadowScale: Float,
    val dotAlpha: Float, val spread: Float,
    val sparkle: Float,
)

// Disconnected: looks left, right and up, blinks, droops, then yawns with a stretch and a tilt
private fun poseOff(t: Float): Pose {
    val c = t % OFF_LOOP
    val lookL = window(c, 0.6f, 1.1f, 1.7f, 2.2f)
    val lookR = window(c, 3.0f, 3.5f, 4.2f, 4.7f)
    val lookU = window(c, 4.7f, 5.1f, 5.5f, 5.9f)
    val blink = blinkCurve(c, 2.5f, 0.22f)
    val droop = window(c, 6.1f, 6.6f, 7.6f, 8.1f)
    val yawn = window(c, 6.4f, 7.2f, 7.8f, 8.5f)
    val stretch = window(c, 6.2f, 6.9f, 7.8f, 8.6f)
    val tilt = window(c, 6.6f, 7.2f, 7.8f, 8.4f)
    return Pose(
        bodyY = 0.35f * sin(TWO_PI * t / 3.2f) - stretch,
        sx = 1f - 0.03f * stretch,
        sy = 1f + 0.06f * stretch,
        rot = -4f * tilt + 1.2f * sin(TWO_PI * t / 4f),
        bodyAlpha = 1f, fill = 0f, faceAlpha = 1f,
        eyeOpen = (1f - 0.92f * blink) * (1f - 0.5f * droop),
        eyeX = -3.2f * lookL + 3.2f * lookR,
        eyeY = -2f - 1.2f * lookU + 0.6f * droop,
        happy = 0f,
        mouthCy = 7f + 0.3f * yawn,
        mouthRx = 2.4f + 0.6f * yawn,
        mouthRy = 0.12f + 3.2f * yawn,
        mouthFill = 0f,
        shadowAlpha = 0f, shadowScale = 1f,
        dotAlpha = 0f, spread = 0.25f,
        sparkle = 0f,
    )
}

// Connecting: the body folds away while the dots spread out
private fun poseLoad(): Pose = Pose(
    bodyY = 0f, sx = 0.5f, sy = 0.5f, rot = 0f,
    bodyAlpha = 0f, fill = 0f, faceAlpha = 0f,
    eyeOpen = 1f, eyeX = 0f, eyeY = -2f, happy = 0f,
    mouthCy = 7f, mouthRx = 2.4f, mouthRy = 0.12f, mouthFill = 0f,
    shadowAlpha = 0f, shadowScale = 1f,
    dotAlpha = 1f, spread = 1f,
    sparkle = 0f,
)

// Connected: hops with squash and stretch, wiggles, smiles and sparkles
private fun poseOn(t: Float): Pose {
    val x = (t % HOP_PERIOD) / HOP_PERIOD
    val d = minOf(x, 1f - x)
    val contact = 1f - smoothstep(0f, 0.12f, d)
    val air = smoothstep(0.12f, 0.32f, d)
    val hop = 8.5f + 3.5f * exp(-t / 0.8f)
    val yOff = -hop * sin(PI_F * x)
    val sy = 1f - 0.12f * contact + 0.05f * air
    val sx = 1f + 0.09f * contact - 0.03f * air
    val airFrac = clamp01(-yOff / hop)
    return Pose(
        bodyY = yOff + 18f * (1f - sy),
        sx = sx, sy = sy,
        rot = 9f * sin(TWO_PI * t / (2f * HOP_PERIOD)),
        bodyAlpha = 1f, fill = 1f, faceAlpha = 1f,
        eyeOpen = 1f, eyeX = 0f, eyeY = -2f, happy = 1f,
        mouthCy = 7f, mouthRx = 2.9f, mouthRy = 1.2f + 1.2f * air, mouthFill = 1f,
        shadowAlpha = 0.9f * (1f - 0.5f * airFrac),
        shadowScale = 1f - 0.3f * airFrac,
        dotAlpha = 0f, spread = 0.25f,
        sparkle = smoothstep(0f, 0.3f, t) * (0.6f + 0.4f * sin(TWO_PI * 1.5f * t)),
    )
}

private fun mix(a: Pose, b: Pose, c: Pose, w: FloatArray): Pose {
    fun pick(f: (Pose) -> Float) = w[0] * f(a) + w[1] * f(b) + w[2] * f(c)
    return Pose(
        bodyY = pick { it.bodyY }, sx = pick { it.sx }, sy = pick { it.sy }, rot = pick { it.rot },
        bodyAlpha = pick { it.bodyAlpha }, fill = pick { it.fill }, faceAlpha = pick { it.faceAlpha },
        eyeOpen = pick { it.eyeOpen }, eyeX = pick { it.eyeX }, eyeY = pick { it.eyeY },
        happy = pick { it.happy },
        mouthCy = pick { it.mouthCy }, mouthRx = pick { it.mouthRx }, mouthRy = pick { it.mouthRy },
        mouthFill = pick { it.mouthFill },
        shadowAlpha = pick { it.shadowAlpha }, shadowScale = pick { it.shadowScale },
        dotAlpha = pick { it.dotAlpha }, spread = pick { it.spread },
        sparkle = pick { it.sparkle },
    )
}

// Keeps the clock and the blend weights between the three states, updated every frame
private class RobotAnimator {
    var now by mutableStateOf(0f)
        private set

    private val weights = floatArrayOf(1f, 0f, 0f)   // disconnected, connecting, connected
    private val targets = floatArrayOf(1f, 0f, 0f)
    private val entered = floatArrayOf(0f, 0f, 0f)   // time each state was entered
    private var state = 0
    private var origin = -1L
    private var last = 0f

    fun setState(index: Int) {
        if (index == state) return
        state = index
        entered[index] = now
        for (i in targets.indices) targets[i] = if (i == index) 1f else 0f
    }

    /** Jumps straight to [index], frozen at [localTime] in that state's loop. */
    fun snapTo(index: Int, localTime: Float) {
        state = index
        entered[index] = now - localTime
        for (i in targets.indices) {
            targets[i] = if (i == index) 1f else 0f
            weights[i] = targets[i]
        }
    }

    fun tick(nanos: Long) {
        if (origin < 0L) origin = nanos
        val t = (nanos - origin) / 1_000_000_000f
        val dt = (t - last).coerceIn(0f, 0.05f)
        last = t
        val k = 1f - exp(-RATE * dt)
        for (i in weights.indices) weights[i] += (targets[i] - weights[i]) * k
        now = t
    }

    fun pose(): Pose = mix(
        poseOff(now - entered[0]),
        poseLoad(),
        poseOn(now - entered[2]),
        weights,
    )

    fun loadTime(): Float = now - entered[1]
    fun onTime(): Float = now - entered[2]
}

private fun DrawScope.drawRobot(animator: RobotAnimator, dotColor: Color) {
    val p = animator.pose()
    val loadT = animator.loadTime()
    val onT = animator.onTime()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val k = size.minDimension / UNITS
    withTransform({
        translate(cx, cy)
        scale(k, k, Offset.Zero)
    }) {
        if (p.shadowAlpha > 0.002f) {
            drawOval(
                color = ShadowGreen.copy(alpha = p.shadowAlpha),
                topLeft = Offset(-17f * p.shadowScale, 19.2f - 2.5f * p.shadowScale),
                size = Size(34f * p.shadowScale, 5f * p.shadowScale),
            )
        }
        withTransform({
            translate(0f, p.bodyY)
            rotate(p.rot, Offset.Zero)
            scale(p.sx, p.sy, Offset.Zero)
        }) {
            drawBody(p)
            drawFace(p)
        }
        drawLoaderDots(p, loadT, dotColor)
        drawSparkles(p, onT, dotColor)
    }
}

private fun DrawScope.drawBody(p: Pose) {
    if (p.bodyAlpha > 0.002f && p.fill > 0.002f) {
        drawRoundRect(
            color = MintFill.copy(alpha = p.bodyAlpha * p.fill),
            topLeft = Offset(-22f, -18f),
            size = Size(44f, 36f),
            cornerRadius = CornerRadius(15f),
        )
    }
    if (p.bodyAlpha > 0.002f && p.fill < 0.998f) {
        drawRoundRect(
            color = OutlineGrey.copy(alpha = p.bodyAlpha * (1f - p.fill)),
            topLeft = Offset(-22f, -18f),
            size = Size(44f, 36f),
            cornerRadius = CornerRadius(15f),
            style = Stroke(width = 1.3f),
        )
    }
}

private fun DrawScope.drawFace(p: Pose) {
    if (p.faceAlpha <= 0.002f) return
    val eyeH = 10f * p.eyeOpen * (1f - p.happy)
    val eyeColor = lerp(EyeGreen, Color.White, p.fill).copy(alpha = p.faceAlpha)
    for (side in floatArrayOf(-1f, 1f)) {
        val ex = side * 10.75f + p.eyeX
        if (eyeH > 0.05f) {
            drawOval(
                color = eyeColor,
                topLeft = Offset(ex - 3.25f, p.eyeY - eyeH / 2f),
                size = Size(6.5f, eyeH),
            )
        }
        if (p.happy > 0.002f) {
            val arc = Path().apply {
                moveTo(ex - 3f, p.eyeY + 1f)
                quadraticBezierTo(ex, p.eyeY - 3.6f, ex + 3f, p.eyeY + 1f)
            }
            drawPath(
                path = arc,
                color = Color.White.copy(alpha = p.faceAlpha * p.happy),
                style = Stroke(width = 1.5f),
            )
        }
    }
    val mouthRy = maxOf(p.mouthRy, 0.05f)
    val mouthTopLeft = Offset(-p.mouthRx, p.mouthCy - mouthRy)
    val mouthSize = Size(2f * p.mouthRx, 2f * mouthRy)
    if (p.mouthFill > 0.002f) {
        drawOval(
            color = Color.White.copy(alpha = p.faceAlpha * p.mouthFill),
            topLeft = mouthTopLeft,
            size = mouthSize,
        )
    }
    if (p.mouthFill < 0.998f) {
        drawOval(
            color = OutlineGrey.copy(alpha = p.faceAlpha * (1f - p.mouthFill)),
            topLeft = mouthTopLeft,
            size = mouthSize,
            style = Stroke(width = 1.2f),
        )
    }
}

private fun DrawScope.drawLoaderDots(p: Pose, loadT: Float, dotColor: Color) {
    if (p.dotAlpha <= 0.002f) return
    val ph = loadT * 5f
    for (i in LOADER_RING.indices) {
        val (gx, gy) = LOADER_RING[i]
        val dd = ((ph - i) % 8f + 8f) % 8f
        val g = clamp01(1f - dd / 3f)
        val glow = g * g * (3f - 2f * g)
        val bob = 1.2f * sin(TWO_PI * loadT / 1.2f - i * PI_F / 4f)
        drawCircle(
            color = dotColor.copy(alpha = p.dotAlpha * (0.18f + 0.82f * glow)),
            radius = 2.2f,
            center = Offset(gx * 12.5f * p.spread, gy * 12.5f * p.spread + bob),
        )
    }
    val pulse = 0.5f + 0.5f * sin(TWO_PI * loadT / 1.6f)
    drawCircle(
        color = dotColor.copy(alpha = p.dotAlpha * (0.2f + 0.8f * pulse)),
        radius = 2.2f,
        center = Offset.Zero,
    )
}

private fun DrawScope.drawSparkles(p: Pose, onT: Float, dotColor: Color) {
    if (p.sparkle <= 0.002f) return
    SPARKLE_POINTS.forEachIndexed { i, (x, y) ->
        val tw = 0.5f + 0.5f * sin(TWO_PI * (onT * 1.1f + i * 0.25f))
        val s = 2.4f + 0.8f * tw
        val c = dotColor.copy(alpha = p.sparkle * tw)
        drawLine(c, Offset(x - s, y), Offset(x + s, y), strokeWidth = 1.2f)
        drawLine(c, Offset(x, y - s), Offset(x, y + s), strokeWidth = 1.2f)
    }
}
