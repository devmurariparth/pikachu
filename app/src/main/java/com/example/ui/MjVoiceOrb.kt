package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.voice.VoiceState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-end, premium 3D voice command orb for MJ Assistant.
 *
 * Features:
 * - 3D spherical depth with multi-layered specular highlights & ambient glow.
 * - Reactive state animations:
 *   - IDLE: subtle, organic sinusoidal breathing.
 *   - LISTENING: dynamic expanding acoustic resonance waves tied to audio RMS.
 *   - THINKING: futuristic orbiting gyroscopic fluid light rings.
 *   - SPEAKING: acoustic vocal pulse waves with reactive internal illumination.
 *   - STOPPED: smooth return to idle.
 *   - ERROR: warm subtle amber/crimson core tint.
 * - 60 FPS hardware-accelerated Canvas rendering.
 * - Minimum interactive component size > 48dp (orb is 170dp+).
 */
@Composable
fun MjVoiceOrb(
    voiceState: VoiceState,
    audioRmsLevel: Float,
    isProcessing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 175.dp
) {
    val isDark = isSystemInDarkTheme()

    // Determine high-level assistant state
    val state = remember(voiceState, isProcessing) {
        when {
            isProcessing || voiceState is VoiceState.Thinking -> AssistantOrbState.THINKING
            voiceState is VoiceState.Speaking -> AssistantOrbState.SPEAKING
            voiceState is VoiceState.Listening -> AssistantOrbState.LISTENING
            voiceState is VoiceState.Clarifying -> AssistantOrbState.LISTENING
            else -> AssistantOrbState.IDLE
        }
    }

    // Infinite transitions for smooth 60fps ambient oscillations
    val infiniteTransition = rememberInfiniteTransition(label = "mj_orb_motion")

    // Idle breathing scale: 0.97f to 1.03f
    val breathScale by infiniteTransition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath_scale"
    )

    // Thinking rotation angle: 0 to 360 degrees
    val thinkingRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "thinking_rotation"
    )

    // Speaking vocal cadence pulse
    val speakingPulse by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "speaking_pulse"
    )

    // Smooth normalized RMS reactivity (clamped 0f..1f)
    val smoothedRms by animateFloatAsState(
        targetValue = (audioRmsLevel / 10f).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "smoothed_rms"
    )

    // Core color animations according to state
    val coreColorPrimary by animateColorAsState(
        targetValue = when (state) {
            AssistantOrbState.IDLE -> if (isDark) Color(0xFF6366F1) else Color(0xFF4F46E5) // Indigo
            AssistantOrbState.LISTENING -> Color(0xFF06B6D4) // Radiant Cyan
            AssistantOrbState.THINKING -> Color(0xFF8B5CF6) // Luminous Violet
            AssistantOrbState.SPEAKING -> Color(0xFF10B981) // Emerald / Teal Resonance
            AssistantOrbState.ERROR -> Color(0xFFEF4444) // Warm Crimson
        },
        animationSpec = tween(durationMillis = 400),
        label = "orb_primary"
    )

    val coreColorSecondary by animateColorAsState(
        targetValue = when (state) {
            AssistantOrbState.IDLE -> if (isDark) Color(0xFF38BDF8) else Color(0xFF60A5FA)
            AssistantOrbState.LISTENING -> Color(0xFF0284C7)
            AssistantOrbState.THINKING -> Color(0xFFEC4899)
            AssistantOrbState.SPEAKING -> Color(0xFF06B6D4)
            AssistantOrbState.ERROR -> Color(0xFFF97316)
        },
        animationSpec = tween(durationMillis = 400),
        label = "orb_secondary"
    )

    val ambientGlowColor by animateColorAsState(
        targetValue = when (state) {
            AssistantOrbState.IDLE -> if (isDark) Color(0x336366F1) else Color(0x224F46E5)
            AssistantOrbState.LISTENING -> Color(0x5506B6D4)
            AssistantOrbState.THINKING -> Color(0x558B5CF6)
            AssistantOrbState.SPEAKING -> Color(0x4410B981)
            AssistantOrbState.ERROR -> Color(0x44EF4444)
        },
        animationSpec = tween(durationMillis = 400),
        label = "ambient_glow"
    )

    // Interaction source with ripple
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .testTag("voice_orb")
            .semantics {
                contentDescription = when (state) {
                    AssistantOrbState.IDLE -> "MJ Voice Assistant: Tap to speak"
                    AssistantOrbState.LISTENING -> "MJ is listening: Tap to stop"
                    AssistantOrbState.THINKING -> "MJ is thinking"
                    AssistantOrbState.SPEAKING -> "MJ is speaking: Tap to interrupt"
                    AssistantOrbState.ERROR -> "MJ Status Notice"
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = size / 2),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension * 0.36f

            // Dynamic scale factor based on state
            val currentScale = when (state) {
                AssistantOrbState.IDLE -> breathScale
                AssistantOrbState.LISTENING -> 1f + (smoothedRms * 0.12f)
                AssistantOrbState.THINKING -> 1.02f
                AssistantOrbState.SPEAKING -> speakingPulse
                AssistantOrbState.ERROR -> 0.98f
            }
            val orbRadius = baseRadius * currentScale

            // 1. Ambient Background Glow (Diffused soft light)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(ambientGlowColor, Color.Transparent),
                    center = center,
                    radius = baseRadius * 1.8f
                ),
                radius = baseRadius * 1.8f,
                center = center
            )

            // 2. Dynamic Outer Acoustic Waves (for LISTENING & SPEAKING)
            if (state == AssistantOrbState.LISTENING || state == AssistantOrbState.SPEAKING) {
                val waveCount = 3
                val expansionFactor = if (state == AssistantOrbState.LISTENING) smoothedRms else 0.5f

                for (i in 1..waveCount) {
                    val waveRadius = orbRadius + (i * 14.dp.toPx()) * (0.8f + expansionFactor * 0.6f)
                    val waveAlpha = ((1f - (i.toFloat() / (waveCount + 1))) * 0.45f).coerceIn(0f, 1f)
                    drawCircle(
                        color = coreColorPrimary.copy(alpha = waveAlpha),
                        radius = waveRadius,
                        center = center,
                        style = Stroke(width = (2.2f - (i * 0.4f)).dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // 3. Gyroscopic Orbiting Processing Rings (for THINKING)
            if (state == AssistantOrbState.THINKING) {
                rotate(thinkingRotation, pivot = center) {
                    // Outer orbit
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                coreColorPrimary.copy(alpha = 0.1f),
                                coreColorPrimary,
                                coreColorSecondary,
                                Color.Transparent
                            ),
                            center = center
                        ),
                        radius = orbRadius * 1.28f,
                        center = center,
                        style = Stroke(width = 2.8.dp.toPx())
                    )
                }
                rotate(-thinkingRotation * 1.4f, pivot = center) {
                    // Counter-rotating inner orbit
                    drawCircle(
                        brush = Brush.sweepGradient(
                            listOf(
                                Color.Transparent,
                                coreColorSecondary.copy(alpha = 0.8f),
                                coreColorPrimary.copy(alpha = 0.2f)
                            ),
                            center = center
                        ),
                        radius = orbRadius * 1.15f,
                        center = center,
                        style = Stroke(width = 1.8.dp.toPx())
                    )
                }
            }

            // 4. Main 3D Spherical Orb Body
            // Radial gradient creates realistic 3D sphere curvature
            val specularOffset = Offset(center.x - orbRadius * 0.28f, center.y - orbRadius * 0.32f)

            val sphereBrush = Brush.radialGradient(
                colors = listOf(
                    coreColorSecondary.copy(alpha = 0.95f),
                    coreColorPrimary.copy(alpha = 0.85f),
                    if (isDark) Color(0xFF0B101E) else Color(0xFF1E293B)
                ),
                center = specularOffset,
                radius = orbRadius * 1.15f
            )

            drawCircle(
                brush = sphereBrush,
                radius = orbRadius,
                center = center
            )

            // 5. Glass Edge Rim Light
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(
                        Color.White.copy(alpha = 0.5f),
                        coreColorSecondary.copy(alpha = 0.3f),
                        Color.White.copy(alpha = 0.1f),
                        coreColorPrimary.copy(alpha = 0.4f),
                        Color.White.copy(alpha = 0.5f)
                    ),
                    center = center
                ),
                radius = orbRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // 6. Top-Left Specular Glass Reflection
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.65f),
                        Color.White.copy(alpha = 0.2f),
                        Color.Transparent
                    ),
                    center = specularOffset,
                    radius = orbRadius * 0.45f
                ),
                radius = orbRadius * 0.42f,
                center = specularOffset
            )

            // 7. Minimalist Futuristic MJ Monogram Centerpiece
            drawMjMonogram(
                center = center,
                size = orbRadius * 0.65f,
                tint = Color.White.copy(alpha = 0.95f)
            )
        }
    }
}

