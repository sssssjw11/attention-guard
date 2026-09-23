package com.attentionguard.app.core

/** A local, explainable reading of the latest visible incoming WeChat text. */
data class IntentInsight(
    val label: String,
    val nextStep: String,
    val evidence: String,
    val sender: String,
    val group: String,
    val capturedAt: Long,
    val eventId: String? = null
)

object JevIntentEngine {
    private val closing = Regex("没事了|不用了|解决了|取消|作废|先这样|改天再说")
    private val reassurance = Regex("在乎我|关心我|还爱|想我|是不是不想|是不是忘|还记得")
    private val emotion = Regex("烦死|气死|难过|委屈|受不了|崩溃|生气")
    private val action = Regex("请|麻烦|帮我|记得|务必|需要|提交|报名|回复|确认|发给|完成|到场")
    private val question = Regex("为什么|怎么|如何|什么意思|是否|能否|请问|[？?]")
    private val questionOpening = Regex("^(请问|为什么|怎么|如何|能否|是否)")

    fun analyze(snapshot: ChatSnapshot): IntentInsight? {
        if (snapshot.sourcePackage != "com.tencent.mm" || snapshot.title.isNullOrBlank()) return null
        val message = snapshot.messages.lastOrNull {
            it.side == "other" && it.type == MessageType.TEXT && it.text.isNotBlank() && it.captureMethod == "nodes"
        } ?: return null
        val text = message.text.trim()
        val (label, nextStep) = when {
            closing.containsMatchIn(text) -> "话题可能已结束" to "核对上下文，不自动关闭已有事项。"
            reassurance.containsMatchIn(text) -> "可能在确认关系或关注" to "先回应对方关切，再决定是否解释具体问题。"
            emotion.containsMatchIn(text) -> "可能在表达情绪" to "先确认对方感受，不急于给出结论。"
            questionOpening.containsMatchIn(text) -> "可能在寻求解释" to "先核对问题所指，再简明回答。"
            action.containsMatchIn(text) -> "可能在提出行动请求" to "核对对象和期限；明确与你有关时再处理。"
            question.containsMatchIn(text) -> "可能在寻求解释" to "先核对问题所指，再简明回答。"
            else -> "暂无明确请求" to "无需立即行动；继续观察上下文。"
        }
        return IntentInsight(label, nextStep, text.take(120), message.sender?.takeIf { it.isNotBlank() } ?: "对方",
            snapshot.title, snapshot.capturedAt)
    }
}
