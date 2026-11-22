package com.agendum.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

enum class DeadlineKind(val wire: String, val label: String) {
    NONE("none", "不设置"),
    DATE("date", "仅日期"),
    DATETIME("datetime", "日期和时刻");

    companion object {
        fun fromWire(value: String?): DeadlineKind =
            entries.firstOrNull { it.wire == value } ?: NONE
    }
}

data class TimeSpec(
    val kind: DeadlineKind = DeadlineKind.NONE,
    val value: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.wire)
        .put("value", value)

    companion object {
        val NONE = TimeSpec(DeadlineKind.NONE, "")

        fun fromJson(obj: JSONObject?): TimeSpec {
            if (obj == null) return NONE
            val kind = DeadlineKind.fromWire(obj.optString("kind", "none"))
            val value = obj.optString("value", "")
            return if (kind == DeadlineKind.NONE) NONE else TimeSpec(kind, value)
        }
    }
}

enum class TaskCategory(val wire: String, val label: String) {
    ACTION("action", "待办"),
    ACADEMIC("academic", "学业"),
    URGENT("urgent", "紧急"),
    INFO("info", "通知");

    companion object {
        fun fromWire(value: String?): TaskCategory =
            entries.firstOrNull { it.wire == value } ?: ACTION
    }
}

enum class TaskStatus(val wire: String, val label: String) {
    OPEN("open", "待处理"),
    CANDIDATE("candidate", "待确认"),
    DONE("done", "已完成"),
    DISMISSED("dismissed", "已忽略"),
    EXPIRED("expired", "已过期");

    val isFinished: Boolean
        get() = this == DONE || this == DISMISSED || this == EXPIRED

    val isActive: Boolean
        get() = this == OPEN || this == CANDIDATE

    companion object {
        fun fromWire(value: String?): TaskStatus =
            entries.firstOrNull { it.wire == value } ?: OPEN
    }
}

enum class UrgentOverride(val wire: String, val label: String, val boolValue: Boolean?) {
    AUTO("auto", "跟随自动判断", null),
    TRUE("true", "紧急", true),
    FALSE("false", "不紧急", false);

    companion object {
        fun fromBool(value: Boolean?): UrgentOverride = when (value) {
            true -> TRUE
            false -> FALSE
            null -> AUTO
        }
    }
}

enum class TaskFilter(val wire: String, val label: String) {
    ACTIVE("active", "待处理"),
    ALL("all", "全部"),
    DONE("done", "已完成"),
    OVERDUE("overdue", "已逾期"),
    PINNED("pinned", "置顶"),
    CANDIDATE("candidate", "待确认"),
    DISMISSED("dismissed", "已忽略"),
    EXPIRED("expired", "已过期"),
}

enum class TaskSort(val wire: String, val label: String) {
    DEADLINE("deadline", "最近到期"),
    TITLE("title", "按标题"),
}

