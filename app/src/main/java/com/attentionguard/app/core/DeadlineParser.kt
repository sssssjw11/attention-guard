package com.attentionguard.app.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Calendar-validated evidence, never a substring of a URL, year range or room number. */
object DeadlineParser {
    data class Result(val label: String?, val at: LocalDateTime? = null, val note: String? = null)
    private val url = Regex("https?://\\S+")
    private val date = Regex("(?<![\\d./-])(?:(\\d{4})\\s*[年/.-]\\s*)?(\\d{1,2})\\s*[月/.]\\s*(\\d{1,2})\\s*[日号]?(?![\\d/])|(?<![\\d/-])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)")
    private val time = Regex("(?<!\\d)(凌晨|上午|中午|下午|晚上)?\\s*(\\d{1,2})[:：](\\d{2})(?!\\d)")
    private val relative = Regex("今天|今日|明天|明日|后天|后日")
    private val deadline = Regex("截止|截至|最晚|不晚于|之前|前提交|前完成|前报名|前交|前发|前回复")

    fun parse(text: String, messageDay: LocalDate?, today: LocalDate): Result {
        val clean = text.replace(url, "")
        val matches = date.findAll(clean).toList()
        val chosen = when {
            matches.size <= 1 -> matches.firstOrNull()
            else -> {
                val marker = Regex("截止|截至|最晚|不晚于").find(clean)
                val after = marker?.let { m -> matches.firstOrNull { it.range.first >= m.range.last && it.range.first - m.range.last < 16 } }
                val bridge = clean.substring(matches.first().range.last + 1, matches.last().range.first)
                after ?: if (Regex("至|到|[—~～]").containsMatchIn(bridge)) matches.last() else
                    return Result("时间待核对", note = "同一通知存在多个日期，未自动认定截止时间")
            }
        }
        var day: LocalDate? = null
        var label: String? = null
        if (chosen != null) {
            val g = chosen.groupValues
            day = runCatching {
                if (g[4].isNotEmpty()) LocalDate.of(g[4].toInt(), g[5].toInt(), g[6].toInt())
                else LocalDate.of(g[1].toIntOrNull() ?: (messageDay ?: today).year, g[2].toInt(), g[3].toInt())
            }.getOrNull() ?: return Result("时间待核对", note = "日期不合法，未用于优先级升级")
            label = day.toString()
        } else {
            relative.find(clean)?.let { match ->
                val days = when (match.value) { "今天", "今日" -> 0L; "明天", "明日" -> 1L; else -> 2L }
                if (messageDay == null) return Result("${match.value} · 日期待核对", note = "相对日期缺少消息日期，未按今天推算")
                day = messageDay.plusDays(days)
                label = day.toString()
            }
        }
        val timeText = if (chosen == null) clean else clean.substring(chosen.range.last + 1).take(18)
        val timeMatch = time.find(timeText)
        val clock = timeMatch?.let { match ->
            runCatching {
                var h = match.groupValues[2].toInt()
                val period = match.groupValues[1]
                if (period.isNotEmpty()) require(h in 1..12)
                if (period in listOf("下午", "晚上") && h < 12) h += 12
                if (period in listOf("上午", "凌晨") && h == 12) h = 0
                if (period == "中午" && h in 1..5) h += 12
                LocalTime.of(h, match.groupValues[3].toInt())
            }.getOrNull() ?: return Result("时间待核对", note = "时间不合法，未用于优先级升级")
        }
        if (day == null) return if (deadline.containsMatchIn(clean) || clock != null)
            Result("截止日期待核对", note = "未确认完整截止日期") else Result(null)
        return Result(label + (clock?.let { " $it" } ?: ""), day!!.atTime(clock ?: LocalTime.of(23, 59)))
    }

    fun displayLabel(label: String?): String? {
        if (label == null) return null
        val parsed = parse(label, null, LocalDate.now())
        return if (parsed.note?.contains("不合法") == true) "时间待核对" else label
    }
}
