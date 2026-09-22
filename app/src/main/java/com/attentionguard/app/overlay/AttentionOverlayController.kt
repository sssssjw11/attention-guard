package com.attentionguard.app.overlay

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.attentionguard.app.MainActivity
import com.attentionguard.app.R
import com.attentionguard.app.core.AttentionEvent
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.capture.CaptureDiagnostics
import com.attentionguard.app.capture.HistoryState
import com.attentionguard.app.capture.HistorySession
import com.attentionguard.app.ui.GuardUi
import kotlin.math.roundToInt

/** Compact by default, no chat input access and no deferred window mounts. */
class AttentionOverlayController(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(context)
    private val ui = GuardUi(ContextThemeWrapper(context, R.style.Theme_AttentionGuard))
    private var panel: LinearLayout? = null
    private var lastEvent: AttentionEvent? = null
    private var expanded = false
    private var group: String? = null
    private var loading = false
    private var usingModel = false
    private var status = ""
    private var actionLabel = "整理当前会话"
    private var history: HistorySession? = null
    private var renderKey = ""
    private var hiddenForCapture = false
    private val diagnostics = CaptureDiagnostics(context)
    private val accessibilityWindow = context is AccessibilityService
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
    var onManualAnalyze: (() -> Unit)? = null
    var onHistorySettings: (() -> Unit)? = null
    var onHistoryStart: (() -> Unit)? = null
    var onHistoryPause: (() -> Unit)? = null
    var onHistoryCancel: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    fun isShowing() = panel != null
    fun setHiddenForCapture(hidden: Boolean) {
        hiddenForCapture = hidden
        panel?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }
    fun hide() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        renderKey = ""
        diagnostics.overlay("已隐藏")
    }
    fun showIdle(group: String?, status: String = "正在监测可见消息", history: HistorySession? = null,
                 actionLabel: String = "整理当前会话") {
        val key = "$group|$status|${history?.state}|${history?.screens}|${history?.reason}|$actionLabel"
        if (panel != null && renderKey == key && lastEvent == null && !loading) return
        this.group = group; this.status = status; this.history = history; this.actionLabel = actionLabel
        lastEvent = null; loading = false; expanded = false; render(); renderKey = key
    }
    fun showLoading(useModel: Boolean = false) {
        history = null
        lastEvent = null; loading = true; usingModel = useModel; expanded = false; render()
    }
    fun showEvent(event: AttentionEvent) {
        history = null
        lastEvent = event; loading = false; expanded = false; render()
    }
    fun showError(message: String) { hide(); toast(message) }
    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    // ImageButton inherits performClick; the drag listener calls it for taps.
    // Semantic click, tap, drag, and cancel are covered by the overlay tests.
    @SuppressLint("ClickableViewAccessibility")
    private fun render() {
        if (!prefs.enabled) return
        if (!accessibilityWindow && !Settings.canDrawOverlays(context)) { diagnostics.overlay("缺少悬浮窗权限"); return }
        hide()
        val event = lastEvent
        val view = ui.column().apply {
            visibility = if (hiddenForCapture) View.INVISIBLE else View.VISIBLE
            background = ui.shape()
            elevation = ui.dp(6).toFloat()
            alpha = prefs.overlayOpacity / 100f
            setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(8))
        }
        val header = ui.row()
        val handle = ui.iconButton(R.drawable.ag_move, "拖动卡片；点按复位") {
            prefs.bubbleX = -1; prefs.bubbleY = -1; render()
        }
        header.addView(handle)
        val label = when { loading -> if (usingModel) "DeepSeek 整理中" else "本地整理中"; event != null -> "${event.priority.label} · 新事件"; else -> "Attention Guard" }
        header.addView(ui.text(label, R.dimen.ag_type_label, ui.brand, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (prefs.overlayCollapsed) {
            header.addView(ui.iconButton(R.drawable.ag_chevron_right, "展开 Attention Guard") { prefs.overlayCollapsed = false; render() })
        } else {
            header.addView(ui.iconButton(R.drawable.ag_minimize_2, "收起悬浮卡片") { prefs.overlayCollapsed = true; render() })
        }
        header.addView(ui.iconButton(R.drawable.ag_x, "关闭悬浮卡片") { onHistoryPause?.invoke(); hide(); onDismiss?.invoke() })
        view.addView(header)
        if (prefs.overlayCollapsed) {
            view.addView(ui.button("回溯收集", R.drawable.ag_clock_3, false) { onHistorySettings?.invoke() })
        }
        group?.takeIf { it.isNotBlank() }?.let {
            view.addView(ui.text(it, R.dimen.ag_type_caption, ui.sub).apply {
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        if (!prefs.overlayCollapsed && history != null) {
            val session = requireNotNull(history)
            view.addView(ui.text("${session.state.label} · ${session.screens} 屏", R.dimen.ag_type_label, ui.brand, true))
            if (session.reason.isNotBlank()) view.addView(ui.text(session.reason, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(4) })
            val controls = ui.row()
            if (session.state in setOf(HistoryState.READY, HistoryState.PAUSED)) {
                controls.addView(ui.button(if (session.state == HistoryState.READY) "开始回溯" else "继续", R.drawable.ag_radio) { onHistoryStart?.invoke() }, LinearLayout.LayoutParams(0, -2, 1f))
            } else if (session.state == HistoryState.RUNNING) controls.addView(ui.iconButton(R.drawable.ag_pause, "暂停回溯") { onHistoryPause?.invoke() })
            controls.addView(ui.iconButton(R.drawable.ag_x, "结束回溯") { onHistoryCancel?.invoke() })
            controls.addView(ui.iconButton(R.drawable.ag_notebook_tabs, "查看采集记录") { onHistorySettings?.invoke() })
            view.addView(controls)
        } else if (!prefs.overlayCollapsed && expanded && event != null) {
            view.addView(ui.text(event.title, R.dimen.ag_type_heading, bold = true).apply { layoutParams = ui.lp(4) })
            view.addView(ui.text(event.summary, R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(8) })
            event.dueLabel?.let { view.addView(ui.text(it, R.dimen.ag_type_label, ui.priority(event.priority).first, true).apply { layoutParams = ui.lp(12) }) }
            view.addView(ui.button("打开观测簿", R.drawable.ag_arrow_up_right) { openApp() }.apply { layoutParams = ui.lp(12) })
            view.addView(ui.button("收起", R.drawable.ag_minimize_2, false) { expanded = false; render() }.apply { layoutParams = ui.lp(8) })
        } else if (!prefs.overlayCollapsed && !loading) {
            view.addView(ui.text(status, R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(4) })
            view.addView(ui.button(event?.title ?: actionLabel, if (event == null) R.drawable.ag_focus else R.drawable.ag_chevron_down, false) {
                if (event != null) { expanded = true; render() } else onManualAnalyze?.invoke()
            })
            view.addView(ui.button("采集与回溯", R.drawable.ag_clock_3, false) { onHistorySettings?.invoke() }.apply { layoutParams = ui.lp(4) })
        }
        val bounds = wm.currentWindowMetrics.bounds
        val width = minOf(ui.dp(if (prefs.overlayCollapsed) 116 else if (expanded) 312 else 256), bounds.width() - ui.dp(24))
        val type = if (accessibilityWindow) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(width, -2, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (if (prefs.bubbleX >= 0) prefs.bubbleX else bounds.width() - width - ui.dp(12)).coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
            y = (if (prefs.bubbleY >= 0) prefs.bubbleY else ui.dp(80)).coerceIn(0, (bounds.height() - ui.dp(280)).coerceAtLeast(0))
        }
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val dragListener = View.OnTouchListener { target, motion ->
            when (motion.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = motion.rawX; downY = motion.rawY; startX = lp.x; startY = lp.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(motion.rawX - downX) > touchSlop || kotlin.math.abs(motion.rawY - downY) > touchSlop) dragged = true
                    if (!dragged) return@OnTouchListener true
                    lp.x = (startX + motion.rawX - downX).roundToInt().coerceIn(0, (bounds.width() - width).coerceAtLeast(0))
                    lp.y = (startY + motion.rawY - downY).roundToInt().coerceIn(0, (bounds.height() - view.height - ui.dp(24)).coerceAtLeast(0))
                    runCatching { wm.updateViewLayout(view, lp) }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) target.performClick()
                    else { prefs.bubbleX = lp.x; prefs.bubbleY = lp.y }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    lp.x = startX; lp.y = startY; dragged = false
                    runCatching { wm.updateViewLayout(view, lp) }
                    true
                }
                else -> false
            }
        }
        handle.setOnTouchListener(dragListener)
        runCatching { wm.addView(view, lp); panel = view; diagnostics.overlay(if (accessibilityWindow) "无障碍悬浮窗已显示" else "应用悬浮窗已显示") }
            .onFailure { diagnostics.overlay("挂窗失败：${it.javaClass.simpleName}") }
    }
    private fun openApp() {
        hide()
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_EVENT_ID, lastEvent?.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.onFailure { toast("暂时无法打开，请从桌面进入 Attention Guard") }
    }
}