data class TaskItem(
    val uuid: String = "",
    val version: Long = 0L,
    val deleted: Boolean = false,
    val summary: String = "",
    val notes: String = "",
    val deadline: TimeSpec = TimeSpec.NONE,
    val category: TaskCategory = TaskCategory.ACTION,
    val status: TaskStatus = TaskStatus.OPEN,
    val pinned: Boolean = false,
    val urgentOverride: Boolean? = null,
    val snoozeUntil: TimeSpec = TimeSpec.NONE,
    val groups: List<String> = emptyList(),
    val details: List<String> = emptyList(),
    val evidence: String = "",
    val syncStatus: String = "",
) {
    val isActive: Boolean get() = status.isActive
    val isRestore: Boolean get() = status.isFinished
    val isUrgent: Boolean get() = urgentOverride == true || (urgentOverride == null && category == TaskCategory.URGENT)

    fun observedJson(): JSONObject = JSONObject()
        .put("uuid", uuid)
        .put("version", version)
        .put("summary", summary)
        .put("notes", notes)
        .put("deadline", deadline.toJson())
        .put("category", category.wire)
        .put("status", status.wire)
        .put("pinned", pinned)
        .put("urgent_override", urgentOverride ?: JSONObject.NULL)
        .put("snooze_until", snoozeUntil.toJson())

    fun editableFieldValue(field: String): Any? = when (field) {
        "summary" -> summary
        "notes" -> notes
        "deadline" -> deadline
        "category" -> category.wire
        "status" -> status.wire
        "pinned" -> pinned
        "urgent_override" -> urgentOverride
        "snooze_until" -> snoozeUntil
        "deleted" -> deleted
        "uuid" -> uuid
        else -> null
    }

    companion object {
        fun fromJson(obj: JSONObject): TaskItem {
            val urgentObj = if (obj.isNull("urgent_override")) null else obj.opt("urgent_override") as? Boolean
            return TaskItem(
                uuid = obj.optString("uuid", ""),
                version = obj.optLong("version", 0L),
                deleted = obj.optBoolean("deleted", false),
                summary = obj.optString("summary", ""),
                notes = obj.optString("notes", ""),
                deadline = TimeSpec.fromJson(obj.optJSONObject("deadline")),
                category = TaskCategory.fromWire(obj.optString("category", "action")),
                status = TaskStatus.fromWire(obj.optString("status", "open")),
                pinned = obj.optBoolean("pinned", false),
                urgentOverride = urgentObj,
                snoozeUntil = TimeSpec.fromJson(obj.optJSONObject("snooze_until")),
                groups = obj.optJSONArray("groups").toStringList(),
                details = obj.optJSONArray("details").toStringList(),
                evidence = obj.optString("evidence", ""),
                syncStatus = obj.optString("syncStatus", ""),
            )
        }
    }
}

data class ConflictItem(
    val uuid: String,
    val operationId: String,
    val type: String,
    val local: TaskItem,
    val resultCode: String,
    val resultError: String,
    val fields: List<String>,
    val current: TaskItem?,
) {
    companion object {
        fun fromJson(obj: JSONObject): ConflictItem {
            val result = obj.optJSONObject("result")
            val currentObj = result?.optJSONObject("current")
            val localObj = obj.optJSONObject("local") ?: JSONObject()
            return ConflictItem(
                uuid = obj.optString("uuid", ""),
                operationId = obj.optString("operation_id", ""),
                type = obj.optString("type", "update"),
                local = TaskItem.fromJson(localObj),
                resultCode = result?.optString("code", "") ?: "",
                resultError = result?.optString("error", "") ?: "",
                fields = result?.optJSONArray("fields").toStringList(),
                current = currentObj?.let { TaskItem.fromJson(it) },
            )
        }
    }
}

data class RepositorySnapshot(
    val tasks: List<TaskItem> = emptyList(),
    val pending: Int = 0,
    val conflicts: List<ConflictItem> = emptyList(),
    val connected: Boolean = false,
    val serverId: String = "",
    val base: String = "",
    val lastSuccess: Long = 0L,
    val error: String = "",
) {
    companion object {
        fun fromJson(obj: JSONObject): RepositorySnapshot {
            val taskArray = obj.optJSONArray("tasks") ?: JSONArray()
            val taskList = buildList(taskArray.length()) {
                for (i in 0 until taskArray.length()) {
                    val item = taskArray.optJSONObject(i) ?: continue
                    add(TaskItem.fromJson(item))
                }
            }
            val conflictArray = obj.optJSONArray("conflicts") ?: JSONArray()
            val conflictList = buildList(conflictArray.length()) {
                for (i in 0 until conflictArray.length()) {
                    val item = conflictArray.optJSONObject(i) ?: continue
                    add(ConflictItem.fromJson(item))
                }
            }
            return RepositorySnapshot(
                tasks = taskList,
                pending = obj.optInt("pending", 0),
                conflicts = conflictList,
                connected = obj.optBoolean("connected", false),
                serverId = obj.optString("server_id", ""),
                base = obj.optString("base", ""),
                lastSuccess = obj.optLong("lastSuccess", 0L),
                error = obj.optString("error", ""),
            )
        }
    }
}

