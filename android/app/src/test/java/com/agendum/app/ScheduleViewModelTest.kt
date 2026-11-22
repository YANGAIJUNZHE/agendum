package com.agendum.app

import com.agendum.app.gateway.PairExchangeResult
import com.agendum.app.gateway.PairTicket
import com.agendum.app.gateway.ScheduleCommand
import com.agendum.app.gateway.ScheduleGateway
import com.agendum.app.model.ConflictItem
import com.agendum.app.model.DateTimeHelper
import com.agendum.app.model.DeadlineKind
import com.agendum.app.model.RepositorySnapshot
import com.agendum.app.model.ScheduleRules
import com.agendum.app.model.TaskFilter
import com.agendum.app.model.TaskItem
import com.agendum.app.model.TaskSort
import com.agendum.app.model.TaskStatus
import com.agendum.app.model.TimeSpec
import com.agendum.app.ui.ClientPage
import com.agendum.app.ui.ScheduleViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleTask(
        uuid: String = "00000000-0000-4000-8000-000000000001",
        summary: String = "交实验报告",
        notes: String = "核对实验数据",
        status: TaskStatus = TaskStatus.OPEN,
    ): TaskItem {
        val today = DateTimeHelper.todayDateString()
        return TaskItem(
            uuid = uuid,
            version = 1L,
            summary = summary,
            notes = notes,
            deadline = TimeSpec(DeadlineKind.DATE, today),
            status = status,
        )
    }

    private fun connectedGateway(
        tasks: List<TaskItem> = listOf(sampleTask()),
        conflicts: List<ConflictItem> = emptyList(),
        pending: Int = 0,
    ): FakeScheduleGateway {
        return FakeScheduleGateway(
            server = "isolated-test",
            base = "https://example.invalid",
            token = "device-token",
            initialSnapshot = RepositorySnapshot(
                tasks = tasks,
                conflicts = conflicts,
                pending = pending,
                serverId = "isolated-test",
                connected = true,
                base = "https://example.invalid",
                lastSuccess = 1L,
            ),
        )
    }

    @Test
    fun syncShortcutTracksCompletionAndPreventsDuplicates() = runTest(testDispatcher) {
        val gateway = connectedGateway()
        val firstGate = CompletableDeferred<Boolean>()
        val secondGate = CompletableDeferred<Boolean>()
        gateway.syncQueue.addLast(firstGate)
        gateway.syncQueue.addLast(secondGate)

        val vm = ScheduleViewModel(gateway, ioDispatcher = testDispatcher)
        runCurrent()

        val firstId = vm.requestSync()
        assertNotNull(firstId)
        runCurrent()

        assertEquals(ClientPage.TASKS, vm.uiState.value.clientPage)
        assertTrue(vm.uiState.value.isSyncing)
        assertEquals(1, gateway.syncCallCount)

        // 同步中再次点击，不得发起第二次同步。
        assertNull(vm.requestSync())
        runCurrent()
        assertEquals(1, gateway.syncCallCount)

        // 列表刷新与错误 requestId 回执不得提前结束同步状态。
        vm.applySnapshot(gateway.currentSnapshot)
        vm.onSyncFinished("wrong-id", true, "")
        assertTrue(vm.uiState.value.isSyncing)

        // 匹配 firstId 完成后解除锁定。
        firstGate.complete(true)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSyncing)
        assertEquals("同步完成", vm.uiState.value.feedback?.text)

        // 第二次同步失败，应留在当前页并提示「本地编辑已保留」。
        val secondId = vm.requestSync()
        assertNotNull(secondId)
        assertNotEquals(firstId, secondId)
        runCurrent()
        assertEquals(2, gateway.syncCallCount)

        gateway.currentSnapshot = gateway.currentSnapshot.copy(error = "network error")
        secondGate.complete(false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSyncing)
        assertEquals(ClientPage.TASKS, vm.uiState.value.clientPage)
        assertTrue(vm.uiState.value.feedback?.isError == true)
        assertTrue(
            "失败提示必须包含「本地编辑已保留」，实际=${vm.uiState.value.feedback?.text}",
            vm.uiState.value.feedback?.text?.contains("本地编辑已保留") == true,
        )

        // 失败后可继续发起第三次同步。
        val thirdGate = CompletableDeferred<Boolean>()
        gateway.syncQueue.addLast(thirdGate)
        val thirdId = vm.requestSync()
        assertNotNull(thirdId)
        runCurrent()
        assertEquals(3, gateway.syncCallCount)
        thirdGate.complete(true)
        advanceUntilIdle()
    }

    @Test
    fun navigationSearchCalendarAndBackSemantics() = runTest(testDispatcher) {
        val gateway = connectedGateway()
        val vm = ScheduleViewModel(gateway, ioDispatcher = testDispatcher)
        runCurrent()

        assertEquals(ClientPage.TASKS, vm.uiState.value.clientPage)

        vm.setSearchQuery("找不到的任务")
        val emptySearch = ScheduleRules.filterTasks(
            vm.uiState.value.snapshot.tasks,
            vm.uiState.value.filter,
            vm.uiState.value.searchQuery,
        )
        assertTrue(emptySearch.isEmpty())

        vm.resetFilters()
        val restored = ScheduleRules.filterTasks(
            vm.uiState.value.snapshot.tasks,
            vm.uiState.value.filter,
            vm.uiState.value.searchQuery,
        )
        assertEquals(1, restored.size)
        assertEquals("交实验报告", restored[0].summary)

        vm.gotoPage(ClientPage.CALENDAR)
        assertEquals(ClientPage.CALENDAR, vm.uiState.value.clientPage)
        val currentYear = vm.uiState.value.calendarYear
        val currentMonth = vm.uiState.value.calendarMonth
        vm.previousMonth()
        assertTrue(
            vm.uiState.value.calendarYear != currentYear ||
                vm.uiState.value.calendarMonth != currentMonth,
        )
        vm.jumpToToday()
        assertEquals(currentYear, vm.uiState.value.calendarYear)
        assertEquals(currentMonth, vm.uiState.value.calendarMonth)

        vm.openEditor(null)
        assertTrue(vm.uiState.value.editorOpen)
        assertTrue(vm.onBackPressed())
        assertFalse(vm.uiState.value.editorOpen)
        assertEquals(ClientPage.CALENDAR, vm.uiState.value.clientPage)

        vm.gotoPage(ClientPage.ACCOUNT)
        vm.gotoPage(ClientPage.CONNECTION)
        assertEquals(ClientPage.CONNECTION, vm.uiState.value.clientPage)

        assertTrue(vm.onBackPressed())
        assertEquals(ClientPage.ACCOUNT, vm.uiState.value.clientPage)
        assertTrue(vm.onBackPressed())
        assertEquals(ClientPage.TASKS, vm.uiState.value.clientPage)
        assertFalse(vm.onBackPressed())
    }

    @Test
    fun editorDraftSurvivesUnrelatedCallbackAndSaveFailureKeepsInput() = runTest(testDispatcher) {
        val task = sampleTask()
        val gateway = connectedGateway(listOf(task))
        val firstCommandGate = CompletableDeferred<Result<RepositorySnapshot>>()
        gateway.commandResults.addLast(firstCommandGate)

        val vm = ScheduleViewModel(gateway, ioDispatcher = testDispatcher)
        runCurrent()

        val priorId = vm.toggleTaskQuickStatus(task)
        assertNotNull(priorId)
        runCurrent()
        assertEquals(1, gateway.recordedCommands.size)

        vm.openEditor(null)
        vm.updateEditor { it.copy(summary = "新建的草稿", notes = "草稿备注") }

        // 先前操作完成与后台快照刷新都不应关闭当前编辑草稿。
        vm.applySnapshot(gateway.currentSnapshot)
        firstCommandGate.complete(Result.success(gateway.currentSnapshot))
        runCurrent()

        assertTrue(vm.uiState.value.editorOpen)
        assertEquals("新建的草稿", vm.uiState.value.editor?.summary)
        assertEquals("草稿备注", vm.uiState.value.editor?.notes)

        // 保存新建草稿并模拟底层失败。
        val saveGate = CompletableDeferred<Result<RepositorySnapshot>>()
        gateway.commandResults.addLast(saveGate)
        val saveId = vm.saveEditor()
        assertNotNull(saveId)
        runCurrent()

        assertEquals(2, gateway.recordedCommands.size)
        val sent = gateway.recordedCommands[1] as ScheduleCommand.Edit
        assertEquals("create", sent.kind)
        assertEquals("草稿备注", sent.changes.getString("notes"))

        saveGate.complete(Result.failure(Exception("模拟保存失败")))
        runCurrent()

        val editorAfterFail = vm.uiState.value.editor
        assertNotNull(editorAfterFail)
        assertFalse(editorAfterFail!!.isSaving)
        assertTrue(
            "失败提示必须包含「输入已保留」，实际=${editorAfterFail.errorMessage}",
            editorAfterFail.errorMessage.contains("输入已保留"),
        )
        assertEquals("新建的草稿", editorAfterFail.summary)
        assertEquals("草稿备注", editorAfterFail.notes)
    }

    @Test
    fun conflictChoiceMapsServerSelectionToFalseAndPreservesAcrossRefresh() = runTest(testDispatcher) {
        val task = sampleTask()
        val local = task.copy(summary = "手机上的标题")
        val current = task.copy(summary = "电脑上的标题", version = 2L)
        val conflict = ConflictItem(
            uuid = task.uuid,
            operationId = "conflict-id",
            type = "update",
            local = local,
            resultCode = "conflict",
            resultError = "",
            fields = listOf("summary"),
            current = current,
        )
        val gateway = connectedGateway(
            tasks = listOf(local),
            conflicts = listOf(conflict),
            pending = 1,
        )
        val vm = ScheduleViewModel(gateway, ioDispatcher = testDispatcher)
        runCurrent()

        vm.gotoPage(ClientPage.ACCOUNT)
        vm.gotoPage(ClientPage.SYNC)

        // 默认选手机值 true；切为电脑当前值后必须变成 false，且刷新不重置。
        assertEquals(true, vm.uiState.value.conflictChoices["conflict-id"]?.get("summary"))
        vm.setConflictChoice("conflict-id", "summary", useLocal = false)
        vm.applySnapshot(gateway.currentSnapshot)
        assertEquals(false, vm.uiState.value.conflictChoices["conflict-id"]?.get("summary"))

        val resolveGate = CompletableDeferred<Result<RepositorySnapshot>>()
        gateway.commandResults.addLast(resolveGate)
        vm.resolveConflict(conflict, saveCopy = false)
        runCurrent()

        assertEquals(1, gateway.recordedCommands.size)
        val cmd = gateway.recordedCommands[0] as ScheduleCommand.Resolve
        assertEquals(false, cmd.choices["summary"])
        assertFalse(cmd.saveCopy)

        resolveGate.complete(Result.success(gateway.currentSnapshot.copy(conflicts = emptyList(), pending = 0)))
        advanceUntilIdle()
    }

    @Test
    fun foregroundPollingRunsEvery10SecondsWhileResumedAndStopsOnPause() = runTest(testDispatcher) {
        val gateway = connectedGateway()
        val vm = ScheduleViewModel(
            gateway,
            ioDispatcher = testDispatcher,
            syncPollIntervalMs = 10_000L,
        )
        runCurrent()

        vm.onResume()
        runCurrent()
        assertEquals(1, gateway.syncCallCount)

        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(2, gateway.syncCallCount)

        vm.onPause()
        advanceTimeBy(25_000L)
        runCurrent()
        assertEquals(2, gateway.syncCallCount)
    }

    @Test
    fun dirtyEditorBackPromptsConfirmAndBlankSummaryIsRejected() = runTest(testDispatcher) {
        val gateway = connectedGateway()
        val vm = ScheduleViewModel(gateway, ioDispatcher = testDispatcher)
        runCurrent()

        vm.openEditor(null)
        vm.updateEditor { it.copy(summary = "   ") }
        assertNull(vm.saveEditor())
        assertEquals("请填写待办内容。", vm.uiState.value.editor?.errorMessage)
        assertTrue(gateway.recordedCommands.isEmpty())

        // 有未保存输入时按返回键必须先弹窗确认，不直接丢弃。
        assertTrue(vm.onBackPressed())
        assertTrue(vm.uiState.value.editor?.showDiscardConfirm == true)
        assertEquals("   ", vm.uiState.value.editor?.summary)

        vm.confirmDiscardEditor()
        assertFalse(vm.uiState.value.editorOpen)
    }

    @Test
    fun pairingRejectsCleartextHttpAndPromptsBeforeSwitchingComputersWithPendingEdits() = runTest(testDispatcher) {
        val gateway = FakeScheduleGateway(
            server = "server-old",
            base = "https://old.example.com",
            token = "",
            pending = 2,
            initialSnapshot = RepositorySnapshot(serverId = "server-old", base = "https://old.example.com"),
        )
        gateway.exchangeResult = PairExchangeResult(
            status = "approved",
            serverId = "server-new",
            token = "new-token",
        )
        val vm = ScheduleViewModel(gateway, ioDispatcher = testDispatcher)
        runCurrent()

        vm.showPairing()
        vm.updatePairingAddress("http://192.168.1.5:8766")
        vm.startManualPairing()
        runCurrent()
        assertTrue(vm.uiState.value.pairing.statusMessage.contains("HTTPS"))

        // 扫码切换到另一台电脑且有未同步编辑时，必须弹确认框而不是悄悄清空。
        val handled = vm.handleIncomingLink(
            "agendum://connect?base=https://new.example.com&pair_id=pid&pair_secret=sec",
        )
        assertTrue(handled)
        runCurrent()
        val prompt = vm.uiState.value.pairing.switchComputerPrompt
        assertNotNull(prompt)
        assertEquals(2, prompt?.pendingCount)
        assertEquals("server-old", gateway.credentialsServer())
    }

    @Test
    fun deepLinkWhenAlreadyConnectedDoesNotOverwriteCredentials() = runTest(testDispatcher) {
        val gateway = connectedGateway()
        val vm = ScheduleViewModel(gateway, ioDispatcher = testDispatcher)
        runCurrent()

        val handled = vm.handleIncomingLink(
            "agendum://connect?base=https://other.example.com&pair_id=p2&pair_secret=s2",
        )
        assertTrue(handled)
        assertEquals("isolated-test", gateway.credentialsServer())
        assertTrue(vm.uiState.value.feedback?.text?.contains("已连接") == true)
    }

    @Test
    fun deepLinkWithBlankPairParamsReportsOldOrInvalidQr() = runTest(testDispatcher) {
        val gateway = FakeScheduleGateway()
        val vm = ScheduleViewModel(gateway, ioDispatcher = testDispatcher)
        runCurrent()

        val handled = vm.handleIncomingLink(
            "agendum://connect?base=https://sync.example.com&pair_id=&pair_secret=",
        )
        runCurrent()
        assertTrue(handled)
        assertTrue(vm.uiState.value.pairing.statusMessage.contains("旧版或无效二维码"))
        assertEquals(0, gateway.exchangeCallCount)
    }

    @Test
    fun dateTimeHelperFormatsValidZonedIsoAndRejectsInvalidInput() {
        val tz = TimeZone.getTimeZone("GMT+08:00")
        val spec = DateTimeHelper.toZonedIso("2026-11-21T15:30", tz)
        assertEquals(DeadlineKind.DATETIME, spec.kind)
        assertEquals("2026-11-21T15:30:00+08:00", spec.value)

        var rejected = false
        try {
            DateTimeHelper.toZonedIso("2026-02-30T10:00", tz)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("无效日期 2月30日 必须被拒绝", rejected)
    }

    private class FakeScheduleGateway(
        private var server: String = "",
        private var base: String = "",
        private var token: String = "",
        private var pending: Int = 0,
        initialSnapshot: RepositorySnapshot = RepositorySnapshot(),
    ) : ScheduleGateway {
        var currentSnapshot: RepositorySnapshot = initialSnapshot
        var syncCallCount: Int = 0
        val syncQueue = ArrayDeque<CompletableDeferred<Boolean>>()
        val recordedCommands = mutableListOf<ScheduleCommand>()
        val commandResults = ArrayDeque<CompletableDeferred<Result<RepositorySnapshot>>>()
        var exchangeCallCount: Int = 0
        var exchangeResult = PairExchangeResult(status = "pending")
        private var listener: (() -> Unit)? = null

        override fun listen(onChanged: () -> Unit) {
            listener = onChanged
        }

        override fun stopListening(onChanged: () -> Unit) {
            if (listener === onChanged) listener = null
        }

        override fun snapshot(): RepositorySnapshot = currentSnapshot

        override suspend fun executeCommand(command: ScheduleCommand): RepositorySnapshot {
            recordedCommands.add(command)
            val deferred = commandResults.removeFirstOrNull() ?: return currentSnapshot
            return deferred.await().getOrThrow()
        }

        override suspend fun sync(): Boolean {
            syncCallCount++
            val deferred = syncQueue.removeFirstOrNull() ?: return true
            return deferred.await()
        }

        override fun scheduleSync(immediate: Boolean) = Unit

        override fun pendingCount(): Int = pending

        override fun discardAll() {
            pending = 0
        }

        override fun exportPending(): String = "{}"

        override fun credentialsServer(): String = server

        override fun credentialsBase(): String = base

        override fun credentialsToken(): String = token

        override fun saveCredentials(base: String, serverId: String, token: String) {
            this.base = base
            this.server = serverId
            this.token = token
        }

        override fun unpairCredentials() {
            this.token = ""
        }

        override fun legacyBase(defaultServer: String): String = defaultServer

        override fun hasLegacyToken(): Boolean = false

        override fun clearLegacyToken() = Unit

        override fun hasLegacySnapshot(): Boolean = false

        override fun readLegacySnapshotText(): String = ""

        override fun fetchInfoProtocol(base: String): Int = 1

        override fun startPairing(base: String, deviceName: String): PairTicket =
            PairTicket(id = "pair-1", secret = "sec-1", code = "123456")

        override fun exchangePairing(base: String, id: String, secret: String): PairExchangeResult {
            exchangeCallCount++
            return exchangeResult
        }
    }
}
