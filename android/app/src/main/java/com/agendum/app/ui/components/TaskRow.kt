package com.agendum.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agendum.app.model.DateTimeHelper
import com.agendum.app.model.DeadlineKind
import com.agendum.app.model.ScheduleRules
import com.agendum.app.model.TaskCategory
import com.agendum.app.model.TaskItem
import com.agendum.app.model.TaskStatus
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion
import com.agendum.app.ui.theme.tabularNums
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    task: TaskItem,
    isInFlight: Boolean,
    onToggleDone: (TaskItem) -> Unit,
    onOpenEditor: (TaskItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val actionLabel = when {
        task.isRestore -> "恢复"
        task.status == TaskStatus.CANDIDATE -> "确认待办"
        else -> "完成"
    }
    val actionIcon = when {
        task.isRestore -> AgendumIcons.CheckedCircle
        task.status == TaskStatus.CANDIDATE -> AgendumIcons.ConfirmCheck
        else -> AgendumIcons.UncheckedCircle
    }

    val sparkProgress = remember(task.uuid) { Animatable(0f) }
    val checkBounce = remember(task.uuid) { Animatable(1f) }

    fun triggerToggle() {
        if (isInFlight) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!reducedMotion) {
            scope.launch {
                checkBounce.snapTo(0.84f)
                launch {
                    checkBounce.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(dampingRatio = 0.48f, stiffness = 620f),
                    )
                }
                sparkProgress.snapTo(0f)
                sparkProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
                )
                sparkProgress.snapTo(0f)
            }
        }
        onToggleDone(task)
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && !isInFlight) {
                triggerToggle()
            }
            false
        },
    )

    val cardInteraction = remember { MutableInteractionSource() }
    val cardPressed by cardInteraction.collectIsPressedAsState()
    val cardScale by animateFloatAsState(
        targetValue = if (cardPressed && !reducedMotion) 0.985f else 1f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 650f),
        label = "taskCardPress",
    )

    val categoryColor = when (task.category) {
        TaskCategory.ACADEMIC -> extColors.categoryAcademic
        TaskCategory.URGENT -> extColors.categoryUrgent
        TaskCategory.INFO -> extColors.categoryInfo
        TaskCategory.ACTION -> extColors.categoryAction
    }

    val isOverdue = ScheduleRules.isLate(task) && task.isActive
    val isFinished = task.status.isFinished

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = !isInFlight,
        enableDismissFromEndToStart = !isInFlight,
        backgroundContent = {
            val isStartToEnd = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val alignment = if (isStartToEnd) Alignment.CenterStart else Alignment.CenterEnd
            val bgColor by animateColorAsState(
                targetValue = if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) {
                    extColors.springBud
                } else {
                    colorScheme.primaryContainer
                },
                animationSpec = if (reducedMotion) snap() else tween(160),
                label = "swipeBg",
            )
            val fgColor by animateColorAsState(
                targetValue = if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) {
                    colorScheme.onPrimary
                } else {
                    colorScheme.onPrimaryContainer
                },
                animationSpec = if (reducedMotion) snap() else tween(160),
                label = "swipeFg",
            )
            val iconScale by animateFloatAsState(
                targetValue = if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) 1.14f else 0.94f,
                animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.6f, stiffness = 520f),
                label = "swipeIconScale",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = alignment,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        tint = fgColor,
                        modifier = Modifier.graphicsLayer {
                            scaleX = iconScale
                            scaleY = iconScale
                        },
                    )
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = fgColor,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        MistCard(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .clip(RoundedCornerShape(18.dp))
                .clickable(
                    interactionSource = cardInteraction,
                    indication = null,
                    onClick = { onOpenEditor(task) },
                ),
            cornerRadius = 18.dp,
            elevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (sparkProgress.value > 0f && !reducedMotion) {
                        val p = sparkProgress.value
                        val sparkColor = extColors.springBud
                        Canvas(modifier = Modifier.size(44.dp)) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val radius = (10.dp.toPx() + 11.dp.toPx() * p)
                            val dotR = (2.6.dp.toPx() * (1f - p * 0.7f)).coerceAtLeast(0.6f)
                            val alpha = (1f - p).coerceIn(0f, 1f)
                            for (i in 0 until 6) {
                                val angle = (i * 60.0) * PI / 180.0
                                val x = cx + (cos(angle) * radius).toFloat()
                                val y = cy + (sin(angle) * radius).toFloat()
                                drawCircle(
                                    color = sparkColor.copy(alpha = alpha),
                                    radius = dotR,
                                    center = Offset(x, y),
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { triggerToggle() },
                        enabled = !isInFlight,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .graphicsLayer {
                                scaleX = checkBounce.value
                                scaleY = checkBounce.value
                            }
                            .semantics {
                                contentDescription = actionLabel
                                stateDescription = if (task.status == TaskStatus.DONE) "已完成" else "未完成"
                            },
                    ) {
                        Icon(
                            imageVector = actionIcon,
                            contentDescription = null,
                            tint = when {
                                isInFlight -> colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                isFinished -> extColors.springBud
                                else -> colorScheme.primary
                            },
                            modifier = Modifier.size(25.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = task.summary.ifEmpty { "未命名日程" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                textDecoration = if (isFinished) TextDecoration.LineThrough else TextDecoration.None,
                            ),
                            color = if (isFinished) colorScheme.outline else colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 2.dp),
                        )

                        if (task.deadline.kind != DeadlineKind.NONE) {
                            val pillBg = when {
                                isOverdue -> colorScheme.errorContainer
                                isFinished -> extColors.subtleSurface
                                else -> colorScheme.primaryContainer
                            }
                            val pillFg = when {
                                isOverdue -> colorScheme.error
                                isFinished -> colorScheme.onSurfaceVariant
                                else -> colorScheme.onPrimaryContainer
                            }
                            val timeLabel = DateTimeHelper.timeText(task.deadline) +
                                if (isOverdue) " 逾期" else ""
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = pillBg,
                            ) {
                                Text(
                                    text = timeLabel,
                                    style = MaterialTheme.typography.labelSmall.tabularNums(),
                                    color = pillFg,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(vertical = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(categoryColor),
                            )
                            Text(
                                text = "${task.category.label} · ${task.status.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }

                        if (task.deadline.kind == DeadlineKind.NONE) {
                            Text(
                                text = "未设截止",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.outline,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }

                        if (task.pinned) {
                            TaskTag(
                                text = "置顶",
                                container = colorScheme.primaryContainer,
                                content = colorScheme.onPrimaryContainer,
                            )
                        }
                        if (task.isUrgent) {
                            TaskTag(
                                text = "紧急",
                                container = colorScheme.errorContainer,
                                content = colorScheme.error,
                            )
                        }
                        if (task.syncStatus == "conflict") {
                            TaskTag(
                                text = "需要处理冲突",
                                container = extColors.warningContainer,
                                content = extColors.warningInk,
                            )
                        } else if (task.syncStatus == "pending") {
                            TaskTag(
                                text = "待同步",
                                container = extColors.warningContainer,
                                content = extColors.warningInk,
                            )
                        }
                        if (task.groups.isNotEmpty()) {
                            TaskTag(
                                text = task.groups.first(),
                                container = extColors.subtleSurface,
                                content = colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (task.notes.isNotBlank()) {
                        Text(
                            text = task.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskTag(
    text: String,
    container: Color,
    content: Color,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
        )
    }
}
