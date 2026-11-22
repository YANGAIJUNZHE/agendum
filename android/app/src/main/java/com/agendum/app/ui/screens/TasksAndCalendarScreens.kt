package com.agendum.app.ui.screens

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.agendum.app.model.DateTimeHelper
import com.agendum.app.model.ScheduleRules
import com.agendum.app.model.TaskFilter
import com.agendum.app.model.TaskItem
import com.agendum.app.model.TaskSort
import com.agendum.app.ui.AgendumUiState
import com.agendum.app.ui.components.AgendumIcons
import com.agendum.app.ui.components.TaskRow
import com.agendum.app.ui.theme.LocalAgendumColors
import com.agendum.app.ui.theme.LocalReducedMotion

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TasksScreen(
    uiState: AgendumUiState,
    onSearchChange: (String) -> Unit,
    onFilterChange: (TaskFilter) -> Unit,
    onSortChange: (TaskSort) -> Unit,
    onResetFilters: () -> Unit,
    onRequestSync: () -> Unit,
    onToggleDone: (TaskItem) -> Unit,
    onOpenEditor: (TaskItem?) -> Unit,
    onReconnect: () -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current

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
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchChange,
            placeholder = { Text("搜索日程、备注或群名") },
            leadingIcon = {
                Icon(
                    imageVector = AgendumIcons.Search,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
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
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = extColors.cardSurface,
                unfocusedContainerColor = extColors.cardSurface,
                unfocusedBorderColor = colorScheme.outlineVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        )

        FilterSortRow(
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
        Text(
            text = resultText,
            style = MaterialTheme.typography.bodySmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )

        PullToRefreshBox(
            isRefreshing = uiState.isSyncing,
            onRefresh = onRequestSync,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 108.dp),
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
                                Text(
                                    text = group.heading,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                )
                            }
                        }
                        items(group.tasks, key = { it.uuid }) { task ->
                            val itemModifier = if (reducedMotion) {
                                Modifier
                            } else {
                                Modifier.animateItem()
                            }
                            TaskRow(
                                task = task,
                                isInFlight = task.uuid in uiState.inFlightUuids,
                                onToggleDone = onToggleDone,
                                onOpenEditor = { onOpenEditor(it) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                modifier = itemModifier,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
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
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    val reducedMotion = LocalReducedMotion.current
    val todayKey = remember(uiState.calendarYear, uiState.calendarMonth) {
        DateTimeHelper.todayDateString()
    }

    val filtered = remember(uiState.snapshot.tasks, uiState.filter, uiState.searchQuery) {
        ScheduleRules.filterTasks(uiState.snapshot.tasks, uiState.filter, uiState.searchQuery)
    }
    val dayCounts = remember(filtered) {
        filtered.groupingBy { DateTimeHelper.deadlineDay(it.deadline) }.eachCount()
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
        FilterSortRow(
            filter = uiState.filter,
            sort = uiState.sort,
            showReset = uiState.hasActiveFilterOrSearch,
            onFilterChange = onFilterChange,
            onSortChange = onSortChange,
            onResetFilters = onResetFilters,
        )

        val trimmed = uiState.searchQuery.trim()
        if (trimmed.isNotEmpty()) {
            Text(
                text = "搜索「$trimmed」 · ${uiState.filter.label} · ${filtered.size} 项",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        PullToRefreshBox(
            isRefreshing = uiState.isSyncing,
            onRefresh = onRequestSync,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "calendar-card") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = extColors.cardSurface,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = onPreviousMonth,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        imageVector = AgendumIcons.Back,
                                        contentDescription = "上一月",
                                    )
                                }
                                Text(
                                    text = "${uiState.calendarYear}年${uiState.calendarMonth}月",
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onToday) {
                                    Text("今天")
                                }
                                IconButton(
                                    onClick = onNextMonth,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        imageVector = AgendumIcons.Next,
                                        contentDescription = "下一月",
                                    )
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth()) {
                                listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(vertical = 6.dp),
                                    )
                                }
                            }

                            val leadingBlanks = DateTimeHelper.firstDayOfWeekInMonth(
                                uiState.calendarYear,
                                uiState.calendarMonth,
                            )
                            val daysInMonth = DateTimeHelper.daysInMonth(
                                uiState.calendarYear,
                                uiState.calendarMonth,
                            )
                            val totalCells = leadingBlanks + daysInMonth
                            val rows = (totalCells + 6) / 7

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
                                            val key = DateTimeHelper.formatDateKey(
                                                uiState.calendarYear,
                                                uiState.calendarMonth,
                                                dayNumber,
                                            )
                                            val count = dayCounts[key] ?: 0
                                            val isSelected = key == uiState.selectedDay
                                            val isToday = key == todayKey
                                            Surface(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(horizontal = 2.dp)
                                                    .heightIn(min = 48.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { onSelectDay(key) }
                                                    .semantics {
                                                        contentDescription = "${key}，${count}项日程"
                                                        stateDescription = if (isSelected) "已选中" else "未选中"
                                                    },
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (isSelected) {
                                                    colorScheme.primary
                                                } else {
                                                    extColors.cardSurface
                                                },
                                                border = if (!isSelected && isToday) {
                                                    BorderStroke(1.dp, colorScheme.outline)
                                                } else {
                                                    null
                                                },
                                            ) {
                                                Column(
                                                    modifier = Modifier.padding(vertical = 4.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center,
                                                ) {
                                                    Text(
                                                        text = dayNumber.toString(),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = if (isSelected) {
                                                            colorScheme.onPrimary
                                                        } else {
                                                            colorScheme.onSurface
                                                        },
                                                    )
                                                    Text(
                                                        text = if (count > 0) "${count}项" else "",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (isSelected) {
                                                            colorScheme.onPrimary
                                                        } else {
                                                            colorScheme.onSurfaceVariant
                                                        },
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

                item(key = "agenda-head") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${uiState.selectedDay.ifEmpty { "全部日期" }} · ${sorted.size} 项",
                            style = MaterialTheme.typography.titleMedium,
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
                                Text(
                                    text = group.heading,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        items(group.tasks, key = { it.uuid }) { task ->
                            val itemModifier = if (reducedMotion) {
                                Modifier
                            } else {
                                Modifier.animateItem()
                            }
                            TaskRow(
                                task = task,
                                isInFlight = task.uuid in uiState.inFlightUuids,
                                onToggleDone = onToggleDone,
                                onOpenEditor = { onOpenEditor(it) },
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
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
private fun FilterSortRow(
    filter: TaskFilter,
    sort: TaskSort,
    showReset: Boolean,
    onFilterChange: (TaskFilter) -> Unit,
    onSortChange: (TaskSort) -> Unit,
    onResetFilters: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val extColors = LocalAgendumColors.current
    var filterExpanded by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = filterExpanded,
            onExpandedChange = { filterExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = filter.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = filterExpanded) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = extColors.cardSurface,
                    unfocusedContainerColor = extColors.cardSurface,
                    unfocusedBorderColor = colorScheme.outlineVariant,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics { contentDescription = "筛选日程" },
            )
            ExposedDropdownMenu(
                expanded = filterExpanded,
                onDismissRequest = { filterExpanded = false },
            ) {
                TaskFilter.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onFilterChange(option)
                            filterExpanded = false
                        },
                    )
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = sortExpanded,
            onExpandedChange = { sortExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = sort.label,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortExpanded) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = extColors.cardSurface,
                    unfocusedContainerColor = extColors.cardSurface,
                    unfocusedBorderColor = colorScheme.outlineVariant,
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics { contentDescription = "日程排序" },
            )
            ExposedDropdownMenu(
                expanded = sortExpanded,
                onDismissRequest = { sortExpanded = false },
            ) {
                TaskSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            onSortChange(option)
                            sortExpanded = false
                        },
                    )
                }
            }
        }

        if (showReset) {
            TextButton(
                onClick = onResetFilters,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("清除筛选")
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 40.dp),
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
                "可以新建日程，或调整筛选条件。"
            } else {
                "连接电脑同步已有日程，也可以先查看本地记录。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = if (hasServer) onNewTask else onReconnect,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(if (hasServer) "新建日程" else "连接电脑")
        }
    }
}
