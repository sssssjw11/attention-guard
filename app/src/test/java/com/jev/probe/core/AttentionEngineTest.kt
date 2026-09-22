package com.jev.probe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun deadlineAndActionBecomeHighPriority() {
        val snapshot = ChatSnapshot(
            title = "班级通知群",
            messages = listOf(
                Msg("other", "辅导员：请在明天 17:00 前提交综测材料", "辅导员"),
                Msg("other", "逾期视为放弃", "辅导员")
            )
        )
        val event = AttentionEngine.buildEvent(snapshot)
        assertNotNull(event)
        assertEquals(EventPriority.P0, event?.priority)
        assertEquals(EventCategory.ACADEMIC_ADMIN, event?.category)
        assertEquals(EventStatus.ACTION_REQUIRED, event?.status)
        assertEquals("明日 17:00", event?.dueLabel)
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
}
