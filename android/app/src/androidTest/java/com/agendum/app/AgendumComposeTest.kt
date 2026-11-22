package com.agendum.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agendum.app.gateway.PairExchangeResult
import com.agendum.app.gateway.PairTicket
import com.agendum.app.gateway.ScheduleCommand
import com.agendum.app.gateway.ScheduleGateway
import com.agendum.app.model.ConflictItem
import com.agendum.app.model.DateTimeHelper
import com.agendum.app.model.DeadlineKind
import com.agendum.app.model.RepositorySnapshot
import com.agendum.app.model.TaskItem
import com.agendum.app.model.TimeSpec
import com.agendum.app.ui.AgendumApp
import com.agendum.app.ui.ScheduleViewModel
import com.agendum.app.ui.theme.AgendumTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgendumComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pairingManualFormRejectsHttpAddress() {
        val gateway = FakeGateway()
        val vm = ScheduleViewModel(gateway, ioDispatcher = Dispatchers.Unconfined)
        composeRule.setContent {
            AgendumTheme(darkTheme = false, dynamicColor = false) {
                AgendumApp(viewModel = vm)
            }
        }
        composeRule.onNodeWithText("扫码连接电脑").assertIsDisplayed()
        composeRule.onNodeWithText("手动填写地址").performClick()
        composeRule.onNodeWithText("连接电脑并显示配对码").assertIsDisplayed()
        composeRule.onNodeWithText("连接电脑并显示配对码").performClick()
        composeRule.onNodeWithText("请填写 HTTPS 地址，例如 https://sync.example.com").assertIsDisplayed()
    }

    @Test
    fun tasksSearchEditorAndConflictChoiceWorkInComposeUi() {
        val today = DateTimeHelper.todayDateString()
        val task = TaskItem(
            uuid = "00000000-0000-4000-8000-000000000001",
            version = 1L,
            summary = "交实验报告",
            notes = "核对实验数据",
            deadline = TimeSpec(DeadlineKind.DATE, today),
        )
        val local = task.copy(summary = "手机上的标题")
        val current = task.copy(summary = "电脑上的标题", version = 2L)
        val conflict = ConflictItem(
            uuid = task.uuid,
            operationId = "op-1",
            type = "update",
            local = local,
            resultCode = "conflict",
            resultError = "",
            fields = listOf("summary"),
            current = current,
        )
        val gateway = FakeGateway(
            server = "test-server",
            base = "https://example.invalid",
            token = "tok",
            snapshot = RepositorySnapshot(
                tasks = listOf(task),
                conflicts = listOf(conflict),
                pending = 1,
                connected = true,
                serverId = "test-server",
                base = "https://example.invalid",
                lastSuccess = 1L,
            ),
        )
        val vm = ScheduleViewModel(gateway, ioDispatcher = Dispatchers.Unconfined)
        composeRule.setContent {
            AgendumTheme(darkTheme = false, dynamicColor = false) {
                AgendumApp(viewModel = vm)
            }
        }

        composeRule.onNodeWithText("交实验报告").assertIsDisplayed()
        composeRule.onNodeWithText("搜索日程、备注或群名").performTextInput("不存在")
        composeRule.onNodeWithText("没有符合条件的日程").assertIsDisplayed()
        composeRule.onNodeWithText("清除筛选").performClick()
        composeRule.onNodeWithText("交实验报告").assertIsDisplayed()

        composeRule.onNodeWithText("交实验报告").performClick()
        composeRule.onNodeWithText("编辑日程").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("取消编辑并返回").performClick()
        composeRule.onNodeWithText("日程").assertIsDisplayed()

        composeRule.onNodeWithText("我的").performClick()
        composeRule.onNodeWithText("同步与冲突").performClick()
        composeRule.onNodeWithText("电脑当前值：电脑上的标题").performClick()
        composeRule.onNodeWithText("按选择合并提交").performClick()
        composeRule.waitForIdle()
        val resolve = gateway.commands.last() as ScheduleCommand.Resolve
        assertEquals(false, resolve.choices["summary"])
    }

    private class FakeGateway(
        private var server: String = "",
        private var base: String = "",
        private var token: String = "",
        private var snapshot: RepositorySnapshot = RepositorySnapshot(),
    ) : ScheduleGateway {
        val commands = mutableListOf<ScheduleCommand>()

        override fun listen(onChanged: () -> Unit) = Unit
        override fun stopListening(onChanged: () -> Unit) = Unit
        override fun snapshot(): RepositorySnapshot = snapshot
        override suspend fun executeCommand(command: ScheduleCommand): RepositorySnapshot {
            commands.add(command)
            return snapshot
        }
        override suspend fun sync(): Boolean = true
        override fun scheduleSync(immediate: Boolean) = Unit
        override fun pendingCount(): Int = snapshot.pending
        override fun discardAll() = Unit
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
            PairTicket("id", "sec", "123456")
        override fun exchangePairing(base: String, id: String, secret: String): PairExchangeResult =
            PairExchangeResult("pending")
    }
}
