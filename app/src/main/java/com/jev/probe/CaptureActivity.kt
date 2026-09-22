package com.attentionguard.app

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.attentionguard.app.capture.*
import com.attentionguard.app.core.MessageArchive
import com.attentionguard.app.core.Prefs
import com.attentionguard.app.ui.GuardUi
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class CaptureActivity : AppCompatActivity() {
    private lateinit var ui: GuardUi
    private lateinit var archive: MessageArchive
    private lateinit var body: LinearLayout
    private lateinit var diagnosticText: TextView
    private lateinit var progressText: TextView
    private lateinit var archiveText: TextView
    private lateinit var messageList: LinearLayout
    private lateinit var titleBox: TextInputLayout
    private lateinit var titleEdit: TextInputEditText
    private lateinit var startButton: MaterialButton
    private lateinit var endButton: MaterialButton
    private lateinit var prepare: MaterialButton
    private lateinit var automatic: SwitchCompat
    private lateinit var more: MaterialButton
    private var start = LocalDate.now().minusDays(7)
    private var end = LocalDate.now()
    private var historyOnly = false
    private var beforeId: Long? = null
    private var loading = false
    private var readGeneration = 0
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val refreshStatus = object : Runnable {
        override fun run() { updateStatus(); main.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = GuardUi(this); archive = MessageArchive(this)
        start = savedInstanceState?.getString("start")?.let(LocalDate::parse) ?: start
        end = savedInstanceState?.getString("end")?.let(LocalDate::parse) ?: end
        historyOnly = savedInstanceState?.getBoolean("history_only") ?: false
        val root = FrameLayout(this).apply { setBackgroundColor(ui.background) }
        val shell = ui.boundedColumn()
        root.addView(shell, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL))
        shell.addView(ui.row().apply {
            setBackgroundColor(ui.surface); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            addView(ui.iconButton(R.drawable.ag_arrow_left, "返回") { finish() })
            addView(ui.text("采集与回溯", R.dimen.ag_type_heading, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton(R.drawable.ag_rotate_ccw, "刷新诊断与记录") { updateStatus(); loadMessages(true) })
        })
        body = ui.column().apply { setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(28)) }
        shell.addView(ui.scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(ui.heading("运行诊断"))
        diagnosticText = ui.text("", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(12); setTextIsSelectable(true) }
        body.addView(diagnosticText)
        body.addView(ui.button("复制诊断", R.drawable.ag_notebook_tabs, false) {
            val value = "Attention Guard ${BuildConfig.VERSION_NAME}\n" + CaptureDiagnostics(this).summary()
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Attention Guard 诊断", value))
            ui.feedback(body, "已复制，不含群名、消息正文或密钥")
        }.apply { layoutParams = ui.lp(12) })
        body.addView(ui.button("无障碍设置", R.drawable.ag_eye, false) {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { ui.feedback(body, "系统页面不可用") }
        }.apply { layoutParams = ui.lp(8) })
        body.addView(SwitchCompat(this).apply {
            text = "本机 OCR 兜底"; textSize = 16f; setTextColor(ui.ink); minHeight = ui.dp(56)
            isChecked = Prefs(this@CaptureActivity).localOcrEnabled
            var updating = false
            setOnCheckedChangeListener { _, checked ->
                if (updating) return@setOnCheckedChangeListener
                if (!checked) Prefs(this@CaptureActivity).localOcrEnabled = false
                else {
                    updating = true; isChecked = false; updating = false
                    MaterialAlertDialogBuilder(this@CaptureActivity).setTitle("启用本机文字识别？")
                        .setMessage("仅在微信会话和气泡区域已确认、但正文不可读时截屏识别。截图只在内存中处理，不保存、不上传；结果仅作为本机待核对记录，不送往 DeepSeek。完全没有气泡节点时仍无法读取。历史自动回溯遇到正文不可读会暂停。")
                        .setNegativeButton("取消", null).setPositiveButton("启用") { _, _ ->
                            Prefs(this@CaptureActivity).localOcrEnabled = true
                            updating = true; isChecked = true; updating = false
                        }.show()
                }
            }
        })
        body.addView(ui.divider(24))
        body.addView(ui.heading("日期回溯"))
        val field = ui.field("目标会话名称", savedInstanceState?.getString("title") ?: intent.getStringExtra(EXTRA_TITLE).orEmpty(), InputType.TYPE_CLASS_TEXT, R.id.ag_history_title)
        titleBox = field.first; titleEdit = field.second
        body.addView(titleBox)
        startButton = ui.button("起始日期 · $start", R.drawable.ag_clock_3, false) { chooseDate(true) }.apply { id = R.id.ag_history_start; layoutParams = ui.lp(12) }
        endButton = ui.button("结束日期 · $end", R.drawable.ag_clock_3, false) { chooseDate(false) }.apply { id = R.id.ag_history_end; layoutParams = ui.lp(8) }
        body.addView(startButton); body.addView(endButton)
        automatic = SwitchCompat(this).apply {
            id = R.id.ag_history_auto; text = "慢速自动翻页"; textSize = 16f; setTextColor(ui.ink); minHeight = ui.dp(56)
            isChecked = savedInstanceState?.getBoolean("automatic") ?: false
        }
        body.addView(automatic)
        prepare = ui.button("准备回溯", R.drawable.ag_radio) { prepareHistory() }.apply { id = R.id.ag_history_prepare }
        body.addView(prepare)
        progressText = ui.text("", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(12); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(progressText)
        body.addView(ui.button("结束当前回溯", R.drawable.ag_x, false) {
            CaptureRuntime.actions?.cancelHistory(); updateStatus(); loadMessages(true)
        }.apply { layoutParams = ui.lp(8) })
        body.addView(ui.text("回溯仅保存可见且位于日期范围内的消息，以及日期未确认的消息。媒体只保存占位符。无法保证全量或零风控风险。", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(12) })
        body.addView(ui.divider(24))
        body.addView(ui.heading("消息记录"))
        val modes = MaterialButtonToggleGroup(this).apply { id = R.id.ag_archive_modes; isSingleSelection = true; isSelectionRequired = true; layoutParams = ui.lp(12) }
        listOf(R.id.ag_archive_all to "最近采集", R.id.ag_archive_history to "本次回溯").forEach { (id, label) ->
            modes.addView(ui.button(label, primary = false) {}.apply { this.id = id; isCheckable = true }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        modes.check(if (historyOnly) R.id.ag_archive_history else R.id.ag_archive_all)
        modes.addOnButtonCheckedListener { _, id, checked -> if (checked) { historyOnly = id == R.id.ag_archive_history; loadMessages(true) } }
        body.addView(modes)
        archiveText = ui.text("", R.dimen.ag_type_label, ui.sub).apply { layoutParams = ui.lp(12); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(archiveText)
        messageList = ui.column(); body.addView(messageList)
        more = ui.button("加载更多", R.drawable.ag_chevron_down, false) { loadMessages(false) }.apply { layoutParams = ui.lp(12) }
        body.addView(more)
        body.addView(ui.button("清空消息记录", R.drawable.ag_x, false) { confirmClear() }.apply { layoutParams = ui.lp(20) })
        ui.install(this, root)
        loadMessages(true)
    }

    private fun chooseDate(first: Boolean) {
        val selected = if (first) start else end
        DatePickerDialog(this, { _, year, month, day ->
            val chosen = LocalDate.of(year, month + 1, day)
            if (first) start = chosen else end = chosen
            startButton.text = "起始日期 · $start"; endButton.text = "结束日期 · $end"
        }, selected.year, selected.monthValue - 1, selected.dayOfMonth).apply {
            datePicker.maxDate = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
        }.show()
    }

    private fun prepareHistory() {
        val title = titleEdit.text.toString().trim()
        titleBox.error = null
        if (title.isEmpty() || title.length > 120) { titleBox.error = "请填写目标会话的完整显示名称（最多 120 字）"; titleEdit.requestFocus(); return }
        if (start > end || end > LocalDate.now()) { ui.feedback(body, "起始日期不能晚于结束日期，且不能选择未来日期"); return }
        val config = HistoryConfig(title, HistoryRange(start, end), automatic.isChecked)
        MaterialAlertDialogBuilder(this).setTitle("准备回溯此会话？")
            .setMessage("$title\n$start 至 $end\n\n${if (config.automatic) "每次翻页至少间隔 4 秒，单次最多 200 屏或 15 分钟。" else "手动查看更早消息，不自动操作微信。"}\n仅在本机保存，不发送给 DeepSeek。回到目标微信会话后，还需在悬浮窗确认开始。")
            .setNegativeButton("取消", null).setPositiveButton("准备回溯") { _, _ ->
                val accepted = CaptureRuntime.actions?.armHistory(config) == true
                if (!accepted) ui.feedback(body, "准备失败：检查观测开关、无障碍连接、会话范围或未结束的任务")
                else {
                    findViewById<MaterialButtonToggleGroup>(R.id.ag_archive_modes).check(R.id.ag_archive_history)
                    ui.feedback(body, "已准备，请回到目标微信会话确认开始")
                }
                updateStatus(); loadMessages(true)
            }.show()
    }

    private fun updateStatus() {
        diagnosticText.text = CaptureDiagnostics(this).summary()
        val session = CaptureRuntime.history
        progressText.text = session?.summary() ?: HistoryReceipt(this).summary()
        prepare.isEnabled = session == null || session.state in setOf(HistoryState.FINISHED, HistoryState.CANCELLED)
    }

    private fun loadMessages(reset: Boolean) {
        if (!reset && loading) return
        val request = ++readGeneration
        if (reset) { beforeId = null; messageList.removeAllViews() }
        val stream = if (historyOnly) CaptureRuntime.history?.id ?: HistoryReceipt(this).stream() else null
        if (historyOnly && stream == null) { archiveText.text = "暂无回溯记录"; more.visibility = View.GONE; loading = false; return }
        val cursor = beforeId
        loading = true; archiveText.text = "读取中"; more.isEnabled = false
        worker.execute {
            val result = runCatching { archive.count(stream) to archive.recent(stream = stream, beforeId = cursor) }
            main.post {
                if (isDestroyed || request != readGeneration) return@post
                loading = false; more.isEnabled = true
                result.onSuccess { (count, messages) ->
                    archiveText.text = if (count.total == 0) "暂无已保存消息" else "${count.total} 条 · ${count.undated} 条日期未确认 · 按采集顺序"
                    messages.forEach { record ->
                        messageList.addView(ui.column().apply {
                            layoutParams = ui.lp(16)
                            addView(ui.text(record.group, R.dimen.ag_type_label, ui.brand, true))
                            addView(ui.text("${record.message.sender ?: if (record.message.side == "me") "自己" else "未识别发送者"} · ${record.message.date ?: "日期未确认"}${if (record.message.captureMethod == "ocr") " · OCR 待核对" else ""}", R.dimen.ag_type_caption, ui.sub).apply { layoutParams = ui.lp(6) })
                            addView(ui.text(record.message.text).apply { layoutParams = ui.lp(8); setTextIsSelectable(true) })
                            addView(ui.divider(12))
                        })
                    }
                    beforeId = messages.lastOrNull()?.id ?: cursor
                    more.visibility = if (messages.size == 50) View.VISIBLE else View.GONE
                }.onFailure { archiveText.text = "本地消息读取失败，原数据未覆盖"; more.visibility = View.VISIBLE }
            }
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this).setTitle("暂停观测并清空消息？")
            .setMessage("删除本机保存的原始消息，不删除观测簿中的事件。此操作不可撤销。观测会保持关闭，直到你再次开启。")
            .setNegativeButton("取消", null).setPositiveButton("暂停并清空") { _, _ ->
                Prefs(this).enabled = false; CaptureRuntime.actions?.cancelHistory()
                worker.execute {
                    val result = runCatching { archive.clear() }
                    main.post { if (!isDestroyed) { ui.feedback(body, if (result.isSuccess) "消息已清空，观测已暂停" else "清空失败，未能完成操作"); loadMessages(true); updateStatus() } }
                }
            }.show()
    }
    override fun onResume() { super.onResume(); main.post(refreshStatus) }
    override fun onPause() { main.removeCallbacks(refreshStatus); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("start", start.toString()); outState.putString("end", end.toString())
        outState.putString("title", titleEdit.text.toString()); outState.putBoolean("automatic", automatic.isChecked)
        outState.putBoolean("history_only", historyOnly); super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        readGeneration++; main.removeCallbacksAndMessages(null)
        worker.execute { archive.close() }; worker.shutdown(); super.onDestroy()
    }
    companion object { const val EXTRA_TITLE = "com.attentionguard.app.capture_title" }
}
