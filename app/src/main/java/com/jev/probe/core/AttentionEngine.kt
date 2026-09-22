package com.jev.probe.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Fast, offline attention gate used before any DeepSeek request. */
object AttentionEngine {

    private val timePattern = Regex("\\d{1,2}[:：]\\d{2}")
    private val datePattern = Regex("(?:\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[月./-]\\d{1,2}(日|号)?)")
    private val deadlinePattern = Regex("(截止|最晚|不晚于|截至|之前|前提交|前完成|前报名|前交|前发|前回复)")
    private val actionPattern = Regex("(提交|填写|报名|参加|完成|回复|确认|上传|下载|发我|到场|登记|预约|交作业|交材料|领取)")
    private val locationPattern = Regex("(教室|会议室|地点|校区|\\d+号楼|\\d+[-—]\\d+|\\d+室)")

    @Suppress("UNUSED_PARAMETER")
    fun buildEvent(snapshot: ChatSnapshot, context: String = ""): AttentionEvent? {
        val messages = snapshot.messages.filter { it.text.isNotBlank() }.takeLast(12)
        if (messages.isEmpty() || snapshot.title.isNullOrBlank()) return null

        val joined = messages.joinToString(" ") { it.text }
        val actionable = actionPattern.containsMatchIn(joined)
        val authoritative = AUTHORITY_WORDS.any { joined.contains(it) || messages.any { m -> m.sender?.contains(it) == true } }
        val mentionsAll = joined.contains("@所有人") || joined.contains("@所有成员") ||
            joined.contains("@全体") || joined.contains("全体成员")
        val hasDeadlineWord = deadlinePattern.containsMatchIn(joined)
        val hasRelativeDate = (joined.contains("今天") || joined.contains("明天") || joined.contains("后天")) && actionable
        val hasExplicitDate = (datePattern.containsMatchIn(joined) || timePattern.containsMatchIn(joined)) && actionable
        val hasDeadline = hasDeadlineWord || hasRelativeDate || hasExplicitDate
        val hasLocation = locationPattern.containsMatchIn(joined)
        val category = categoryOf(joined)
        val score = scoreOf(actionable, authoritative, mentionsAll, hasDeadline, hasLocation, category)
        if (score < 26 && !actionable && !hasDeadline) return null

        val priority = when {
            score >= 78 || (mentionsAll && actionable) -> EventPriority.P0
            score >= 52 -> EventPriority.P1
            else -> EventPriority.P2
        }
        val latest = messages.last()
        val title = titleOf(joined)
        val now = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val updates = messages.takeLast(4).map { message ->
            val sender = message.sender ?: if (message.side == "me") "我" else "群成员"
            EventUpdate(
                time = message.timestamp?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: now,
                title = if (message == latest) "最新观测" else "上下文消息",
                detail = "$sender：${message.text.take(72)}",
                tone = if (hasDeadline && deadlinePattern.containsMatchIn(message.text)) UpdateTone.WARNING else UpdateTone.NEUTRAL
            )
        }
        val sourcePerson = messages.mapNotNull { it.sender }.distinct().take(2).joinToString(" / ")
            .ifBlank { if (authoritative) "老师 / 班委" else "群成员" }
        val dueLabel = dueLabelOf(joined)
        val eventId = stableId(snapshot.title + "|" + title)
        val summary = messages.firstOrNull { actionPattern.containsMatchIn(it.text) }?.text
            ?: latest.text

        return AttentionEvent(
            id = eventId,
            title = title,
            summary = summary.take(100),
            sourceGroup = snapshot.title,
            sourcePerson = sourcePerson,
            priority = priority,
            status = if (actionable) EventStatus.ACTION_REQUIRED else if (hasDeadline) EventStatus.CONFIRMED else EventStatus.MONITORING,
            category = category,
            attentionScore = score.coerceIn(1, 99),
            dueLabel = dueLabel,
            actionLabel = actionLabelOf(joined),
            consequence = consequenceOf(joined),
            updatedLabel = "$now 更新",
            updates = updates,
            evidence = messages.takeLast(4).map { it.text.take(160) }.distinct()
        )
    }

