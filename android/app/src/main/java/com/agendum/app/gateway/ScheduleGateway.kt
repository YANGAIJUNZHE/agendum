package com.agendum.app.gateway

import android.content.Context
import com.agendum.app.Credentials
import com.agendum.app.ScheduleRepository
import com.agendum.app.ScheduleSyncWorker
import com.agendum.app.SyncTransport
import com.agendum.app.model.RepositorySnapshot
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.text.DateFormat
import java.util.Date

sealed interface ScheduleCommand {
    val uuid: String

    data class Edit(
        val kind: String,
        override val uuid: String,
        val base: JSONObject?,
        val changes: JSONObject,
    ) : ScheduleCommand

    data class Resolve(
        override val uuid: String,
        val choices: Map<String, Boolean>,
        val saveCopy: Boolean,
    ) : ScheduleCommand

    data class Discard(
        override val uuid: String,
    ) : ScheduleCommand
}

data class PairTicket(
    val id: String,
    val secret: String,
    val code: String,
)

data class PairExchangeResult(
    val status: String,
    val serverId: String = "",
    val token: String = "",
)

interface ScheduleGateway {
    fun listen(onChanged: () -> Unit)
    fun stopListening(onChanged: () -> Unit)
    fun snapshot(): RepositorySnapshot
    suspend fun executeCommand(command: ScheduleCommand): RepositorySnapshot
    suspend fun sync(): Boolean
    fun scheduleSync(immediate: Boolean)
    fun pendingCount(): Int
    fun discardAll()
    fun exportPending(): String
    fun credentialsServer(): String
    fun credentialsBase(): String
    fun credentialsToken(): String
    fun saveCredentials(base: String, serverId: String, token: String)
    fun unpairCredentials()
    fun legacyBase(defaultServer: String): String
    fun hasLegacyToken(): Boolean
    fun clearLegacyToken()
    fun hasLegacySnapshot(): Boolean
    fun readLegacySnapshotText(): String
    fun fetchInfoProtocol(base: String): Int
    fun startPairing(base: String, deviceName: String): PairTicket
    fun exchangePairing(base: String, id: String, secret: String): PairExchangeResult
}

class AndroidScheduleGateway(context: Context) : ScheduleGateway {
    private val appContext = context.applicationContext
    private val credentials = Credentials(appContext)
    private val repository = ScheduleRepository(appContext)
    private val transport = SyncTransport()
    private val legacyPrefs = appContext.getSharedPreferences("notice_hub", Context.MODE_PRIVATE)
    private val listenerLock = Any()
    private val listenerMap = mutableMapOf<() -> Unit, Runnable>()

    override fun listen(onChanged: () -> Unit) {
        val runnable = synchronized(listenerLock) {
            listenerMap.getOrPut(onChanged) { Runnable { onChanged() } }
        }
        ScheduleRepository.listen(runnable)
    }

    override fun stopListening(onChanged: () -> Unit) {
        val runnable = synchronized(listenerLock) {
            listenerMap.remove(onChanged)
        }
        if (runnable != null) {
            ScheduleRepository.stopListening(runnable)
        }
    }

    override fun snapshot(): RepositorySnapshot = RepositorySnapshot.fromJson(repository.snapshot())

    override suspend fun executeCommand(command: ScheduleCommand): RepositorySnapshot {
        when (command) {
            is ScheduleCommand.Resolve -> {
                val jsonChoices = JSONObject()
                for ((field, useLocal) in command.choices) {
                    jsonChoices.put(field, useLocal)
                }
                repository.resolve(command.uuid, jsonChoices, command.saveCopy)
            }
            is ScheduleCommand.Discard -> {
                repository.discard(command.uuid)
            }
            is ScheduleCommand.Edit -> {
                repository.edit(command.uuid, command.kind, command.changes, command.base)
            }
        }
        return snapshot()
    }

    override suspend fun sync(): Boolean = repository.sync()

    override fun scheduleSync(immediate: Boolean) {
        ScheduleSyncWorker.schedule(appContext, immediate)
    }

    override fun pendingCount(): Int = repository.pendingCount()

    override fun discardAll() {
        repository.discardAll()
    }

    override fun exportPending(): String = repository.exportPending()

    override fun credentialsServer(): String = credentials.server()

    override fun credentialsBase(): String = credentials.base()

    override fun credentialsToken(): String = credentials.token()

    override fun saveCredentials(base: String, serverId: String, token: String) {
        credentials.save(base, serverId, token)
    }

    override fun unpairCredentials() {
        credentials.unpair()
    }

    override fun legacyBase(defaultServer: String): String =
        legacyPrefs.getString("base", defaultServer) ?: defaultServer

    override fun hasLegacyToken(): Boolean =
        !legacyPrefs.getString("token", "").isNullOrEmpty()

    override fun clearLegacyToken() {
        legacyPrefs.edit().remove("token").apply()
    }

    override fun hasLegacySnapshot(): Boolean =
        File(appContext.filesDir, "snapshot.json").isFile

    override fun readLegacySnapshotText(): String {
        val snapshotFile = File(appContext.filesDir, "snapshot.json")
        BufferedReader(InputStreamReader(FileInputStream(snapshotFile), "UTF-8")).use { reader ->
            val raw = buildString {
                var line = reader.readLine()
                while (line != null) {
                    append(line)
                    line = reader.readLine()
                }
            }
            val snapshot = JSONObject(raw)
            val board = snapshot.getJSONObject("tasks")
            val text = StringBuilder("这是升级前保存的快照。重新配对并同步后，才能编辑日程。\n")
            val saved = snapshot.optLong("savedAt")
            if (saved > 0L) {
                text.append("\n保存于 ")
                    .append(DateFormat.getDateTimeInstance().format(Date(saved)))
                    .append('\n')
            }
            val seen = HashSet<String>()
            val groups = board.keys()
            while (groups.hasNext()) {
                val tasks = board.optJSONArray(groups.next()) ?: continue
                for (i in 0 until tasks.length()) {
                    val task = tasks.optJSONObject(i) ?: continue
                    val id = task.optString("id", task.optString("summary"))
                    if (!seen.add(id)) continue
                    text.append("\n• ").append(task.optString("summary", "未命名日程"))
                    val deadline = task.optString("deadline")
                    if (deadline.isNotEmpty()) {
                        text.append("\n截止：").append(deadline)
                    }
                    text.append('\n')
                }
            }
            if (seen.isEmpty()) {
                text.append("\n旧快照中没有可显示的日程。")
            }
            return text.toString()
        }
    }

    override fun fetchInfoProtocol(base: String): Int {
        val info = transport.call(base, "/api/v1/info", null, null)
        return info.optInt("protocol")
    }

    override fun startPairing(base: String, deviceName: String): PairTicket {
        val ticket = transport.call(
            base,
            "/api/v1/pair/start",
            JSONObject().put("name", deviceName),
            null,
        )
        return PairTicket(
            id = ticket.getString("id"),
            secret = ticket.getString("secret"),
            code = ticket.optString("code", ""),
        )
    }

    override fun exchangePairing(base: String, id: String, secret: String): PairExchangeResult {
        val result = transport.call(
            base,
            "/api/v1/pair/exchange",
            JSONObject().put("id", id).put("secret", secret),
            null,
        )
        return PairExchangeResult(
            status = result.optString("status", ""),
            serverId = result.optString("server_id", ""),
            token = result.optString("token", ""),
        )
    }
}