data class TaskGroup(
    val heading: String,
    val tasks: List<TaskItem>,
)

object ScheduleRules {
    private val STATUS_LABELS = mapOf(
        "candidate" to "待确认",
        "open" to "待处理",
        "done" to "已完成",
        "dismissed" to "已忽略",
        "expired" to "已过期",
    )

    private val FIELD_LABELS = mapOf(
        "summary" to "待办内容",
        "notes" to "备注",
        "deadline" to "截止时间",
        "category" to "分类",
        "status" to "状态",
        "pinned" to "置顶",
        "urgent_override" to "紧急标记",
        "snooze_until" to "稍后提醒",
        "deleted" to "删除状态",
        "uuid" to "日程身份",
    )

    private val AUTH_ERROR_REGEX = Regex("配对|令牌|撤销|未授权|身份|另一台|协议|升级|401|403")
    private val SSL_ERROR_REGEX = Regex("SSL|证书|certificate|handshake", RegexOption.IGNORE_CASE)

    fun fieldLabel(field: String): String = FIELD_LABELS[field] ?: field

    fun statusLabel(wire: String): String = STATUS_LABELS[wire] ?: wire

    fun displayConflictValue(
        field: String,
        task: TaskItem?,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val value = task?.editableFieldValue(field)
        return when {
            value is TimeSpec -> DateTimeHelper.timeText(value, timeZone)
            value == true -> "是"
            value == false -> "否"
            value == null -> "跟随自动判断"
            else -> STATUS_LABELS[value.toString()] ?: value.toString()
        }
    }

    fun friendlySyncError(error: String?): String {
        if (error.isNullOrBlank()) return ""
        if (AUTH_ERROR_REGEX.containsMatchIn(error)) {
            return "连接凭据需要核对，请到「电脑连接」检查或重新配对。"
        }
        if (SSL_ERROR_REGEX.containsMatchIn(error)) {
            return "安全连接未建立，请核对电脑同步地址和证书。"
        }
        return "暂时连不上电脑，本地编辑已保留，可继续离线使用。"
    }

