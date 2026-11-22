package com.agendum.app.ui

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.agendum.app.BuildConfig
import com.agendum.app.NetworkEndpoint
import com.agendum.app.gateway.PairExchangeResult
import com.agendum.app.gateway.ScheduleCommand
import com.agendum.app.gateway.ScheduleGateway
import com.agendum.app.model.ConflictItem
import com.agendum.app.model.DateTimeHelper
import com.agendum.app.model.DeadlineKind
import com.agendum.app.model.RepositorySnapshot
import com.agendum.app.model.ScheduleRules
import com.agendum.app.model.TaskCategory
import com.agendum.app.model.TaskFilter
import com.agendum.app.model.TaskItem
import com.agendum.app.model.TaskSort
import com.agendum.app.model.TaskStatus
import com.agendum.app.model.TimeSpec
import com.agendum.app.model.UrgentOverride
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

enum class ClientPage(val title: String) {
    TASKS("日程"),
    CALENDAR("月历"),
    ACCOUNT("我的"),
    SYNC("同步状态"),
    CONNECTION("电脑连接");

    val navDestination: ClientPage
        get() = when (this) {
            SYNC, CONNECTION -> ACCOUNT
            else -> this
        }
}

data class FeedbackMessage(
    val id: Long,
    val text: String,
    val isError: Boolean,
)

data class EditorState(
    val originalTask: TaskItem? = null,
    val isNew: Boolean = true,
    val uuid: String = "",
    val summary: String = "",
    val notes: String = "",
    val deadlineKind: DeadlineKind = DeadlineKind.NONE,
    val deadlineDate: String = "",
    val deadlineDatetime: String = "",
    val category: TaskCategory = TaskCategory.ACTION,
    val status: TaskStatus = TaskStatus.OPEN,
    val pinned: Boolean = false,
    val urgentOverride: UrgentOverride = UrgentOverride.AUTO,
    val snoozeDatetime: String = "",
    val evidence: String = "",
    val details: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val initialFingerprint: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String = "",
    val returnPage: ClientPage = ClientPage.TASKS,
    val showDiscardConfirm: Boolean = false,
    val showDeleteConfirm: Boolean = false,
) {
    fun fingerprint(): String = listOf(
        summary,
        notes,
        deadlineKind.wire,
        deadlineDate,
        deadlineDatetime,
        category.wire,
        status.wire,
        pinned.toString(),
        urgentOverride.wire,
        snoozeDatetime,
    ).joinToString("\u0001")

    val isDirty: Boolean
        get() = fingerprint() != initialFingerprint
}

data class SwitchComputerPrompt(
    val pendingCount: Int,
    val base: String,
    val result: PairExchangeResult,
)

data class PairingUiState(
    val address: String = "",
    val manualExpanded: Boolean = false,
    val statusMessage: String = "",
    val pairCode: String = "",
    val isConnecting: Boolean = false,
    val scanInProgress: Boolean = false,
    val canReturnOffline: Boolean = false,
    val hasLegacySnapshot: Boolean = false,
    val legacySnapshotText: String? = null,
    val switchComputerPrompt: SwitchComputerPrompt? = null,
)

sealed interface UnpairDialogState {
    data class PendingChoices(val pendingCount: Int) : UnpairDialogState
    data object DiscardConfirm : UnpairDialogState
    data object SimpleConfirm : UnpairDialogState
}

data class AgendumUiState(
    val showingClient: Boolean = false,
    val clientPage: ClientPage = ClientPage.TASKS,
    val snapshot: RepositorySnapshot = RepositorySnapshot(),
    val filter: TaskFilter = TaskFilter.ACTIVE,
    val sort: TaskSort = TaskSort.DEADLINE,
    val searchQuery: String = "",
    val calendarYear: Int = 2026,
    val calendarMonth: Int = 1,
    val selectedDay: String = "",
    val isSyncing: Boolean = false,
    val activeSyncRequestId: String = "",
    val inFlightUuids: Set<String> = emptySet(),
    val editor: EditorState? = null,
    val conflictChoices: Map<String, Map<String, Boolean>> = emptyMap(),
    val pairing: PairingUiState = PairingUiState(),
    val unpairDialog: UnpairDialogState? = null,
    val feedback: FeedbackMessage? = null,
) {
    val editorOpen: Boolean get() = editor != null
    val hasActiveFilterOrSearch: Boolean
        get() = filter != TaskFilter.ACTIVE || sort != TaskSort.DEADLINE || searchQuery.isNotEmpty()
    val headerSummary: String
        get() = when (clientPage) {
            ClientPage.TASKS -> "${snapshot.tasks.count { it.isActive }} 项待处理"
            ClientPage.CALENDAR -> "按截止日期安排日程"
            else -> "本地编辑与电脑同步"
        }
}

sealed interface UiEvent {
    data object LaunchScanner : UiEvent
    data class LaunchExportDocument(val suggestedName: String) : UiEvent
}

