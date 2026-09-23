package com.attentionguard.app.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.Duration
import java.security.MessageDigest

/** Fast, offline attention gate used before any DeepSeek request. */
object AttentionEngine {

    private val deadlinePattern = Regex("(截止|最晚|不晚于|截至|之前|前提交|前完成|前报名|前交|前发|前回复)")
    private val actionPattern = Regex("(提交|填写|报名|参加|完成|回复|确认|上传|下载|发我|到场|到课|登记|预约|交作业|交材料|领取)")
    private val locationPattern = Regex("(教室|会议室|地点|校区|\\d+号楼|\\d+[-—]\\d+|\\d+室)")
    private val noisePattern = Regex("^(收到|好的|好滴|哈哈|谢谢|请查收|顶|已阅)[。！!、，, ]*$")

    @Suppress("UNUSED_PARAMETER")
    fun buildEvent(snapshot: ChatSnapshot, context: String = "", origin: CaptureOrigin = CaptureOrigin.UNKNOWN): AttentionEvent? {
        val visible = snapshot.messages.filter { it.text.isNotBlank() && !noisePattern.matches(it.text.trim()) }.takeLast(12)
        if (visible.isEmpty() || snapshot.title.isNullOrBlank()) return null
        // Only connect adjacent corrections from the same identifiable sender.
        // Independent notices never lend each other a deadline or authority.
        val anchorIndex = visible.indexOfLast { hasAction(it.text) && !correction.containsMatchIn(it.text) }
            .takeIf { it >= 0 } ?: visible.lastIndex
        val anchor = visible[anchorIndex]
        val followups = visible.drop(anchorIndex + 1).takeWhile {
            anchor.sender != null && it.sender == anchor.sender &&
                (correction.containsMatchIn(it.text) || it.text.startsWith("逾期") || it.text.startsWith("否则"))
        }
        val messages = listOf(anchor) + followups
        val latestCorrection = followups.lastOrNull { correction.containsMatchIn(it.text) }
        val joined = (latestCorrection ?: anchor).text
        val review = reviewContext(messages, context)
        val cancelled = Regex("取消|作废|无需|不用|不必").containsMatchIn(joined)
        val actionable = hasAction(anchor.text) && !cancelled
        val authoritative = AUTHORITY_WORDS.any { anchor.sender?.contains(it) == true }
        val mentionsAll = joined.contains("@所有人") || joined.contains("@所有成员") ||
            joined.contains("@全体") || joined.contains("全体成员")
        val hasDeadlineWord = deadlinePattern.containsMatchIn(joined)
        val nowDateTime = Instant.ofEpochMilli(snapshot.capturedAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val deadlineMessage = latestCorrection ?: anchor
        val messageDay = deadlineMessage.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val deadline = DeadlineParser.parse(joined, messageDay, nowDateTime.toLocalDate())
        val hasDeadline = deadline.at != null || hasDeadlineWord
        val hours = deadline.at?.let { Duration.between(nowDateTime, it).toMinutes() / 60.0 }
        val expired = hours != null && hours < 0
        val hasLocation = locationPattern.containsMatchIn(joined)
        val category = categoryOf(joined)
        val score = (scoreOf(actionable, authoritative, mentionsAll, hasDeadline, hasLocation, category) + review.adjustment).coerceIn(0, 99)
        if (review.suppress || (!actionable && !hasDeadline && !authoritative && !mentionsAll && !hasLocation) || (score < 26 && !actionable && !hasDeadline)) return null

        val priority = when {
            cancelled -> EventPriority.P3
            expired -> EventPriority.P2
            actionable && hours != null && hours in 0.0..24.0 && (authoritative || mentionsAll) -> EventPriority.P0
            actionable && (deadline.at != null || authoritative || mentionsAll) -> EventPriority.P1
            actionable || hasDeadline || score >= 42 -> EventPriority.P2
            else -> EventPriority.P3
        }
        val latest = messages.last()
        val title = titleOf(anchor.text)
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
        val dueLabel = if (cancelled) null else deadline.label
        val eventId = stableId(snapshot.title + "|" + anchor.sender.orEmpty() + "|" + anchor.date.orEmpty() + "|" + anchor.text.trim())
        val summary = (latestCorrection ?: anchor).text

        return AttentionEvent(
            id = eventId,
            title = title,
            summary = summary.take(100),
            sourceGroup = snapshot.title,
            sourcePerson = sourcePerson,
            priority = priority,
            status = if (cancelled || expired) EventStatus.MONITORING else if (actionable) EventStatus.ACTION_REQUIRED else EventStatus.MONITORING,
            category = category,
            attentionScore = score.coerceIn(1, 99),
            dueLabel = dueLabel,
            actionLabel = if (cancelled) null else actionLabelOf(anchor.text),
            consequence = consequenceOf(joined),
            updatedLabel = "$now 更新",
            updates = updates,
            evidence = messages.takeLast(4).map { it.text.take(160) }.distinct(),
            captureOrigin = origin,
            sourceCapturedAt = snapshot.capturedAt,
            reviewNotes = buildList {
                if (authoritative) add("发送者名称包含管理方信号，身份尚未验证")
                if (actionable) add("检测到明确行动要求")
                if (deadline.at != null) add("日期已通过日历校验")
                deadline.note?.let { add(it) }
                if (expired) add("截止时间已过，保留记录而非升级紧急提醒")
                if (priority == EventPriority.P0) add("明确行动且截止在 24 小时内，存在来源或全体通知信号")
                if (latestCorrection != null) add("同一发送者的相邻更正覆盖旧时间，保留原始依据")
                if (cancelled) add("存在取消或作废信号，待人工核对，不自动标记完成")
                if (mentionsAll) add("消息面向全体成员")
                if (review.adjustment < 0) add("上下文存在取消或作废信号，已降低置信度")
                if (priority == EventPriority.P3) add("证据较弱，仅作为低优先级记录")
            }
        )
    }

    fun shouldNotify(event: AttentionEvent): Boolean =
        event.priority == EventPriority.P0 || event.status == EventStatus.ACTION_REQUIRED || event.dueLabel != null

    private data class ContextReview(val adjustment: Int, val suppress: Boolean)

    /** Self-review reduces false positives from acknowledgements and weak isolated cues. */
    private fun reviewContext(messages: List<Msg>, context: String): ContextReview {
        val meaningful = messages.count { !noisePattern.matches(it.text.trim()) }
        val hasDistinctSenders = messages.mapNotNull { it.sender }.distinct().size > 1
        val hasEvidence = messages.any { actionPattern.containsMatchIn(it.text) || deadlinePattern.containsMatchIn(it.text) }
        val contradiction = messages.any { it.text.contains("取消") || it.text.contains("作废") || it.text.contains("不用") }
        val weakContext = context.isBlank() && meaningful <= 1 && !hasEvidence
        return ContextReview(
            adjustment = (if (meaningful >= 3) 5 else 0) + (if (hasDistinctSenders) 3 else 0) - (if (contradiction) 12 else 0),
            suppress = weakContext || meaningful == 0
        )
    }

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
        text.contains("重修") -> "重修报名安排"
        text.contains("竞赛") || text.contains("比赛") -> "竞赛报名"
        text.contains("报名") -> "报名安排"
        else -> text.replace(Regex("https?://\\S+|@所有人|@所有成员"), "").trim(' ', '：', ':').replace(Regex("\\s+"), " ").take(26)
    }

