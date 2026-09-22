package com.jev.probe.capture

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ForegroundChatWindowTest {
    @Suppress("DEPRECATION")
    private fun node(pkg: String) = AccessibilityNodeInfo.obtain().apply { packageName = pkg }
    private val chat = node("com.tencent.mm")
    private fun app(root: AccessibilityNodeInfo, focus: Boolean = true, layer: Int = 1) = CaptureWindow(root, AccessibilityWindowInfo.TYPE_APPLICATION, layer, focus, focus)
    private fun choose(active: AccessibilityNodeInfo?, windows: List<CaptureWindow>) = ForegroundChatWindow.choose(active, windows, "com.jev.probe")
    @Test fun emptyWindowInventoryUsesOnlyActiveWeChatRoot() {
        assertSame(chat, choose(chat, emptyList())); assertNull(choose(node("other.app"), emptyList()))
    }
    @Test fun ownAccessibilityOverlayDoesNotHideForegroundChat() {
        val own = node("com.jev.probe")
        assertSame(chat, choose(own, listOf(app(chat), CaptureWindow(own, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, 5, true, true))))
    }
    @Test fun keyboardDoesNotSelectItsInputTextAsChat() {
        val ime = node("com.tencent.wetype")
        assertSame(chat, choose(ime, listOf(app(chat), CaptureWindow(ime, AccessibilityWindowInfo.TYPE_INPUT_METHOD, 5, true, true))))
    }
    @Test fun anotherForegroundAppBlocksAnyBackgroundWechatRoot() {
        val other = node("other.app")
        assertNull(choose(other, listOf(app(chat, false), app(other, true, 3))))
        assertNull(choose(chat, listOf(app(chat, false), app(other, true, 3))))
    }
    @Test fun applicationScreenIsNotMistakenForOurOverlay() {
        val own = node("com.jev.probe")
        assertNull(choose(own, listOf(app(chat, false), app(own, true, 4))))
    }
    @Test fun transientMissingRootCanRecoverOnlyWithFocusedChatWindow() {
        assertSame(chat, choose(null, listOf(app(chat))))
        assertNull(choose(null, listOf(app(chat, false))))
    }
}
