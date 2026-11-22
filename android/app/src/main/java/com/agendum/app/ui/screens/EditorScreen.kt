package com.agendum.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion

private enum class PickerTarget {
    DEADLINE_DATE,
    DEADLINE_DATETIME,
    SNOOZE_DATETIME,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
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
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
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

    val sharedKey = if (editor.isNew) "editor-fab" else "task-${editor.uuid}"
    val sharedModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && !reducedMotion) {
        with(sharedTransitionScope) {
            Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = sharedKey),
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }
    } else {
        Modifier
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .then(sharedModifier),
        containerColor = colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = if (editor.isNew) "新建日程" else "编辑日程",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            enabled = !editor.isSaving,
                        ) {
                            Icon(
                                imageVector = AgendumIcons.Back,
                                contentDescription = "取消编辑并返回",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colorScheme.background,
                    ),
                )
                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        },
        bottomBar = {
            Surface(
                color = extColors.cardSurface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSave()
                        },
                        enabled = !editor.isSaving,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .widthIn(max = 688.dp)
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                    ) {
                        Text(
                            text = if (editor.isSaving) "正在保存…" else "保存到手机",
                            style = MaterialTheme.typography.titleMedium,
                        )
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
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "待办内容 · 必填",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
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
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = extColors.cardSurface,
                            unfocusedContainerColor = extColors.cardSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "截止时间类型",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        DeadlineKind.entries.forEachIndexed { index, kind ->
                            SegmentedButton(
                                selected = editor.deadlineKind == kind,
                                onClick = {
                                    if (!editor.isSaving) {
                                        onUpdate { it.copy(deadlineKind = kind) }
                                    }
                                },
                                enabled = !editor.isSaving,
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = DeadlineKind.entries.size,
                                ),
                            ) {
                                Text(kind.label)
                            }
                        }
                    }
                }

                if (editor.deadlineKind == DeadlineKind.DATE) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "截止日期",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        PickerButtonField(
                            value = editor.deadlineDate.ifEmpty { "点击选择日期" },
                            isPlaceholder = editor.deadlineDate.isEmpty(),
                            enabled = !editor.isSaving,
                            onClick = { activeDatePicker = PickerTarget.DEADLINE_DATE },
                        )
                    }
                }

                if (editor.deadlineKind == DeadlineKind.DATETIME) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "截止时刻",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
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
                        fontWeight = FontWeight.SemiBold,
                    )
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
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = extColors.cardSurface,
                            unfocusedContainerColor = extColors.cardSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = extColors.cardSurface,
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
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
                                imageVector = if (advancedExpanded) AgendumIcons.ExpandLess else AgendumIcons.ExpandMore,
                                contentDescription = null,
                            )
                        }
                        AnimatedVisibility(
                            visible = advancedExpanded,
                            enter = if (reducedMotion) EnterTransition.None else expandVertically(),
                            exit = if (reducedMotion) ExitTransition.None else shrinkVertically(),
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
                                        fontWeight = FontWeight.SemiBold,
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
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = extColors.cardSurface,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
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
                                    imageVector = if (sourceExpanded) AgendumIcons.ExpandLess else AgendumIcons.ExpandMore,
                                    contentDescription = null,
                                )
                            }
                            AnimatedVisibility(
                                visible = sourceExpanded,
                                enter = if (reducedMotion) EnterTransition.None else expandVertically(),
                                exit = if (reducedMotion) ExitTransition.None else shrinkVertically(),
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
                    Text(
                        text = editor.errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.error,
                    )
                }

                if (!editor.isNew) {
                    TextButton(
                        onClick = onRequestDelete,
                        enabled = !editor.isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
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
    val extColors = LocalAgendumColors.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = value,
                color = if (isPlaceholder) colorScheme.onSurfaceVariant else colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
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
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = extColors.cardSurface,
                    unfocusedContainerColor = extColors.cardSurface,
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