    private fun categoryOf(text: String): EventCategory = when {
        text.contains("就业") || text.contains("招聘") || text.contains("双选") || text.contains("宣讲") -> EventCategory.EMPLOYMENT
        text.contains("竞赛") || text.contains("比赛") -> EventCategory.COMPETITION
        text.contains("调课") || text.contains("作业") || text.contains("实验") || text.contains("课程") || text.contains("重修") -> EventCategory.COURSE
        text.contains("活动") || text.contains("讲座") || text.contains("志愿") -> EventCategory.ACTIVITY
        else -> EventCategory.ACADEMIC_ADMIN
    }

    private val correction = Regex("更正|改为|改到|调整为|取消|作废|无需|不用|以.{0,12}为准")
    private fun hasAction(text: String): Boolean {
        if (Regex("^(请问|是否|怎么|能否)").containsMatchIn(text.trim())) return false
        if (Regex("已(经)?(提交|完成|报名|回复)|完成搬迁").containsMatchIn(text) && !text.contains("请")) return false
        return actionPattern.containsMatchIn(text) &&
            (Regex("请|须|务必|需要|记得|尽快|统一|截止|最晚|之前|前提交|报名时间|报名登记").containsMatchIn(text))
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

    private fun stableId(value: String): String = "event_v2_" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

    private val AUTHORITY_WORDS = listOf("老师", "辅导员", "班长", "团支书", "学习委员", "学院", "教务", "就业中心", "管理员")
}


