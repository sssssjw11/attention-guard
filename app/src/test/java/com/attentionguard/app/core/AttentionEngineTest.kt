package com.attentionguard.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AttentionEngineTest {

    @Test
    fun casualConversationDoesNotCreateEvent() {
        val snapshot = ChatSnapshot(
            title = "宿舍闲聊群",
            messages = listOf(
                Msg("other", "今天晚饭吃什么？", "小王"),
                Msg("other", "我想吃面", "小李")
            )
        )
        assertNull(AttentionEngine.buildEvent(snapshot))
    }

    @Test
    fun verifiedDeadlineWithinTwentyFourHoursBecomesHighPriority() {
        val day = LocalDate.of(2026, 9, 23)
        val snapshot = ChatSnapshot(
            title = "班级通知群",
            messages = listOf(
                Msg("other", "辅导员：请在今天 17:00 前提交综测材料", "辅导员", date = day.toString()),
                Msg("other", "逾期视为放弃", "辅导员")
            ), capturedAt = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        val event = AttentionEngine.buildEvent(snapshot)
        assertNotNull(event)
        assertEquals(EventPriority.P0, event?.priority)
        assertEquals(EventCategory.ACADEMIC_ADMIN, event?.category)
        assertEquals(EventStatus.ACTION_REQUIRED, event?.status)
        assertEquals("2026-09-23 17:00", event?.dueLabel)
    }

    @Test
    fun locationChangeIsClassifiedAsCourse() {
        val snapshot = ChatSnapshot(
            title = "课程群",
            messages = listOf(Msg("other", "明天下午实验课调整到 3-105，请按新教室到课", "张老师"))
        )
        val event = AttentionEngine.buildEvent(snapshot)
        assertNotNull(event)
        assertEquals(EventCategory.COURSE, event?.category)
        assertNotNull(event?.actionLabel)
    }

    @Test
    fun sameConversationAndTitleUseStableEventId() {
        val first = ChatSnapshot(
            title = "学院通知群",
            messages = listOf(Msg("other", "奖学金申请材料请报名登记", "学院教务"))
        )
        val second = first.copy(messages = first.messages + Msg("other", "请继续关注后续通知", "学院教务"))
        val firstEvent = AttentionEngine.buildEvent(first)
        val secondEvent = AttentionEngine.buildEvent(second)
        assertNotNull(firstEvent)
        assertNotNull(secondEvent)
        assertEquals(firstEvent?.id, secondEvent?.id)
    }

    @Test
    fun weakActionKeepsReviewNoteAndDoesNotEscalate() {
        val event = AttentionEngine.buildEvent(ChatSnapshot(
            title = "课程群",
            messages = listOf(Msg("other", "请关注后续安排，报名意向请回复", "群成员"))
        ))
        assertNotNull(event)
        assertEquals(EventPriority.P2, event?.priority)
        assertTrue(event?.reviewNotes?.any { it.contains("明确行动要求") } == true)
    }

    private fun event(vararg messages: Msg) = AttentionEngine.buildEvent(ChatSnapshot("Synthetic group", messages.toList(),
        capturedAt = LocalDate.of(2026, 9, 23).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))!!

    @Test fun distantDeadlineAndUnknownRelativeDayCannotBecomeP0() {
        assertEquals(EventPriority.P1, event(Msg("other", "请在2026年10月12日17:00前提交报告", "老师")).priority)
        val unknown = event(Msg("other", "请在明天17:00前提交报告", "老师"))
        assertEquals(EventPriority.P1, unknown.priority)
        assertTrue(unknown.dueLabel!!.contains("待核对"))
    }

    @Test fun expiredNoticeIsNotAnUrgentAction() {
        val past = event(Msg("other", "请在2026年9月20日17:00前提交报告", "老师"))
        assertEquals(EventPriority.P2, past.priority)
        assertEquals(EventStatus.MONITORING, past.status)
    }

    @Test fun unrelatedMessagesCannotSupplyAuthorityOrDeadline() {
        val result = event(Msg("other", "老师：明天17:00活动开始", "群成员"), Msg("other", "请提交报名意向", "同学"))
        assertEquals(EventPriority.P2, result.priority)
        assertNull(result.dueLabel)
    }

    @Test fun correctionKeepsIdentityAndUsesLatestTime() {
        val original = Msg("other", "请于2026年9月23日17:00前提交报告", "老师")
        val first = event(original)
        val corrected = event(original, Msg("other", "更正：改为2026年9月25日17:00", "老师"))
        assertEquals(first.id, corrected.id)
        assertEquals("2026-09-25 17:00", corrected.dueLabel)
        assertEquals(EventPriority.P1, corrected.priority)
        assertEquals(2, corrected.evidence.size)
    }

    @Test fun cancellationIsReviewableAndNeverAutoCompletes() {
        val cancelled = event(Msg("other", "请提交报告", "老师"), Msg("other", "更正：本次提交取消", "老师"))
        assertEquals(EventPriority.P3, cancelled.priority)
        assertEquals(EventStatus.MONITORING, cancelled.status)
        assertNull(cancelled.actionLabel)
    }

    @Test fun separateNoticesInSameCategoryDoNotShareIdentity() {
        val a = event(Msg("other", "请提交网络实验报告", "老师"))
        val b = event(Msg("other", "请提交数据库实验报告", "老师"))
        org.junit.Assert.assertNotEquals(a.id, b.id)
    }
}



