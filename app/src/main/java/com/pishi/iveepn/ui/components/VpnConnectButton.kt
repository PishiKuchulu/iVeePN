package com.pishi.iveepn.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
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
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Animated VPN connect button — robot artwork supplied by the project
 * owner (ConnectRobotButton), integrated behind the existing
 * controlled-widget API:
 *
 *  - disconnected: outline robot head with green eyes that blink;
 *  - connecting: a 3x3 dot loader with a highlight rolling around the
 *    ring and a breathing centre dot;
 *  - connected: a filled mint blob that drops in with a spring bounce
 *    over its own shadow.
 *
 * The caller owns [state] and [onClick]; the label block under the
 * artwork (titles/subtitle, or the live HH:MM:SS session timer while
 * connected) is unchanged from the previous design. Label colours
 * follow the Material colour scheme; the painted robot keeps its
 * fixed palette in both themes, except the loader dots, which take
 * the scheme's on-surface colour so they stay visible on the light
 * background (the reference artwork assumed a dark screen).
 * Animations honour the system animator setting via
 * [rememberAnimationsEnabled]: with animations off, the robot is
 * static (eyes open, loader frozen, blob already landed).
 */
enum class VpnButtonState { Disconnected, Connecting, Connected }

// Robot palette, sampled from the owner's reference video.
private val MintGreen = Color(0xFF54F891)
private val MintGreenDeep = Color(0xFF3BD38D)
private val EyeGreen = Color(0xFF4CFC88)
private val OutlineGrey = Color(0xFFC6C9D4)
private val ShadowGreen = Color(0xFF27865D)

// Robot shapes are drawn in design units, (0, 0) at the centre;
// one unit is roughly one pixel of the 240x180 reference video.
private const val DESIGN_UNITS = 64f

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
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (fadeIn(animationSpec = tween(450)) +
                    scaleIn(initialScale = 0.86f, animationSpec = tween(450)))
                    .togetherWith(fadeOut(animationSpec = tween(450)) +
                        scaleOut(targetScale = 0.86f, animationSpec = tween(450)))
            },
            label = "vpnButtonDisc",
        ) { targetState ->
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
                when (targetState) {
                    VpnButtonState.Disconnected -> IdleRobot(animationsEnabled, Modifier.fillMaxSize())
                    VpnButtonState.Connecting -> LoadingRobot(animationsEnabled, Modifier.fillMaxSize())
                    VpnButtonState.Connected -> ActiveRobot(animationsEnabled, Modifier.fillMaxSize())
                }
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
// Robot artwork (owner-supplied; see the file header for the adaptations)
// ---------------------------------------------------------------------------

/** Disconnected: outline head with green eyes that blink every ~3.2 s. */
@Composable
private fun IdleRobot(animate: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "idle")
    val animatedEyeOpen by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3200
                1f at 0
                1f at 2900
                0.1f at 3000 // eyes closed
                1f at 3150
            },
        ),
        label = "blink",
    )
    val eyeOpen = if (animate) animatedEyeOpen else 1f

    Canvas(modifier) {
        val k = size.minDimension / DESIGN_UNITS
        val cx = size.width / 2f
        val cy = size.height / 2f
        withTransform({
            translate(cx, cy)
            scale(k, k, Offset.Zero)
        }) {
            drawRoundRect(
                color = OutlineGrey,
                topLeft = Offset(-22f, -19f),
                size = Size(44f, 38f),
                cornerRadius = CornerRadius(16f),
                style = Stroke(width = 1.3f),
            )
            val eyeHeight = 10f * eyeOpen
            drawOval(EyeGreen, topLeft = Offset(-14f, -2f - eyeHeight / 2f), size = Size(6.5f, eyeHeight))
            drawOval(EyeGreen, topLeft = Offset(7.5f, -2f - eyeHeight / 2f), size = Size(6.5f, eyeHeight))
        }
    }
}

// Clockwise path around the 3x3 grid (centre dot is drawn separately).
private val LoaderRing = listOf(
    -1f to -1f, 0f to -1f, 1f to -1f, // top row, left to right
    1f to 0f, 1f to 1f,               // right column, downward
    0f to 1f, -1f to 1f, -1f to 0f,   // bottom row, then left column
)

/** Connecting: nine dots with a highlight rolling around the ring. */
@Composable
private fun LoadingRobot(animate: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loading")
    val animatedPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 8f, // 8 ring steps per loop
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "phase",
    )
    val phase = if (animate) animatedPhase else 0f
    val dotColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier) {
        val k = size.minDimension / DESIGN_UNITS
        val cx = size.width / 2f
        val cy = size.height / 2f
        val spacing = 12.5f * k
        val dotRadius = 2.2f * k

        LoaderRing.forEachIndexed { index, (gx, gy) ->
            // How far this dot is behind the moving highlight (wraps around 0..8)
            val distance = ((phase - index) % 8f + 8f) % 8f
            val glow = (1f - distance / 3f).coerceIn(0f, 1f) // bright head, fading tail
            drawCircle(
                color = dotColor.copy(alpha = 0.18f + 0.82f * glow),
                radius = dotRadius,
                center = Offset(cx + gx * spacing, cy + gy * spacing),
            )
        }

        // Centre dot breathes slowly
        val pulse = 0.5f + 0.5f * sin(phase * (PI.toFloat() / 4f))
        drawCircle(
            color = dotColor.copy(alpha = 0.2f + 0.8f * pulse),
            radius = dotRadius,
            center = Offset(cx, cy),
        )
    }
}

/** Connected: filled green blob with white eyes that drops in and bounces. */
@Composable
private fun ActiveRobot(animate: Boolean, modifier: Modifier = Modifier) {
    // Starts 12 units above the ground and settles with a bouncy spring.
    val lift = remember { Animatable(if (animate) -12f else 0f) }
    LaunchedEffect(animate) {
        if (animate) {
            lift.animateTo(0f, spring(dampingRatio = 0.12f, stiffness = 160f))
        }
    }

    Canvas(modifier) {
        val k = size.minDimension / DESIGN_UNITS
        val cx = size.width / 2f
        val cy = size.height / 2f
        // Negative means up. Clamp so the blob never sinks below the ground line.
        val y = lift.value.coerceAtMost(0f)
        val airborne = (-y / 12f).coerceIn(0f, 1f) // 0 = on the ground, 1 = highest point

        withTransform({
            translate(cx, cy)
            scale(k, k, Offset.Zero)
        }) {
            // Shadow shrinks and fades while the blob is in the air
            val shadowScale = 1f - 0.35f * airborne
            drawOval(
                color = ShadowGreen.copy(alpha = 1f - 0.5f * airborne),
                topLeft = Offset(-17f * shadowScale, 17.5f - 2.5f * shadowScale),
                size = Size(34f * shadowScale, 5f * shadowScale),
            )

            // Body
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(MintGreen, MintGreenDeep),
                    startY = -17f + y,
                    endY = 15f + y,
                ),
                topLeft = Offset(-22f, -17f + y),
                size = Size(44f, 32f),
                cornerRadius = CornerRadius(14f),
            )

            // Eyes
            drawOval(Color.White, topLeft = Offset(-13.5f, -9f + y), size = Size(6f, 8f))
            drawOval(Color.White, topLeft = Offset(7.5f, -9f + y), size = Size(6f, 8f))
        }
    }
}