    fun shouldNotify(event: AttentionEvent): Boolean =
        event.priority == EventPriority.P0 || event.status == EventStatus.ACTION_REQUIRED || event.dueLabel != null

    private fun scoreOf(
        actionable: Boolean,
        authoritative: Boolean,
        mentionsAll: Boolean,
        hasDeadline: Boolean,
        hasLocation: Boolean,
        category: EventCategory
    ): Int {
        var score = 18
        if (actionable) score += 27
        if (authoritative) score += 17
        if (mentionsAll) score += 15
        if (hasDeadline) score += 18
        if (hasLocation) score += 6
        if (category == EventCategory.EMPLOYMENT || category == EventCategory.ACADEMIC_ADMIN) score += 5
        return score
    }

    private fun titleOf(text: String): String = when {
        text.contains("综测") || text.contains("综合测评") -> "综合测评材料提交"
        text.contains("奖学金") -> "奖学金申请"
        text.contains("调课") || text.contains("教室") -> "课程安排更新"
        text.contains("作业") || text.contains("实验报告") -> "课程作业与实验要求"
        text.contains("招聘") || text.contains("双选会") || text.contains("宣讲") -> "就业活动提醒"
        text.contains("竞赛") || text.contains("报名") -> "竞赛 / 活动报名"
        else -> "群聊事项：${text.trim().replace(Regex("\\s+"), " ").take(22)}"
    }

    private fun categoryOf(text: String): EventCategory = when {
        text.contains("就业") || text.contains("招聘") || text.contains("双选") || text.contains("宣讲") -> EventCategory.EMPLOYMENT
        text.contains("竞赛") || text.contains("报名") || text.contains("比赛") -> EventCategory.COMPETITION
        text.contains("调课") || text.contains("作业") || text.contains("实验") || text.contains("课程") -> EventCategory.COURSE
        text.contains("活动") || text.contains("讲座") || text.contains("志愿") -> EventCategory.ACTIVITY
        else -> EventCategory.ACADEMIC_ADMIN
    }

    private fun dueLabelOf(text: String): String? {
        val time = timePattern.find(text)?.value?.replace('：', ':')
        val date = datePattern.find(text)?.value
        return when {
            date != null -> "$date${time?.let { " $it" } ?: ""}"
            text.contains("今天") -> "今日${time?.let { " $it" } ?: ""}"
            text.contains("明天") -> "明日${time?.let { " $it" } ?: ""}"
            text.contains("后天") -> "后日${time?.let { " $it" } ?: ""}"
            deadlinePattern.containsMatchIn(text) -> "请确认截止时间"
            else -> null
        }
    }

    private fun actionLabelOf(text: String): String? = when {
        text.contains("填写") || text.contains("申请") -> "填写并提交材料"
        text.contains("报名") -> "完成报名"
        text.contains("提交") || text.contains("发送") || text.contains("发我") -> "提交或发送材料"
        text.contains("到场") || text.contains("到课") || text.contains("参加") || text.contains("出席") -> "按通知到场"
        text.contains("确认") || text.contains("回复") -> "回复确认"
        else -> null
    }

    private fun consequenceOf(text: String): String? = when {
        text.contains("逾期") -> text.substringAfter("逾期").take(32).ifBlank { "逾期可能影响办理" }
        text.contains("否则") -> text.substringAfter("否则").take(32)
        text.contains("不要去") || text.contains("不要错过") -> text.substringAfter("不要").take(32)
        else -> null
    }

    private fun stableId(value: String): String = "event_${value.trim().lowercase(Locale.getDefault()).hashCode().toString(16).replace('-', 'n')}"

    private val AUTHORITY_WORDS = listOf("老师", "辅导员", "班长", "团支书", "学习委员", "学院", "教务", "就业中心", "管理员")
}
