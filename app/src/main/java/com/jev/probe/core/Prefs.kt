package com.attentionguard.app.core

import android.content.Context

/**
 * App-private config store. Holds provider-specific keys and model choices, the
 * relationship description used for event extraction, and the conversation whitelist.
 *
 * Active DeepSeek credentials are encrypted with an Android Keystore key.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("attention_guard", Context.MODE_PRIVATE)

    var openRouterKey: String
        get() = sp.getString(K_KEY, "") ?: ""
        set(v) = sp.edit().putString(K_KEY, v.trim()).apply()

    /** Legacy reply model kept only so existing installs can migrate cleanly. */
    var replyModel: String
        get() = sp.getString(K_REPLY_MODEL, DEFAULT_REPLY_MODEL) ?: DEFAULT_REPLY_MODEL
        set(v) = sp.edit().putString(K_REPLY_MODEL, v.trim()).apply()

    var apiProvider: ApiProvider
        get() = ApiProvider.fromStorage(sp.getString(K_API_PROVIDER, null))
        set(v) = sp.edit().putString(K_API_PROVIDER, v.storageValue).apply()

    var deepSeekKey: String
        get() = synchronized(KEY_LOCK) {
            val encrypted = sp.getString("deepseek_encrypted_v1", null)
            if (encrypted != null) return@synchronized runCatching { KeyVault.decrypt(encrypted) }.getOrDefault("")
            val legacy = sp.getString(K_DEEPSEEK_KEY, "").orEmpty()
            if (legacy.isNotBlank()) runCatching { deepSeekKey = legacy }.getOrElse { return@synchronized "" }
            legacy
        }
        set(v) = synchronized(KEY_LOCK) {
            val editor = sp.edit().remove(K_DEEPSEEK_KEY)
            if (v.isBlank()) editor.remove("deepseek_encrypted_v1")
            else editor.putString("deepseek_encrypted_v1", KeyVault.encrypt(v.trim()))
            check(editor.commit()) { "密钥保存失败" }
        }

    val keyUnavailable: Boolean
        get() = (sp.contains("deepseek_encrypted_v1") || !sp.getString(K_DEEPSEEK_KEY, "").isNullOrBlank()) && deepSeekKey.isBlank()

    var cloudEnabled: Boolean
        get() = sp.getBoolean("cloud_enabled", false)
        set(v) = sp.edit().putBoolean("cloud_enabled", v).apply()

    var localOcrEnabled: Boolean
        get() = sp.getBoolean("local_ocr_enabled", false)
        set(v) = sp.edit().putBoolean("local_ocr_enabled", v).apply()

    var demoMode: Boolean
        get() = sp.getBoolean("demo_mode", false)
        set(v) = sp.edit().putBoolean("demo_mode", v).apply()

    var deepSeekModel: String
        get() = sp.getString(K_DEEPSEEK_MODEL, DEFAULT_DEEPSEEK_MODEL) ?: DEFAULT_DEEPSEEK_MODEL
        set(v) = sp.edit().putString(K_DEEPSEEK_MODEL, v.trim()).apply()

    /** Free-text describing the active group context; goes into event extraction. */
    var relationship: String
        get() = sp.getString(K_REL, DEFAULT_REL) ?: DEFAULT_REL
        set(v) = sp.edit().putString(K_REL, v).apply()

    /** Master on/off for showing the overlay + running attention observation. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    /**
     * Conversation whitelist: titles the assistant is allowed to act on. Empty
     * set means "all conversations". Stored as a plain string set.
     */
    var whitelist: Set<String>
        get() = sp.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(K_WHITELIST, v).apply()

    /** Overlay panel opacity, 60..100 (%). Lower lets the chat show through. */
    var overlayOpacity: Int
        get() = sp.getInt(K_OPACITY, 92).coerceIn(60, 100)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(60, 100)).apply()

    /** Remembered vertical position of the bubble (px); -1 = default. */
    var bubbleY: Int
        get() = sp.getInt(K_BUBBLE_Y, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_Y, v).apply()

    /** Remembered horizontal position of the bubble (px); -1 = default. */
    var bubbleX: Int
        get() = sp.getInt(K_BUBBLE_X, -1)
        set(v) = sp.edit().putInt(K_BUBBLE_X, v).apply()

    /** Auto-observe incoming message bursts; if false, user taps to analyze. */
    var autoAnalyze: Boolean
        get() = sp.getBoolean(K_AUTO, true)
        set(v) = sp.edit().putBoolean(K_AUTO, v).apply()

    fun isAllowed(title: String?): Boolean {
        val wl = whitelist
        if (wl.isEmpty()) return true
        if (title == null) return false
        return wl.any { title.contains(it) }
    }

    fun activeKey(): String = deepSeekKey

    fun activeModel(): String = deepSeekModel

    fun hasKey(): Boolean = activeKey().isNotBlank()

    companion object {
        private val KEY_LOCK = Any()
        private const val K_KEY = "openrouter_key"
        private const val K_REPLY_MODEL = "reply_model"
        private const val K_API_PROVIDER = "api_provider"
        private const val K_DEEPSEEK_KEY = "deepseek_key"
        private const val K_DEEPSEEK_MODEL = "deepseek_model"
        private const val K_REL = "relationship"
        private const val K_ENABLED = "enabled"
        private const val K_WHITELIST = "whitelist"
        private const val K_OPACITY = "overlay_opacity"
        private const val K_BUBBLE_Y = "bubble_y"
        private const val K_BUBBLE_X = "bubble_x"
        private const val K_AUTO = "auto_analyze"

        // Kept for migration compatibility with the original reply assistant.
        const val DEFAULT_REPLY_MODEL = "deepseek/deepseek-chat-v3.1"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-flash"
        const val DEFAULT_REL = "这是校园微信群；优先关注老师、辅导员、班委发布的通知、截止时间和明确行动要求。"
    }
}
