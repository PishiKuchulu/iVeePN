package com.pishi.iveepn.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Animated VPN connect button — a faithful Compose port of the Flutter
 * `VpnConnectionButton` widget supplied by the project owner
 * (lib/widgets/vpn_connection_button.dart):
 *
 *  - disconnected: pale-pink radial disc, breathing slowly, with a
 *    blue→pink gradient power glyph;
 *  - connecting: layered grey discs, a 16-particle burst and a rotating
 *    four-point sparkle;
 *  - connected: liquid orange disc with drifting wave bands and bubbles,
 *    a white power glyph, and a live HH:MM:SS session timer as the label.
 *
 * The widget is controlled: the caller owns [state] and [onClick]; the
 * timer is driven by [connectedAtEpochMs]. Label colours follow the
 * Material colour scheme so the text stays readable in the dark theme
 * (the painted disc keeps the original fixed palette in both themes).
 */
enum class VpnButtonState { Disconnected, Connecting, Connected }

@Composable
fun VpnConnectButton(
    state: VpnButtonState,
    onClick: () -> Unit,
    connectedAtEpochMs: Long?,
    disconnectedTitle: String,
    disconnectedSubtitle: String,
    connectingTitle: String,
    connectingSubtitle: String,
    stateDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
) {
    val animationsEnabled = rememberAnimationsEnabled()
    val haptics = LocalHapticFeedback.current

    // Continuous 2.6 s loop driving the breathing, the sparkle rotation
    // and the liquid waves (frozen at 0 when animations are disabled).
    val loopTransition = rememberInfiniteTransition(label = "vpnButtonLoop")
    val animatedLoop by loopTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "vpnButtonLoopValue",
    )
    val loop = if (animationsEnabled) animatedLoop else 0f

    // One-shot 1.4 s particle burst, restarted on every entry into the
    // connecting state.
    val burst = remember { Animatable(0f) }
    LaunchedEffect(state, animationsEnabled) {
        if (state == VpnButtonState.Connecting) {
            if (animationsEnabled) {
                burst.snapTo(0f)
                burst.animateTo(1f, animationSpec = tween(durationMillis = 1400))
            } else {
                burst.snapTo(1f)
            }
        }
    }

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
            Canvas(
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
                        stateDescription = stateDescription
                    },
            ) {
                when (targetState) {
                    VpnButtonState.Disconnected -> drawDisconnected(loop)
                    VpnButtonState.Connecting -> drawConnecting(loop, burst.value)
                    VpnButtonState.Connected -> drawConnected(loop)
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
// Painters (ported 1:1 from the Dart CustomPainters)
// ---------------------------------------------------------------------------

private fun DrawScope.drawPowerIcon(
    center: Offset,
    iconRadius: Float,
    strokeScale: Float,
    brush: Brush? = null,
    solidColor: Color? = null,
) {
    val stroke = Stroke(width = iconRadius * strokeScale, cap = StrokeCap.Round)
    val arcTopLeft = Offset(center.x - iconRadius, center.y - iconRadius)
    val arcSize = Size(iconRadius * 2, iconRadius * 2)
    if (brush != null) {
        drawArc(
            brush = brush,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = stroke,
        )
        drawLine(
            brush = brush,
            start = Offset(center.x, center.y - iconRadius * 1.35f),
            end = Offset(center.x, center.y - iconRadius * 0.25f),
            strokeWidth = iconRadius * strokeScale,
            cap = StrokeCap.Round,
        )
    } else {
        val color = solidColor ?: Color.Unspecified
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = stroke,
        )
        drawLine(
            color = color,
            start = Offset(center.x, center.y - iconRadius * 1.35f),
            end = Offset(center.x, center.y - iconRadius * 0.25f),
            strokeWidth = iconRadius * strokeScale,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawDisconnected(loop: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val breathe = 1f + 0.012f * sin(loop * 2 * Math.PI).toFloat()
    val radius = size.minDimension / 2 * 0.98f * breathe

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFF6E4E8), Color(0xFFEFEAEE)),
            center = Offset(center.x - 0.35f * radius, center.y - 0.35f * radius),
            radius = radius,
        ),
        radius = radius,
        center = center,
    )

    val iconRadius = radius * 0.20f
    val iconBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF66A3E4), Color(0xFFD97F97)),
        startY = center.y - iconRadius * 1.4f,
        endY = center.y + iconRadius * 1.4f,
    )
    drawPowerIcon(center, iconRadius, strokeScale = 0.30f, brush = iconBrush)
}

private data class ParticleSpec(
    val angle: Float,
    val distance: Float,
    val radius: Float,
    val delay: Float,
)

