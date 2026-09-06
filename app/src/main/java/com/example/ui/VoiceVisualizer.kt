package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.voice.VoiceState

@Composable
fun VoiceVisualizerBar(
    voiceState: VoiceState,
    liveTranscription: String,
    audioRmsLevel: Float,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = voiceState !is VoiceState.Idle

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("voice_visualizer_bar")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header Row: Status Badge & Interruption Control
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VoiceStateIndicator(voiceState = voiceState)

                        Text(
                            text = when (voiceState) {
                                is VoiceState.Listening -> if (voiceState.isWakeWordActive) "Listening for 'Hey MJ'..." else "Listening..."
                                is VoiceState.Thinking -> "Thinking..."
                                is VoiceState.Speaking -> "MJ is speaking"
                                is VoiceState.Clarifying -> "Clarification needed"
                                is VoiceState.Error -> "Voice notice"
                                is VoiceState.Idle -> "Ready"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Low-latency Interruption Button (Barge-in affordance)
                    if (voiceState is VoiceState.Speaking) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onInterrupt() }
                                .testTag("interrupt_speech_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = "Stop speaking",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Tap to interrupt",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // Dynamic Audio Waveform or Activity Indicator
                when (voiceState) {
                    is VoiceState.Listening -> {
                        DynamicAudioWaveform(
                            audioRmsLevel = audioRmsLevel,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp)
                        )
                        if (liveTranscription.isNotBlank()) {
                            Text(
                                text = "\"$liveTranscription\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("live_transcription_text")
                            )
                        }
                    }
                    is VoiceState.Speaking -> {
                        SpeakingPulsarBar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                        )
                        Text(
                            text = voiceState.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    is VoiceState.Clarifying -> {
                        Text(
                            text = voiceState.question,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    is VoiceState.Thinking -> {
                        ThinkingPulseBar(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                        )
                    }
                    is VoiceState.Error -> {
                        Text(
                            text = voiceState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun VoiceStateIndicator(voiceState: VoiceState) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val color by animateColorAsState(
        targetValue = when (voiceState) {
            is VoiceState.Listening -> MaterialTheme.colorScheme.primary
            is VoiceState.Thinking -> MaterialTheme.colorScheme.secondary
            is VoiceState.Speaking -> MaterialTheme.colorScheme.tertiary
            is VoiceState.Clarifying -> MaterialTheme.colorScheme.error
            is VoiceState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        },
        label = "color"
    )

    Box(
        modifier = Modifier
            .size(24.dp)
            .background(color.copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when (voiceState) {
                is VoiceState.Listening -> Icons.Rounded.Mic
                is VoiceState.Thinking -> Icons.Rounded.AutoAwesome
                is VoiceState.Speaking -> Icons.AutoMirrored.Rounded.VolumeUp
                is VoiceState.Clarifying -> Icons.Rounded.QuestionAnswer
                else -> Icons.Rounded.Hearing
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
fun DynamicAudioWaveform(
    audioRmsLevel: Float,
    modifier: Modifier = Modifier
) {
    val barColor = MaterialTheme.colorScheme.primary
    val barSecondary = MaterialTheme.colorScheme.tertiary

    Canvas(modifier = modifier) {
        val totalBars = 21
        val barWidth = 4.dp.toPx()
        val spacing = (size.width - (totalBars * barWidth)) / (totalBars - 1)
        val middleIndex = totalBars / 2

        for (i in 0 until totalBars) {
            val distFromCenter = kotlin.math.abs(i - middleIndex).toFloat() / middleIndex
            val heightFactor = (1f - distFromCenter * 0.5f).coerceIn(0.2f, 1f)
            
            // Dynamic height based on current RMS audio level + subtle baseline wave
            val variableHeight = (audioRmsLevel * size.height * heightFactor * 1.3f)
                .coerceIn(6.dp.toPx(), size.height)

            val x = i * (barWidth + spacing)
            val y = (size.height - variableHeight) / 2

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(barColor, barSecondary)
                ),
                topLeft = Offset(x, y),
                size = Size(barWidth, variableHeight),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}

@Composable
private fun SpeakingPulsarBar(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "speaking")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val tint = MaterialTheme.colorScheme.tertiary

    Canvas(modifier = modifier) {
        val totalBars = 15
        val barWidth = 5.dp.toPx()
        val spacing = (size.width - (totalBars * barWidth)) / (totalBars - 1)

        for (i in 0 until totalBars) {
            val sineVal = kotlin.math.sin(phase + i * 0.45).toFloat()
            val height = ((sineVal + 1f) / 2f * (size.height - 6.dp.toPx()) + 6.dp.toPx())
            val x = i * (barWidth + spacing)
            val y = (size.height - height) / 2

            drawRoundRect(
                color = tint.copy(alpha = 0.7f + (sineVal * 0.3f)),
                topLeft = Offset(x, y),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
            )
        }
    }
}

@Composable
private fun ThinkingPulseBar(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val offset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier) {
        val dotRadius = 4.dp.toPx()
        val totalDots = 5
        val spacing = 20.dp.toPx()
        val startX = (size.width - ((totalDots - 1) * spacing)) / 2

        for (i in 0 until totalDots) {
            val dynamicScale = (1f - kotlin.math.abs(offset - (i.toFloat() / (totalDots - 1)))).coerceIn(0.4f, 1.2f)
            drawCircle(
                color = primaryColor.copy(alpha = dynamicScale),
                radius = dotRadius * dynamicScale,
                center = Offset(startX + (i * spacing), size.height / 2)
            )
        }
    }
}