/**
 * Draws the refined "MJ" minimalist typographic monogram inside the orb core.
 */
private fun DrawScope.drawMjMonogram(
    center: Offset,
    size: Float,
    tint: Color
) {
    val half = size / 2f
    val strokeWidth = 2.4.dp.toPx()

    // Letter 'M'
    val mLeft = center.x - half * 0.9f
    val mRight = center.x - half * 0.1f
    val topY = center.y - half * 0.45f
    val botY = center.y + half * 0.45f
    val midX = (mLeft + mRight) / 2f
    val valleyY = center.y + half * 0.05f

    // Left vertical line of M
    drawLine(
        color = tint,
        start = Offset(mLeft, botY),
        end = Offset(mLeft, topY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    // Left diagonal of M
    drawLine(
        color = tint,
        start = Offset(mLeft, topY),
        end = Offset(midX, valleyY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    // Right diagonal of M
    drawLine(
        color = tint,
        start = Offset(midX, valleyY),
        end = Offset(mRight, topY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    // Right vertical line of M
    drawLine(
        color = tint,
        start = Offset(mRight, topY),
        end = Offset(mRight, botY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )

    // Letter 'J'
    val jX = center.x + half * 0.5f
    val jTopY = topY
    val jBottomCurveY = botY - (half * 0.22f)
    val jHookLeft = center.x + half * 0.22f

    // Top horizontal serif of J
    drawLine(
        color = tint,
        start = Offset(jX - half * 0.2f, jTopY),
        end = Offset(jX + half * 0.22f, jTopY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    // Vertical stem of J
    drawLine(
        color = tint,
        start = Offset(jX, jTopY),
        end = Offset(jX, jBottomCurveY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
    // Hook of J
    drawLine(
        color = tint,
        start = Offset(jX, jBottomCurveY),
        end = Offset(jHookLeft, botY),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

enum class AssistantOrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}
