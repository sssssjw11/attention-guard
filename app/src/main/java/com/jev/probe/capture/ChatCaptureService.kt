package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.CaptureActivity
import com.jev.probe.core.AttentionEngine
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.EventStore
import com.jev.probe.core.MessageArchive
import com.jev.probe.core.Prefs
import com.jev.probe.jev.DeepSeekAttentionClient
import com.jev.probe.overlay.AttentionOverlayController
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Visible, authorized WeChat only. History scrolling requires an explicit session. */
open class ChatCaptureService : AccessibilityService(), CaptureActions {
    private val main = Handler(Looper.getMainLooper())
    private val storageWorker = Executors.newSingleThreadExecutor()
    private val analysisWorker = Executors.newSingleThreadExecutor()
    private val adapter = WeChatAdapter()
    private lateinit var prefs: Prefs
    private lateinit var store: EventStore
    private lateinit var archive: MessageArchive
    private lateinit var diagnostics: CaptureDiagnostics
    private lateinit var receipt: HistoryReceipt
    private lateinit var shared: SharedPreferences
    private var overlay: AttentionOverlayController? = null
    private var snapshot: ChatSnapshot? = null
    private var inspection: ChatInspection? = null
    private var signature = ""
    private var dismissedTitle: String? = null
    private var liveSuppressedTitle: String? = null
    private var viewportRevision = 0
    private var ocr: OnDeviceChatOcr? = null
    @Volatile private var generation = 0
    @Volatile private var dataGeneration = 0
    @Volatile private var alive = false
    private var archiving = false
    private var task: Future<*>? = null
    private var client: DeepSeekAttentionClient? = null
    private val debounce = Runnable { analyze() }
    private val captureSoon = Runnable { safeCapture() }
    private val poll = object : Runnable {
        override fun run() {
            if (!alive) return
            diagnostics.heartbeat(true)
            safeCapture()
            main.postDelayed(this, 1500)
        }
    }
    private val scrollStep = Runnable { scrollHistory() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("enabled", "auto_analyze", "whitelist", "cloud_enabled", "deepseek_encrypted_v1", "deepseek_model", "relationship", "local_ocr_enabled")) {
            main.post {
                if (!alive) return@post
                pauseHistory("观测设置已变化，请重新确认回溯")
                dataGeneration++; invalidate(); ocr?.retry()
                if (key == "enabled") updateKeepAlive()
                if (prefs.enabled) safeCapture() else diagnostics.state("观测已暂停")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (alive) return
        prefs = Prefs(this); store = EventStore(this); archive = MessageArchive(this)
        diagnostics = CaptureDiagnostics(this); receipt = HistoryReceipt(this)
        receipt.interrupted()
        CaptureRuntime.history = null
        CaptureRuntime.actions = this
        shared = getSharedPreferences("jev_assistant", MODE_PRIVATE)
        shared.registerOnSharedPreferenceChangeListener(preferenceListener)
        alive = true
        overlay = AttentionOverlayController(this).also { panel ->
            panel.onManualAnalyze = {
                if (snapshot == null || snapshot?.messages?.any { it.captureMethod == "ocr" } == true) {
                    ocr?.retry(); safeCapture()
                } else analyze(manual = true)
            }
            panel.onHistorySettings = {
                pauseHistory()
                runCatching { startActivity(Intent(this, CaptureActivity::class.java)
                    .putExtra(CaptureActivity.EXTRA_TITLE, snapshot?.title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
                    .onFailure { overlay?.toast("请从应用的来源页打开采集与回溯") }
            }
            panel.onHistoryStart = { startHistory() }
            panel.onHistoryPause = { pauseHistory() }
            panel.onHistoryCancel = { cancelHistory() }
            panel.onDismiss = { dismissedTitle = snapshot?.title ?: "" }
        }
        ocr = OnDeviceChatOcr(this) { hidden -> overlay?.setHiddenForCapture(hidden) }
        updateKeepAlive()
        diagnostics.state("等待前台微信会话")
        main.post(poll)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !alive) return
        if (event.packageName?.toString() == adapter.pkg) viewportRevision++
        if (event.packageName?.toString() == adapter.pkg && event.eventType in intArrayOf(
                AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) ocr?.retry()
        if (event.eventType in intArrayOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            // Throttle tree reads without indefinitely postponing a burst.
            if (!main.hasCallbacks(captureSoon)) main.postDelayed(captureSoon, 180)
        }
    }

    private fun cancelPendingAnalysis() {
        generation++
        main.removeCallbacks(debounce)
        client?.cancel(); client = null
        task?.cancel(true); task = null
    }

    private fun invalidate() {
        ocr?.cancel()
        cancelPendingAnalysis()
        snapshot = null; inspection = null; signature = ""
        CaptureRuntime.lastVisibleTitle = null
        overlay?.hide()
    }

    private fun updateKeepAlive() {
        if (!prefs.enabled) {
            stopService(Intent(this, KeepAliveService::class.java))
            diagnostics.keepAlive("观测已暂停")
        } else runCatching { KeepAliveService.start(this) }
            .onSuccess { diagnostics.keepAlive("启动请求已发出") }
            .onFailure { diagnostics.keepAlive("启动失败：${it.javaClass.simpleName}") }
    }

    private fun foregroundRoot(): AccessibilityNodeInfo? {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val visibleWindows = windows.map { CaptureWindow(it.root, it.type, it.layer, it.isFocused, it.isActive) }
        return ForegroundChatWindow.choose(rootInActiveWindow, visibleWindows, packageName)
    }

    private fun safeCapture() {
        if (!alive) return
        runCatching { capture() }.onFailure {
            pauseHistory("读取暂时失败，已停止翻页")
            invalidate()
            diagnostics.state("读取失败：${it.javaClass.simpleName}")
        }
    }

    private fun capture() {
        if (!prefs.enabled) { invalidate(); diagnostics.state("观测已暂停"); return }
        val root = foregroundRoot() ?: run {
            pauseHistory("已离开微信或锁屏，回到目标会话后可继续")
            if (snapshot != null) dataGeneration++
            invalidate(); dismissedTitle = null; liveSuppressedTitle = null
            diagnostics.state("等待前台微信会话")
            return
        }
        val result = adapter.inspect(root, resources)
        diagnostics.inspected(result.nodeCount, result.knownBubbles, result.structuralBubbles,
            result.snapshot?.messages?.size ?: 0, result.reason)
        val next = result.snapshot
        if (next == null || next.title.isNullOrBlank() || next.messages.isEmpty() || !prefs.isAllowed(next.title)) {
            pauseHistory("会话无法确认或没有可读消息，已停止翻页")
            if (snapshot != null) dataGeneration++
            cancelPendingAnalysis(); snapshot = null; inspection = null; signature = ""
            CaptureRuntime.lastVisibleTitle = null
            val reason = if (next != null && !prefs.isAllowed(next.title)) "会话未匹配观测范围" else result.reason
            diagnostics.state(reason)
            // A blocked tree must still be diagnosable, but never treated as messages.
            if (dismissedTitle == null) overlay?.showIdle(null, reason, actionLabel = "重新读取")
            if (prefs.localOcrEnabled && next != null && prefs.isAllowed(next.title)) requestOcr(result, root.windowId)
            return
        }
        acceptSnapshot(next, result)
    }

    private fun requestOcr(result: ChatInspection, windowId: Int) {
        if (!OnDeviceChatOcr.eligible(result) || CaptureRuntime.history?.let { it.state != HistoryState.CANCELLED && it.state != HistoryState.FINISHED } == true) return
        val revision = viewportRevision
        val title = result.snapshot?.title
        ocr?.capture(result, stillCurrent = {
            alive && prefs.enabled && prefs.localOcrEnabled && revision == viewportRevision && prefs.isAllowed(title) &&
                runCatching {
                    val root = foregroundRoot()
                    root != null && root.windowId == windowId && adapter.inspect(root, resources).let { fresh ->
                        fresh.snapshot?.title == title && fresh.ocrRegions == result.ocrRegions
                    }
                }.getOrDefault(false)
        }) { recognized, reason ->
            diagnostics.inspected(result.nodeCount, result.knownBubbles, 0, recognized?.messages?.size ?: 0, reason)
            if (recognized != null && recognized.messages.isNotEmpty()) acceptSnapshot(recognized, result)
            else { diagnostics.state(reason); if (dismissedTitle == null) overlay?.showIdle(null, reason) }
        }
    }

    private fun acceptSnapshot(next: ChatSnapshot, result: ChatInspection) {
        if (snapshot?.title != next.title) {
            dataGeneration++; invalidate(); dismissedTitle = null
            if (liveSuppressedTitle != next.title) liveSuppressedTitle = null
        }
        CaptureRuntime.lastVisibleTitle = next.title
        snapshot = next; inspection = result
        val session = CaptureRuntime.history
        if (session?.state == HistoryState.RUNNING && session.config.title != next.title) pauseHistory("会话已切换，返回目标会话后可继续")
        val relevantHistory = session?.takeIf { it.config.title == next.title && it.state != HistoryState.CANCELLED }
        val fromOcr = next.messages.any { it.captureMethod == "ocr" }
        diagnostics.state(if (fromOcr) "本机 OCR 采集，内容待核对" else if (relevantHistory?.state == HistoryState.RUNNING) "历史回溯中" else "正在监测可见消息")
        val changed = next.signature() != signature
        if (changed || overlay?.isShowing() != true || relevantHistory != null) {
            if (dismissedTitle != next.title) overlay?.showIdle(next.title,
                if (fromOcr) "OCR ${next.messages.size} 条，内容待核对" else "已识别 ${next.messages.size} 条可见消息", relevantHistory,
                actionLabel = if (fromOcr) "重新读取" else "整理当前会话")
        }
        // A configured/paused history session must not leak historical content into live AI events.
        if (relevantHistory != null && relevantHistory.state != HistoryState.RUNNING) return
        if (!changed || archiving) return
        cancelPendingAnalysis()
        signature = next.signature()
        val dataRequest = dataGeneration
        val activeSession = relevantHistory?.takeIf { it.state == HistoryState.RUNNING }
        val stream = activeSession?.id ?: "live:${next.sourcePackage}:${next.title}"
        archiving = true
        storageWorker.execute {
            if (!alive || dataRequest != dataGeneration || !prefs.enabled) {
                main.post { archiving = false }
                return@execute
            }
            val outcome = runCatching {
                archive.append(next, stream, activeSession?.config?.range) {
                    alive && dataRequest == dataGeneration && prefs.enabled
                }
            }
            main.post {
                archiving = false
                if (!alive || dataRequest != dataGeneration) return@post
                outcome.onSuccess { write ->
                    diagnostics.saved(write.added)
                    if (activeSession != null && CaptureRuntime.history === activeSession) {
                        activeSession.observe(next, write.gap); receipt.save(activeSession)
                        showHistory()
                    } else if (prefs.autoAnalyze && !fromOcr && liveSuppressedTitle != next.title) {
                        // Persist local event immediately; cloud refinement remains debounced.
                        analyzeLocal(next)
                        main.postDelayed(debounce, 2200)
                    }
                }.onFailure {
                    signature = ""; diagnostics.storageError()
                    pauseHistory("本机保存失败，已停止翻页")
                    overlay?.showIdle(next.title, "保存失败，请检查本机存储")
                }
            }
        }
    }

    private fun analyzeLocal(current: ChatSnapshot) {
        if (current.messages.any { it.captureMethod != "nodes" } || liveSuppressedTitle == current.title) return
        val base = AttentionEngine.buildEvent(current, prefs.relationship) ?: return
        val request = dataGeneration
        storageWorker.execute {
            if (!alive || request != dataGeneration || !prefs.enabled) return@execute
            val saved = runCatching { store.upsert(base) }
            main.post {
                if (!alive || request != dataGeneration) return@post
                if (saved.isFailure) diagnostics.storageError()
                else if (AttentionEngine.shouldNotify(base) && dismissedTitle != current.title) overlay?.showEvent(base)
            }
        }
    }

    private fun analyze(manual: Boolean = false) {
        val current = snapshot ?: return
        if (current.messages.any { it.captureMethod != "nodes" } || liveSuppressedTitle == current.title) return
        if (!alive || !prefs.enabled || !prefs.isAllowed(current.title) || CaptureRuntime.history?.let { it.config.title == current.title && it.state != HistoryState.CANCELLED } == true) return
        if (manual) analyzeLocal(current)
        val groupContext = prefs.relationship
        val base = AttentionEngine.buildEvent(current, groupContext) ?: return
        if (!prefs.cloudEnabled || !prefs.hasKey() || !AttentionEngine.shouldNotify(base)) return
        cancelPendingAnalysis()
        val request = generation
        val api = DeepSeekAttentionClient(prefs.activeKey(), prefs.activeModel())
        client = api
        if (dismissedTitle != current.title) overlay?.showLoading(true)
        task = analysisWorker.submit {
            val result = runCatching { api.enrich(current, base, groupContext) }
            main.post {
                if (!alive || request != generation || !prefs.enabled || snapshot?.signature() != current.signature()) return@post
                task = null; client = null
                result.onSuccess { enriched ->
                    storageWorker.execute {
                        if (!alive || request != generation) return@execute
                        val saved = runCatching { store.upsert(enriched) }
                        main.post stored@{
                            if (!alive || request != generation) return@stored
                            if (saved.isFailure) diagnostics.storageError()
                            else if (dismissedTitle != current.title) overlay?.showEvent(enriched)
                        }
                    }
                }.onFailure {
                    if (dismissedTitle != current.title) overlay?.showEvent(base)
                    overlay?.toast("DeepSeek 暂不可用，本地消息和事件已保留")
                }
            }
        }
    }

    override fun armHistory(config: HistoryConfig): Boolean {
        if (!alive || !prefs.enabled || !prefs.isAllowed(config.title) || config.range.end > java.time.LocalDate.now()) return false
        CaptureRuntime.history?.let { if (it.state in setOf(HistoryState.READY, HistoryState.RUNNING, HistoryState.PAUSED)) return false }
        cancelPendingAnalysis(); dataGeneration++
        CaptureRuntime.history = HistorySession(config).also { receipt.save(it) }
        dismissedTitle = null; signature = ""
        return true
    }

    private fun startHistory() {
        safeCapture()
        val current = snapshot ?: return
        val session = CaptureRuntime.history ?: return
        if (!prefs.enabled || !prefs.isAllowed(current.title)) return
        if (!session.start(current.title.orEmpty(), SystemClock.elapsedRealtime())) {
            receipt.save(session); showHistory(); return
        }
        liveSuppressedTitle = current.title
        dataGeneration++; cancelPendingAnalysis(); signature = ""
        receipt.save(session); safeCapture(); showHistory()
        main.removeCallbacks(scrollStep)
        if (session.config.automatic) main.postDelayed(scrollStep, 4000)
    }

    override fun pauseHistory() = pauseHistory("由用户暂停")
    private fun pauseHistory(reason: String) {
        main.removeCallbacks(scrollStep)
        val session = CaptureRuntime.history ?: return
        if (session.state != HistoryState.RUNNING) return
        dataGeneration++; session.pause(reason); receipt.save(session); showHistory()
    }
    override fun cancelHistory() {
        main.removeCallbacks(scrollStep)
        CaptureRuntime.history?.let { it.cancel(); receipt.save(it) }
        dataGeneration++; signature = ""; dismissedTitle = null
        safeCapture()
    }
    private fun showHistory() {
        val session = CaptureRuntime.history ?: return
        if (session.config.title == snapshot?.title && dismissedTitle != snapshot?.title)
            overlay?.showIdle(snapshot?.title, "仅保存在本机", session)
    }
    private fun scrollHistory() {
        safeCapture()
        val session = CaptureRuntime.history ?: return
        if (!alive || !prefs.enabled || session.state != HistoryState.RUNNING || snapshot?.title != session.config.title) return
        if (overlay?.isShowing() != true) { pauseHistory("悬浮控制不可用，已停止自动翻页"); return }
        if (archiving) { main.postDelayed(scrollStep, 1000); return }
        if (!session.canScroll(SystemClock.elapsedRealtime())) { receipt.save(session); showHistory(); return }
        // Reacquired in safeCapture; no input, click, paste or coordinate gestures.
        val target = inspection?.scrollTarget
        val ok = target != null && runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }.getOrDefault(false)
        if (!ok) { pauseHistory("聊天列表不支持向前翻页，请改用手动回溯"); return }
        receipt.save(session); showHistory()
        main.postDelayed(scrollStep, 4000)
    }

    override fun onInterrupt() {
        if (!alive) return
        pauseHistory("无障碍服务被中断"); dataGeneration++; invalidate()
        diagnostics.state("服务被中断，正在等待恢复")
    }
    override fun onDestroy() {
        if (alive) {
            pauseHistory("服务已断开，需重新开始任务")
            alive = false; dataGeneration++
            shared.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            invalidate(); diagnostics.heartbeat(false)
            CaptureRuntime.actions = null; CaptureRuntime.lastVisibleTitle = null
            receipt.interrupted(); CaptureRuntime.history = null
            storageWorker.execute { archive.close() }
            stopService(Intent(this, KeepAliveService::class.java))
        }
        main.removeCallbacksAndMessages(null)
        ocr?.close(); ocr = null
        overlay = null
        storageWorker.shutdown(); analysisWorker.shutdownNow()
        super.onDestroy()
    }
}
