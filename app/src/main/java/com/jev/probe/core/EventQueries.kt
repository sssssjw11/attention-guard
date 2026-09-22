package com.jev.probe.core

enum class EventFilter(val label: String) { ALL("全部"), ACTION("待处理"), FOLLOWING("关注中"), COMPLETED("已完成") }

fun AttentionEvent.needsAction(): Boolean = status != EventStatus.COMPLETED &&
    (status == EventStatus.ACTION_REQUIRED || priority == EventPriority.P0)

fun AttentionEvent.withCompletion(completed: Boolean): AttentionEvent = when {
    completed && status != EventStatus.COMPLETED -> copy(status = EventStatus.COMPLETED, previousStatus = status)
    !completed && status == EventStatus.COMPLETED -> copy(status = previousStatus ?: EventStatus.ACTION_REQUIRED, previousStatus = null)
    else -> this
}

fun filterEvents(events: List<AttentionEvent>, filter: EventFilter, query: String): List<AttentionEvent> {
    val search = query.trim()
    return events.filter { event ->
        val matchesFilter = when (filter) {
            EventFilter.ALL -> true
            EventFilter.ACTION -> event.needsAction()
            EventFilter.FOLLOWING -> !event.needsAction() && event.status != EventStatus.COMPLETED
            EventFilter.COMPLETED -> event.status == EventStatus.COMPLETED
        }
        matchesFilter && (search.isBlank() || listOf(event.title, event.summary, event.sourceGroup, event.sourcePerson)
            .any { it.contains(search, ignoreCase = true) })
    }.sortedWith(compareBy<AttentionEvent> { it.status == EventStatus.COMPLETED }.thenBy { it.priority.ordinal })
}
