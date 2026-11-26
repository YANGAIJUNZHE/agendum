package com.agendum.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agendum.app.model.DateTimeHelper
import com.agendum.app.model.DeadlineKind
import com.agendum.app.model.TaskCategory
import com.agendum.app.model.TaskStatus
import com.agendum.app.model.UrgentOverride
import com.agendum.app.ui.EditorState
import com.agendum.app.ui.components.AgendumIcons
import com.agendum.app.ui.components.MagneticSegmentedControl
import com.agendum.app.ui.components.MistCard
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion
import com.agendum.app.ui.theme.tabularNums

private enum class PickerTarget {
    DEADLINE_DATE,
    DEADLINE_DATETIME,
    SNOOZE_DATETIME,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    editor: EditorState,
    onUpdate: ((EditorState) -> EditorState) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDismissDiscard: () -> Unit,
    onConfirmDiscard: () -> Unit,
    onRequestDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current

    var advancedExpanded by remember(editor.uuid, editor.isNew) { mutableStateOf(false) }
    var sourceExpanded by remember(editor.uuid, editor.isNew) { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var statusExpanded by remember { mutableStateOf(false) }
    var urgentExpanded by remember { mutableStateOf(false) }

    var activeDatePicker by remember { mutableStateOf<PickerTarget?>(null) }
    var pendingTimeDate by remember { mutableStateOf<Pair<PickerTarget, String>?>(null) }

    val advChevronDeg by animateFloatAsState(
        targetValue = if (advancedExpanded) 180f else 0f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "advChevron",
    )
    val srcChevronDeg by animateFloatAsState(
        targetValue = if (sourceExpanded) 180f else 0f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.8f, stiffness = 420f),
        label = "srcChevron",
    )

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(extColors.canvasBrush),
        containerColor = Color.Transparent,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(extColors.glassHeaderBackground)
                    .statusBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = extColors.cardSurface,
                        modifier = Modifier
                            .size(42.dp)
                            .shadow(
                                elevation = if (extColors.isDark) 2.dp else 4.dp,
                                shape = RoundedCornerShape(12.dp),
                                ambientColor = extColors.cardShadowAmbient,
                                spotColor = extColors.cardShadowAmbient,
                            ),
                    ) {
                        IconButton(
                            onClick = onBack,
                            enabled = !editor.isSaving,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Icon(
                                imageVector = AgendumIcons.Back,
                                contentDescription = "取消编辑并返回",
                                tint = colorScheme.onSurface,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (editor.isNew) "新建日程" else "编辑日程",
                        style = MaterialTheme.typography.titleLarge,
                        color = colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (!editor.isNew) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = colorScheme.errorContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(enabled = !editor.isSaving, onClick = onRequestDelete),
                        ) {
                            Text(
                                text = "删除",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.error,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = extColors.glassHeaderBorder)
            }
        },
        bottomBar = {
            Surface(
                color = extColors.glassHeaderBackground,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(color = extColors.glassHeaderBorder)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            modifier = Modifier
                                .widthIn(max = 688.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(
                                onClick = onBack,
                                enabled = !editor.isSaving,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.heightIn(min = 52.dp),
                            ) {
                                Text("取消")
                            }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onSave()
                                },
                                enabled = !editor.isSaving,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 52.dp),
                            ) {
                                Text(
                                    text = if (editor.isSaving) "正在保存…" else "保存到手机",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 688.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "待办内容 · 必填",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurfaceVariant,
                    )
                    MistCard(cornerRadius = 16.dp, elevation = 4.dp) {
                        OutlinedTextField(
                            value = editor.summary,
                            onValueChange = { value ->
                                if (value.length <= 200) {
                                    onUpdate { it.copy(summary = value) }
                                }
                            },
                            enabled = !editor.isSaving,
                            placeholder = { Text("需要做什么？") },
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "截止时间类型",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurfaceVariant,
                    )
                    MagneticSegmentedControl(
                        options = DeadlineKind.entries.map { it.label },
                        selectedIndex = DeadlineKind.entries.indexOf(editor.deadlineKind).coerceAtLeast(0),
                        enabled = !editor.isSaving,
                        onSelect = { idx ->
                            val chosen = DeadlineKind.entries.getOrNull(idx) ?: return@MagneticSegmentedControl
                            onUpdate { it.copy(deadlineKind = chosen) }
                        },
                    )
                }

                AnimatedVisibility(
                    visible = editor.deadlineKind == DeadlineKind.DATE,
                    enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                    exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "截止日期",
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSurfaceVariant,
                        )
                        PickerButtonField(
                            value = editor.deadlineDate.ifEmpty { "点击选择日期" },
                            isPlaceholder = editor.deadlineDate.isEmpty(),
                            enabled = !editor.isSaving,
                            onClick = { activeDatePicker = PickerTarget.DEADLINE_DATE },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = editor.deadlineKind == DeadlineKind.DATETIME,
                    enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                    exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "截止时刻",
                            style = MaterialTheme.typography.labelLarge,
                            color = colorScheme.onSurfaceVariant,
                        )
                        PickerButtonField(
                            value = if (editor.deadlineDatetime.isEmpty()) {
                                "点击选择日期和时刻"
                            } else {
                                DateTimeHelper.formatLocalDisplayFromInput(editor.deadlineDatetime)
                            },
                            isPlaceholder = editor.deadlineDatetime.isEmpty(),
                            enabled = !editor.isSaving,
                            onClick = { activeDatePicker = PickerTarget.DEADLINE_DATETIME },
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "备注 · 选填",
                        style = MaterialTheme.typography.labelLarge,
                        color = colorScheme.onSurfaceVariant,
                    )
                    MistCard(cornerRadius = 16.dp, elevation = 4.dp) {
                        OutlinedTextField(
                            value = editor.notes,
                            onValueChange = { value ->
                                if (value.length <= 10000) {
                                    onUpdate { it.copy(notes = value) }
                                }
                            },
                            enabled = !editor.isSaving,
                            placeholder = { Text("补充要求、安排或相关链接") },
                            minLines = 3,
                            maxLines = 8,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                MistCard(
                    cornerRadius = 18.dp,
                    elevation = 4.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .clickable { advancedExpanded = !advancedExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "更多选项",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = AgendumIcons.ExpandMore,
                                contentDescription = null,
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.graphicsLayer { rotationZ = advChevronDeg },
                            )
                        }
                        AnimatedVisibility(
                            visible = advancedExpanded,
                            enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                            exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
                        ) {
                            Column(
                                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                EditorDropdownField(
                                    label = "分类",
                                    selectedText = editor.category.label,
                                    expanded = categoryExpanded,
                                    enabled = !editor.isSaving,
                                    onExpandedChange = { categoryExpanded = it },
                                ) {
                                    TaskCategory.entries.forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text(cat.label) },
                                            onClick = {
                                                onUpdate { it.copy(category = cat) }
                                                categoryExpanded = false
                                            },
                                        )
                                    }
                                }

                                EditorDropdownField(
                                    label = "状态",
                                    selectedText = editor.status.label,
                                    expanded = statusExpanded,
                                    enabled = !editor.isSaving,
                                    onExpandedChange = { statusExpanded = it },
                                ) {
                                    TaskStatus.entries.forEach { st ->
                                        DropdownMenuItem(
                                            text = { Text(st.label) },
                                            onClick = {
                                                onUpdate { it.copy(status = st) }
                                                statusExpanded = false
                                            },
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = !editor.isSaving) {
                                            onUpdate { it.copy(pinned = !it.pinned) }
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = editor.pinned,
                                        onCheckedChange = { checked ->
                                            if (!editor.isSaving) {
                                                onUpdate { it.copy(pinned = checked) }
                                            }
                                        },
                                        enabled = !editor.isSaving,
                                    )
                                    Text(
                                        text = "置顶",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }

                                EditorDropdownField(
                                    label = "紧急标记",
                                    selectedText = editor.urgentOverride.label,
                                    expanded = urgentExpanded,
                                    enabled = !editor.isSaving,
                                    onExpandedChange = { urgentExpanded = it },
                                ) {
                                    UrgentOverride.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            onClick = {
                                                onUpdate { it.copy(urgentOverride = option) }
                                                urgentExpanded = false
                                            },
                                        )
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "稍后提醒时刻",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = colorScheme.onSurfaceVariant,
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            PickerButtonField(
                                                value = if (editor.snoozeDatetime.isEmpty()) {
                                                    "点击设置时刻"
                                                } else {
                                                    DateTimeHelper.formatLocalDisplayFromInput(editor.snoozeDatetime)
                                                },
                                                isPlaceholder = editor.snoozeDatetime.isEmpty(),
                                                enabled = !editor.isSaving,
                                                onClick = { activeDatePicker = PickerTarget.SNOOZE_DATETIME },
                                            )
                                        }
                                        if (editor.snoozeDatetime.isNotEmpty()) {
                                            TextButton(
                                                onClick = {
                                                    if (!editor.isSaving) {
                                                        onUpdate { it.copy(snoozeDatetime = "") }
                                                    }
                                                },
                                                enabled = !editor.isSaving,
                                                modifier = Modifier.heightIn(min = 48.dp),
                                            ) {
                                                Text("清除")
                                            }
                                        }
                                    }
                                    Text(
                                        text = "留空表示不设置；提醒仍由电脑在线时执行。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (editor.evidence.isNotEmpty() || editor.details.isNotEmpty()) {
                    val sourceText = remember(editor.evidence, editor.details, editor.groups) {
                        listOfNotNull(
                            editor.evidence.takeIf { it.isNotEmpty() },
                            editor.details.joinToString("\n").takeIf { it.isNotEmpty() },
                            editor.groups.joinToString("、").takeIf { it.isNotEmpty() },
                        ).joinToString("\n\n")
                    }
                    MistCard(
                        cornerRadius = 18.dp,
                        elevation = 4.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .clickable { sourceExpanded = !sourceExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "来源与详细要求",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    imageVector = AgendumIcons.ExpandMore,
                                    contentDescription = null,
                                    tint = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.graphicsLayer { rotationZ = srcChevronDeg },
                                )
                            }
                            AnimatedVisibility(
                                visible = sourceExpanded,
                                enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                                exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = sourceText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 14.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (editor.errorMessage.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = editor.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }

                if (!editor.isNew) {
                    TextButton(
                        onClick = onRequestDelete,
                        enabled = !editor.isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) {
                        Text("删除这条日程", color = colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    activeDatePicker?.let { target ->
        val existingDate = when (target) {
            PickerTarget.DEADLINE_DATE -> editor.deadlineDate
            PickerTarget.DEADLINE_DATETIME -> DateTimeHelper.extractDatePart(editor.deadlineDatetime)
            PickerTarget.SNOOZE_DATETIME -> DateTimeHelper.extractDatePart(editor.snoozeDatetime)
        }
        val initialUtcMillis = DateTimeHelper.dateStringToUtcMillis(existingDate)
            ?: DateTimeHelper.dateStringToUtcMillis(DateTimeHelper.todayDateString())
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialUtcMillis)

        DatePickerDialog(
            onDismissRequest = { activeDatePicker = null },
            confirmButton = {
                TextButton(onClick = {
                    val selectedMillis = datePickerState.selectedDateMillis
                    if (selectedMillis != null) {
                        val dateStr = DateTimeHelper.utcMillisToDateString(selectedMillis)
                        if (target == PickerTarget.DEADLINE_DATE) {
                            onUpdate { it.copy(deadlineDate = dateStr) }
                        } else {
                            pendingTimeDate = target to dateStr
                        }
                    }
                    activeDatePicker = null
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { activeDatePicker = null }) {
                    Text("取消")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    pendingTimeDate?.let { (target, dateStr) ->
        val existingLocal = when (target) {
            PickerTarget.DEADLINE_DATETIME -> editor.deadlineDatetime
            PickerTarget.SNOOZE_DATETIME -> editor.snoozeDatetime
            else -> ""
        }
        val (initHour, initMinute) = DateTimeHelper.extractHourMinute(existingLocal) ?: (18 to 0)
        val timePickerState = rememberTimePickerState(
            initialHour = initHour,
            initialMinute = initMinute,
            is24Hour = true,
        )

        AlertDialog(
            onDismissRequest = { pendingTimeDate = null },
            title = { Text("选择时刻") },
            text = { TimePicker(state = timePickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val combined = DateTimeHelper.combineDateAndTime(
                        dateStr,
                        timePickerState.hour,
                        timePickerState.minute,
                    )
                    when (target) {
                        PickerTarget.DEADLINE_DATETIME -> onUpdate { it.copy(deadlineDatetime = combined) }
                        PickerTarget.SNOOZE_DATETIME -> onUpdate { it.copy(snoozeDatetime = combined) }
                        else -> Unit
                    }
                    pendingTimeDate = null
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTimeDate = null }) {
                    Text("取消")
                }
            },
        )
    }

    if (editor.showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = onDismissDiscard,
            text = { Text("有尚未保存的内容，放弃修改？") },
            confirmButton = {
                TextButton(onClick = onConfirmDiscard) {
                    Text("放弃修改", color = colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDiscard) {
                    Text("继续编辑")
                }
            },
        )
    }

    if (editor.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            text = { Text("删除这条日程？离线时先保存在手机，连接电脑后同步删除。") },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) {
                    Text("删除", color = colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun PickerButtonField(
    value: String,
    isPlaceholder: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    MistCard(
        cornerRadius = 15.dp,
        elevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = value,
                color = if (isPlaceholder) colorScheme.onSurfaceVariant else colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge.tabularNums(),
            )
            Icon(
                imageVector = AgendumIcons.Calendar,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDropdownField(
    label: String,
    selectedText: String,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    menuItems: @Composable () -> Unit,
) {
    val extColors = LocalAgendumColors.current
    val colorScheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = colorScheme.onSurfaceVariant,
        )
        ExposedDropdownMenuBox(
            expanded = expanded && enabled,
            onExpandedChange = { if (enabled) onExpandedChange(it) },
        ) {
            OutlinedTextField(
                value = selectedText,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = extColors.subtleSurface,
                    unfocusedContainerColor = extColors.subtleSurface,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = colorScheme.primary,
                ),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { onExpandedChange(false) },
            ) {
                menuItems()
            }
        }
    }
}
