package com.attentionguard.app.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DeadlineParserTest {
    private val today = LocalDate.of(2026, 9, 23)
    private fun parse(text: String, day: LocalDate? = today) = DeadlineParser.parse(text, day, today)

    @Test fun rejectsImpossibleDatesAndTimes() {
        listOf("39/30 12:00", "2026-02-30", "9月24日25:61").forEach { assertNull(parse(it).at) }
        assertEquals("时间待核对", DeadlineParser.displayLabel("39/30 12:00"))
        assertNull(parse("2026-2027学年，教室3-105，https://example.invalid/39/30").label)
    }
    @Test fun rangeUsesEndDateAndAssociatedClock() {
        val result = parse("报名时间：9 月 22 日 10:00 — 9 月 27 日 23:00")
        assertEquals("2026-09-27 23:00", result.label)
    }
    @Test fun relativeDatesRequireMessageDayNotObservationDay() {
        assertNull(parse("明天17:00前提交", null).at)
        assertEquals("2026-09-21 17:00", parse("明天17:00前提交", today.minusDays(3)).label)
    }
    @Test fun ambiguousDatesRequireReview() {
        assertNull(parse("9月24日通知，9月28日另行安排").at)
        assertEquals("2026-09-28 17:00", parse("9月24日通知，截止9月28日17:00").label)
    }
    @Test fun validatesLeapYearsAndChinesePeriods() {
        assertNull(parse("2026年2月29日").at)
        assertEquals("2028-02-29 18:30", parse("2028年2月29日晚上6:30").label)
    }
}
