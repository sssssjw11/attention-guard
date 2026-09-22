package com.jev.probe.jev

import android.util.Log
import com.jev.probe.core.Analysis
import com.jev.probe.core.ApiProvider
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Choice
import com.jev.probe.core.RankedReply
import com.jev.probe.core.Score
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs the same chat-assistant flow through one of two user-selectable backends:
 *
 * - OpenRouter keeps the original Jev decisions + generative reply pipeline.
 * - DeepSeek Official uses its OpenAI-compatible Chat Completions endpoint and
 *   JSON Output for judgments, reply generation, and ranking.
 *
 * The API key is passed in per call; it is never logged.
 */
class JevClient(
    private val key: String,
    private val replyModel: String,
    private val provider: ApiProvider = ApiProvider.OPENROUTER
) {

    private val decisionsUrl = "https://openrouter.ai/api/alpha/decisions"
    private val openRouterChatUrl = "https://openrouter.ai/api/v1/chat/completions"
    private val deepSeekChatUrl = "https://api.deepseek.com/chat/completions"

    /** The 7 judgment questions only (fast). No candidate generation. */
    fun judge(snapshot: ChatSnapshot, relationship: String): Analysis {
        val start = System.currentTimeMillis()
        try {
            val answers = when (provider) {
                ApiProvider.OPENROUTER -> openRouterAnswers(
                    snapshot,
                    relationship,
                    JevQuestions.judge()
                )
                ApiProvider.DEEPSEEK_OFFICIAL -> deepSeekAnswers(
                    snapshot,
                    relationship,
                    JevQuestions.judge()
                )
            }
            return Analysis(
                trueIntent = parseChoice(answers.optJSONObject("true_intent")),
                dangerLevel = parseScore(answers.optJSONObject("danger_level")),
                sheNeeds = parseChoice(answers.optJSONObject("she_needs")),
                shouldReplyNow = answers.optJSONObject("should_reply_now")?.optDouble("noul"),
                bestAction = parseChoice(answers.optJSONObject("best_action")),
                tensionResolved = answers.optJSONObject("tension_resolved")?.optDouble("noul"),
                literalQuestion = answers.optJSONObject("literal_question")?.optDouble("noul"),
                rankedReplies = emptyList(),
                latencyMs = System.currentTimeMillis() - start
            )
        } catch (e: Exception) {
            Log.w(TAG, "judge failed: ${e.message}")
            return Analysis(
                null, null, null, null, null, null, null, emptyList(),
                System.currentTimeMillis() - start,
                error = readableError(e)
            )
        }
    }

    /** Draft 3 candidate replies, then rank them with the selected backend. */
    fun draftAndRank(snapshot: ChatSnapshot, relationship: String): List<RankedReply> {
        val candidates = generateCandidates(snapshot, relationship)
        val questions = JSONObject().put(
            "best_reply",
            JevQuestions.rankQuestion(candidates).getJSONObject("best_reply")
        )
        val answers = when (provider) {
            ApiProvider.OPENROUTER -> openRouterAnswers(snapshot, relationship, questions)
            ApiProvider.DEEPSEEK_OFFICIAL -> deepSeekAnswers(snapshot, relationship, questions)
        }
        return parseRanked(answers.optJSONObject("best_reply"), candidates)
    }

    /** Convenience for the settings connectivity test: judge + replies, sequential. */
    fun analyze(snapshot: ChatSnapshot, relationship: String): Analysis {
        val analysis = judge(snapshot, relationship)
        if (analysis.error != null) return analysis
        val ranked = try {
            draftAndRank(snapshot, relationship)
        } catch (e: Exception) {
            Log.w(TAG, "draft/rank failed: ${e.message}")
            emptyList()
        }
        return analysis.copy(rankedReplies = ranked)
    }

    private fun openRouterAnswers(
        snapshot: ChatSnapshot,
        relationship: String,
        questions: JSONObject
    ): JSONObject {
        val body = JSONObject()
            .put("model", "typesafe/jev-1.13")
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", questions)
        return postJson(decisionsUrl, body).optJSONObject("answers") ?: JSONObject()
    }

    /**
     * Evaluate Jev's existing calibrated question definitions with DeepSeek JSON
     * Output. The output shape deliberately mirrors OpenRouter's decisions API so
     * the original parsing and overlay behavior remain unchanged.
     */
    private fun deepSeekAnswers(
        snapshot: ChatSnapshot,
        relationship: String,
        questions: JSONObject
    ): JSONObject {
        val system = """
            You are a deterministic chat-analysis engine. Evaluate every question in the supplied JSON.
            Follow each question's instructions and criteria exactly, using the whole chat state.
            Return JSON only, with this top-level shape: {"answers": {<question key>: <answer>}}.

            Answer formats by question type:
            - noul: {"noul": number from 0 to 1}, the probability that the true criterion applies.
            - choice: {"choice": one exact criteria key, "confidence": 0..1,
              "probabilities": {every criteria key: probability}}. Probabilities must sum to about 1.
            - score: {"score": integer from 0 to criteria.length - 1, "confidence": 0..1}.

            Do not add prose, markdown, new keys, or facts not present in the state. The JSON must be complete.
        """.trimIndent()
        val input = JSONObject()
            .put("state", JevQuestions.buildState(snapshot, relationship))
            .put("questions", questions)
        val result = deepSeekJsonCompletion(system, input.toString(), temperature = 0.1, maxTokens = 1800)
        val answers = result.optJSONObject("answers")
            ?: throw RuntimeException("DeepSeek 响应缺少 answers")
        val missing = questions.keys().asSequence().filterNot(answers::has).toList()
        if (missing.isNotEmpty()) {
            throw RuntimeException("DeepSeek 响应缺少判断项：${missing.joinToString()}")
        }
        return answers
    }

    /** Ask the selected generative model for exactly 3 varied candidate replies. */
    private fun generateCandidates(snapshot: ChatSnapshot, relationship: String): List<String> {
        val convo = snapshot.messages.takeLast(10).joinToString("\n") {
            (if (it.side == "me") "我" else "对方") + "：" + it.text
        }
        val user = "关系：$relationship\n\n最近对话：\n$convo\n\n请给出 3 条候选回复。"
        return when (provider) {
            ApiProvider.OPENROUTER -> generateOpenRouterCandidates(user)
            ApiProvider.DEEPSEEK_OFFICIAL -> generateDeepSeekCandidates(user)
        }
    }

    /** Original OpenRouter candidate-generation path, kept behavior-compatible. */
    private fun generateOpenRouterCandidates(user: String): List<String> {
        val system = "你是中文即时通讯回复助手。只输出一个 JSON 数组，含且仅含 3 条候选回复文本，" +
            "三条策略要有区别（例如：一条稳妥承接、一条给具体行动或承诺、一条简短低姿态）。" +
            "每条不超过 40 字，口语、自然、像真人在聊天软件里发消息。不要解释，不要加引号以外的内容，直接输出 JSON 数组。"
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", replyModel)
            .put("messages", messages)
            .put("temperature", 0.8)
        val response = postJson(openRouterChatUrl, body)
        return parseThree(chatContent(response))
    }

    /** DeepSeek JSON Output requires an object at the root, hence the replies key. */
    private fun generateDeepSeekCandidates(user: String): List<String> {
        val system = "你是中文即时通讯回复助手。只输出 JSON 对象，格式为 " +
            "{\"replies\":[\"回复1\",\"回复2\",\"回复3\"]}。数组必须含且仅含 3 条不同策略的候选回复：" +
            "一条稳妥承接、一条给具体行动或承诺、一条简短低姿态。每条不超过 40 字，口语、自然，" +
            "像真人在聊天软件里发消息。不要解释，不要输出 markdown。"
        val result = deepSeekJsonCompletion(system, user, temperature = 0.8, maxTokens = 512)
        val replies = result.optJSONArray("replies")
            ?: throw RuntimeException("DeepSeek 响应缺少 replies")
        val out = ArrayList<String>()
        for (i in 0 until replies.length()) {
            val text = replies.optString(i).trim()
            if (text.isNotEmpty()) out.add(text)
        }
        if (out.size < 3) throw RuntimeException("DeepSeek 返回的候选回复不足 3 条")
        return out.take(3)
    }

    private fun deepSeekJsonCompletion(
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int
    ): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", replyModel)
            .put("messages", messages)
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("temperature", temperature)
            .put("max_tokens", maxTokens)

        // DeepSeek documents that JSON Output can rarely be empty. A response can
        // also occasionally be truncated before the final brace, so retry JSON
        // decoding as well; HTTP-level retries remain in postJson().
        var lastParseError: Exception? = null
        repeat(3) {
            val content = chatContent(postJson(deepSeekChatUrl, body))
            if (content.isBlank()) {
                lastParseError = RuntimeException("DeepSeek 返回了空 JSON")
                return@repeat
            }
            try {
                return parseJsonObject(content)
            } catch (e: Exception) {
                lastParseError = e
            }
        }
        throw lastParseError ?: RuntimeException("DeepSeek 返回了无效 JSON")
    }

    private fun chatContent(response: JSONObject): String =
        response.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content") ?: ""

    private fun parseJsonObject(content: String): JSONObject {
        val trimmed = content.trim()
        try {
            return JSONObject(trimmed)
        } catch (_: Exception) {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) return JSONObject(trimmed.substring(start, end + 1))
            throw RuntimeException("DeepSeek 返回的内容不是有效 JSON")
        }
    }

    private fun parseThree(content: String): List<String> {
        val start = content.indexOf('[')
        val end = content.lastIndexOf(']')
        if (start >= 0 && end > start) {
            try {
                val arr = JSONArray(content.substring(start, end + 1))
                val out = ArrayList<String>()
                for (i in 0 until arr.length()) out.add(arr.getString(i).trim())
                if (out.size >= 3) return out.take(3)
                while (out.size < 3) out.add("（稍等，我看下）")
                return out
            } catch (_: Exception) {
            }
        }
        // Fallback: split lines.
        val lines = content.split("\n")
            .map { it.trim().trimStart('-', '*', '1', '2', '3', '.', ' ', '"') }
            .filter { it.isNotBlank() }
        val out = lines.take(3).toMutableList()
        while (out.size < 3) out.add("（稍等，我看下）")
        return out
    }

    private fun parseChoice(o: JSONObject?): Choice? {
        o ?: return null
        val probs = HashMap<String, Double>()
        o.optJSONObject("probabilities")?.let { p ->
            p.keys().forEach { k -> probs[k] = p.optDouble(k) }
        }
        return Choice(o.optString("choice"), o.optDouble("confidence", 0.0), probs)
    }

    private fun parseScore(o: JSONObject?): Score? {
        o ?: return null
        val legend = o.optJSONObject("legend")
        val maxLevel = legend?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.maxOrNull() ?: 9
        return Score(o.optDouble("score", 0.0), o.optDouble("confidence", 0.0), maxLevel)
    }

    private fun parseRanked(o: JSONObject?, candidates: List<String>): List<RankedReply> {
        val keys = listOf("reply_a", "reply_b", "reply_c")
        val probs = o?.optJSONObject("probabilities")
        val choice = o?.optString("choice")
        val confidence = o?.optDouble("confidence", 0.0) ?: 0.0
        val list = candidates.mapIndexed { i, text ->
            val keyName = keys.getOrElse(i) { "" }
            val probability = when {
                probs?.has(keyName) == true -> probs.optDouble(keyName, 0.0)
                keyName == choice -> confidence
                choice?.isNotBlank() == true -> (1.0 - confidence).coerceAtLeast(0.0) / 2.0
                else -> 0.0
            }
            RankedReply(text, probability)
        }
        return list.sortedByDescending { it.prob }
    }

    /** POST JSON with one retry chain for rate-limit and transient failures. */
    private fun postJson(urlStr: String, body: JSONObject): JSONObject {
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                    if (provider == ApiProvider.OPENROUTER) {
                        setRequestProperty("HTTP-Referer", "https://jev-assistant.local")
                        setRequestProperty("X-Title", "Jev Assistant")
                    }
                }
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use { output: OutputStream -> output.write(bytes) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
                }.orEmpty()
                if (code == 429 || code == 529 || code in 500..504) {
                    lastErr = RuntimeException("HTTP $code: ${text.take(160)}")
                    attempt++
                    if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
                    continue
                }
                if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(160)}")
                return JSONObject(text)
            } catch (e: Exception) {
                lastErr = e
                if (e.message?.contains("HTTP 4") == true) throw e // client error: no retry
                attempt++
                if (attempt < 3) Thread.sleep(500L * (1L shl attempt))
            } finally {
                conn?.disconnect()
            }
        }
        throw lastErr ?: RuntimeException("request failed")
    }

    private fun readableError(e: Exception): String {
        val message = e.message ?: e.javaClass.simpleName
        return when {
            message.contains("HTTP 401") -> "密钥无效或未设置（401）"
            message.contains("HTTP 402") -> "账户余额不足或不可用（402）"
            message.contains("HTTP 429") -> "请求过于频繁，请稍后重试（429）"
            message.contains("HTTP 4") -> "请求被拒：$message"
            message.contains("timed out") || message.contains("timeout") -> "网络超时，请检查连接"
            message.contains("Unable to resolve host") || message.contains("Failed to connect") -> "无法连接网络"
            else -> "分析失败：$message"
        }
    }

    companion object {
        private const val TAG = "JEVASSIST"
    }
}
