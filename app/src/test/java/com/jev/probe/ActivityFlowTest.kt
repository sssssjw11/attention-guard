package com.attentionguard.app

import android.app.Activity
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.attentionguard.app.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w360dp-h800dp-mdpi")
class ActivityFlowTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun firstLaunchIsEmptyAndDemoNeverWritesRealEvents() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertTrue(texts(activity).contains("记录本还是空的"))
            assertTrue(EventStore(context).load().isEmpty())
            button(activity, "浏览示例").performClick()
            assertTrue(texts(activity).contains("示例模式"))
            assertTrue(texts(activity).contains(DemoAttentionData.events.first().title))
            assertTrue(EventStore(context).load().isEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun realEventCanBeCompletedRestoredAndReopened() {
        val event = DemoAttentionData.events[2].copy(id = "test-event")
        EventStore(context).upsert(event)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).first { it.isClickable && it.contentDescription?.startsWith(event.title) == true }.performClick()
            button(activity, "标记完成").performClick()
            assertEquals(EventStatus.COMPLETED, EventStore(context).load().single().status)
            button(activity, "恢复原状态").performClick()
            assertEquals(EventStatus.MONITORING, EventStore(context).load().single().status)
            activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(texts(activity).contains("继续关注"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun ledgerSearchAndFilterSurviveActivityRecreation() {
        EventStore(context).upsert(DemoAttentionData.events.first().copy(id = "test-event"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.ag_ledger
            activity.findViewById<MaterialButton>(500 + EventFilter.ACTION.ordinal).performClick()
            activity.findViewById<TextInputEditText>(R.id.ag_search).setText("no matching event")
            assertTrue(texts(activity).contains("没有匹配的事件"))
            controller.recreate()
            val recreated = controller.get()
            assertEquals("no matching event", recreated.findViewById<TextInputEditText>(R.id.ag_search).text.toString())
            assertTrue(recreated.findViewById<MaterialButton>(500 + EventFilter.ACTION.ordinal).isChecked)
            button(recreated, "清除筛选").performClick()
            assertEquals("", recreated.findViewById<TextInputEditText>(R.id.ag_search).text.toString())
            assertTrue(texts(recreated).contains(DemoAttentionData.events.first().title))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun overlayIntentOpensRealEventAndBackReturnsToLedger() {
        val event = DemoAttentionData.events[2].copy(id = "overlay-event")
        EventStore(context).upsert(event)
        Prefs(context).demoMode = true
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EVENT_ID, event.id)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            assertFalse(Prefs(context).demoMode)
            assertTrue(texts(activity).contains("事件记录"))
            assertTrue(texts(activity).contains(event.title))
            activity.onBackPressedDispatcher.onBackPressed()
            assertNotNull(activity.findViewById<TextInputEditText>(R.id.ag_search))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun overlayIntentReusesOpenActivityAndMissingEventFallsBackToLedger() {
        val event = DemoAttentionData.events[2].copy(id = "overlay-event")
        EventStore(context).upsert(event)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.newIntent(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EVENT_ID, event.id))
            assertTrue(texts(controller.get()).contains("事件记录"))
            controller.newIntent(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EVENT_ID, "missing-event"))
            assertNotNull(controller.get().findViewById<TextInputEditText>(R.id.ag_search))
            assertFalse(texts(controller.get()).contains("事件记录"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun settingsRemainLocalByDefaultAndValidateBeforeSaving() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            val prefs = Prefs(context)
            assertFalse(activity.findViewById<SwitchCompat>(R.id.ag_cloud).isChecked)
            assertEquals(Prefs.DEFAULT_DEEPSEEK_MODEL, activity.findViewById<TextInputEditText>(R.id.ag_model).text.toString())
            activity.findViewById<SwitchCompat>(R.id.ag_cloud).isChecked = true
            button(activity, "保存设置").performClick()
            val key = activity.findViewById<TextInputEditText>(R.id.ag_key)
            val keyBox = descendants(activity.window.decorView).filterIsInstance<TextInputLayout>().first { it.editText == key }
            assertEquals("请填写 DeepSeek API Key", keyBox.error.toString())
            assertFalse(prefs.cloudEnabled)
            assertFalse(key.isSaveEnabled)
            activity.findViewById<SwitchCompat>(R.id.ag_cloud).isChecked = false
            activity.findViewById<TextInputEditText>(R.id.ag_context).setText("New test context")
            button(activity, "保存设置").performClick()
            assertEquals("New test context", prefs.relationship)
            assertFalse(prefs.cloudEnabled)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun unsavedSettingsRequireExplicitDiscard() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<TextInputEditText>(R.id.ag_context).setText("Unsaved context")
            activity.onBackPressedDispatcher.onBackPressed()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.isShowing)
            assertFalse(activity.isFinishing)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(activity.isFinishing)
            assertEquals(Prefs.DEFAULT_REL, Prefs(context).relationship)
            activity.onBackPressedDispatcher.onBackPressed()
            (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun narrowLedgerKeepsFilterLabelsWithinMeasuredBoundsAtDoubleFontScale() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-mdpi")
        RuntimeEnvironment.setFontScale(2f)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            descendants(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.ag_ledger
            shadowOf(Looper.getMainLooper()).idle()
            val root = activity.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 320, 640)
            EventFilter.values().forEach { filter ->
                val button = activity.findViewById<MaterialButton>(500 + filter.ordinal)
                assertTrue("Filter target too short", button.height >= 48)
                val layout = requireNotNull(button.layout)
                assertTrue("Filter label clipped vertically", layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                assertTrue("Filter outside screen", button.width > 0 && button.width <= 80)
                for (line in 0 until layout.lineCount) assertEquals("Filter label ellipsized", 0, layout.getEllipsisCount(line))
            }
        } finally {
            controller.pause().stop().destroy()
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun texts(activity: Activity) = descendants(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }.toList()
    private fun button(activity: Activity, label: String) = descendants(activity.window.decorView).filterIsInstance<MaterialButton>().first { it.text.toString() == label }
}
