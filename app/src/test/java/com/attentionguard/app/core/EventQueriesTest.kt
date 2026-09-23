package com.attentionguard.app.core

import org.junit.Assert.*
import org.junit.Test

class EventQueriesTest {
    private val events = DemoAttentionData.events
    @Test fun completionRestoresMonitoringInsteadOfInventingAnAction() {
        val original = events.first { it.status == EventStatus.MONITORING }
        val completed = original.withCompletion(true)
        assertEquals(EventStatus.COMPLETED, completed.status)
        assertEquals(original.status, completed.withCompletion(false).status)
    }
    @Test fun completionIsIdempotent() {
        val completed = events[0].withCompletion(true)
        assertEquals(completed, completed.withCompletion(true))
        assertEquals(events[0], completed.withCompletion(false))
    }
    @Test fun completedP0IsNeverActionable() {
        val completed = events[0].withCompletion(true)
        assertFalse(completed.needsAction())
        assertTrue(filterEvents(listOf(completed), EventFilter.ACTION, "").isEmpty())
        assertEquals(1, filterEvents(listOf(completed), EventFilter.COMPLETED, "").size)
    }
    @Test fun archiveIsReversibleAndIndependentOfCompletion() {
        val completed = events[0].withCompletion(true)
        val archived = completed.withArchive(true)
        assertEquals(EventStatus.COMPLETED, archived.status)
        assertFalse(archived.needsAction())
        assertTrue(filterEvents(listOf(archived), EventFilter.ALL, "").isEmpty())
        assertTrue(filterEvents(listOf(archived), EventFilter.COMPLETED, "").isEmpty())
        assertEquals(listOf(archived), filterEvents(listOf(archived), EventFilter.ARCHIVED, ""))
        assertEquals(completed, archived.withArchive(false))
        val stats = attentionStatsFrom(listOf(events[0], archived))
        assertEquals(1, stats.observed)
        assertEquals(1, stats.actionRequired)
        assertEquals(0, stats.completed)
    }
    @Test fun searchUsesGroupAndTitleAndTrimmedQuery() {
        assertEquals(1, filterEvents(events, EventFilter.ALL, "  双选会 ").size)
        assertEquals(1, filterEvents(events, EventFilter.ALL, "计算机网络课程群").size)
        assertTrue(filterEvents(events, EventFilter.ALL, "不存在的事件").isEmpty())
    }
    @Test fun emptyAndFollowingFiltersAreConsistent() {
        assertTrue(filterEvents(emptyList(), EventFilter.ALL, "").isEmpty())
        assertEquals(3, filterEvents(events, EventFilter.FOLLOWING, "").size)
        assertEquals(1, filterEvents(events, EventFilter.ACTION, "").size)
    }
    @Test fun signatureIncludesConversationAndApplication() {
        val sample = ChatSnapshot("群 A", listOf(Msg("other", "同一条消息")), "wechat")
        assertNotEquals(sample.signature(), sample.copy(title = "群 B").signature())
        assertNotEquals(sample.signature(), sample.copy(sourcePackage = "other").signature())
        assertEquals(sample.signature(), sample.copy(capturedAt = 1).signature())
    }
}
