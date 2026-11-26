package com.agendum.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agendum.app.ui.components.AgendumIcons
import com.agendum.app.ui.components.MagneticFusionDock
import com.agendum.app.ui.components.clientPageSlideDirection
import com.agendum.app.ui.screens.AccountScreen
import com.agendum.app.ui.screens.CalendarScreen
import com.agendum.app.ui.screens.ConnectionScreen
import com.agendum.app.ui.screens.EditorScreen
import com.agendum.app.ui.screens.PairingScreen
import com.agendum.app.ui.screens.SyncConflictsScreen
import com.agendum.app.ui.screens.TasksScreen
import com.agendum.app.ui.screens.UnpairDialogs
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion
import com.agendum.app.ui.theme.tabularNums
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val PageEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
fun AgendumApp(
    viewModel: ScheduleViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current

    var tasksScrolled by remember { mutableStateOf(false) }

    val hasOpenDialog = uiState.unpairDialog != null ||
        uiState.pairing.legacySnapshotText != null ||
        uiState.pairing.switchComputerPrompt != null ||
        uiState.editor?.showDiscardConfirm == true ||
        uiState.editor?.showDeleteConfirm == true

    val editorCleanBack = uiState.showingClient &&
        !hasOpenDialog &&
        uiState.editor != null &&
        !uiState.editor!!.isDirty &&
        !uiState.editor!!.isSaving

    val subPageBack = uiState.showingClient &&
        !hasOpenDialog &&
        uiState.editor == null &&
        uiState.clientPage != ClientPage.TASKS

    val immediateBackOnly = hasOpenDialog ||
        (uiState.showingClient && uiState.editor != null && (uiState.editor!!.isDirty || uiState.editor!!.isSaving))

    var predictiveProgress by remember { mutableFloatStateOf(0f) }

    BackHandler(enabled = immediateBackOnly) {
        viewModel.onBackPressed()
    }

    PredictiveBackHandler(enabled = editorCleanBack || subPageBack) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                predictiveProgress = if (reducedMotion) 0f else backEvent.progress
            }
            predictiveProgress = 0f
            viewModel.onBackPressed()
        } catch (_: CancellationException) {
            predictiveProgress = 0f
        }
    }

    LaunchedEffect(uiState.feedback?.id) {
        val feedback = uiState.feedback ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        val result = snackbarHostState.showSnackbar(
            message = feedback.text,
            actionLabel = "关闭",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed || result == SnackbarResult.Dismissed) {
            viewModel.dismissFeedback()
        }
    }

    // 普通成功提示 5 秒自动收起。
    LaunchedEffect(uiState.feedback?.id, uiState.feedback?.isError) {
        val feedback = uiState.feedback ?: return@LaunchedEffect
        if (!feedback.isError) {
            delay(5_000L)
            if (viewModel.uiState.value.feedback?.id == feedback.id) {
                snackbarHostState.currentSnackbarData?.dismiss()
                viewModel.dismissFeedback()
            }
        }
    }

    val backPreviewModifier = if (!reducedMotion && predictiveProgress > 0f) {
        val scale = 1f - (predictiveProgress * 0.04f)
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = 1f - (predictiveProgress * 0.14f)
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(extColors.canvasBrush),
    ) {
        AnimatedContent(
            targetState = uiState.showingClient,
            transitionSpec = {
                if (reducedMotion) {
                    ContentTransform(EnterTransition.None, ExitTransition.None)
                } else {
                    fadeIn(tween(220)) togetherWith fadeOut(tween(180))
                }
            },
            label = "root-pairing-client",
        ) { showingClient ->
            if (!showingClient) {
                PairingScreen(
                    pairing = uiState.pairing,
                    onScanClick = viewModel::startQrScan,
                    onToggleManual = viewModel::toggleManualPairing,
                    onAddressChange = viewModel::updatePairingAddress,
                    onStartManualPairing = viewModel::startManualPairing,
                    onReturnOffline = viewModel::returnToOfflineClient,
                    onOpenLegacySnapshot = viewModel::openLegacySnapshot,
                    onDismissLegacySnapshot = viewModel::dismissLegacySnapshot,
                    onExportAndSwitch = viewModel::exportAndSwitchComputer,
                    onDiscardAndSwitch = viewModel::discardAndSwitchComputer,
                    onDismissSwitchPrompt = viewModel::dismissSwitchComputerPrompt,
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val useRail = maxWidth >= 600.dp
                    val isEditorOpen = uiState.editorOpen
                    val showSyncAndAdd = uiState.clientPage == ClientPage.TASKS ||
                        uiState.clientPage == ClientPage.CALENDAR

                    Row(modifier = Modifier.fillMaxSize()) {
                        if (useRail && !isEditorOpen) {
                            AgendumNavigationRail(
                                selectedDestination = uiState.clientPage.navDestination,
                                showAddAction = showSyncAndAdd,
                                conflictCount = uiState.snapshot.conflicts.size,
                                onSelectTab = { tab ->
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    viewModel.selectTab(tab)
                                },
                                onAddClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.openEditor(null)
                                },
                            )
                        }

                        AnimatedContent(
                            targetState = uiState.editor != null,
                            transitionSpec = {
                                if (reducedMotion) {
                                    ContentTransform(EnterTransition.None, ExitTransition.None)
                                } else if (targetState) {
                                    (slideInVertically(tween(260, easing = PageEaseOut)) { it / 8 } +
                                        fadeIn(tween(220))) togetherWith fadeOut(tween(160))
                                } else {
                                    fadeIn(tween(200)) togetherWith
                                        (slideOutVertically(tween(220, easing = PageEaseOut)) { it / 10 } +
                                            fadeOut(tween(180)))
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            label = "client-vs-editor",
                        ) { editorVisible ->
                            val editor = uiState.editor
                            if (editorVisible && editor != null) {
                                EditorScreen(
                                    editor = editor,
                                    onUpdate = viewModel::updateEditor,
                                    onBack = viewModel::requestLeaveEditor,
                                    onSave = viewModel::saveEditor,
                                    onDismissDiscard = viewModel::dismissDiscardConfirm,
                                    onConfirmDiscard = viewModel::confirmDiscardEditor,
                                    onRequestDelete = viewModel::requestDeleteInEditor,
                                    onDismissDelete = viewModel::dismissDeleteConfirm,
                                    onConfirmDelete = viewModel::confirmDeleteInEditor,
                                    modifier = backPreviewModifier,
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Column(modifier = Modifier.fillMaxSize()) {
                                        MistTopHeader(
                                            uiState = uiState,
                                            compact = uiState.clientPage == ClientPage.TASKS &&
                                                tasksScrolled &&
                                                uiState.searchQuery.isEmpty(),
                                            showSyncShortcut = showSyncAndAdd,
                                            reducedMotion = reducedMotion,
                                            onSearchChange = viewModel::setSearchQuery,
                                            onSyncClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                viewModel.requestSync()
                                            },
                                        )

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxWidth(),
                                            contentAlignment = Alignment.TopCenter,
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .widthIn(max = 760.dp)
                                                    .fillMaxSize()
                                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                            ) {
                                                if (!uiState.snapshot.connected && showSyncAndAdd) {
                                                    Surface(
                                                        shape = RoundedCornerShape(14.dp),
                                                        color = extColors.warningContainer,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(bottom = 10.dp),
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 14.dp, vertical = 8.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            Text(
                                                                text = "配对尚未完成或已失效，本地编辑仍保留。",
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = extColors.onWarningContainer,
                                                                modifier = Modifier.weight(1f),
                                                            )
                                                            TextButton(onClick = viewModel::reconnect) {
                                                                Text("连接电脑")
                                                            }
                                                        }
                                                    }
                                                }

                                                AnimatedContent(
                                                    targetState = uiState.clientPage,
                                                    transitionSpec = {
                                                        if (reducedMotion) {
                                                            ContentTransform(EnterTransition.None, ExitTransition.None)
                                                        } else {
                                                            val dir = clientPageSlideDirection(initialState, targetState)
                                                            (slideInHorizontally(tween(260, easing = PageEaseOut)) { dir * it / 8 } +
                                                                fadeIn(tween(220))) togetherWith
                                                                (slideOutHorizontally(tween(200, easing = PageEaseOut)) { -dir * it / 10 } +
                                                                    fadeOut(tween(150)))
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .weight(1f)
                                                        .then(if (subPageBack) backPreviewModifier else Modifier),
                                                    label = "client-pages",
                                                ) { page ->
                                                    when (page) {
                                                        ClientPage.TASKS -> TasksScreen(
                                                            uiState = uiState,
                                                            onFilterChange = viewModel::setFilter,
                                                            onSortChange = viewModel::setSort,
                                                            onResetFilters = viewModel::resetFilters,
                                                            onRequestSync = { viewModel.requestSync() },
                                                            onToggleDone = { viewModel.toggleTaskQuickStatus(it) },
                                                            onOpenEditor = { viewModel.openEditor(it) },
                                                            onReconnect = viewModel::reconnect,
                                                            onScrolledChange = { tasksScrolled = it },
                                                        )
                                                        ClientPage.CALENDAR -> CalendarScreen(
                                                            uiState = uiState,
                                                            onFilterChange = viewModel::setFilter,
                                                            onSortChange = viewModel::setSort,
                                                            onResetFilters = viewModel::resetFilters,
                                                            onPreviousMonth = viewModel::previousMonth,
                                                            onNextMonth = viewModel::nextMonth,
                                                            onToday = viewModel::jumpToToday,
                                                            onSelectDay = viewModel::selectDay,
                                                            onClearDay = viewModel::clearSelectedDay,
                                                            onRequestSync = { viewModel.requestSync() },
                                                            onToggleDone = { viewModel.toggleTaskQuickStatus(it) },
                                                            onOpenEditor = { viewModel.openEditor(it) },
                                                            onReconnect = viewModel::reconnect,
                                                        )
                                                        ClientPage.ACCOUNT -> AccountScreen(
                                                            uiState = uiState,
                                                            onNavigate = viewModel::gotoPage,
                                                            onRequestSync = { viewModel.requestSync() },
                                                            onReconnect = viewModel::reconnect,
                                                            onExportPending = viewModel::exportPending,
                                                        )
                                                        ClientPage.SYNC -> SyncConflictsScreen(
                                                            uiState = uiState,
                                                            onBack = { viewModel.gotoPage(ClientPage.ACCOUNT) },
                                                            onRequestSync = { viewModel.requestSync() },
                                                            onExportPending = viewModel::exportPending,
                                                            onChoiceChange = viewModel::setConflictChoice,
                                                            onResolveConflict = { c, copy ->
                                                                viewModel.resolveConflict(c, copy)
                                                            },
                                                            onDiscardConflict = { c ->
                                                                viewModel.discardConflict(c)
                                                            },
                                                        )
                                                        ClientPage.CONNECTION -> ConnectionScreen(
                                                            uiState = uiState,
                                                            onBack = { viewModel.gotoPage(ClientPage.ACCOUNT) },
                                                            onReconnect = viewModel::reconnect,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // 底部渐隐柔幕 + 青岚磁吸底栏（彻底零浮动遮挡）
                                    if (!useRail) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .fillMaxWidth()
                                                .background(
                                                    Brush.verticalGradient(
                                                        0.0f to Color.Transparent,
                                                        0.45f to extColors.canvasBottom.copy(alpha = 0.88f),
                                                        1.0f to extColors.canvasBottom,
                                                    ),
                                                ),
                                            contentAlignment = Alignment.BottomCenter,
                                        ) {
                                            Box(modifier = Modifier.widthIn(max = 560.dp)) {
                                                MagneticFusionDock(
                                                    selectedDestination = uiState.clientPage.navDestination,
                                                    showAddButton = showSyncAndAdd,
                                                    conflictCount = uiState.snapshot.conflicts.size,
                                                    onSelectTab = viewModel::selectTab,
                                                    onAddClick = { viewModel.openEditor(null) },
                                                )
                                            }
                                        }
                                    }

                                    // Snackbar 严格锚定于底栏正上方，绝不与导航坞或按钮重叠
                                    SnackbarHost(
                                        hostState = snackbarHostState,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .widthIn(max = 560.dp)
                                            .navigationBarsPadding()
                                            .padding(
                                                start = 16.dp,
                                                end = 16.dp,
                                                bottom = if (useRail) 16.dp else 86.dp,
                                            ),
                                    ) { data ->
                                        val isError = uiState.feedback?.isError == true
                                        Snackbar(
                                            snackbarData = data,
                                            shape = RoundedCornerShape(16.dp),
                                            containerColor = if (isError) colorScheme.error else colorScheme.inverseSurface,
                                            contentColor = if (isError) colorScheme.onError else colorScheme.inverseOnSurface,
                                            actionColor = if (isError) colorScheme.onError else colorScheme.inversePrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 配对页 Snackbar（补齐底部手势安全区与键盘避让）
        if (!uiState.showingClient) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(16.dp),
            ) { data ->
                val isError = uiState.feedback?.isError == true
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = if (isError) colorScheme.error else colorScheme.inverseSurface,
                    contentColor = if (isError) colorScheme.onError else colorScheme.inverseOnSurface,
                    actionColor = if (isError) colorScheme.onError else colorScheme.inversePrimary,
                )
            }
        }
    }

    UnpairDialogs(
        dialogState = uiState.unpairDialog,
        onDismiss = viewModel::dismissUnpairDialog,
        onKeepLocalAndRepair = viewModel::unpairKeepingLocalEdits,
        onExportPending = viewModel::unpairExportFirst,
        onRequestDiscardConfirm = viewModel::requestUnpairDiscardConfirm,
        onConfirmDiscardAll = viewModel::confirmUnpairDiscardAll,
    )
}

@Composable
private fun MistTopHeader(
    uiState: AgendumUiState,
    compact: Boolean,
    showSyncShortcut: Boolean,
    reducedMotion: Boolean,
    onSearchChange: (String) -> Unit,
    onSyncClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(extColors.glassHeaderBackground)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 10.dp, bottom = if (uiState.clientPage == ClientPage.TASKS && !compact) 12.dp else 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = uiState.clientPage.title,
                        style = if (compact) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.headlineMedium
                        },
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AnimatedVisibility(
                        visible = !compact && uiState.clientPage != ClientPage.CALENDAR,
                        enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                        exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
                    ) {
                        Text(
                            text = uiState.headerSummary,
                            style = MaterialTheme.typography.bodySmall.tabularNums(),
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (showSyncShortcut) {
                    SyncStatusPill(
                        uiState = uiState,
                        reducedMotion = reducedMotion,
                        onClick = onSyncClick,
                    )
                }
            }

            AnimatedVisibility(
                visible = uiState.clientPage == ClientPage.TASKS && !compact,
                enter = if (reducedMotion) EnterTransition.None else fadeIn() + expandVertically(),
                exit = if (reducedMotion) ExitTransition.None else fadeOut() + shrinkVertically(),
            ) {
                val searchShape = RoundedCornerShape(15.dp)
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = {
                        Text(
                            text = "搜索日程、备注或群名",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = AgendumIcons.Search,
                            contentDescription = null,
                            tint = colorScheme.outline,
                            modifier = Modifier.size(19.dp),
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { onSearchChange("") },
                                modifier = Modifier.semantics { contentDescription = "清除搜索" },
                            ) {
                                Icon(
                                    imageVector = AgendumIcons.Close,
                                    contentDescription = "清除搜索",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = searchShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = extColors.cardSurface,
                        unfocusedContainerColor = extColors.cardSurface,
                        focusedBorderColor = colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .shadow(
                            elevation = if (extColors.isDark) 2.dp else 4.dp,
                            shape = searchShape,
                            ambientColor = extColors.cardShadowAmbient,
                            spotColor = extColors.cardShadowAmbient,
                        ),
                )
            }
        }
        HorizontalDivider(color = extColors.glassHeaderBorder)
    }
}

@Composable
private fun SyncStatusPill(
    uiState: AgendumUiState,
    reducedMotion: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val snap = uiState.snapshot

    val label = when {
        uiState.isSyncing -> "正在同步…"
        snap.conflicts.isNotEmpty() -> "${snap.conflicts.size} 项冲突"
        snap.pending > 0 -> "待同步 ${snap.pending}"
        snap.error.isNotEmpty() -> "离线可用"
        snap.connected -> "已同步"
        else -> "未连接"
    }

    val dotColor = when {
        snap.conflicts.isNotEmpty() -> colorScheme.error
        snap.pending > 0 || snap.error.isNotEmpty() || !snap.connected -> extColors.warning
        else -> extColors.springBud
    }

    val bgColor = when {
        snap.conflicts.isNotEmpty() -> colorScheme.errorContainer
        snap.pending > 0 || snap.error.isNotEmpty() || !snap.connected -> extColors.warningContainer
        else -> extColors.cardSurface
    }

    val fgColor = when {
        snap.conflicts.isNotEmpty() -> colorScheme.onErrorContainer
        snap.pending > 0 || snap.error.isNotEmpty() || !snap.connected -> extColors.warningInk
        else -> colorScheme.onSurface
    }

    val pulseAlpha = if (!reducedMotion && snap.connected && snap.conflicts.isEmpty() && snap.error.isEmpty() && !uiState.isSyncing) {
        val inf = rememberInfiniteTransition(label = "syncDotPulse")
        inf.animateFloat(
            initialValue = 1f,
            targetValue = 0.45f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        ).value
    } else {
        1f
    }

    val pillShape = RoundedCornerShape(999.dp)
    Surface(
        shape = pillShape,
        color = bgColor,
        modifier = Modifier
            .heightIn(min = 38.dp)
            .shadow(
                elevation = if (extColors.isDark) 2.dp else 4.dp,
                shape = pillShape,
                ambientColor = extColors.cardShadowAmbient,
                spotColor = extColors.cardShadowAmbient,
            )
            .clip(pillShape)
            .clickable(enabled = !uiState.isSyncing, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (uiState.isSyncing) {
                if (reducedMotion) {
                    Icon(
                        imageVector = AgendumIcons.Sync,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .graphicsLayer { alpha = pulseAlpha }
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.tabularNums(),
                color = fgColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AgendumNavigationRail(
    selectedDestination: ClientPage,
    showAddAction: Boolean,
    conflictCount: Int,
    onSelectTab: (ClientPage) -> Unit,
    onAddClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    NavigationRail(
        containerColor = extColors.glassHeaderBackground,
        header = {
            AnimatedVisibility(visible = showAddAction) {
                FloatingActionButton(
                    onClick = onAddClick,
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Icon(AgendumIcons.Add, contentDescription = "新建日程")
                }
            }
        },
    ) {
        Spacer(modifier = Modifier.weight(1f))
        NavigationRailItem(
            selected = selectedDestination == ClientPage.TASKS,
            onClick = { onSelectTab(ClientPage.TASKS) },
            icon = { Icon(AgendumIcons.Tasks, contentDescription = null) },
            label = { Text("日程") },
        )
        NavigationRailItem(
            selected = selectedDestination == ClientPage.CALENDAR,
            onClick = { onSelectTab(ClientPage.CALENDAR) },
            icon = { Icon(AgendumIcons.Calendar, contentDescription = null) },
            label = { Text("月历") },
        )
        NavigationRailItem(
            selected = selectedDestination == ClientPage.ACCOUNT,
            onClick = { onSelectTab(ClientPage.ACCOUNT) },
            icon = { AccountNavIcon(conflictCount = conflictCount) },
            label = { Text("我的") },
        )
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AccountNavIcon(conflictCount: Int) {
    if (conflictCount > 0) {
        BadgedBox(
            badge = {
                Badge {
                    Text(conflictCount.toString())
                }
            },
        ) {
            Icon(AgendumIcons.Account, contentDescription = null)
        }
    } else {
        Icon(AgendumIcons.Account, contentDescription = null)
    }
}
