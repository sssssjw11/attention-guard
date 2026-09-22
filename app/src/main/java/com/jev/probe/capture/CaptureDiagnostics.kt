package com.attentionguard.app.capture

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded metadata only: no chat title, body, screenshot or API key. */
class CaptureDiagnostics(context: Context) {
    private val sp = context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE)
    private val weChatVersion = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo("com.tencent.mm", 0).versionName
    }.getOrNull() ?: "未能获取"
    fun heartbeat(connected: Boolean) = sp.edit().putBoolean("connected", connected)
        .putLong("heartbeat", System.currentTimeMillis()).apply()
    fun state(value: String) { if (sp.getString("state", "") != value) sp.edit().putString("state", value).apply() }
    fun inspected(nodes: Int, known: Int, structural: Int, messages: Int, reason: String) {
        sp.edit().putInt("nodes", nodes).putInt("known", known).putInt("structural", structural)
            .putInt("messages", messages).putString("read", reason).putLong("inspected", System.currentTimeMillis()).apply()
    }
    fun saved(added: Int) = sp.edit().putInt("saved", sp.getInt("saved", 0) + added)
        .putLong("saved_at", System.currentTimeMillis()).putString("storage", "正常").apply()
    fun storageError() = sp.edit().putString("storage", "保存失败，未覆盖已有消息").apply()
    fun overlay(value: String) {
        if (sp.getString("overlay", "") == value) return
        val edit = sp.edit().putString("overlay", value)
        if (value != "已隐藏") edit.putString("overlay_result", value).putLong("overlay_at", System.currentTimeMillis())
        edit.apply()
    }
    fun keepAlive(value: String) = sp.edit().putString("keep_alive", value).apply()
    fun summary(now: Long = System.currentTimeMillis()): String {
        val connected = sp.getBoolean("connected", false) && now - sp.getLong("heartbeat", 0) in 0..10_000
        fun time(key: String) = sp.getLong(key, 0).let { if (it == 0L) "无" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(it)) }
        return listOf(
            "服务：${if (connected) "已连接" else "未连接或心跳过期"}",
            "当前状态：${sp.getString("state", "等待开启无障碍")}",
            "上次微信检查：${time("inspected")}",
            "读取结果：${sp.getString("read", "尚未读取")}",
            "节点 ${sp.getInt("nodes", 0)} · 气泡 ${sp.getInt("known", 0)} · 结构匹配 ${sp.getInt("structural", 0)} · 消息 ${sp.getInt("messages", 0)}",
            "上次落盘：${time("saved_at")} · ${sp.getString("storage", "尚未保存")}",
            "悬浮窗：${sp.getString("overlay", "尚未显示")}",
            "上次挂窗：${time("overlay_at")} · ${sp.getString("overlay_result", "尚未尝试")}",
            "保活通知：${sp.getString("keep_alive", "尚未启动")}",
            "微信版本：$weChatVersion",
            "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} · ${android.os.Build.MODEL}"
        ).joinToString("\n")
    }
}
