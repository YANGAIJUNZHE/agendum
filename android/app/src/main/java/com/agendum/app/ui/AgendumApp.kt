package com.agendum.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agendum.app.ui.components.AgendumIcons
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
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

    // 普通成功提示 5 秒自动收起（与 client.html 一致）。
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
        val scale = 1f - (predictiveProgress * 0.05f)
        Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = 1f - (predictiveProgress * 0.15f)
        }
    } else {
        Modifier
    }

    Box(modifier = modifier.fillMaxSize()) {
        SharedTransitionLayout {
            val sharedScope = this
            AnimatedContent(
                targetState = uiState.showingClient,
                transitionSpec = {
                    if (reducedMotion) {
                        ContentTransform(EnterTransition.None, ExitTransition.None)
                    } else {
                        fadeIn() togetherWith fadeOut()
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

                        Row(modifier = Modifier.fillMaxSize()) {
                            if (useRail && !isEditorOpen) {
                                AgendumNavigationRail(
                                    selectedDestination = uiState.clientPage.navDestination,
                                    conflictCount = uiState.snapshot.conflicts.size,
                                    onSelectTab = { tab ->
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.selectTab(tab)
                                    },
                                )
                            }

                            AnimatedContent(
                                targetState = uiState.editor != null,
                                transitionSpec = {
                                    if (reducedMotion) {
                                        ContentTransform(EnterTransition.None, ExitTransition.None)
                                    } else {
                                        fadeIn() togetherWith fadeOut()
                                    }
                                },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                label = "client-vs-editor",
                            ) { editorVisible ->
                                val animScope = this
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
                                        sharedTransitionScope = sharedScope,
                                        animatedVisibilityScope = animScope,
                                        modifier = backPreviewModifier,
                                    )
                                } else {
                                    val showSyncShortcut = uiState.clientPage == ClientPage.TASKS ||
                                        uiState.clientPage == ClientPage.CALENDAR
                                    val showFab = showSyncShortcut

                                    Scaffold(
                                        contentWindowInsets = WindowInsets.safeDrawing,
                                        containerColor = colorScheme.background,
                                        snackbarHost = {
                                            SnackbarHost(hostState = snackbarHostState) { data ->
                                                val isError = uiState.feedback?.isError == true
                                                Snackbar(
                                                    snackbarData = data,
                                                    containerColor = if (isError) {
                                                        colorScheme.error
                                                    } else {
                                                        colorScheme.inverseSurface
                                                    },
                                                    contentColor = if (isError) {
                                                        colorScheme.onError
                                                    } else {
                                                        colorScheme.inverseOnSurface
                                                    },
                                                    actionColor = if (isError) {
                                                        colorScheme.onError
                                                    } else {
                                                        colorScheme.inversePrimary
                                                    },
                                                )
                                            }
                                        },
                                        topBar = {
                                            TopAppBar(
                                                title = {
                                                    Column {
                                                        Text(
                                                            text = uiState.clientPage.title,
                                                            style = MaterialTheme.typography.headlineMedium,
                                                        )
                                                        if (uiState.clientPage != ClientPage.CALENDAR) {
                                                            Text(
                                                                text = uiState.headerSummary,
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = colorScheme.onSurfaceVariant,
                                                            )
                                                        }
                                                    }
                                                },
                                                actions = {
                                                    if (showSyncShortcut) {
                                                        SyncShortcutButton(
                                                            uiState = uiState,
                                                            reducedMotion = reducedMotion,
                                                            onClick = {
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                viewModel.requestSync()
                                                            },
                                                        )
                                                    }
                                                },
                                                colors = TopAppBarDefaults.topAppBarColors(
                                                    containerColor = colorScheme.background,
                                                ),
                                            )
                                        },
                                        floatingActionButton = {
                                            if (showFab) {
                                                val fabSharedModifier = if (!reducedMotion) {
                                                    with(sharedScope) {
                                                        Modifier.sharedBounds(
                                                            sharedContentState = rememberSharedContentState("editor-fab"),
                                                            animatedVisibilityScope = animScope,
                                                        )
                                                    }
                                                } else {
                                                    Modifier
                                                }
                                                ExtendedFloatingActionButton(
                                                    onClick = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        viewModel.openEditor(null)
                                                    },
                                                    icon = { Icon(AgendumIcons.Add, contentDescription = null) },
                                                    text = { Text("新建日程") },
                                                    containerColor = colorScheme.primary,
                                                    contentColor = colorScheme.onPrimary,
                                                    modifier = fabSharedModifier,
                                                )
                                            }
                                        },
                                        bottomBar = {
                                            if (!useRail) {
                                                AgendumBottomBar(
                                                    selectedDestination = uiState.clientPage.navDestination,
                                                    conflictCount = uiState.snapshot.conflicts.size,
                                                    onSelectTab = { tab ->
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                        viewModel.selectTab(tab)
                                                    },
                                                )
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
                                                    .widthIn(max = 760.dp)
                                                    .fillMaxSize()
                                                    .padding(horizontal = 16.dp),
                                            ) {
                                                if (!uiState.snapshot.connected && showSyncShortcut) {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = extColors.warningContainer,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(bottom = 10.dp),
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                                            (fadeIn() + slideInHorizontally { it / 18 }) togetherWith
                                                                (fadeOut() + slideOutHorizontally { -it / 18 })
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
                                                            onSearchChange = viewModel::setSearchQuery,
                                                            onFilterChange = viewModel::setFilter,
                                                            onSortChange = viewModel::setSort,
                                                            onResetFilters = viewModel::resetFilters,
                                                            onRequestSync = { viewModel.requestSync() },
                                                            onToggleDone = { viewModel.toggleTaskQuickStatus(it) },
                                                            onOpenEditor = { viewModel.openEditor(it) },
                                                            onReconnect = viewModel::reconnect,
                                                            sharedTransitionScope = sharedScope,
                                                            animatedVisibilityScope = animScope,
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
                                                            sharedTransitionScope = sharedScope,
                                                            animatedVisibilityScope = animScope,
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
                                }
                            }
                        }
                    }
                }
            }
        }

        // Pairing 页也可能需要显示 Snackbar 反馈（例如导出或操作结果）。
        if (!uiState.showingClient) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
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
private fun SyncShortcutButton(
    uiState: AgendumUiState,
    reducedMotion: Boolean,
    onClick: () -> Unit,
) {
    val snap = uiState.snapshot
    val label = when {
        uiState.isSyncing -> "正在同步…"
        snap.conflicts.isNotEmpty() -> "${snap.conflicts.size} 项冲突"
        snap.pending > 0 -> "${snap.pending} 项待同步"
        snap.error.isNotEmpty() -> "离线可用"
        snap.connected -> "已配对"
        else -> "未连接"
    }
    FilledTonalButton(
        onClick = onClick,
        enabled = !uiState.isSyncing,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .padding(end = 12.dp)
            .heightIn(min = 42.dp),
    ) {
        if (uiState.isSyncing) {
            if (reducedMotion) {
                Icon(
                    imageVector = AgendumIcons.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AgendumBottomBar(
    selectedDestination: ClientPage,
    conflictCount: Int,
    onSelectTab: (ClientPage) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedDestination == ClientPage.TASKS,
            onClick = { onSelectTab(ClientPage.TASKS) },
            icon = { Icon(AgendumIcons.Tasks, contentDescription = null) },
            label = { Text("日程") },
        )
        NavigationBarItem(
            selected = selectedDestination == ClientPage.CALENDAR,
            onClick = { onSelectTab(ClientPage.CALENDAR) },
            icon = { Icon(AgendumIcons.Calendar, contentDescription = null) },
            label = { Text("月历") },
        )
        NavigationBarItem(
            selected = selectedDestination == ClientPage.ACCOUNT,
            onClick = { onSelectTab(ClientPage.ACCOUNT) },
            icon = {
                AccountNavIcon(conflictCount = conflictCount)
            },
            label = { Text("我的") },
        )
    }
}

@Composable
private fun AgendumNavigationRail(
    selectedDestination: ClientPage,
    conflictCount: Int,
    onSelectTab: (ClientPage) -> Unit,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