    fun isLate(
        task: TaskItem,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Boolean {
        val dl = task.deadline
        return when (dl.kind) {
            DeadlineKind.NONE -> false
            DeadlineKind.DATE -> dl.value < DateTimeHelper.todayDateString(nowMillis, timeZone)
            DeadlineKind.DATETIME -> {
                val millis = DateTimeHelper.parseIsoInstantMillis(dl.value, timeZone) ?: return false
                millis < nowMillis
            }
        }
    }

    fun filterTasks(
        tasks: List<TaskItem>,
        filter: TaskFilter,
        query: String,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<TaskItem> {
        val normalizedQuery = query.trim().lowercase(Locale.getDefault())
        return tasks.filter { task ->
            val matchesFilter = when (filter) {
                TaskFilter.ACTIVE -> task.isActive
                TaskFilter.ALL -> true
                TaskFilter.OVERDUE -> task.isActive && isLate(task, nowMillis, timeZone)
                TaskFilter.PINNED -> task.pinned
                TaskFilter.DONE -> task.status == TaskStatus.DONE
                TaskFilter.CANDIDATE -> task.status == TaskStatus.CANDIDATE
                TaskFilter.DISMISSED -> task.status == TaskStatus.DISMISSED
                TaskFilter.EXPIRED -> task.status == TaskStatus.EXPIRED
            }
            if (!matchesFilter) return@filter false
            if (normalizedQuery.isEmpty()) return@filter true
            val haystack = listOf(task.summary, task.notes, task.groups.joinToString(" "))
                .joinToString(" ")
                .lowercase(Locale.getDefault())
            haystack.contains(normalizedQuery)
        }
    }

    fun sortTasks(
        tasks: List<TaskItem>,
        sort: TaskSort,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<TaskItem> {
        return when (sort) {
            TaskSort.TITLE -> tasks.sortedWith { a, b ->
                a.summary.compareTo(b.summary)
            }
            TaskSort.DEADLINE -> tasks.sortedWith { a, b ->
                val pinnedCmp = (if (b.pinned) 1 else 0) - (if (a.pinned) 1 else 0)
                if (pinnedCmp != 0) return@sortedWith pinnedCmp
                val da = DateTimeHelper.deadlineSortMillis(a.deadline, timeZone)
                val db = DateTimeHelper.deadlineSortMillis(b.deadline, timeZone)
                when {
                    da < db -> -1
                    da > db -> 1
                    else -> a.summary.compareTo(b.summary)
                }
            }
        }
    }

    fun groupTasks(
        tasks: List<TaskItem>,
        showDateHeadings: Boolean,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): List<TaskGroup> {
        if (tasks.isEmpty()) return emptyList()
        val result = mutableListOf<TaskGroup>()
        var currentHeading: String? = null
        var bucket = mutableListOf<TaskItem>()
        for (task in tasks) {
            val heading = when {
                task.pinned -> "置顶"
                !showDateHeadings -> ""
                else -> DateTimeHelper.deadlineDay(task.deadline, timeZone).ifEmpty { "未设截止时间" }
            }
            if (currentHeading == null || currentHeading != heading) {
                if (currentHeading != null) {
                    result.add(TaskGroup(currentHeading, bucket))
                    bucket = mutableListOf()
                }
                currentHeading = heading
            }
            bucket.add(task)
        }
        if (currentHeading != null) {
            result.add(TaskGroup(currentHeading, bucket))
        }
        return result
    }
}

object DateTimeHelper {
    private val ISO_DATETIME_REGEX = Regex(
        """^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$"""
    )
    private val LOCAL_INPUT_REGEX = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$""")
    private val DATE_ONLY_REGEX = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    fun todayDateString(
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = timeZone }
        return fmt.format(Date(nowMillis))
    }

    fun currentYearMonth(
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Pair<Int, Int> {
        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        return cal.get(Calendar.YEAR) to (cal.get(Calendar.MONTH) + 1)
    }

    fun daysInMonth(year: Int, month: Int): Int {
        val cal = GregorianCalendar(year, month - 1, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /** 0 = 周日 .. 6 = 周六 */
    fun firstDayOfWeekInMonth(year: Int, month: Int): Int {
        val cal = GregorianCalendar(year, month - 1, 1)
        return cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    }

    fun formatDateKey(year: Int, month: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

    fun parseYearMonthFromDate(dateStr: String): Pair<Int, Int>? {
        val m = DATE_ONLY_REGEX.matchEntire(dateStr.trim()) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        return if (mo in 1..12) y to mo else null
    }

    fun parseIsoInstantMillis(
        value: String?,
        defaultZone: TimeZone = TimeZone.getDefault(),
    ): Long? {
        if (value.isNullOrBlank()) return null
        val m = ISO_DATETIME_REGEX.matchEntire(value.trim()) ?: return null
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        val second = m.groupValues[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val offsetToken = m.groupValues[7]
        val zone = when {
            offsetToken.isEmpty() -> defaultZone
            offsetToken == "Z" -> TimeZone.getTimeZone("UTC")
            else -> {
                val normalized = if (offsetToken.length == 5) {
                    offsetToken.substring(0, 3) + ":" + offsetToken.substring(3)
                } else {
                    offsetToken
                }
                TimeZone.getTimeZone("GMT$normalized")
            }
        }
        return runCatching {
            val cal = GregorianCalendar(zone).apply {
                isLenient = false
                set(year, month - 1, day, hour, minute, second)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }.getOrNull()
    }

    fun formatLocalInput(
        isoValue: String?,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val millis = parseIsoInstantMillis(isoValue, timeZone) ?: return ""
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply {
            this.timeZone = timeZone
        }
        return fmt.format(Date(millis))
    }

    fun toZonedIso(
        localInput: String,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): TimeSpec {
        val trimmed = localInput.trim()
        if (trimmed.isEmpty()) return TimeSpec.NONE
        val m = LOCAL_INPUT_REGEX.matchEntire(trimmed)
            ?: throw IllegalArgumentException("请选择有效时刻")
        val year = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        val millis = runCatching {
            val cal = GregorianCalendar(timeZone).apply {
                isLenient = false
                set(year, month - 1, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }.getOrElse { throw IllegalArgumentException("请选择有效时刻") }

        val offsetMinutes = timeZone.getOffset(millis) / 60_000
        val sign = if (offsetMinutes < 0) "-" else "+"
        val absMinutes = abs(offsetMinutes)
        val offsetStr = String.format(Locale.US, "%s%02d:%02d", sign, absMinutes / 60, absMinutes % 60)
        return TimeSpec(DeadlineKind.DATETIME, "$trimmed:00$offsetStr")
    }

    fun timeText(
        spec: TimeSpec?,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        if (spec == null || spec.kind == DeadlineKind.NONE) return "未设置"
        if (spec.kind == DeadlineKind.DATE) return spec.value
        val millis = parseIsoInstantMillis(spec.value, timeZone) ?: return spec.value
        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).apply {
            this.timeZone = timeZone
        }
        return fmt.format(Date(millis))
    }

    fun deadlineDay(
        spec: TimeSpec?,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        if (spec == null || spec.kind == DeadlineKind.NONE) return ""
        if (spec.kind == DeadlineKind.DATE) return spec.value
        val local = formatLocalInput(spec.value, timeZone)
        return if (local.length >= 10) local.substring(0, 10) else ""
    }

    fun deadlineSortMillis(
        spec: TimeSpec?,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long {
        if (spec == null || spec.kind == DeadlineKind.NONE) return Long.MAX_VALUE
        if (spec.kind == DeadlineKind.DATE) {
            return parseIsoInstantMillis("${spec.value}T23:59:59", timeZone) ?: Long.MAX_VALUE
        }
        return parseIsoInstantMillis(spec.value, timeZone) ?: Long.MAX_VALUE
    }

    fun formatDateTimeMillis(millis: Long): String {
        if (millis <= 0L) return "尚未同步"
        return DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.CHINA)
            .format(Date(millis))
    }

    fun formatLocalDisplayFromInput(localInput: String): String {
        val m = LOCAL_INPUT_REGEX.matchEntire(localInput.trim()) ?: return localInput
        val year = m.groupValues[1]
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4]
        val minute = m.groupValues[5]
        return "${year}年${month}月${day}日 $hour:$minute"
    }

    fun utcMillisToDateString(utcMillis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date(utcMillis))
    }

    fun dateStringToUtcMillis(dateStr: String): Long? {
        val m = DATE_ONLY_REGEX.matchEntire(dateStr.trim()) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        return runCatching {
            val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                set(y, mo - 1, d, 0, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        }.getOrNull()
    }

    fun extractDatePart(localInput: String): String =
        if (localInput.length >= 10) localInput.substring(0, 10) else ""

    fun extractHourMinute(localInput: String): Pair<Int, Int>? {
        val m = LOCAL_INPUT_REGEX.matchEntire(localInput.trim()) ?: return null
        return m.groupValues[4].toInt() to m.groupValues[5].toInt()
    }

    fun combineDateAndTime(dateStr: String, hour: Int, minute: Int): String =
        String.format(Locale.US, "%sT%02d:%02d", dateStr, hour, minute)
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList(length()) {
        for (i in 0 until length()) {
            val value = optString(i, "")
            if (value.isNotEmpty() || opt(i) is String) {
                add(value)
            }
        }
    }
}
