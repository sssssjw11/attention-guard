package com.attentionguard.app.core

import org.junit.Assert.*
import org.junit.Test

class JevIntentEngineTest {
    private fun snapshot(text: String, sender: String = "同学") = ChatSnapshot("课程群",
        listOf(Msg("other", text, sender)), "com.tencent.mm", 1_700_000_000_000L)

    @Test fun actionableMessageProducesTraceableNextStep() {
        val insight = requireNotNull(JevIntentEngine.analyze(snapshot("请大家明天提交作业")))
        assertEquals("可能在提出行动请求", insight.label)
        assertEquals("请大家明天提交作业", insight.evidence)
        assertEquals("同学", insight.sender)
        assertEquals("课程群", insight.group)
    }

    @Test fun emotionalAndClosingMessagesDoNotBecomeTasks() {
        assertEquals("可能在表达情绪", JevIntentEngine.analyze(snapshot("今天真难过"))?.label)
        assertEquals("话题可能已结束", JevIntentEngine.analyze(snapshot("不用了，已经解决了"))?.label)
        assertEquals("可能在确认关系或关注", JevIntentEngine.analyze(snapshot("你是不是不想理我了"))?.label)
        assertEquals("可能在寻求解释", JevIntentEngine.analyze(snapshot("为什么这样安排？"))?.label)
        assertEquals("可能在寻求解释", JevIntentEngine.analyze(snapshot("请问明天要提交作业吗？"))?.label)
        assertEquals("暂无明确请求", JevIntentEngine.analyze(snapshot("今天见到了老同学"))?.label)
    }

    @Test fun unsupportedOrUnverifiedTextIsNeverAnalyzed() {
        assertNull(JevIntentEngine.analyze(snapshot("请提交作业").copy(sourcePackage = "com.other.app")))
        assertNull(JevIntentEngine.analyze(snapshot("请提交作业").copy(messages = listOf(Msg("me", "请提交作业")))))
        assertNull(JevIntentEngine.analyze(snapshot("请提交作业").copy(messages = listOf(Msg("other", "请提交作业", captureMethod = "ocr")))))
    }
}
