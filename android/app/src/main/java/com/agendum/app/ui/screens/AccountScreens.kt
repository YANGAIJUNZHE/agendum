package com.agendum.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agendum.app.model.ConflictItem
import com.agendum.app.model.DateTimeHelper
import com.agendum.app.model.ScheduleRules
import com.agendum.app.ui.AgendumUiState
import com.agendum.app.ui.ClientPage
import com.agendum.app.ui.UnpairDialogState
import com.agendum.app.ui.components.AgendumIcons
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion

@Composable
fun AccountScreen(
    uiState: AgendumUiState,
    onNavigate: (ClientPage) -> Unit,
    onRequestSync: () -> Unit,
    onReconnect: () -> Unit,
    onExportPending: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val snap = uiState.snapshot
    val friendlyError = ScheduleRules.friendlySyncError(snap.error)

    val stateTitle = when {
        snap.conflicts.isNotEmpty() -> "有 ${snap.conflicts.size} 项冲突待处理"
        !snap.connected -> "尚未完成配对"
        snap.error.isNotEmpty() -> "离线使用中"
        snap.pending > 0 -> "${snap.pending} 项修改待同步"
        else -> "日程已保存在手机"
    }

    val stateNote = when {
        friendlyError.isNotEmpty() -> friendlyError
        snap.lastSuccess > 0L -> "最近同步：${DateTimeHelper.formatDateTimeMillis(snap.lastSuccess)}"
        else -> "连接电脑同步日程，之后离线也可查看和编辑。"
    }

    val buttonLabel = when {
        snap.serverId.isEmpty() -> "连接电脑"
        snap.conflicts.isNotEmpty() -> "处理冲突"
        else -> "立即同步"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = colorScheme.primaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stateTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stateNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        when {
                            snap.serverId.isEmpty() -> onReconnect()
                            snap.conflicts.isNotEmpty() -> onNavigate(ClientPage.SYNC)
                            else -> onRequestSync()
                        }
                    },
                    enabled = snap.serverId.isEmpty() || snap.conflicts.isNotEmpty() || !uiState.isSyncing,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(buttonLabel)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = extColors.cardSurface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                AccountMenuRow(
                    title = "同步与冲突",
                    subtitle = "${snap.pending} 项未同步操作" +
                        if (snap.conflicts.isNotEmpty()) "，需要你确认冲突" else "",
                    badgeCount = snap.conflicts.size,
                    onClick = { onNavigate(ClientPage.SYNC) },
                )
                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.6f))
                AccountMenuRow(
                    title = "电脑连接",
                    subtitle = snap.base.ifEmpty { "查看地址或扫码连接电脑" },
                    badgeCount = 0,
                    onClick = { onNavigate(ClientPage.CONNECTION) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = extColors.cardSurface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                AccountMenuRow(
                    title = "导出未同步编辑",
                    subtitle = "保留本地修改记录，不包含连接凭据",
                    badgeCount = 0,
                    onClick = onExportPending,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "编辑先保存到手机，电脑可连接时再同步。提醒由电脑发送，电脑关机时不会产生新提醒。",
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountMenuRow(
    title: String,
    subtitle: String,
    badgeCount: Int,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface,
                )
                if (badgeCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge(
                        containerColor = colorScheme.errorContainer,
                        contentColor = colorScheme.error,
                    ) {
                        Text(badgeCount.toString())
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = AgendumIcons.Next,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncConflictsScreen(
    uiState: AgendumUiState,
    onBack: () -> Unit,
    onRequestSync: () -> Unit,
    onExportPending: () -> Unit,
    onChoiceChange: (operationId: String, field: String, useLocal: Boolean) -> Unit,
    onResolveConflict: (ConflictItem, Boolean) -> Unit,
    onDiscardConflict: (ConflictItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val snap = uiState.snapshot
    var showRawError by remember { mutableStateOf(false) }
    var deleteConfirmConflict by remember { mutableStateOf<ConflictItem?>(null) }
    var discardConfirmConflict by remember { mutableStateOf<ConflictItem?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("返回我的")
        }

        Text(
            text = "编辑先保存在手机，再同步到电脑。电脑关机或网络不可用时，你仍可继续管理日程。",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = extColors.cardSurface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KeyValueRow(label = "未同步操作", value = snap.pending.toString())
                KeyValueRow(
                    label = "最近一次同步完成",
                    value = if (snap.lastSuccess > 0L) {
                        DateTimeHelper.formatDateTimeMillis(snap.lastSuccess)
                    } else {
                        "尚未同步"
                    },
                )
            }
        }

        if (snap.error.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = ScheduleRules.friendlySyncError(snap.error),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = extColors.cardSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { showRawError = !showRawError },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "查看错误详情",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = if (showRawError) AgendumIcons.ExpandLess else AgendumIcons.ExpandMore,
                            contentDescription = null,
                        )
                    }
                    AnimatedVisibility(
                        visible = showRawError,
                        enter = if (reducedMotion) EnterTransition.None else androidx.compose.animation.expandVertically(),
                        exit = if (reducedMotion) ExitTransition.None else androidx.compose.animation.shrinkVertically(),
                    ) {
                        SelectionContainer {
                            Text(
                                text = snap.error,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRequestSync,
                enabled = !uiState.isSyncing,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("立即同步")
            }
            OutlinedButton(
                onClick = onExportPending,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("导出未同步编辑")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "需要处理的冲突",
            style = MaterialTheme.typography.titleMedium,
            color = colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (snap.conflicts.isEmpty()) {
            Text(
                text = "没有需要处理的冲突。",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )
        } else {
            snap.conflicts.forEach { conflict ->
                ConflictCard(
                    conflict = conflict,
                    choices = uiState.conflictChoices[conflict.operationId].orEmpty(),
                    isInFlight = conflict.uuid in uiState.inFlightUuids,
                    onChoiceChange = { field, useLocal ->
                        onChoiceChange(conflict.operationId, field, useLocal)
                    },
                    onPrimaryResolve = {
                        if (conflict.type == "delete") {
                            deleteConfirmConflict = conflict
                        } else {
                            onResolveConflict(conflict, false)
                        }
                    },
                    onSaveCopy = { onResolveConflict(conflict, true) },
                    onDiscard = { discardConfirmConflict = conflict },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    deleteConfirmConflict?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteConfirmConflict = null },
            text = { Text("确定删除电脑当前版本？") },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirmConflict = null
                    onResolveConflict(target, false)
                }) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmConflict = null }) {
                    Text("取消")
                }
            },
        )
    }

    discardConfirmConflict?.let { target ->
        AlertDialog(
            onDismissRequest = { discardConfirmConflict = null },
            text = { Text("放弃这条日程的全部未同步修改？其他日程不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    discardConfirmConflict = null
                    onDiscardConflict(target)
                }) {
                    Text("放弃修改", color = colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { discardConfirmConflict = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConflictCard(
    conflict: ConflictItem,
    choices: Map<String, Boolean>,
    isInFlight: Boolean,
    onChoiceChange: (field: String, useLocal: Boolean) -> Unit,
    onPrimaryResolve: () -> Unit,
    onSaveCopy: () -> Unit,
    onDiscard: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val current = conflict.current
    val serverAvailable = current != null && !current.deleted

    val reason = conflict.resultError.ifEmpty {
        when {
            !serverAvailable -> "电脑上已删除或不存在。本地编辑仍保留。"
            conflict.type == "delete" -> "删除前，电脑上的内容发生了变化。"
            else -> "两端修改了相同字段，请选择保留的值。"
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = extColors.cardSurface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = conflict.local.summary.ifEmpty { "已删除的日程" },
                style = MaterialTheme.typography.titleMedium,
                color = colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
            )

            if (serverAvailable) {
                conflict.fields.filter { it != "uuid" }.forEach { field ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = ScheduleRules.fieldLabel(field),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val useLocal = choices[field] ?: true
                    ConflictOptionRow(
                        label = "手机修改：${ScheduleRules.displayConflictValue(field, conflict.local)}",
                        selected = useLocal,
                        onSelect = { onChoiceChange(field, true) },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ConflictOptionRow(
                        label = "电脑当前值：${ScheduleRules.displayConflictValue(field, current)}",
                        selected = !useLocal,
                        onSelect = { onChoiceChange(field, false) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (serverAvailable && conflict.resultCode != "validation") {
                    Button(
                        onClick = onPrimaryResolve,
                        enabled = !isInFlight,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (conflict.type == "delete") "确认删除当前版本" else "按选择合并提交")
                    }
                }
                if (!serverAvailable || conflict.resultCode == "validation") {
                    OutlinedButton(
                        onClick = onSaveCopy,
                        enabled = !isInFlight,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("另存为新日程")
                    }
                }
                OutlinedButton(
                    onClick = onDiscard,
                    enabled = !isInFlight,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("放弃此条全部本地修改")
                }
            }
        }
    }
}

@Composable
private fun ConflictOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) colorScheme.primary else colorScheme.outlineVariant,
        ),
        color = if (selected) colorScheme.primaryContainer.copy(alpha = 0.25f) else colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun ConnectionScreen(
    uiState: AgendumUiState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val snap = uiState.snapshot
    var showDetails by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("返回我的")
        }

        Text(
            text = "在电脑「设置 → 手机日程同步」生成二维码即可连接，每台手机有独立的凭据。",
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = extColors.cardSurface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KeyValueRow(label = "连接地址", value = snap.base.ifEmpty { "未设置" })
                KeyValueRow(
                    label = "配对状态",
                    value = if (snap.connected) "已配对" else "需要配对",
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onReconnect,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text("重新配对或更换电脑")
        }

        Spacer(modifier = Modifier.height(20.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = extColors.cardSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { showDetails = !showDetails },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "连接详情",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (showDetails) AgendumIcons.ExpandLess else AgendumIcons.ExpandMore,
                        contentDescription = null,
                    )
                }
                AnimatedVisibility(
                    visible = showDetails,
                    enter = if (reducedMotion) EnterTransition.None else androidx.compose.animation.expandVertically(),
                    exit = if (reducedMotion) ExitTransition.None else androidx.compose.animation.shrinkVertically(),
                ) {
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        KeyValueRow(
                            label = "电脑标识",
                            value = snap.serverId.ifEmpty { "尚未配对" },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "重新配对时保留本地日程。切换电脑前，未同步编辑可以导出；未经明确选择不会丢弃。",
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = colorScheme.onSurface,
        )
    }
}

@Composable
fun UnpairDialogs(
    dialogState: UnpairDialogState?,
    onDismiss: () -> Unit,
    onKeepLocalAndRepair: () -> Unit,
    onExportPending: () -> Unit,
    onRequestDiscardConfirm: () -> Unit,
    onConfirmDiscardAll: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    when (dialogState) {
        null -> Unit
        is UnpairDialogState.PendingChoices -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("还有 ${dialogState.pendingCount} 项未同步操作") },
                text = {
                    Column {
                        TextButton(
                            onClick = onKeepLocalAndRepair,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("保留本地编辑，重新配对", modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(
                            onClick = onExportPending,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("导出未同步编辑", modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(
                            onClick = onRequestDiscardConfirm,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "丢弃编辑并解绑",
                                color = colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                },
            )
        }
        UnpairDialogState.DiscardConfirm -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                text = { Text("确定丢弃这台手机的全部未同步编辑？") },
                confirmButton = {
                    TextButton(onClick = onConfirmDiscardAll) {
                        Text("丢弃并解绑", color = colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                },
            )
        }
        UnpairDialogState.SimpleConfirm -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("重新配对？") },
                text = { Text("本地日程会保留，之后可以离线查看。") },
                confirmButton = {
                    TextButton(onClick = onKeepLocalAndRepair) {
                        Text("重新配对")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                },
            )
        }
    }
}
