package com.attentionguard.app.overlay

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.google.android.material.button.MaterialButton
import com.attentionguard.app.MainActivity
import com.attentionguard.app.core.DemoAttentionData
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.capture.*
import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class AttentionOverlayControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val prefs = Prefs(context)
    private val overlay = AttentionOverlayController(context)
    private val windows = Shadow.extract<ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java))

    @After fun removeWindows() { overlay.hide() }

    @Test fun noPermissionDoesNotMountOrCrash() {
        ShadowSettings.setCanDrawOverlays(false)
        overlay.showIdle("Test group")
        assertFalse(overlay.isShowing())
        assertTrue(windows.views.isEmpty())
    }

    @Test fun tapAndSemanticClickBothResetPosition() {
        showAtRememberedPosition()
        val handle = handle()
        touch(handle, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(handle, MotionEvent.ACTION_UP, 100f, 100f)
        assertEquals(-1, prefs.bubbleX)
        assertEquals(-1, prefs.bubbleY)
        showAtRememberedPosition()
        handle().performClick()
        assertEquals(-1, prefs.bubbleX)
        assertEquals(-1, prefs.bubbleY)
    }

    @Test fun dragReturningToStartIsNotMistakenForTap() {
        showAtRememberedPosition()
        val handle = handle()
        touch(handle, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(handle, MotionEvent.ACTION_MOVE, 135f, 140f)
        touch(handle, MotionEvent.ACTION_MOVE, 100f, 100f)
        touch(handle, MotionEvent.ACTION_UP, 100f, 100f)
        assertEquals(40, prefs.bubbleX)
        assertEquals(80, prefs.bubbleY)
    }

    @Test fun cancelledDragRestoresWindowWithoutSavingPartialPosition() {
        showAtRememberedPosition()
        val handle = handle()
        touch(handle, MotionEvent.ACTION_DOWN, 100f, 100f)
        touch(handle, MotionEvent.ACTION_MOVE, 125f, 145f)
        touch(handle, MotionEvent.ACTION_CANCEL, 125f, 145f)
        val params = windows.views.single().layoutParams as WindowManager.LayoutParams
        assertEquals(40, params.x)
        assertEquals(80, params.y)
        assertEquals(40, prefs.bubbleX)
        assertEquals(80, prefs.bubbleY)
    }

    @Test fun openActionTargetsTheDisplayedEventAndRemovesWindow() {
        ShadowSettings.setCanDrawOverlays(true)
        val event = DemoAttentionData.events.first().copy(id = "live-event")
        overlay.showEvent(event)
        button(event.title).performClick()
        button("打开观测簿").performClick()
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(event.id, intent.getStringExtra(MainActivity.EXTRA_EVENT_ID))
        assertFalse(overlay.isShowing())
        assertTrue(windows.views.isEmpty())
    }

    @Test fun collapsedCardKeepsHistoryActionAndCanExpand() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.overlayCollapsed = true
        var opened = false
        overlay.onHistorySettings = { opened = true }
        overlay.showIdle("Test group")
        assertTrue(overlay.isShowing())
        descendants(windows.views.single()).first { it.contentDescription == "回溯收集" }.performClick()
        assertTrue(opened)
        descendants(windows.views.single()).first { it.contentDescription == "展开 Attention Guard" }.performClick()
        assertFalse(prefs.overlayCollapsed)
    }

    @Test fun compactControlsFitAndPauseIsAlwaysReachable() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.overlayCollapsed = true
        val day = LocalDate.now()
        val session = HistorySession(HistoryConfig("Test group", HistoryRange(day, day), true))
        session.start("Test group", 0L)
        var paused = false
        overlay.onHistoryPause = { paused = true }
        overlay.showIdle("Test group", history = session)
        val view = windows.views.single()
        val width = (view.layoutParams as WindowManager.LayoutParams).width
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals(152, view.measuredWidth)
        assertEquals(56, view.measuredHeight)
        val actions = descendants(view).filter { it.isClickable }.toList()
        assertEquals(3, actions.size)
        assertTrue(actions.all { it.width >= 48 && it.height >= 48 && it.right <= width })
        actions.first { it.contentDescription == "暂停回溯" }.performClick()
        assertTrue(paused)
        val mounted = windows.views.single()
        overlay.showIdle("Test group", "different status", session)
        assertSame(mounted, windows.views.single())
    }

    private fun showAtRememberedPosition() {
        ShadowSettings.setCanDrawOverlays(true)
        prefs.bubbleX = 40
        prefs.bubbleY = 80
        overlay.showIdle("Test group")
        assertTrue(overlay.isShowing())
    }

    private fun handle() = descendants(windows.views.single()).first { it.contentDescription == "拖动卡片；点按复位" }
    private fun button(label: String) = descendants(windows.views.single()).filterIsInstance<MaterialButton>().first { it.text.toString() == label }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
}
