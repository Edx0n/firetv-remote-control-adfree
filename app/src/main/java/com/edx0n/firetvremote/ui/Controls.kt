package com.edx0n.firetvremote.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.edx0n.firetvremote.ui.theme.Accent
import com.edx0n.firetvremote.ui.theme.ButtonFace
import com.edx0n.firetvremote.ui.theme.ButtonFacePressed
import com.edx0n.firetvremote.ui.theme.DpadRing
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

enum class Direction { UP, DOWN, LEFT, RIGHT, CENTER }

@Composable
fun rememberHaptics(): () -> Unit {
    val view = LocalView.current
    return remember(view) { { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) } }
}

/**
 * Botão redondo do controle. Reporta separadamente o toque ([onDown]) e a soltura ([onUp]),
 * para que o ViewModel decida entre clique simples, repetição ou pressionar longo.
 */
@Composable
fun RemoteButton(
    icon: ImageVector,
    description: String,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    label: String? = null,
    accent: Boolean = false,
    active: Boolean = false,
) {
    val haptic = rememberHaptics()
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    var pressed by remember { mutableStateOf(false) }
    val face = when {
        active -> Accent
        accent && pressed -> Accent.copy(alpha = 0.75f)
        accent -> Accent
        pressed -> ButtonFacePressed
        else -> ButtonFace
    }
    val background by animateColorAsState(face, label = "face")
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "scale")
    val tint = if (accent || active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(background)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    onClick { down(); up(); true }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        haptic()
                        down()
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            pressed = false
                            up()
                        }
                    }
                },
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.42f))
        }
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Direcional circular com botão OK no centro. */
@Composable
fun DPad(
    onDown: (Direction) -> Unit,
    onUp: (Direction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptics()
    val down by rememberUpdatedState(onDown)
    val up by rememberUpdatedState(onUp)
    var pressed by remember { mutableStateOf<Direction?>(null) }
    val centerFace = if (pressed == Direction.CENTER) ButtonFacePressed else ButtonFace
    val edge = Color.White.copy(alpha = 0.07f)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Direcional" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val part = hitTest(first.position, size) ?: return@awaitEachGesture
                    pressed = part
                    haptic()
                    down(part)
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        pressed = null
                        up(part)
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            drawCircle(DpadRing, radius)
            pressed?.takeIf { it != Direction.CENTER }?.let { part ->
                val start = when (part) {
                    Direction.RIGHT -> -45f
                    Direction.DOWN -> 45f
                    Direction.LEFT -> 135f
                    else -> 225f
                }
                drawArc(ButtonFacePressed, start, 90f, useCenter = true)
            }
            drawCircle(edge, radius, style = Stroke(1.dp.toPx()))
            drawCircle(centerFace, radius * CENTER_RATIO)
            drawCircle(edge, radius * CENTER_RATIO, style = Stroke(1.dp.toPx()))
        }
        // Posições absolutas: o direcional não espelha em idiomas da direita para a esquerda.
        val tint = MaterialTheme.colorScheme.onSurface
        val arrow = Icons.Rounded.KeyboardArrowUp
        Icon(arrow, null, Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(40.dp), tint)
        Icon(arrow, null, Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).size(40.dp).rotate(180f), tint)
        Icon(arrow, null, Modifier.align(AbsoluteAlignment.CenterLeft).padding(start = 14.dp).size(40.dp).rotate(-90f), tint)
        Icon(arrow, null, Modifier.align(AbsoluteAlignment.CenterRight).padding(end = 14.dp).size(40.dp).rotate(90f), tint)
        Text(
            "OK",
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
    }
}

private const val CENTER_RATIO = 0.38f

private fun hitTest(p: Offset, size: IntSize): Direction? {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val dx = p.x - cx
    val dy = p.y - cy
    val radius = min(cx, cy)
    val distance = hypot(dx, dy)
    return when {
        distance > radius -> null
        distance < radius * CENTER_RATIO -> Direction.CENTER
        abs(dx) > abs(dy) -> if (dx > 0) Direction.RIGHT else Direction.LEFT
        else -> if (dy > 0) Direction.DOWN else Direction.UP
    }
}

/**
 * Área de deslizar: cada [stepDp] percorridos viram uma seta, toque rápido vira OK.
 * Deslizes longos geram vários passos, como rolar uma lista no controle físico.
 */
@Composable
fun Touchpad(
    onStep: (Direction) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    stepDp: Dp = 52.dp,
) {
    val haptic = rememberHaptics()
    val step by rememberUpdatedState(onStep)
    val tap by rememberUpdatedState(onTap)
    val stepPx = with(LocalDensity.current) { stepDp.toPx() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(DpadRing)
            .semantics {
                contentDescription = "Touchpad: deslize para navegar, toque para OK"
                onClick { tap(); true }
            }
            .pointerInput(stepPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pending = Offset.Zero
                    var travelled = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            val quick = change.uptimeMillis - down.uptimeMillis < 450
                            if (travelled < viewConfiguration.touchSlop && quick) {
                                haptic()
                                tap()
                            }
                            break
                        }
                        val delta = change.positionChange()
                        travelled += delta.getDistance()
                        pending += delta
                        while (abs(pending.x) >= stepPx || abs(pending.y) >= stepPx) {
                            val direction = if (abs(pending.x) >= abs(pending.y)) {
                                if (pending.x > 0) Direction.RIGHT else Direction.LEFT
                            } else {
                                if (pending.y > 0) Direction.DOWN else Direction.UP
                            }
                            pending = when (direction) {
                                Direction.RIGHT -> pending.copy(x = pending.x - stepPx)
                                Direction.LEFT -> pending.copy(x = pending.x + stepPx)
                                Direction.DOWN -> pending.copy(y = pending.y - stepPx)
                                else -> pending.copy(y = pending.y + stepPx)
                            }
                            haptic()
                            step(direction)
                        }
                        change.consume()
                    }
                }
            },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.TouchApp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp),
            )
            Text(
                "Deslize para navegar\nToque para OK",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
