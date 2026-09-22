package com.attentionguard.app.core

enum class MessageType { TEXT, IMAGE, FILE, SYSTEM, UNKNOWN }

/** One captured chat bubble. side is "me" (right) or "other" (left). */
data class Msg(
    val side: String,
    val text: String,
    val sender: String? = null,
    val timestamp: Long? = null,
    val type: MessageType = MessageType.TEXT,
    val mentions: List<String> = emptyList(),
    // A separator proves a day, not an exact per-message time.
    val date: String? = null,
    val timeLabel: String? = null,
    val captureMethod: String = "nodes"
)

/** A snapshot of the currently-open conversation in whichever chat app is
 *  foreground (see ChatAppAdapter). */
data class ChatSnapshot(
    val title: String?,
    val messages: List<Msg>,
    val sourcePackage: String? = null,
    val capturedAt: Long = System.currentTimeMillis()
) {
    val latestFrom: String? get() = messages.lastOrNull()?.side

    val isLikelyGroup: Boolean
        get() = title?.contains("群") == true || messages.mapNotNull { it.sender }.distinct().size > 1

    /** Include the entire viewport so scrolling at the top is not discarded. */
    fun signature(): String =
        "${sourcePackage.orEmpty()}|${title.orEmpty()}|" + messages.joinToString("|") {
            "${it.side}:${it.sender.orEmpty()}:${it.type}:${it.text.length}:${it.text}:${it.date}:${it.timeLabel}:${it.captureMethod}"
        }
}

/** Optional structured model result retained for compatibility with stored data. */
data class Analysis(
    val trueIntent: Choice?,
    val dangerLevel: Score?,
    val sheNeeds: Choice?,
    val shouldReplyNow: Double?,
    val bestAction: Choice?,
    val tensionResolved: Double?,
    val literalQuestion: Double?,
    val rankedReplies: List<RankedReply>,
    val latencyMs: Long,
    val error: String? = null
)

data class Choice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)
data class Score(val score: Double, val confidence: Double, val maxLevel: Int)
data class RankedReply(val text: String, val prob: Double)