// Same layout recipe as the Dart original (16 particles, fixed seed);
// Kotlin's PRNG sequence differs from Dart's, so the exact arrangement
// differs while the look and distribution are the same.
private val particles: List<ParticleSpec> = run {
    val rnd = Random(42)
    List(16) {
        ParticleSpec(
            angle = (rnd.nextDouble() * 2 * Math.PI).toFloat(),
            distance = (0.55 + rnd.nextDouble() * 0.85).toFloat(),
            radius = (0.010 + rnd.nextDouble() * 0.020).toFloat(),
            delay = (rnd.nextDouble() * 0.25).toFloat(),
        )
    }
}

private fun DrawScope.drawConnecting(loop: Float, burstT: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val radius = size.minDimension / 2

    drawCircle(color = Color(0xFFEBEDF0), radius = radius * 0.98f, center = center)
    drawCircle(color = Color(0xFFF4F4F6), radius = radius * 0.86f, center = center)

    // Particle burst.
    for (p in particles) {
        val local = ((burstT - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
        val eased = 1f - (1f - local) * (1f - local) * (1f - local) // easeOutCubic
        val dist = (0.30f + p.distance * eased) * radius
        val alpha = (1f - local) * 0.95f
        if (alpha <= 0.01f) continue
        val pr = p.radius * radius * (1.25f - 0.45f * local)
        val offset = Offset(
            center.x + cos(p.angle) * dist,
            center.y + sin(p.angle) * dist,
        )
        drawCircle(
            color = Color(red = 27, green = 24, blue = 66, alpha = (alpha * 255).toInt()),
            radius = pr,
            center = offset,
        )
    }

    // Rotating four-point sparkle.
    val rotation = loop * Math.PI
    val starR = radius * 0.30f
    val innerR = starR * 0.30f
    val path = Path()
    for (i in 0 until 4) {
        val a = rotation + i * Math.PI / 2
        val outer = Offset(
            center.x + (cos(a) * starR).toFloat(),
            center.y + (sin(a) * starR).toFloat(),
        )
        if (i == 0) path.moveTo(outer.x, outer.y)
        val mid = a + Math.PI / 4
        val inner = Offset(
            center.x + (cos(mid) * innerR).toFloat(),
            center.y + (sin(mid) * innerR).toFloat(),
        )
        val nextA = rotation + (i + 1) * Math.PI / 2
        val nextOuter = Offset(
            center.x + (cos(nextA) * starR).toFloat(),
            center.y + (sin(nextA) * starR).toFloat(),
        )
        path.quadraticBezierTo(inner.x, inner.y, nextOuter.x, nextOuter.y)
    }
    path.close()
    drawPath(path, color = Color(0xFF141414))
}

private fun DrawScope.drawConnected(loop: Float) {
    val center = Offset(size.width / 2, size.height / 2)
    val radius = size.minDimension / 2 * 0.86f
    val phase = (loop * 2 * Math.PI).toFloat()

    // Pale halo behind the liquid disc.
    drawCircle(color = Color(0xFFF8E7DB), radius = radius * 1.07f, center = center)

    val clip = Path().apply { addOval(Rect(center = center, radius = radius)) }
    clipPath(clip) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color(0xFFF49B70), Color(0xFFED7203)),
                startY = center.y - radius,
                endY = center.y + radius,
            ),
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
        )
        drawWaveBand(center, radius, phase, side = -1, color = Color.White.copy(alpha = 0.22f))
        drawWaveBand(center, radius, phase * 1.13f + 1.7f, side = 1, color = Color.White.copy(alpha = 0.18f))

        // Floating bubbles.
        for (i in 0 until 3) {
            val seed = i * 2.39996
            val bx = center.x + (sin(seed * 3.7) * radius * 0.55f).toFloat()
            val by = center.y + (cos(phase * 0.6 + seed) * radius * 0.5f).toFloat()
            drawCircle(
                color = Color.White.copy(alpha = 0.45f),
                radius = radius * (0.018f + 0.010f * i),
                center = Offset(bx, by),
            )
        }
    }

    val iconRadius = radius * 0.20f
    drawPowerIcon(center, iconRadius, strokeScale = 0.32f, solidColor = Color(0xFFF4F6F8))
}

private fun DrawScope.drawWaveBand(
    center: Offset,
    radius: Float,
    phase: Float,
    side: Int,
    color: Color,
) {
    val baseX = center.x + side * radius * 0.62f
    val top = center.y - radius
    val height = radius * 2
    val steps = 26
    val path = Path()
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val y = top + height * t
        val x = baseX - radius * 0.16f +
            (sin(t * Math.PI * 2.4 + phase) * radius * 0.07f).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    for (i in steps downTo 0) {
        val t = i.toFloat() / steps
        val y = top + height * t
        val x = baseX + radius * 0.16f +
            (sin(t * Math.PI * 2.4 + phase + 1.9) * radius * 0.07f).toFloat()
        path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color = color)
}