class ScheduleViewModel(
    private val gateway: ScheduleGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultServer: String = BuildConfig.PAIR_SERVER,
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    private val syncPollIntervalMs: Long = 10_000L,
    private val pairPollIntervalMs: Long = 2_000L,
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val timeZoneProvider: () -> TimeZone = { TimeZone.getDefault() },
) : ViewModel() {

    private data class PendingRequest(
        val uuid: String,
        val fromEditor: Boolean,
    )

    private class SyncRun(
        var requestId: String?,
    )

    private val requestCounter = AtomicInteger(0)
    private val feedbackCounter = AtomicInteger(0)
    private val pendingRequests = mutableMapOf<String, PendingRequest>()
    private var pendingEditorRequestId: String? = null
    private var activeSyncRun: SyncRun? = null

    private var resumed = false
    private var foregroundSyncJob: Job? = null
    private var pairPollJob: Job? = null

    private var activePairingBase: String = ""
    private var activePairId: String? = null
    private var activePairSecret: String? = null
    private var pendingExportContent: String? = null
    private var pendingPairAfterExportBase: String? = null
    private var pendingPairAfterExportResult: PairExchangeResult? = null

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    private val _uiState = MutableStateFlow(initialUiState())
    val uiState: StateFlow<AgendumUiState> = _uiState.asStateFlow()

    private val repositoryListener: () -> Unit = {
        viewModelScope.launch {
            refreshSnapshot()
        }
    }

    init {
        gateway.listen(repositoryListener)
        val server = gateway.credentialsServer()
        if (server.isNotEmpty()) {
            _uiState.update { it.copy(showingClient = true) }
            refreshSnapshot()
        } else {
            showPairingInternal()
        }
        viewModelScope.launch(ioDispatcher) {
            gateway.scheduleSync(false)
        }
    }

    private fun initialUiState(): AgendumUiState {
        val (year, month) = DateTimeHelper.currentYearMonth(nowMillisProvider(), timeZoneProvider())
        return AgendumUiState(
            calendarYear = year,
            calendarMonth = month,
            pairing = buildPairingUiState(),
        )
    }

    private fun buildPairingUiState(
        addressOverride: String? = null,
        manualExpanded: Boolean = false,
        statusMessage: String = "",
        pairCode: String = "",
    ): PairingUiState {
        val server = gateway.credentialsServer()
        val credBase = gateway.credentialsBase()
        val legacyBase = gateway.legacyBase(defaultServer)
        val addr = addressOverride ?: credBase.ifEmpty { legacyBase }
        return PairingUiState(
            address = addr,
            manualExpanded = manualExpanded,
            statusMessage = statusMessage,
            pairCode = pairCode,
            canReturnOffline = server.isNotEmpty(),
            hasLegacySnapshot = server.isEmpty() && gateway.hasLegacySnapshot(),
        )
    }

    private fun nextRequestId(prefix: String): String =
        "$prefix-${requestCounter.incrementAndGet()}"

    fun onResume() {
        gateway.listen(repositoryListener)
        resumed = true
        foregroundSyncJob?.cancel()
        pairPollJob?.cancel()
        if (_uiState.value.showingClient) {
            refreshSnapshot()
            triggerBackgroundSync()
            startForegroundPolling()
        } else if (activePairId != null) {
            schedulePairPoll()
        }
    }

    fun onPause() {
        resumed = false
        foregroundSyncJob?.cancel()
        pairPollJob?.cancel()
    }

    override fun onCleared() {
        gateway.stopListening(repositoryListener)
        foregroundSyncJob?.cancel()
        pairPollJob?.cancel()
        super.onCleared()
    }

    private fun startForegroundPolling() {
        foregroundSyncJob?.cancel()
        foregroundSyncJob = viewModelScope.launch {
            while (isActive && resumed && _uiState.value.showingClient) {
                delay(syncPollIntervalMs)
                if (resumed && _uiState.value.showingClient) {
                    triggerBackgroundSync()
                }
            }
        }
    }

    fun refreshSnapshot() {
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { gateway.snapshot() }
            }
            result.onSuccess { snap ->
                applySnapshot(snap)
            }.onFailure { error ->
                showFeedback(rootCauseMessage(error), isError = true)
            }
        }
    }

    fun applySnapshot(snap: RepositorySnapshot) {
        _uiState.update { state ->
            val updatedChoices = pruneAndDefaultConflictChoices(snap.conflicts, state.conflictChoices)
            state.copy(
                snapshot = snap,
                conflictChoices = updatedChoices,
                pairing = state.pairing.copy(
                    canReturnOffline = snap.serverId.isNotEmpty() || gateway.credentialsServer().isNotEmpty(),
                    hasLegacySnapshot = gateway.credentialsServer().isEmpty() && gateway.hasLegacySnapshot(),
                ),
            )
        }
    }

    private fun pruneAndDefaultConflictChoices(
        conflicts: List<ConflictItem>,
        existing: Map<String, Map<String, Boolean>>,
    ): Map<String, Map<String, Boolean>> {
        if (conflicts.isEmpty()) return emptyMap()
        val next = LinkedHashMap<String, Map<String, Boolean>>(conflicts.size)
        for (conflict in conflicts) {
            val current = conflict.current
            if (current == null || current.deleted) continue
            val prevMap = existing[conflict.operationId].orEmpty()
            val fieldMap = LinkedHashMap<String, Boolean>()
            for (field in conflict.fields) {
                if (field == "uuid") continue
                fieldMap[field] = prevMap[field] ?: true
            }
            next[conflict.operationId] = fieldMap
        }
        return next
    }

    fun showFeedback(text: String, isError: Boolean = false) {
        val msg = FeedbackMessage(
            id = feedbackCounter.incrementAndGet().toLong(),
            text = text,
            isError = isError,
        )
        _uiState.update { it.copy(feedback = msg) }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    // ------------------------------------------------------------------ 导航与返回

    fun selectTab(page: ClientPage) {
        val state = _uiState.value
        if (state.editorOpen) {
            val editor = state.editor!!
            if (editor.isSaving) return
            if (editor.isDirty) {
                _uiState.update { it.copy(editor = editor.copy(showDiscardConfirm = true)) }
                return
            }
            closeEditorAndNavigate(page)
            return
        }
        gotoPage(page)
    }

    fun gotoPage(page: ClientPage) {
        val state = _uiState.value
        if (state.editor?.isSaving == true) return
        _uiState.update { current ->
            var nextYear = current.calendarYear
            var nextMonth = current.calendarMonth
            var nextSelectedDay = current.selectedDay
            if (page == ClientPage.CALENDAR && nextSelectedDay.isEmpty()) {
                val now = nowMillisProvider()
                val tz = timeZoneProvider()
                nextSelectedDay = DateTimeHelper.todayDateString(now, tz)
                val (y, m) = DateTimeHelper.currentYearMonth(now, tz)
                nextYear = y
                nextMonth = m
            }
            current.copy(
                showingClient = true,
                clientPage = page,
                editor = null,
                calendarYear = nextYear,
                calendarMonth = nextMonth,
                selectedDay = nextSelectedDay,
            )
        }
    }

    fun onBackPressed(): Boolean {
        val state = _uiState.value
        if (state.unpairDialog != null) {
            dismissUnpairDialog()
            return true
        }
        if (state.pairing.legacySnapshotText != null) {
            dismissLegacySnapshot()
            return true
        }
        if (state.pairing.switchComputerPrompt != null) {
            dismissSwitchComputerPrompt()
            return true
        }
        if (!state.showingClient) {
            return false
        }
        val editor = state.editor
        if (editor != null) {
            if (editor.isSaving) return true
            if (editor.isDirty) {
                _uiState.update { it.copy(editor = editor.copy(showDiscardConfirm = true)) }
            } else {
                closeEditor()
            }
            return true
        }
        return when (state.clientPage) {
            ClientPage.SYNC, ClientPage.CONNECTION -> {
                gotoPage(ClientPage.ACCOUNT)
                true
            }
            ClientPage.TASKS -> false
            ClientPage.CALENDAR, ClientPage.ACCOUNT -> {
                gotoPage(ClientPage.TASKS)
                true
            }
        }
    }

    // ------------------------------------------------------------------ 列表筛选、排序、月历

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setFilter(filter: TaskFilter) {
        _uiState.update { it.copy(filter = filter) }
    }

    fun setSort(sort: TaskSort) {
        _uiState.update { it.copy(sort = sort) }
    }

    fun resetFilters() {
        _uiState.update {
            it.copy(
                filter = TaskFilter.ACTIVE,
                sort = TaskSort.DEADLINE,
                searchQuery = "",
            )
        }
    }

    fun previousMonth() {
        _uiState.update { state ->
            if (state.calendarMonth == 1) {
                state.copy(calendarYear = state.calendarYear - 1, calendarMonth = 12)
            } else {
                state.copy(calendarMonth = state.calendarMonth - 1)
            }
        }
    }

    fun nextMonth() {
        _uiState.update { state ->
            if (state.calendarMonth == 12) {
                state.copy(calendarYear = state.calendarYear + 1, calendarMonth = 1)
            } else {
                state.copy(calendarMonth = state.calendarMonth + 1)
            }
        }
    }

    fun jumpToToday() {
        val now = nowMillisProvider()
        val tz = timeZoneProvider()
        val (y, m) = DateTimeHelper.currentYearMonth(now, tz)
        _uiState.update {
            it.copy(
                calendarYear = y,
                calendarMonth = m,
                selectedDay = DateTimeHelper.todayDateString(now, tz),
            )
        }
    }

    fun selectDay(dateKey: String) {
        _uiState.update { it.copy(selectedDay = dateKey) }
    }

    fun clearSelectedDay() {
        _uiState.update { it.copy(selectedDay = "") }
    }

    // ------------------------------------------------------------------ 同步

    fun requestSync(): String? {
        val state = _uiState.value
        if (state.isSyncing) return null
        if (state.snapshot.serverId.isEmpty() || !state.snapshot.connected) {
            reconnect()
            return null
        }
        val reqId = nextRequestId("sync")
        _uiState.update { it.copy(isSyncing = true, activeSyncRequestId = reqId) }
        startOrAttachSync(reqId)
        return reqId
    }

    fun triggerBackgroundSync() {
        startOrAttachSync(null)
    }

    private fun startOrAttachSync(requestId: String?) {
        val current = activeSyncRun
        if (current != null) {
            if (requestId != null) {
                current.requestId = requestId
            }
            return
        }
        val run = SyncRun(requestId)
        activeSyncRun = run
        viewModelScope.launch {
            var success = false
            var snapshotResult: RepositorySnapshot? = null
            var errorDetail = ""
            withContext(ioDispatcher) {
                try {
                    success = gateway.sync()
                } catch (t: Throwable) {
                    errorDetail = rootCauseMessage(t)
                }
                try {
                    snapshotResult = gateway.snapshot()
                } catch (t: Throwable) {
                    if (errorDetail.isEmpty()) {
                        errorDetail = rootCauseMessage(t)
                    }
                }
            }
            if (activeSyncRun === run) {
                activeSyncRun = null
            }
            snapshotResult?.let { applySnapshot(it) }
            val finishedReqId = run.requestId
            if (finishedReqId != null) {
                onSyncFinished(finishedReqId, success && snapshotResult != null, errorDetail)
            }
        }
    }

    fun onSyncFinished(requestId: String, success: Boolean, error: String) {
        val state = _uiState.value
        if (!state.isSyncing || requestId != state.activeSyncRequestId) return
        _uiState.update { it.copy(isSyncing = false, activeSyncRequestId = "") }
        val snap = _uiState.value.snapshot
        if (success) {
            val text = when {
                snap.conflicts.isNotEmpty() -> "同步完成，有 ${snap.conflicts.size} 项冲突需要处理。"
                snap.pending > 0 -> "本次同步结束，仍有 ${snap.pending} 项操作待同步。"
                else -> "同步完成"
            }
            showFeedback(text, isError = false)
        } else {
            val friendly = ScheduleRules.friendlySyncError(error.ifEmpty { snap.error })
            showFeedback(
                friendly.ifEmpty { "同步尚未完成，请稍后重试。本地编辑仍保留。" },
                isError = true,
            )
        }
    }

    // ------------------------------------------------------------------ 日程操作与编辑器

    fun toggleTaskQuickStatus(task: TaskItem): String? {
        if (task.uuid in _uiState.value.inFlightUuids) return null
        val targetStatus = if (task.isRestore || task.status == TaskStatus.CANDIDATE) "open" else "done"
        val changes = JSONObject().put("status", targetStatus)
        return sendCommand(
            ScheduleCommand.Edit(
                kind = "update",
                uuid = task.uuid,
                base = task.observedJson(),
                changes = changes,
            ),
            fromEditor = false,
        )
    }

    fun openEditor(task: TaskItem? = null) {
        val state = _uiState.value
        if (task == null && state.snapshot.serverId.isEmpty()) {
            showFeedback("请先连接电脑，完成首次配对。", isError = true)
            return
        }
        if (task != null && task.uuid in state.inFlightUuids) {
            showFeedback("这条日程正在保存，请稍后再编辑。", isError = false)
            return
        }
        val tz = timeZoneProvider()
        val initialDeadline = when {
            task != null -> task.deadline
            state.clientPage == ClientPage.CALENDAR && state.selectedDay.isNotEmpty() ->
                TimeSpec(DeadlineKind.DATE, state.selectedDay)
            else -> TimeSpec.NONE
        }
        val draft = if (task == null) {
            EditorState(
                originalTask = null,
                isNew = true,
                uuid = "",
                deadlineKind = initialDeadline.kind,
                deadlineDate = if (initialDeadline.kind == DeadlineKind.DATE) initialDeadline.value else "",
                returnPage = state.clientPage,
            )
        } else {
            EditorState(
                originalTask = task,
                isNew = false,
                uuid = task.uuid,
                summary = task.summary,
                notes = task.notes,
                deadlineKind = task.deadline.kind,
                deadlineDate = if (task.deadline.kind == DeadlineKind.DATE) task.deadline.value else "",
                deadlineDatetime = if (task.deadline.kind == DeadlineKind.DATETIME) {
                    DateTimeHelper.formatLocalInput(task.deadline.value, tz)
                } else {
                    ""
                },
                category = task.category,
                status = task.status,
                pinned = task.pinned,
                urgentOverride = UrgentOverride.fromBool(task.urgentOverride),
                snoozeDatetime = DateTimeHelper.formatLocalInput(task.snoozeUntil.value, tz),
                evidence = task.evidence,
                details = task.details,
                groups = task.groups,
                returnPage = state.clientPage,
            )
        }
        val withFingerprint = draft.copy(initialFingerprint = draft.fingerprint())
        _uiState.update { it.copy(editor = withFingerprint) }
    }

    fun updateEditor(transform: (EditorState) -> EditorState) {
        _uiState.update { state ->
            val current = state.editor ?: return@update state
            state.copy(editor = transform(current))
        }
    }

    fun requestLeaveEditor() {
        val editor = _uiState.value.editor ?: return
        if (editor.isSaving) return
        if (editor.isDirty) {
            _uiState.update { it.copy(editor = editor.copy(showDiscardConfirm = true)) }
        } else {
            closeEditor()
        }
    }

    fun dismissDiscardConfirm() {
        updateEditor { it.copy(showDiscardConfirm = false) }
    }

    fun confirmDiscardEditor() {
        closeEditor()
    }

    fun requestDeleteInEditor() {
        val editor = _uiState.value.editor ?: return
        if (editor.isSaving || editor.isNew) return
        _uiState.update { it.copy(editor = editor.copy(showDeleteConfirm = true)) }
    }

    fun dismissDeleteConfirm() {
        updateEditor { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDeleteInEditor(): String? {
        val editor = _uiState.value.editor ?: return null
        if (editor.isSaving || editor.isNew) return null
        _uiState.update {
            it.copy(editor = editor.copy(showDeleteConfirm = false, isSaving = true, errorMessage = ""))
        }
        val base = (editor.originalTask ?: TaskItem(uuid = editor.uuid)).observedJson()
        return sendCommand(
            ScheduleCommand.Edit(
                kind = "delete",
                uuid = editor.uuid,
                base = base,
                changes = JSONObject(),
            ),
            fromEditor = true,
        )
    }

    fun saveEditor(): String? {
        val editor = _uiState.value.editor ?: return null
        if (editor.isSaving) return null
        val tz = timeZoneProvider()
        val summary = editor.summary.trim()
        if (summary.isEmpty()) {
            updateEditor { it.copy(errorMessage = "请填写待办内容。") }
            return null
        }
        val deadline = try {
            when (editor.deadlineKind) {
                DeadlineKind.NONE -> TimeSpec.NONE
                DeadlineKind.DATE -> {
                    if (editor.deadlineDate.isBlank()) {
                        updateEditor { it.copy(errorMessage = "请选择截止日期") }
                        return null
                    }
                    TimeSpec(DeadlineKind.DATE, editor.deadlineDate.trim())
                }
                DeadlineKind.DATETIME -> {
                    if (editor.deadlineDatetime.isBlank()) {
                        updateEditor { it.copy(errorMessage = "请选择截止时刻") }
                        return null
                    }
                    DateTimeHelper.toZonedIso(editor.deadlineDatetime, tz)
                }
            }
        } catch (error: IllegalArgumentException) {
            updateEditor { it.copy(errorMessage = error.message ?: "请选择有效时刻") }
            return null
        }

        val finished = editor.status.isFinished
        val pinned = if (finished) false else editor.pinned
        val snoozeUntil = if (finished) {
            TimeSpec.NONE
        } else {
            try {
                DateTimeHelper.toZonedIso(editor.snoozeDatetime, tz)
            } catch (error: IllegalArgumentException) {
                updateEditor { it.copy(errorMessage = error.message ?: "请选择有效时刻") }
                return null
            }
        }

        val baseTask = editor.originalTask ?: TaskItem()
        val changes = JSONObject()
        fun putIfChanged(key: String, newValue: Any?, oldValue: Any?, jsonValue: Any) {
            if (editor.isNew || newValue != oldValue) {
                changes.put(key, jsonValue)
            }
        }

        putIfChanged("summary", summary, baseTask.summary, summary)
        putIfChanged("notes", editor.notes, baseTask.notes, editor.notes)
        putIfChanged("deadline", deadline, baseTask.deadline, deadline.toJson())
        putIfChanged("category", editor.category, baseTask.category, editor.category.wire)
        putIfChanged("status", editor.status, baseTask.status, editor.status.wire)
        putIfChanged("pinned", pinned, baseTask.pinned, pinned)
        putIfChanged(
            "urgent_override",
            editor.urgentOverride.boolValue,
            baseTask.urgentOverride,
            editor.urgentOverride.boolValue ?: JSONObject.NULL,
        )
        putIfChanged("snooze_until", snoozeUntil, baseTask.snoozeUntil, snoozeUntil.toJson())

        if (changes.length() == 0) {
            closeEditor()
            return null
        }

        updateEditor { it.copy(errorMessage = "", isSaving = true) }
        return sendCommand(
            ScheduleCommand.Edit(
                kind = if (editor.isNew) "create" else "update",
                uuid = editor.uuid,
                base = baseTask.observedJson(),
                changes = changes,
            ),
            fromEditor = true,
        )
    }

    private fun closeEditor() {
        _uiState.update { state ->
            val returnPage = state.editor?.returnPage ?: state.clientPage
            state.copy(editor = null, clientPage = returnPage)
        }
    }

    private fun closeEditorAndNavigate(target: ClientPage) {
        _uiState.update { it.copy(editor = null) }
        gotoPage(target)
    }

    // ------------------------------------------------------------------ 冲突处理

    fun setConflictChoice(operationId: String, field: String, useLocal: Boolean) {
        _uiState.update { state ->
            val updated = state.conflictChoices.toMutableMap()
            val perOp = (updated[operationId] ?: emptyMap()).toMutableMap()
            perOp[field] = useLocal
            updated[operationId] = perOp
            state.copy(conflictChoices = updated)
        }
    }

    fun resolveConflict(conflict: ConflictItem, saveCopy: Boolean): String? {
        if (conflict.uuid in _uiState.value.inFlightUuids) return null
        val choices = if (saveCopy) {
            emptyMap()
        } else {
            val saved = _uiState.value.conflictChoices[conflict.operationId].orEmpty()
            conflict.fields
                .filter { it != "uuid" }
                .associateWith { field -> saved[field] ?: true }
        }
        return sendCommand(
            ScheduleCommand.Resolve(
                uuid = conflict.uuid,
                choices = choices,
                saveCopy = saveCopy,
            ),
            fromEditor = false,
        )
    }

    fun discardConflict(conflict: ConflictItem): String? {
        if (conflict.uuid in _uiState.value.inFlightUuids) return null
        return sendCommand(ScheduleCommand.Discard(conflict.uuid), fromEditor = false)
    }

    // ------------------------------------------------------------------ 命令回执与防串台

    private fun sendCommand(command: ScheduleCommand, fromEditor: Boolean): String {
        val requestId = nextRequestId("cmd")
        pendingRequests[requestId] = PendingRequest(command.uuid, fromEditor)
        if (fromEditor) {
            pendingEditorRequestId = requestId
        }
        updateInFlightUuids()
        viewModelScope.launch {
            val outcome = withContext(ioDispatcher) {
                runCatching {
                    val snap = gateway.executeCommand(command)
                    gateway.scheduleSync(true)
                    snap
                }
            }
            outcome.onSuccess { snap ->
                onCommandSaved(requestId, snap)
                triggerBackgroundSync()
            }.onFailure { error ->
                onCommandError(requestId, rootCauseMessage(error))
            }
        }
        return requestId
    }

    private fun updateInFlightUuids() {
        val uuids = pendingRequests.values.mapNotNull { it.uuid.takeIf(String::isNotEmpty) }.toSet()
        _uiState.update { it.copy(inFlightUuids = uuids) }
    }

    fun onCommandSaved(requestId: String, snap: RepositorySnapshot? = null) {
        val request = if (requestId.isEmpty()) null else pendingRequests.remove(requestId)
        if (requestId.isNotEmpty() && request == null) return
        updateInFlightUuids()
        if (snap != null) {
            applySnapshot(snap)
        }

        val currentState = _uiState.value
        val editor = currentState.editor
        if (editor != null && (
                (requestId.isEmpty() && editor.isSaving) ||
                    (request?.fromEditor == true && pendingEditorRequestId == requestId)
                )
        ) {
            pendingEditorRequestId = null
            val wasCreated = editor.isNew
            val returnPage = editor.returnPage
            val tz = timeZoneProvider()
            _uiState.update { state ->
                var nextFilter = state.filter
                var nextSearch = state.searchQuery
                var nextSort = state.sort
                var nextSelectedDay = state.selectedDay
                var nextYear = state.calendarYear
                var nextMonth = state.calendarMonth
                if (wasCreated) {
                    nextFilter = TaskFilter.ACTIVE
                    nextSearch = ""
                    nextSort = TaskSort.DEADLINE
                    if (returnPage == ClientPage.CALENDAR) {
                        val createdSpec = when (editor.deadlineKind) {
                            DeadlineKind.DATE -> TimeSpec(DeadlineKind.DATE, editor.deadlineDate)
                            DeadlineKind.DATETIME -> runCatching {
                                DateTimeHelper.toZonedIso(editor.deadlineDatetime, tz)
                            }.getOrDefault(TimeSpec.NONE)
                            DeadlineKind.NONE -> TimeSpec.NONE
                        }
                        val dayKey = DateTimeHelper.deadlineDay(createdSpec, tz)
                        if (dayKey.isNotEmpty()) {
                            nextSelectedDay = dayKey
                            DateTimeHelper.parseYearMonthFromDate(dayKey)?.let { (y, m) ->
                                nextYear = y
                                nextMonth = m
                            }
                        }
                    }
                }
                state.copy(
                    editor = null,
                    clientPage = returnPage,
                    filter = nextFilter,
                    searchQuery = nextSearch,
                    sort = nextSort,
                    selectedDay = nextSelectedDay,
                    calendarYear = nextYear,
                    calendarMonth = nextMonth,
                )
            }
        }
        val pendingCount = _uiState.value.snapshot.pending
        val suffix = if (pendingCount > 0) "，连接电脑后同步" else ""
        showFeedback("已保存到手机$suffix", isError = false)
    }

    fun onCommandError(requestId: String, text: String) {
        val request = if (requestId.isEmpty()) null else pendingRequests.remove(requestId)
        if (requestId.isNotEmpty() && request == null) return
        updateInFlightUuids()

        val state = _uiState.value
        val editor = state.editor
        if (editor != null && (
                (requestId.isEmpty() && editor.isSaving) ||
                    (request?.fromEditor == true && pendingEditorRequestId == requestId)
                )
        ) {
            pendingEditorRequestId = null
            _uiState.update {
                it.copy(
                    editor = editor.copy(
                        isSaving = false,
                        errorMessage = "${text}，输入已保留。",
                    ),
                )
            }
        } else {
            showFeedback(text, isError = true)
        }
    }

    // ------------------------------------------------------------------ 配对与连接

    fun showPairing() {
        showPairingInternal()
    }

    private fun showPairingInternal(statusOverride: String = "", manualExpanded: Boolean = false) {
        activePairId = null
        activePairSecret = null
        foregroundSyncJob?.cancel()
        pairPollJob?.cancel()
        val hasLegacyToken = gateway.credentialsServer().isEmpty() && gateway.hasLegacyToken()
        val status = when {
            statusOverride.isNotEmpty() -> statusOverride
            hasLegacyToken -> "本版需要重新配对。旧快照仍保留；请在电脑设置中打开「手机日程同步」"
            else -> ""
        }
        _uiState.update { state ->
            state.copy(
                showingClient = false,
                editor = null,
                unpairDialog = null,
                pairing = buildPairingUiState(
                    manualExpanded = manualExpanded || status.isNotEmpty(),
                    statusMessage = status,
                ),
            )
        }
    }

    fun returnToOfflineClient() {
        if (gateway.credentialsServer().isEmpty() && _uiState.value.snapshot.serverId.isEmpty()) return
        activePairId = null
        activePairSecret = null
        pairPollJob?.cancel()
        _uiState.update { it.copy(showingClient = true) }
        refreshSnapshot()
        if (resumed) {
            triggerBackgroundSync()
            startForegroundPolling()
        }
    }

    fun updatePairingAddress(address: String) {
        _uiState.update { it.copy(pairing = it.pairing.copy(address = address)) }
    }

    fun toggleManualPairing() {
        _uiState.update {
            it.copy(pairing = it.pairing.copy(manualExpanded = !it.pairing.manualExpanded))
        }
    }

    private fun setPairingStatus(text: String, pairCode: String? = null) {
        _uiState.update { state ->
            if (state.showingClient) return@update state
            state.copy(
                pairing = state.pairing.copy(
                    manualExpanded = true,
                    statusMessage = text,
                    pairCode = pairCode ?: state.pairing.pairCode,
                    isConnecting = false,
                ),
            )
        }
    }

    fun startManualPairing() {
        val rawAddress = _uiState.value.pairing.address
        val base = NetworkEndpoint.normalize(rawAddress)
        if (base.isEmpty()) {
            setPairingStatus("请填写 HTTPS 地址，例如 https://sync.example.com")
            return
        }
        pairPollJob?.cancel()
        activePairId = null
        activePairSecret = null
        _uiState.update {
            it.copy(
                pairing = it.pairing.copy(
                    manualExpanded = true,
                    statusMessage = "正在连接电脑…",
                    isConnecting = true,
                ),
            )
        }
        viewModelScope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    val protocol = gateway.fetchInfoProtocol(base)
                    if (protocol != 1) {
                        throw Exception("电脑版本不支持离线同步，请升级电脑端")
                    }
                    gateway.startPairing(base, deviceName.ifEmpty { "安卓手机" })
                }
            }
            result.onSuccess { ticket ->
                if (_uiState.value.showingClient) return@onSuccess
                activePairingBase = base
                activePairId = ticket.id
                activePairSecret = ticket.secret
                setPairingStatus(
                    text = "请在电脑「手机日程同步」中核对数字并允许连接",
                    pairCode = ticket.code,
                )
                schedulePairPoll()
            }.onFailure { error ->
                setPairingStatus(rootCauseMessage(error))
            }
        }
    }

    private fun schedulePairPoll() {
        pairPollJob?.cancel()
        val id = activePairId ?: return
        val secret = activePairSecret ?: return
        val base = activePairingBase
        if (!resumed || _uiState.value.showingClient) return
        pairPollJob = viewModelScope.launch {
            delay(pairPollIntervalMs)
            if (!resumed || _uiState.value.showingClient || activePairId != id) return@launch
            exchangePairing(base, id, secret)
        }
    }

    private fun exchangePairing(base: String, ident: String, secret: String) {
        viewModelScope.launch {
            val outcome = withContext(ioDispatcher) {
                runCatching {
                    val res = gateway.exchangePairing(base, ident, secret)
                    val pending = if (res.status == "approved") gateway.pendingCount() else 0
                    res to pending
                }
            }
            outcome.onSuccess { (res, pending) ->
                if (res.status != "approved") {
                    if (!_uiState.value.showingClient && resumed && activePairId == ident) {
                        schedulePairPoll()
                    }
                    return@onSuccess
                }
                val currentServer = gateway.credentialsServer()
                if (currentServer.isNotEmpty() && currentServer != res.serverId && pending > 0) {
                    _uiState.update { state ->
                        state.copy(
                            pairing = state.pairing.copy(
                                isConnecting = false,
                                switchComputerPrompt = SwitchComputerPrompt(pending, base, res),
                            ),
                        )
                    }
                } else {
                    acceptPair(base, res)
                }
            }.onFailure { error ->
                setPairingStatus(rootCauseMessage(error))
            }
        }
    }

    fun handleIncomingLink(rawLink: String?): Boolean {
        if (rawLink.isNullOrBlank()) return false
        val parsed = runCatching { parseConnectUri(rawLink) }.getOrNull() ?: return false
        if (!parsed.isConnectUri) return false
        if (gateway.credentialsToken().isNotEmpty()) {
            if (!_uiState.value.showingClient) {
                returnToOfflineClient()
            }
            showFeedback("已连接，如需换电脑请先在连接页解绑", isError = false)
            return true
        }
        val base = NetworkEndpoint.normalize(parsed.base)
        val ident = parsed.pairId
        val secret = parsed.pairSecret
        showPairingInternal()
        if (base.isEmpty() || ident.isNullOrBlank() || secret.isNullOrBlank()) {
            setPairingStatus("这是旧版或无效二维码，请在电脑「手机日程同步」中重新生成")
            return true
        }
        activePairingBase = base
        activePairId = ident
        activePairSecret = secret
        setPairingStatus("正在交换一次性配对凭据…")
        exchangePairing(base, ident, secret)
        return true
    }

    fun startQrScan() {
        if (_uiState.value.pairing.scanInProgress) return
        _uiState.update { it.copy(pairing = it.pairing.copy(scanInProgress = true)) }
        _events.trySend(UiEvent.LaunchScanner)
    }

    fun onScanLaunchFailed() {
        _uiState.update {
            it.copy(
                pairing = it.pairing.copy(
                    scanInProgress = false,
                    manualExpanded = true,
                    statusMessage = "无法打开扫码器，请允许相机权限或手动填写地址",
                ),
            )
        }
    }

    fun onScanResult(contents: String?) {
        _uiState.update { it.copy(pairing = it.pairing.copy(scanInProgress = false)) }
        if (contents == null) {
            setPairingStatus("扫码未完成，可重试或手动填写地址")
            return
        }
        try {
            if (!handleIncomingLink(contents)) {
                setPairingStatus("这不是Agendum的配对二维码")
            }
        } catch (_: RuntimeException) {
            setPairingStatus("二维码无法读取，请重新生成")
        }
    }

    fun dismissSwitchComputerPrompt() {
        _uiState.update {
            it.copy(pairing = it.pairing.copy(switchComputerPrompt = null))
        }
    }

    fun exportAndSwitchComputer() {
        val prompt = _uiState.value.pairing.switchComputerPrompt ?: return
        pendingPairAfterExportBase = prompt.base
        pendingPairAfterExportResult = prompt.result
        dismissSwitchComputerPrompt()
        exportPending()
    }

    fun discardAndSwitchComputer() {
        val prompt = _uiState.value.pairing.switchComputerPrompt ?: return
        dismissSwitchComputerPrompt()
        viewModelScope.launch {
            withContext(ioDispatcher) {
                gateway.discardAll()
            }
            acceptPair(prompt.base, prompt.result)
        }
    }

    private fun acceptPair(base: String, result: PairExchangeResult) {
        viewModelScope.launch {
            val saved = withContext(ioDispatcher) {
                runCatching {
                    gateway.saveCredentials(base, result.serverId, result.token)
                    gateway.clearLegacyToken()
                    gateway.scheduleSync(true)
                }
            }
            saved.onSuccess {
                activePairId = null
                activePairSecret = null
                pairPollJob?.cancel()
                _uiState.update {
                    it.copy(
                        showingClient = true,
                        clientPage = ClientPage.TASKS,
                        editor = null,
                    )
                }
                refreshSnapshot()
                if (resumed) {
                    triggerBackgroundSync()
                    startForegroundPolling()
                }
                showFeedback("已连接，正在同步日程", isError = false)
            }.onFailure { error ->
                setPairingStatus(rootCauseMessage(error))
            }
        }
    }

    fun openLegacySnapshot() {
        viewModelScope.launch {
            val text = withContext(ioDispatcher) {
                runCatching { gateway.readLegacySnapshotText() }
            }
            text.onSuccess { content ->
                _uiState.update {
                    it.copy(pairing = it.pairing.copy(legacySnapshotText = content))
                }
            }.onFailure {
                showFeedback("无法读取旧快照；原文件仍保留，请先重新配对", isError = true)
            }
        }
    }

    fun dismissLegacySnapshot() {
        _uiState.update { it.copy(pairing = it.pairing.copy(legacySnapshotText = null)) }
    }

    // ------------------------------------------------------------------ 导出与解绑

    fun exportPending() {
        viewModelScope.launch {
            val exported = withContext(ioDispatcher) {
                runCatching { gateway.exportPending() }
            }
            exported.onSuccess { text ->
                pendingExportContent = text
                _events.trySend(UiEvent.LaunchExportDocument("Agendum-未同步编辑.json"))
            }.onFailure { error ->
                pendingPairAfterExportBase = null
                pendingPairAfterExportResult = null
                showFeedback(rootCauseMessage(error), isError = true)
            }
        }
    }

    fun consumePendingExportContent(): String? = pendingExportContent

    fun onExportCompleted(success: Boolean, errorMessage: String = "") {
        val text = pendingExportContent
        pendingExportContent = null
        val pairBase = pendingPairAfterExportBase
        val pairResult = pendingPairAfterExportResult
        pendingPairAfterExportBase = null
        pendingPairAfterExportResult = null

        if (!success || text == null) {
            if (errorMessage.isNotEmpty()) {
                showFeedback(errorMessage, isError = true)
            }
            return
        }
        showFeedback("已导出未同步编辑", isError = false)
        if (pairBase != null && pairResult != null) {
            acceptPair(pairBase, pairResult)
        }
    }

    fun onExportLaunchFailed() {
        pendingExportContent = null
        pendingPairAfterExportBase = null
        pendingPairAfterExportResult = null
        showFeedback("无法打开文件选择器，请先保留本地数据", isError = true)
    }

    fun reconnect() {
        if (gateway.credentialsToken().isEmpty()) {
            showPairingInternal()
            return
        }
        viewModelScope.launch {
            val pending = withContext(ioDispatcher) { gateway.pendingCount() }
            _uiState.update {
                it.copy(
                    unpairDialog = if (pending > 0) {
                        UnpairDialogState.PendingChoices(pending)
                    } else {
                        UnpairDialogState.SimpleConfirm
                    },
                )
            }
        }
    }

    fun dismissUnpairDialog() {
        _uiState.update { it.copy(unpairDialog = null) }
    }

    fun unpairKeepingLocalEdits() {
        _uiState.update { it.copy(unpairDialog = null) }
        gateway.unpairCredentials()
        showPairingInternal()
    }

    fun unpairExportFirst() {
        _uiState.update { it.copy(unpairDialog = null) }
        exportPending()
    }

    fun requestUnpairDiscardConfirm() {
        _uiState.update { it.copy(unpairDialog = UnpairDialogState.DiscardConfirm) }
    }

    fun confirmUnpairDiscardAll() {
        _uiState.update { it.copy(unpairDialog = null) }
        viewModelScope.launch {
            withContext(ioDispatcher) {
                gateway.discardAll()
                gateway.unpairCredentials()
            }
            showPairingInternal()
        }
    }

    // ------------------------------------------------------------------ 辅助

    private data class ParsedConnectUri(
        val isConnectUri: Boolean,
        val base: String?,
        val pairId: String?,
        val pairSecret: String?,
    )

    private fun parseConnectUri(raw: String): ParsedConnectUri {
        val uri = URI(raw)
        val isConnect = "agendum".equals(uri.scheme, ignoreCase = true) &&
            "connect".equals(uri.host, ignoreCase = true)
        if (!isConnect) return ParsedConnectUri(false, null, null, null)
        val params = mutableMapOf<String, String>()
        val query = uri.rawQuery.orEmpty()
        if (query.isNotEmpty()) {
            for (part in query.split('&')) {
                val eq = part.indexOf('=')
                if (eq >= 0) {
                    val key = URLDecoder.decode(part.substring(0, eq), "UTF-8")
                    val value = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                    params[key] = value
                } else if (part.isNotEmpty()) {
                    params[URLDecoder.decode(part, "UTF-8")] = ""
                }
            }
        }
        return ParsedConnectUri(
            isConnectUri = true,
            base = params["base"],
            pairId = params["pair_id"],
            pairSecret = params["pair_secret"],
        )
    }

    private fun rootCauseMessage(error: Throwable): String {
        var cause: Throwable = error
        while (cause.cause != null) {
            cause = cause.cause!!
        }
        return cause.message ?: "操作未完成，请重试"
    }

    class Factory(
        private val gateway: ScheduleGateway,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ScheduleViewModel(gateway = gateway) as T
        }
    }
}
