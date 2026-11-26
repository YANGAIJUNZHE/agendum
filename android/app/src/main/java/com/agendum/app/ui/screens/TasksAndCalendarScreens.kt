package com.agendum.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agendum.app.model.DateTimeHelper
import com.agendum.app.model.ScheduleRules
import com.agendum.app.model.TaskFilter
import com.agendum.app.model.TaskItem
import com.agendum.app.model.TaskSort
import com.agendum.app.ui.AgendumUiState
import com.agendum.app.ui.components.AgendumIcons
import com.agendum.app.ui.components.MistCard
import com.agendum.app.ui.components.TaskRow
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion
import com.agendum.app.ui.theme.tabularNums

/** 月历日期格密度圆点数量：最多 3 颗，彻底杜绝窄格内 "12项" 文字溢出。 */
fun calendarDensityDotCount(taskCount: Int): Int = taskCount.coerceIn(0, 3)

private val SmoothEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    uiState: AgendumUiState,
    onFilterChange: (TaskFilter) -> Unit,
    onSortChange: (TaskSort) -> Unit,
    onResetFilters: () -> Unit,
    onRequestSync: () -> Unit,
    onToggleDone: (TaskItem) -> Unit,
    onOpenEditor: (TaskItem?) -> Unit,
    onReconnect: () -> Unit,
    onScrolledChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val reducedMotion = LocalReducedMotion.current
    val listState = rememberLazyListState()

    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 28
        }
    }
    LaunchedEffect(isScrolled) {
        onScrolledChange(isScrolled)
    }

    val filtered = remember(uiState.snapshot.tasks, uiState.filter, uiState.searchQuery) {
        ScheduleRules.filterTasks(uiState.snapshot.tasks, uiState.filter, uiState.searchQuery)
    }
    val sorted = remember(filtered, uiState.sort) {
        ScheduleRules.sortTasks(filtered, uiState.sort)
    }
    val groups = remember(sorted) {
        ScheduleRules.groupTasks(sorted, showDateHeadings = true)
    }

    Column(modifier = modifier.fillMaxSize()) {
        FilterSortBar(
            filter = uiState.filter,
            sort = uiState.sort,
            showReset = uiState.hasActiveFilterOrSearch,
            onFilterChange = onFilterChange,
            onSortChange = onSortChange,
            onResetFilters = onResetFilters,
        )

        val trimmed = uiState.searchQuery.trim()
        val resultText = buildString {
            if (trimmed.isNotEmpty()) append("搜索「$trimmed」 · ")
            append("${uiState.filter.label} · ${filtered.size} 项")
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = resultText,
                style = MaterialTheme.typography.bodySmall.tabularNums(),
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = uiState.hasActiveFilterOrSearch,
                enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(180)) + expandHorizontally(),
                exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(150)) + shrinkHorizontally(),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = colorScheme.primaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(onClick = onResetFilters),
                ) {
                    Text(
                        text = "清除筛选",
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = uiState.isSyncing,
            onRefresh = onRequestSync,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 2.dp, bottom = 124.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (groups.isEmpty()) {
                    item(key = "empty-tasks") {
                        EmptyScheduleState(
                            title = "没有符合条件的日程",
                            hasServer = uiState.snapshot.serverId.isNotEmpty(),
                            onNewTask = { onOpenEditor(null) },
                            onReconnect = onReconnect,
                        )
                    }
                } else {
                    for (group in groups) {
                        if (group.heading.isNotEmpty()) {
                            item(key = "heading-${group.heading}") {
                                GroupHeadingRow(
                                    heading = group.heading,
                                    count = group.tasks.size,
                                )
                            }
                        }
                        items(group.tasks, key = { it.uuid }) { task ->
                            val itemModifier = if (reducedMotion) {
                                Modifier
                            } else {
                                Modifier.animateItem(
                                    placementSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                                )
                            }
                            TaskRow(
                                task = task,
                                isInFlight = task.uuid in uiState.inFlightUuids,
                                onToggleDone = onToggleDone,
                                onOpenEditor = { onOpenEditor(it) },
                                modifier = itemModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    uiState: AgendumUiState,
    onFilterChange: (TaskFilter) -> Unit,
    onSortChange: (TaskSort) -> Unit,
    onResetFilters: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onSelectDay: (String) -> Unit,
    onClearDay: () -> Unit,
    onRequestSync: () -> Unit,
    onToggleDone: (TaskItem) -> Unit,
    onOpenEditor: (TaskItem?) -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current
    val todayKey = remember(uiState.calendarYear, uiState.calendarMonth) {
        DateTimeHelper.todayDateString()
    }

    val filtered = remember(uiState.snapshot.tasks, uiState.filter, uiState.searchQuery) {
        ScheduleRules.filterTasks(uiState.snapshot.tasks, uiState.filter, uiState.searchQuery)
    }
    val dayTasksMap = remember(filtered) {
        filtered.groupBy { DateTimeHelper.deadlineDay(it.deadline) }
    }
    val dayFiltered = remember(filtered, uiState.selectedDay) {
        if (uiState.selectedDay.isEmpty()) {
            filtered
        } else {
            filtered.filter { DateTimeHelper.deadlineDay(it.deadline) == uiState.selectedDay }
        }
    }
    val sorted = remember(dayFiltered, uiState.sort) {
        ScheduleRules.sortTasks(dayFiltered, uiState.sort)
    }
    val groups = remember(sorted, uiState.selectedDay) {
        ScheduleRules.groupTasks(sorted, showDateHeadings = uiState.selectedDay.isEmpty())
    }

    Column(modifier = modifier.fillMaxSize()) {
        FilterSortBar(
            filter = uiState.filter,
            sort = uiState.sort,
            showReset = uiState.hasActiveFilterOrSearch,
            onFilterChange = onFilterChange,
            onSortChange = onSortChange,
            onResetFilters = onResetFilters,
        )

        val trimmed = uiState.searchQuery.trim()
        if (trimmed.isNotEmpty() || uiState.hasActiveFilterOrSearch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = buildString {
                        if (trimmed.isNotEmpty()) append("搜索「$trimmed」 · ")
                        append("${uiState.filter.label} · ${filtered.size} 项")
                    },
                    style = MaterialTheme.typography.bodySmall.tabularNums(),
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (uiState.hasActiveFilterOrSearch) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = colorScheme.primaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onResetFilters),
                    ) {
                        Text(
                            text = "清除筛选",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        PullToRefreshBox(
            isRefreshing = uiState.isSyncing,
            onRefresh = onRequestSync,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 124.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "calendar-card") {
                    MistCard(
                        cornerRadius = 20.dp,
                        elevation = 6.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = extColors.subtleSurface,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onPreviousMonth()
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Icon(
                                            imageVector = AgendumIcons.Back,
                                            contentDescription = "上一月",
                                            tint = colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Text(
                                    text = "${uiState.calendarYear}年${uiState.calendarMonth}月",
                                    style = MaterialTheme.typography.titleMedium.tabularNums(),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f),
                                )
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = colorScheme.primaryContainer,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onToday()
                                        },
                                ) {
                                    Text(
                                        text = "今天",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = extColors.subtleSurface,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    IconButton(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onNextMonth()
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Icon(
                                            imageVector = AgendumIcons.Next,
                                            contentDescription = "下一月",
                                            tint = colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.outline,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 4.dp),
                                    )
                                }
                            }

                            val monthKey = uiState.calendarYear * 12 + uiState.calendarMonth
                            AnimatedContent(
                                targetState = monthKey,
                                transitionSpec = {
                                    if (reducedMotion) {
                                        ContentTransform(EnterTransition.None, ExitTransition.None)
                                    } else {
                                        val dir = if (targetState >= initialState) 1 else -1
                                        (slideInHorizontally(tween(240, easing = SmoothEaseOut)) { dir * it / 5 } +
                                            fadeIn(tween(200))) togetherWith
                                            (slideOutHorizontally(tween(200, easing = SmoothEaseOut)) { -dir * it / 6 } +
                                                fadeOut(tween(150)))
                                    }
                                },
                                label = "calendar-month-grid",
                            ) { keyInt ->
                                val year = (keyInt - 1) / 12
                                val month = ((keyInt - 1) % 12) + 1
                                val leadingBlanks = DateTimeHelper.firstDayOfWeekInMonth(year, month)
                                val daysInMonth = DateTimeHelper.daysInMonth(year, month)
                                val totalCells = leadingBlanks + daysInMonth
                                val rows = (totalCells + 6) / 7

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    for (rowIndex in 0 until rows) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp),
                                        ) {
                                            for (colIndex in 0 until 7) {
                                                val cellIndex = rowIndex * 7 + colIndex
                                                val dayNumber = cellIndex - leadingBlanks + 1
                                                if (dayNumber !in 1..daysInMonth) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                } else {
                                                    val dateKey = DateTimeHelper.formatDateKey(year, month, dayNumber)
                                                    val tasksForDay = dayTasksMap[dateKey].orEmpty()
                                                    val count = tasksForDay.size
                                                    val hasUrgentOrLate = tasksForDay.any {
                                                        it.isUrgent || (ScheduleRules.isLate(it) && it.isActive)
                                                    }
                                                    val isSelected = dateKey == uiState.selectedDay
                                                    val isToday = dateKey == todayKey

                                                    val cellBg by animateColorAsState(
                                                        targetValue = if (isSelected) colorScheme.primary else extColors.cardSurface,
                                                        animationSpec = if (reducedMotion) snap() else tween(180),
                                                        label = "dayBg-$dateKey",
                                                    )
                                                    val dayTextColor by animateColorAsState(
                                                        targetValue = when {
                                                            isSelected -> colorScheme.onPrimary
                                                            isToday -> colorScheme.primary
                                                            else -> colorScheme.onSurface
                                                        },
                                                        animationSpec = if (reducedMotion) snap() else tween(180),
                                                        label = "dayFg-$dateKey",
                                                    )

                                                    Surface(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .padding(horizontal = 2.dp)
                                                            .height(46.dp)
                                                            .clip(RoundedCornerShape(12.dp))
                                                            .clickable {
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                onSelectDay(dateKey)
                                                            }
                                                            .semantics {
                                                                contentDescription = "${dateKey}，${count}项日程"
                                                                stateDescription = if (isSelected) "已选中" else "未选中"
                                                            },
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = cellBg,
                                                        border = if (!isSelected && isToday) {
                                                            BorderStroke(1.5.dp, extColors.springBud)
                                                        } else {
                                                            null
                                                        },
                                                    ) {
                                                        Column(
                                                            modifier = Modifier.fillMaxSize(),
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.Center,
                                                        ) {
                                                            Text(
                                                                text = dayNumber.toString(),
                                                                style = MaterialTheme.typography.bodyMedium.tabularNums(),
                                                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium,
                                                                color = dayTextColor,
                                                            )
                                                            Spacer(modifier = Modifier.height(3.dp))
                                                            val dotCount = calendarDensityDotCount(count)
                                                            Row(
                                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.height(5.dp),
                                                            ) {
                                                                for (dotIdx in 0 until dotCount) {
                                                                    val dotColor = when {
                                                                        isSelected -> colorScheme.onPrimary
                                                                        dotIdx == 0 && hasUrgentOrLate -> colorScheme.error
                                                                        else -> extColors.springBud
                                                                    }
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(4.5.dp)
                                                                            .clip(CircleShape)
                                                                            .background(dotColor),
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
                }

                item(key = "agenda-head") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${uiState.selectedDay.ifEmpty { "全部日期" }} · ${sorted.size} 项",
                            style = MaterialTheme.typography.titleMedium.tabularNums(),
                            modifier = Modifier.weight(1f),
                        )
                        if (uiState.selectedDay.isNotEmpty()) {
                            TextButton(onClick = onClearDay) {
                                Text("显示所有日期")
                            }
                        }
                    }
                }

                if (groups.isEmpty()) {
                    item(key = "empty-calendar") {
                        EmptyScheduleState(
                            title = if (uiState.selectedDay.isNotEmpty()) {
                                "这一天没有日程"
                            } else {
                                "没有符合条件的日程"
                            },
                            hasServer = uiState.snapshot.serverId.isNotEmpty(),
                            onNewTask = { onOpenEditor(null) },
                            onReconnect = onReconnect,
                        )
                    }
                } else {
                    for (group in groups) {
                        if (group.heading.isNotEmpty()) {
                            item(key = "cal-heading-${group.heading}") {
                                GroupHeadingRow(
                                    heading = group.heading,
                                    count = group.tasks.size,
                                )
                            }
                        }
                        items(group.tasks, key = { it.uuid }) { task ->
                            val itemModifier = if (reducedMotion) {
                                Modifier
                            } else {
                                Modifier.animateItem(
                                    placementSpec = spring(dampingRatio = 0.82f, stiffness = 380f),
                                )
                            }
                            TaskRow(
                                task = task,
                                isInFlight = task.uuid in uiState.inFlightUuids,
                                onToggleDone = onToggleDone,
                                onOpenEditor = { onOpenEditor(it) },
                                modifier = itemModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeadingRow(
    heading: String,
    count: Int,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp, start = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall.tabularNums(),
            color = if (heading.contains("逾期")) colorScheme.error else colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${count} 项",
            style = MaterialTheme.typography.bodySmall.tabularNums(),
            color = colorScheme.outline,
        )
    }
}

/**
 * 单行横向滑动筛选芯片轨（内凹冰雾槽）+ 紧凑排序切换胶囊，彻底消除原版双 ExposedDropdownMenu 挤压遮挡。
 */
@Composable
private fun FilterSortBar(
    filter: TaskFilter,
    sort: TaskSort,
    showReset: Boolean,
    onFilterChange: (TaskFilter) -> Unit,
    onSortChange: (TaskSort) -> Unit,
    onResetFilters: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val haptic = LocalHapticFeedback.current

    val sortRotation by animateFloatAsState(
        targetValue = if (sort == TaskSort.TITLE) 180f else 0f,
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "sortIconRotation",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val trayShape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(trayShape)
                .background(extColors.traySurface)
                .border(1.dp, extColors.trayBorder, trayShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(TaskFilter.entries, key = { it.wire }) { option ->
                    val isSelected = option == filter
                    val chipBg by animateColorAsState(
                        targetValue = if (isSelected) colorScheme.primary else colorScheme.surface.copy(alpha = 0f),
                        animationSpec = if (reducedMotion) snap() else tween(180),
                        label = "chipBg-${option.wire}",
                    )
                    val chipFg by animateColorAsState(
                        targetValue = if (isSelected) colorScheme.onPrimary else colorScheme.onSurfaceVariant,
                        animationSpec = if (reducedMotion) snap() else tween(180),
                        label = "chipFg-${option.wire}",
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = chipBg,
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(
                                role = Role.Tab,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onFilterChange(option)
                                },
                            )
                            .semantics {
                                selected = isSelected
                                contentDescription = "筛选：${option.label}"
                            },
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = chipFg,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        val sortShape = RoundedCornerShape(14.dp)
        Surface(
            shape = sortShape,
            color = extColors.cardSurface,
            modifier = Modifier
                .height(42.dp)
                .shadow(
                    elevation = if (extColors.isDark) 2.dp else 4.dp,
                    shape = sortShape,
                    ambientColor = extColors.cardShadowAmbient,
                    spotColor = extColors.cardShadowAmbient,
                )
                .clip(sortShape)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val nextSort = if (sort == TaskSort.DEADLINE) TaskSort.TITLE else TaskSort.DEADLINE
                    onSortChange(nextSort)
                }
                .semantics {
                    contentDescription = "切换排序，当前${sort.label}"
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = AgendumIcons.Sort,
                    contentDescription = null,
                    tint = colorScheme.primary,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = sortRotation },
                )
                Text(
                    text = sort.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyScheduleState(
    title: String,
    hasServer: Boolean,
    onNewTask: () -> Unit,
    onReconnect: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    MistCard(
        cornerRadius = 20.dp,
        elevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (hasServer) {
                    "可以新建日程，或切换上方的筛选条件。"
                } else {
                    "连接电脑同步已有日程，也可以先查看本地记录。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = if (hasServer) onNewTask else onReconnect,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(if (hasServer) "新建日程" else "连接电脑")
            }
        }
    }
}
