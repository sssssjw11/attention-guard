package com.jev.probe.capture

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg

/** Opt-in, local-only fallback restricted to proven, visible text-bubble rectangles. */
class OnDeviceChatOcr(private val service: AccessibilityService, private val hideOverlay: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: TextRecognizer? = null
    private var busy = false
    private var generation = 0
    private var lastAttempt = -INTERVAL
    private var scene = ""
    private var attempts = 0
    private var completedScene = false

    fun capture(inspection: ChatInspection, stillCurrent: () -> Boolean, done: (ChatSnapshot?, String) -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (!eligible(inspection)) return
        val source = requireNotNull(inspection.snapshot)
        val key = "${source.title}|${inspection.ocrRegions}"
        if (key != scene) { cancel(); scene = key; retry() }
        if (busy || completedScene || attempts >= 3 || now - lastAttempt < INTERVAL) return
        lastAttempt = now; busy = true
        attempts++
        val request = ++generation
        val messages = mutableListOf<Msg>()
        fun complete(result: ChatSnapshot?, reason: String) {
            if (request != generation) return
            generation++; busy = false; hideOverlay(false)
            main.removeCallbacksAndMessages(null)
            if (result != null && result.messages.isNotEmpty()) completedScene = true
            if (stillCurrent()) done(result, reason)
        }
        main.postDelayed({ complete(null, "本机 OCR 超时，稍后重试") }, 20_000)
        hideOverlay(true)
        main.postDelayed({
            if (request != generation) return@postDelayed
            if (!stillCurrent()) { complete(null, "会话已变化"); return@postDelayed }
            runCatching {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onFailure(errorCode: Int) { complete(null, "系统截屏不可用（$errorCode）") }
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val bitmap = try {
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            try { hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle() }
                        } catch (_: Exception) { null } finally { buffer.close() }
                        if (request == generation) hideOverlay(false)
                        if (bitmap == null) { complete(null, "截屏图像暂不可用"); return }
                        if (request != generation || !stillCurrent()) { bitmap.recycle(); complete(null, "会话已变化"); return }
                        val regions = inspection.ocrRegions
                        fun recognize(index: Int) {
                            if (request != generation || !stillCurrent()) { bitmap.recycle(); complete(null, "会话已变化"); return }
                            if (index >= regions.size) {
                                bitmap.recycle()
                                complete(source.copy(messages = messages.toList()), if (messages.isEmpty()) "OCR 未识别到文字" else "本机 OCR 识别完成，内容待核对")
                                return
                            }
                            val region = regions[index]; val b = region.bounds
                            if (b.left < 0 || b.top < 0 || b.right > bitmap.width || b.bottom > bitmap.height) {
                                bitmap.recycle(); complete(null, "画面尺寸已变化，已放弃本次识别"); return
                            }
                            val crop = Bitmap.createBitmap(bitmap, b.left, b.top, b.width(), b.height())
                            runCatching {
                                val engine = recognizer ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).also { recognizer = it }
                                engine.process(InputImage.fromBitmap(crop, 0))
                                    .addOnSuccessListener(service.mainExecutor) { text ->
                                        val value = text.text.trim()
                                        if (value.isNotEmpty()) messages.add(Msg(region.side, value, date = region.date, timeLabel = region.timeLabel, captureMethod = "ocr"))
                                        crop.recycle(); recognize(index + 1)
                                    }.addOnFailureListener(service.mainExecutor) {
                                        crop.recycle(); bitmap.recycle(); complete(null, "本机 OCR 识别失败")
                                    }
                            }.onFailure { crop.recycle(); bitmap.recycle(); complete(null, "本机 OCR 无法初始化") }
                        }
                        recognize(0)
                    }
                })
            }.onFailure { complete(null, "系统不允许本次截屏") }
        }, 250)
    }

    fun retry() { attempts = 0; completedScene = false }
    fun cancel() { generation++; busy = false; main.removeCallbacksAndMessages(null); hideOverlay(false) }
    fun close() { cancel(); recognizer?.close(); recognizer = null }

    companion object {
        private const val INTERVAL = 8000L
        fun eligible(inspection: ChatInspection) = !inspection.snapshot?.title.isNullOrBlank() &&
            inspection.snapshot?.messages?.isEmpty() == true && inspection.knownBubbles > 0 &&
            inspection.ocrRegions.size in 1..30 && inspection.ocrRegions.all { !it.bounds.isEmpty }
    }
}
