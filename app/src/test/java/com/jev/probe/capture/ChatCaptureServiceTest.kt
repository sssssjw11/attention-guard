package com.jev.probe.capture

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.material.button.MaterialButton
import com.jev.probe.core.MessageArchive
import com.jev.probe.core.Prefs
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class TestCaptureService : ChatCaptureService() {
    var activeRoot: AccessibilityNodeInfo? = null
    override fun getRootInActiveWindow() = activeRoot
    override fun getWindows() = emptyList<AccessibilityWindowInfo>()
    fun connect() { super.onServiceConnected() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class ChatCaptureServiceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val controller = Robolectric.buildService(TestCaptureService::class.java).create()
    private val service = controller.get()
    private val windows: ShadowWindowManagerImpl get() = Shadow.extract(service.getSystemService(WindowManager::class.java))
    @Suppress("DEPRECATION")
    private fun node(text: String? = null, rect: Rect = Rect(0, 0, 360, 800)) = AccessibilityNodeInfo.obtain().apply {
        this.text = text; packageName = "com.tencent.mm"; isVisibleToUser = true; setBoundsInScreen(rect)
    }
    private fun root(title: String = "Test group", message: String = "hello", onScroll: (() -> Boolean)? = null): AccessibilityNodeInfo = node().apply {
        shadowOf(this).addChild(node(title, Rect(80, 35, 280, 68)))
        val list = node(rect = Rect(0, 110, 360, 700)).apply { isScrollable = true; className = "android.widget.ListView" }
        if (onScroll != null) shadowOf(list).setOnPerformActionListener { action, _ ->
            assertEquals(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, action)
            onScroll()
        }
        shadowOf(list).addChild(node(message, Rect(40, 220, 210, 270)).apply { viewIdResourceName = "com.tencent.mm:id/bkl" })
        shadowOf(this).addChild(list)
    }
    private fun drain() {
        val field = ChatCaptureService::class.java.getDeclaredField("storageWorker").apply { isAccessible = true }
        repeat(3) {
            val latch = CountDownLatch(1)
            (field.get(service) as ExecutorService).execute { latch.countDown() }
            assertTrue(latch.await(3, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun tick(seconds: Long = 2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds)); drain() }
    @After fun cleanup() { controller.destroy(); CaptureRuntime.actions = null; CaptureRuntime.history = null }

    @Test fun entryShowsAccessibilityOverlayImmediatelyAndRecordsOrdinaryChat() {
        ShadowSettings.setCanDrawOverlays(false)
        service.activeRoot = root(); service.connect(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(windows.views.isNotEmpty())
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, (windows.views.first().layoutParams as WindowManager.LayoutParams).type)
        drain()
        MessageArchive(context).use { assertEquals("hello", it.recent().single().message.text) }
        assertTrue(CaptureDiagnostics(context).summary().contains("已读取"))
    }
    @Test fun pollingRecoversFromInitiallyMissingTreeWithoutANewEvent() {
        service.connect(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(windows.views.isEmpty())
        service.activeRoot = root(); tick()
        MessageArchive(context).use { assertEquals(1, it.count().total) }
        assertTrue(windows.views.isNotEmpty())
    }
    @Test fun sameScreenDoesNotDuplicateMessagesAndDisabledAutoStillRecords() {
        Prefs(context).autoAnalyze = false
        service.activeRoot = root(); service.connect(); tick(); tick()
        MessageArchive(context).use { assertEquals(1, it.count().total) }
    }
    @Test fun foreignForegroundAndWhitelistNeverPersistContent() {
        Prefs(context).whitelist = setOf("Allowed")
        service.activeRoot = root(message = "private text"); service.connect(); tick()
        assertTrue(CaptureDiagnostics(context).summary().contains("未匹配"))
        assertFalse(CaptureDiagnostics(context).summary().contains("private text"))
        service.activeRoot = root().apply { packageName = "other.app" }; tick()
        MessageArchive(context).use { assertEquals(0, it.count().total) }
        assertTrue(windows.views.isEmpty())
    }
    @Test fun preparedHistoryCannotScrollUntilTheOverlayStartIsPressedAndLeavingPauses() {
        service.activeRoot = root(); service.connect(); tick()
        val config = HistoryConfig("Test group", HistoryRange(LocalDate.now().minusDays(3), LocalDate.now()), false)
        assertTrue(service.armHistory(config)); tick()
        val session = CaptureRuntime.history!!
        assertEquals(HistoryState.READY, session.state)
        button("开始回溯").performClick(); drain()
        assertEquals(HistoryState.RUNNING, session.state)
        assertEquals(0, session.attempts)
        service.activeRoot = null; tick()
        assertEquals(HistoryState.PAUSED, session.state)
        service.activeRoot = root(); tick()
        assertEquals(HistoryState.PAUSED, session.state)
        assertTrue(windows.views.isNotEmpty())
    }
    @Test fun disablingMasterSwitchHidesWindowAndRejectsPendingHistory() {
        service.activeRoot = root(); service.connect(); tick()
        Prefs(context).enabled = false; tick()
        assertTrue(windows.views.isEmpty())
        assertFalse(service.armHistory(HistoryConfig("Test group", HistoryRange(LocalDate.now(), LocalDate.now()), true)))
    }

    @Test fun automaticHistoryOnlyScrollsBackwardAtTheIntervalAndStopsWhenStalled() {
        var scrolls = 0
        service.activeRoot = root(onScroll = { scrolls++; true }); service.connect(); tick()
        assertTrue(service.armHistory(HistoryConfig("Test group", HistoryRange(LocalDate.now().minusDays(1), LocalDate.now()), true)))
        tick(); button("开始回溯").performClick(); drain()
        tick(3); assertEquals(0, scrolls)
        tick(1); assertEquals(1, scrolls)
        repeat(3) { tick(4) }
        assertEquals(3, scrolls)
        assertEquals(HistoryState.PAUSED, CaptureRuntime.history!!.state)
        assertTrue(CaptureRuntime.history!!.reason.contains("未发现新页面"))
    }

    @Test fun unsupportedScrollPausesInsteadOfTryingCoordinateGestures() {
        service.activeRoot = root(onScroll = { false }); service.connect(); tick()
        assertTrue(service.armHistory(HistoryConfig("Test group", HistoryRange(LocalDate.now(), LocalDate.now()), true)))
        tick(); button("开始回溯").performClick(); drain(); tick(4)
        assertEquals(HistoryState.PAUSED, CaptureRuntime.history!!.state)
        assertTrue(CaptureRuntime.history!!.reason.contains("不支持"))
        assertTrue(shadowOf(service).gesturesDispatched.isEmpty())
    }
    private fun button(text: String): MaterialButton = windows.views.asSequence().flatMap { children(it) }.filterIsInstance<MaterialButton>().first { it.text.toString() == text }
    private fun children(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(children(view.getChildAt(i)))
    }
}
