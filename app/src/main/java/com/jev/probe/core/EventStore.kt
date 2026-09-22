package com.jev.probe.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small local event repository. It keeps the MVP useful without a server. */
class EventStore(context: Context) {

    private val prefs = context.getSharedPreferences("attention_guard_events", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, FILE_NAME)

    var readFailed = false
        private set

    fun load(): List<AttentionEvent> = synchronized(LOCK) {
        readFailed = false
        runCatching {
            // Never fall back to an older preference value if the new file is damaged.
            val raw = if (file.exists()) file.readText(Charsets.UTF_8)
                else prefs.getString(KEY_EVENTS, null) ?: return@synchronized emptyList()
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
            }.filterNot { it.id in LEGACY_DEMO_IDS }
        }.getOrElse { readFailed = true; emptyList() }
    }

    fun upsert(incoming: AttentionEvent): List<AttentionEvent> = synchronized(LOCK) {
        val current = load().toMutableList()
        check(!readFailed) { "本地记录暂时无法读取，未覆盖原数据" }
        val index = current.indexOfFirst { it.id == incoming.id }
        if (index >= 0) current[index] = merge(current[index], incoming) else current.add(0, incoming)
        save(current)
        current
    }

    fun setCompleted(id: String, completed: Boolean): List<AttentionEvent> = synchronized(LOCK) {
        val current = load().map { event ->
            if (event.id != id) event else event.withCompletion(completed)
        }
        check(!readFailed) { "本地记录暂时无法读取，未覆盖原数据" }
        save(current)
        current
    }

    private fun merge(old: AttentionEvent, fresh: AttentionEvent): AttentionEvent {
        val updates = (old.updates + fresh.updates).distinctBy { "${it.title}|${it.detail}" }
        return fresh.copy(
            status = if (old.status == EventStatus.COMPLETED) old.status else fresh.status,
            previousStatus = old.previousStatus,
            updates = updates.takeLast(8),
            evidence = (old.evidence + fresh.evidence).distinct().takeLast(6),
            updatedLabel = fresh.updatedLabel.ifBlank { old.updatedLabel }
        )
    }

    private fun save(events: List<AttentionEvent>) {
        val bytes = JSONArray().apply {
            events.forEach { put(toJson(it)) }
        }.toString().toByteArray(Charsets.UTF_8)
        val pending = File(file.parentFile, "${file.name}.pending")
        try {
            FileOutputStream(pending).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            // Same-directory atomic replacement either commits or throws. There is
            // no SharedPreferences memory update before a successful disk write.
            Files.move(pending.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (pending.isFile) pending.delete()
        }
    }

    private fun toJson(event: AttentionEvent): JSONObject = JSONObject().apply {
        put("id", event.id)
        put("title", event.title)
        put("summary", event.summary)
        put("sourceGroup", event.sourceGroup)
        put("sourcePerson", event.sourcePerson)
        put("priority", event.priority.name)
        put("status", event.status.name)
        put("previousStatus", event.previousStatus?.name)
        put("analysisSource", event.analysisSource)
        put("category", event.category.name)
        put("attentionScore", event.attentionScore)
        put("dueLabel", event.dueLabel)
        put("actionLabel", event.actionLabel)
        put("consequence", event.consequence)
        put("updatedLabel", event.updatedLabel)
        put("updates", JSONArray().apply {
            event.updates.forEach { update ->
                put(JSONObject().apply {
                    put("time", update.time)
                    put("title", update.title)
                    put("detail", update.detail)
                    put("tone", update.tone.name)
                })
            }
        })
        put("evidence", JSONArray(event.evidence))
    }

    private fun fromJson(json: JSONObject): AttentionEvent {
        for (field in listOf("id", "title", "sourceGroup")) {
            val value = json.get(field)
            require(value is String && value.isNotBlank())
        }
        return AttentionEvent(
            id = json.optString("id"),
            title = json.optString("title"),
            summary = json.optString("summary"),
            sourceGroup = json.optString("sourceGroup"),
            sourcePerson = json.optString("sourcePerson"),
            priority = enumOr(EventPriority.P2, json.optString("priority")),
            status = enumOr(EventStatus.MONITORING, json.optString("status")),
            previousStatus = json.optString("previousStatus").takeIf { it.isNotBlank() && it != "null" }?.let {
                enumOr(EventStatus.ACTION_REQUIRED, it)
            },
            analysisSource = json.optString("analysisSource", "本地规则"),
            category = enumOr(EventCategory.ACTIVITY, json.optString("category")),
            attentionScore = json.optInt("attentionScore", 0),
            dueLabel = json.optString("dueLabel").takeIf { it.isNotBlank() && it != "null" },
            actionLabel = json.optString("actionLabel").takeIf { it.isNotBlank() && it != "null" },
            consequence = json.optString("consequence").takeIf { it.isNotBlank() && it != "null" },
            updatedLabel = json.optString("updatedLabel"),
            updates = json.optJSONArray("updates")?.let { array ->
                buildList(array.length()) {
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        add(EventUpdate(
                            time = item.optString("time"),
                            title = item.optString("title"),
                            detail = item.optString("detail"),
                            tone = enumOr(UpdateTone.NEUTRAL, item.optString("tone"))
                        ))
                    }
                }
            }.orEmpty(),
            evidence = json.optJSONArray("evidence")?.let { array ->
                buildList(array.length()) { for (i in 0 until array.length()) add(array.optString(i)) }
            }.orEmpty()
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(fallback: T, value: String): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    companion object {
        internal const val FILE_NAME = "attention_guard_events_v1.json"
        private const val KEY_EVENTS = "events_json"
        private val LOCK = Any()
        // Only the four fixed fixture IDs from v1.2. Live engine IDs have a different format.
        private val LEGACY_DEMO_IDS = setOf("assessment", "network-lab", "scholarship", "career-fair")
    }
}

fun attentionStatsFrom(events: List<AttentionEvent>): AttentionStats = AttentionStats(
    observed = events.size,
    actionRequired = events.count {
        it.status != EventStatus.COMPLETED &&
            (it.status == EventStatus.ACTION_REQUIRED || it.priority == EventPriority.P0)
    },
    dueSoon = events.count { it.dueLabel != null && it.status != EventStatus.COMPLETED },
    completed = events.count { it.status == EventStatus.COMPLETED }
)
