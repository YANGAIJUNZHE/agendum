package com.agendum.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agendum.app.model.DateTimeHelper
import com.agendum.app.model.ScheduleRules
import com.agendum.app.model.TaskItem
import com.agendum.app.model.TaskStatus
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion

@OptIn(ExperimentalLayoutApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TaskRow(
    task: TaskItem,
    isInFlight: Boolean,
    onToggleDone: (TaskItem) -> Unit,
    onOpenEditor: (TaskItem) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current

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

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled && !isInFlight) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggleDone(task)
            }
            false
        },
    )

    val containerKey = "task-${task.uuid}"
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && !reducedMotion) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = containerKey),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Modifier
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.then(sharedModifier),
        enableDismissFromStartToEnd = !isInFlight,
        enableDismissFromEndToStart = !isInFlight,
        backgroundContent = {
            val alignment = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colorScheme.primaryContainer)
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
                        tint = colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenEditor(task) },
            shape = RoundedCornerShape(16.dp),
            color = extColors.cardSurface,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                IconButton(
                    onClick = {
                        if (!isInFlight) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleDone(task)
                        }
                    },
                    enabled = !isInFlight,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .semantics {
                            contentDescription = actionLabel
                            stateDescription = if (task.status == TaskStatus.DONE) "已完成" else "未完成"
                        },
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                        tint = if (isInFlight) {
                            colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        } else {
                            colorScheme.primary
                        },
                        modifier = Modifier.size(26.dp),
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = task.summary.ifEmpty { "未命名日程" },
                        style = MaterialTheme.typography.titleMedium,
                        color = colorScheme.onSurface,
                        modifier = Modifier.heightIn(min = 28.dp),
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val isOverdue = ScheduleRules.isLate(task) && task.isActive
                        Text(
                            text = DateTimeHelper.timeText(task.deadline),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOverdue) colorScheme.error else colorScheme.onSurfaceVariant,
                            fontWeight = if (isOverdue) FontWeight.Medium else FontWeight.Normal,
                        )
                        Text(
                            text = task.status.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        if (task.pinned) {
                            TaskTag(
                                text = "置顶",
                                container = colorScheme.primaryContainer,
                                content = colorScheme.primary,
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
                                content = extColors.warning,
                            )
                        } else if (task.syncStatus == "pending") {
                            TaskTag(
                                text = "待同步",
                                container = extColors.warningContainer,
                                content = extColors.warning,
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
        shape = RoundedCornerShape(6.dp),
        color = container,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}
