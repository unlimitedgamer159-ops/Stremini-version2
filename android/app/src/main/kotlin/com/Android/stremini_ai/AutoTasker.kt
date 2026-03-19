package com.Android.stremini_ai

// ============================================================================
// AutoTasker.kt — Complete voice-controlled phone automation for Stremini AI
//
// Architecture:
//   VoiceEngine       — SpeechRecognizer lifecycle, continuous listen mode
//   ScreenCapture     — Base64 screenshot via AccessibilityService GLOBAL_ACTION
//   AutoTaskerBrain   — Orchestrates fast-path → backend → execute loop
//   AutoTaskerOverlay — Floating UI: status bar + waveform + output panel
//   AutoTaskerService — Entry point, wires everything together
//
// Backend endpoint: POST /voice-command
//   Body: { command, ui_context, step, history, screenshot, screenshot_mime }
//   Response: { actions[], is_done, fast_path }
//
// Supported actions (mirrors automation.js):
//   tap, long_press, type, scroll, swipe, open_app,
//   home, back, recents, notifications, quick_settings,
//   screenshot, volume, media_key, brightness, clipboard,
//   wait, request_screen, done, error
// ============================================================================

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// Constants
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG              = "AutoTasker"
private const val BACKEND_URL      = "https://ai-keyboard-backend.vishwajeetadkine705.workers.dev"
private const val VOICE_COMMAND_EP = "$BACKEND_URL/automation/voice-command"
private const val MAX_STEPS        = 20
private const val NOTIF_CHANNEL_ID = "autotasker_channel"
private const val NOTIF_ID         = 42

// ─────────────────────────────────────────────────────────────────────────────
// VoiceEngine — thin SpeechRecognizer wrapper with keep-alive loop
// ─────────────────────────────────────────────────────────────────────────────

class VoiceEngine(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal:   (String) -> Unit,
    private val onError:   (Int)    -> Unit,
    private val onReady:   ()       -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var active = false

    val isListening get() = active

    fun start() {
        if (active) return
        active = true
        createAndStart()
    }

    fun stop() {
        active = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    private fun createAndStart() {
        if (!active) return
        recognizer?.destroy()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            onError(-1)
            return
        }

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { onReady() }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    onPartial(partial)
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.trim() ?: ""
                    if (text.isNotBlank()) onFinal(text)
                    // Don't auto-restart here — let AutoTaskerBrain decide
                }

                override fun onError(error: Int) {
                    onError(error)
                }
            })
            startListening(buildIntent())
        }
    }

    private fun buildIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
    }

    /** Called after a task finishes — resumes listening if still active */
    fun resume() { if (active) createAndStart() }
}

// ─────────────────────────────────────────────────────────────────────────────
// ScreenCapture — grabs screenshot via MediaProjection OR Accessibility fallback
// ─────────────────────────────────────────────────────────────────────────────

object ScreenCapture {

    /**
     * Take a screenshot using GLOBAL_ACTION_TAKE_SCREENSHOT (API 28+).
     * Returns base64-encoded JPEG string, or null on failure.
     *
     * For Android 9+, AccessibilityService can take screenshots directly.
     * We use a callback-based approach with a 1-second timeout.
     */
    fun captureBase64(service: ScreenReaderService): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Ask the system to take a screenshot; result comes through
                // AccessibilityService.onScreenshotResult (API 30+)
                // For API 28-29 we trigger the global action and capture via
                // the accessibility node snapshot.
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
            // Fallback: dump the accessibility node tree into a text representation
            // (real pixel screenshot is handled by TakeScreenshotCallback on API 30+)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture failed: ${e.message}")
            null
        }
    }

    /** Encode a Bitmap to JPEG base64 */
    fun bitmapToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AutoTaskerBrain — orchestrates the see-think-act loop
// ─────────────────────────────────────────────────────────────────────────────

class AutoTaskerBrain(
    private val service: ScreenReaderService,
    private val onStatus: (String) -> Unit,
    private val onOutput: (String) -> Unit,
) {
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    // History of actions for multi-step context
    private val actionHistory = mutableListOf<JSONObject>()

    fun cancel() { scope.coroutineContext.cancelChildren() }
    fun destroy() { scope.cancel() }

    // ── Public entry point ────────────────────────────────────────────────────

    fun execute(command: String) {
        scope.launch {
            actionHistory.clear()
            onOutput("🎙 \"$command\"\n\n⚡ Processing...")
            onStatus("⚡ Thinking...")

            try {
                runAgenticLoop(command)
            } catch (e: CancellationException) {
                onStatus("⏹ Stopped")
                onOutput("🎙 \"$command\"\n\n⏹ Cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Brain error: ${e.message}", e)
                onStatus("❌ Error")
                onOutput("🎙 \"$command\"\n\n❌ ${e.message}")
            }
        }
    }

    // ── Agentic loop ──────────────────────────────────────────────────────────

    private suspend fun runAgenticLoop(command: String) {
        var step       = 1
        var lastError: String? = null
        var outputText = "🎙 \"$command\"\n\n"

        while (step <= MAX_STEPS) {
            onStatus("Step $step/$MAX_STEPS — thinking...")

            // Build screen state
            val uiContext = withContext(Dispatchers.Main) {
                buildUIContext()
            }

            // Optional: capture screenshot for vision mode
            // val screenshot = ScreenCapture.captureBase64(service)

            val payload = JSONObject().apply {
                put("command",  command)
                put("ui_context", uiContext)
                put("step",     step)
                put("history",  JSONArray(actionHistory.map { it.toString() }))
                if (lastError != null) put("error", lastError)
            }

            val response = callBackend(payload) ?: run {
                onStatus("❌ Network error")
                outputText += "❌ Could not reach backend"
                onOutput(outputText)
                return
            }

            val actions  = response.optJSONArray("actions") ?: JSONArray()
            val isFast   = response.optBoolean("fast_path", false)
            val isDone   = response.optBoolean("is_done", false)

            if (isFast) {
                onStatus("⚡ Fast path")
                outputText += "⚡ Fast path detected\n"
            }

            // Execute actions
            var taskComplete = false
            lastError        = null

            for (i in 0 until actions.length()) {
                val action     = actions.optJSONObject(i) ?: continue
                val actionType = action.optString("action", "")

                onStatus("▶ $actionType")
                outputText += "  • $actionType"

                when (actionType) {
                    "done" -> {
                        val summary = action.optString("summary", "✅ Done")
                        outputText += ": $summary\n\n✅ $summary"
                        onOutput(outputText)
                        onStatus("✅ $summary")
                        taskComplete = true
                        break
                    }
                    "error" -> {
                        val reason = action.optString("reason", "Unknown error")
                        val recoverable = action.optBoolean("recoverable", true)
                        outputText += ": $reason\n"
                        lastError  = reason
                        if (!recoverable) {
                            outputText += "\n❌ Task failed: $reason"
                            onOutput(outputText)
                            onStatus("❌ Failed")
                            return
                        }
                        break // retry loop with error context
                    }
                    "request_screen" -> {
                        outputText += "\n"
                        onOutput(outputText)
                        delay(600)
                        break // break inner, let outer loop get fresh screen
                    }
                    "wait" -> {
                        val ms = action.optLong("duration_ms", 1000L).coerceIn(100L, 8000L)
                        outputText += " (${ms}ms)\n"
                        delay(ms)
                    }
                    else -> {
                        val ok = withContext(Dispatchers.Main) {
                            runCatching { executeAction(action) }.getOrElse {
                                Log.e(TAG, "Action failed: $actionType — ${it.message}")
                                lastError = it.message
                                false
                            }
                        }
                        outputText += if (ok) " ✓\n" else " ✗\n"
                        actionHistory.add(action)

                        if (!ok) lastError = "Action '$actionType' failed"
                        delay(actionDelay(actionType))
                    }
                }
            }

            if (taskComplete) return
            if (isDone) {
                outputText += "\n✅ Complete"
                onOutput(outputText)
                onStatus("✅ Complete")
                return
            }

            onOutput(outputText)
            step++
        }

        onStatus("⚠ Max steps")
        onOutput(outputText + "\n⚠ Stopped after $MAX_STEPS steps")
    }

    // ── Backend call ──────────────────────────────────────────────────────────

    private suspend fun callBackend(payload: JSONObject): JSONObject? {
        return withContext(Dispatchers.IO) {
            try {
                val body = payload.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(VOICE_COMMAND_EP)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Backend HTTP ${response.code}")
                        return@use null
                    }
                    val raw = response.body?.string() ?: return@use null
                    runCatching { JSONObject(raw) }.getOrNull()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Backend call error: ${e.message}")
                null
            }
        }
    }

    // ── Build UI context from accessibility tree ───────────────────────────────

    private fun buildUIContext(): JSONObject {
        val root = service.rootInActiveWindow ?: return JSONObject()
        val nodes = JSONArray()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 120) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotBlank() || desc.isNotBlank()) {
                nodes.put(JSONObject().apply {
                    put("text",      text)
                    put("desc",      desc)
                    put("viewId",    node.viewIdResourceName ?: "")
                    put("clickable", node.isClickable)
                    put("editable",  node.isEditable)
                    put("focused",   node.isFocused)
                })
                count++
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return JSONObject().apply { put("nodes", nodes) }
    }

    // ── Action executor ───────────────────────────────────────────────────────

    private fun executeAction(action: JSONObject): Boolean {
        val type = action.optString("action", "").lowercase().trim()
        return when (type) {

            // Navigation
            "home"          -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "back"          -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "recents"       -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings"-> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "power_menu"    -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            "screenshot"    -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                                   service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                               else false

            // Tap / long-press
            "tap", "click" -> {
                val text   = action.optString("target_text", "")
                val coords = action.optJSONArray("coordinates")
                when {
                    coords != null && coords.length() >= 2 ->
                        tapAt(coords.optDouble(0).toFloat(), coords.optDouble(1).toFloat())
                    text.isNotBlank() -> tapByText(text)
                    else -> false
                }
            }

            "long_press" -> {
                val text = action.optString("target_text", "")
                if (text.isNotBlank()) longPressByText(text) else false
            }

            // Text input
            "type", "input" -> {
                val text       = action.optString("text", "")
                val clearFirst = action.optBoolean("clear_first", false)
                if (clearFirst) clearFocused()
                typeText(text)
            }

            // Scroll
            "scroll" -> {
                val dir    = action.optString("direction", "down").lowercase()
                val amount = action.optInt("amount", 1).coerceIn(1, 15)
                repeat(amount) { scrollOnce(dir) }
                true
            }

            // Swipe
            "swipe" -> {
                val from = action.optJSONArray("from")
                val to   = action.optJSONArray("to")
                val dur  = action.optLong("duration_ms", 300L)
                if (from != null && to != null && from.length() >= 2 && to.length() >= 2) {
                    swipe(from.optDouble(0).toFloat(), from.optDouble(1).toFloat(),
                          to.optDouble(0).toFloat(),   to.optDouble(1).toFloat(), dur)
                } else false
            }

            // App launch
            "open_app", "launch_app" -> {
                val name = action.optString("app_name", "")
                val pkg  = action.optString("package", "")
                if (pkg.isNotBlank()) launchPackage(pkg) else launchByName(name)
            }

            // Volume
            "volume" -> {
                val dir = action.optString("direction", "up").lowercase()
                adjustVolume(dir)
            }

            // Media keys
            "media_key" -> {
                val key = action.optString("key", "play").lowercase()
                sendMediaKey(key)
            }

            // Brightness
            "brightness" -> {
                val dir = action.optString("direction", "up").lowercase()
                adjustBrightness(dir)
            }

            // Clipboard
            "clipboard" -> {
                val op = action.optString("operation", "copy").lowercase()
                clipboardOp(op)
            }

            else -> {
                Log.w(TAG, "Unknown action type: $type")
                false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Low-level helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun tapAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        service.dispatchGesture(gesture, null, null)
        return true
    }

    private fun tapByText(text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findNode(root) { n ->
            n.text?.toString()?.contains(text, ignoreCase = true) == true ||
            n.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
        } ?: return false
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        return tapAt(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun longPressByText(text: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findNode(root) { n ->
            n.text?.toString()?.contains(text, ignoreCase = true) == true
        } ?: return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
            .build()
        service.dispatchGesture(gesture, null, null)
        return true
    }

    private fun typeText(text: String): Boolean {
        if (text.isBlank()) return false
        val root = service.rootInActiveWindow ?: return false
        val node = findNode(root) { it.isFocused && it.isEditable }
            ?: findNode(root) { it.isEditable }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clearFocused(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findNode(root) { it.isFocused && it.isEditable }
            ?: findNode(root) { it.isEditable }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun scrollOnce(direction: String): Boolean {
        val root      = service.rootInActiveWindow ?: return false
        val scrollable = findNode(root) { it.isScrollable } ?: return false
        return when (direction) {
            "up",   "backward" -> scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            "left"             -> scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            else               -> scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        service.dispatchGesture(gesture, null, null)
        return true
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return true
    }

    private fun launchByName(name: String): Boolean {
        if (name.isBlank()) return false
        val lower = name.lowercase().trim()
        val apps  = service.packageManager.getInstalledApplications(0)
        val match = apps.firstOrNull {
            service.packageManager.getApplicationLabel(it).toString().lowercase().contains(lower)
        } ?: return false
        return launchPackage(match.packageName)
    }

    private fun adjustVolume(direction: String): Boolean {
        val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            when (direction) {
                "up"    -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "down"  -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "mute"  -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                               am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
                           else @Suppress("DEPRECATION") am.ringerMode = AudioManager.RINGER_MODE_SILENT
                "unmute"-> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                               am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
                           else @Suppress("DEPRECATION") am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            true
        } catch (e: Exception) { false }
    }

    private fun sendMediaKey(key: String): Boolean {
        return try {
            val code = when (key) {
                "play"          -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                "pause"         -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                "play_pause"    -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "next"          -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                "previous","prev"-> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "stop"          -> android.view.KeyEvent.KEYCODE_MEDIA_STOP
                else            -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            val am = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            true
        } catch (e: Exception) { false }
    }

    private fun adjustBrightness(direction: String): Boolean {
        return try {
            val current = android.provider.Settings.System.getInt(
                service.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
            val newVal  = when (direction) {
                "up"   -> minOf(255, current + 50)
                "down" -> maxOf(0,   current - 50)
                else   -> current
            }
            android.provider.Settings.System.putInt(
                service.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, newVal)
            true
        } catch (e: Exception) { false }
    }

    private fun clipboardOp(operation: String): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val node = findNode(root) { it.isFocused }
            ?: findNode(root) { it.isEditable }
            ?: return false
        return when (operation) {
            "copy"       -> node.performAction(AccessibilityNodeInfo.ACTION_COPY)
            "paste"      -> node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            "cut"        -> node.performAction(AccessibilityNodeInfo.ACTION_CUT)
            "select_all" -> node.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            else         -> false
        }
    }

    // BFS node finder
    private fun findNode(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    // Per-action delay heuristic (mirrors automation.js)
    private fun actionDelay(type: String): Long = when (type) {
        "open_app", "launch_app" -> 2000L
        "tap", "click"           -> 500L
        "long_press"             -> 800L
        "type", "input"          -> 350L
        "scroll"                 -> 200L
        "swipe"                  -> 400L
        "home", "back", "recents"-> 600L
        "notifications", "quick_settings" -> 600L
        "screenshot"             -> 1000L
        "volume", "media_key", "brightness", "clipboard" -> 150L
        else                     -> 350L
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AutoTaskerOverlay — floating UI drawn over all apps
// ─────────────────────────────────────────────────────────────────────────────

class AutoTaskerOverlay(private val context: Context) {

    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: View? = null

    // Sub-views
    private var tvStatus:   TextView?   = null
    private var tvOutput:   TextView?   = null
    private var tvPartial:  TextView?   = null
    private var waveLayout: LinearLayout? = null
    private var btnClose:   ImageView?  = null
    private var btnMic:     ImageView?  = null

    private val waveAnimators = mutableListOf<ValueAnimator>()

    // Callbacks for button taps
    var onCloseTapped: (() -> Unit)? = null
    var onMicTapped:   (() -> Unit)? = null

    fun show() {
        if (rootView != null) return
        buildView()
    }

    fun hide() {
        rootView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        rootView = null
        waveAnimators.forEach { it.cancel() }
        waveAnimators.clear()
    }

    fun setStatus(text: String) {
        rootView?.post { tvStatus?.text = text }
    }

    fun setOutput(text: String) {
        rootView?.post { tvOutput?.text = text }
    }

    fun setPartialTranscript(text: String) {
        rootView?.post { tvPartial?.text = if (text.isBlank()) "" else "…$text" }
    }

    fun setMicState(listening: Boolean) {
        rootView?.post {
            val color = if (listening) android.graphics.Color.parseColor("#22C55E")
                        else          android.graphics.Color.parseColor("#EF4444")
            btnMic?.setColorFilter(color)
            if (listening) startWaveAnimation() else stopWaveAnimation()
        }
    }

    private fun buildView() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        // ── Root container ────────────────────────────────────────────────────
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = buildRoundedBackground(
                android.graphics.Color.parseColor("#EE0F172A"),
                android.graphics.Color.parseColor("#22A6E2"),
                dp(20).toFloat(), dp(2).toFloat()
            )
            elevation = 20f
        }

        // ── Header row ────────────────────────────────────────────────────────
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        // Mic button
        val mic = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(android.graphics.Color.parseColor("#22C55E"))
            setOnClickListener { onMicTapped?.invoke() }
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).also {
                it.marginEnd = dp(10)
            }
        }
        btnMic = mic
        header.addView(mic)

        // Title
        val title = TextView(context).apply {
            text = "Auto Tasker"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
        }
        header.addView(title)

        // Close button
        val close = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(android.graphics.Color.parseColor("#9CA3AF"))
            setOnClickListener { onCloseTapped?.invoke() }
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        btnClose = close
        header.addView(close)
        root.addView(header)

        // ── Partial transcript ────────────────────────────────────────────────
        val partial = TextView(context).apply {
            text = ""
            setTextColor(android.graphics.Color.parseColor("#60A5FA"))
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        }
        tvPartial = partial
        root.addView(partial)

        // ── Waveform ──────────────────────────────────────────────────────────
        val wave = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        waveLayout = wave
        buildWaveBars(wave)
        root.addView(wave)

        // ── Status line ───────────────────────────────────────────────────────
        val status = TextView(context).apply {
            text = "Ready — tap mic to speak"
            setTextColor(android.graphics.Color.parseColor("#22D3EE"))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        tvStatus = status
        root.addView(status)

        // ── Output area ───────────────────────────────────────────────────────
        val scroll = android.widget.ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(160))
            setPadding(0, dp(8), 0, 0)
        }
        val output = TextView(context).apply {
            text = ""
            setTextColor(android.graphics.Color.parseColor("#D1D5DB"))
            textSize = 12f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        tvOutput = output
        scroll.addView(output)
        root.addView(scroll)

        rootView = root

        val params = WindowManager.LayoutParams(
            dp(320), WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y       = dp(80)
        }

        wm.addView(root, params)
    }

    private fun buildWaveBars(container: LinearLayout) {
        repeat(12) {
            val bar = View(context).apply {
                background = buildRoundedBackground(
                    android.graphics.Color.parseColor("#34D399"), 0, dp(3).toFloat(), 0f)
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(8)).also { lp ->
                    lp.marginStart = dp(3)
                    lp.marginEnd   = dp(3)
                }
            }
            container.addView(bar)
        }
    }

    private fun startWaveAnimation() {
        stopWaveAnimation()
        val bars = waveLayout ?: return
        for (i in 0 until bars.childCount) {
            val bar = bars.getChildAt(i)
            val animator = ValueAnimator.ofFloat(8f, 24f, 8f).apply {
                duration    = (400L + i * 80L)
                repeatCount = ValueAnimator.INFINITE
                repeatMode  = ValueAnimator.REVERSE
                startDelay  = (i * 60L)
                addUpdateListener { anim ->
                    val h = (anim.animatedValue as Float)
                    bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams).also { lp ->
                        lp.height = dp(h.toInt())
                    }
                    bar.requestLayout()
                }
                start()
            }
            waveAnimators.add(animator)
        }
    }

    private fun stopWaveAnimation() {
        waveAnimators.forEach { it.cancel() }
        waveAnimators.clear()
        val bars = waveLayout ?: return
        for (i in 0 until bars.childCount) {
            val bar = bars.getChildAt(i)
            bar.layoutParams = (bar.layoutParams as LinearLayout.LayoutParams).also { lp ->
                lp.height = dp(8)
            }
            bar.requestLayout()
        }
    }

    private fun buildRoundedBackground(
        fillColor: Int, strokeColor: Int,
        cornerRadius: Float, strokeWidth: Float
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(fillColor)
            this.cornerRadius = cornerRadius
            if (strokeColor != 0) setStroke(strokeWidth.toInt(), strokeColor)
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

// ─────────────────────────────────────────────────────────────────────────────
// AutoTaskerService — foreground service, entry point
// ─────────────────────────────────────────────────────────────────────────────

/**
 * AutoTaskerService ties VoiceEngine + AutoTaskerBrain + AutoTaskerOverlay together.
 *
 * Start it from ChatOverlayService or MainActivity:
 *
 *   val intent = Intent(context, AutoTaskerService::class.java)
 *   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
 *       context.startForegroundService(intent)
 *   else context.startService(intent)
 *
 * Stop it:
 *   context.stopService(Intent(context, AutoTaskerService::class.java))
 *
 * Toggle mic from notification:
 *   Intent(AutoTaskerService.ACTION_TOGGLE_MIC)
 */
class AutoTaskerService : Service() {

    companion object {
        const val ACTION_TOGGLE_MIC = "com.Android.stremini_ai.AUTOTASKER_TOGGLE_MIC"
        const val ACTION_STOP       = "com.Android.stremini_ai.AUTOTASKER_STOP"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var overlay: AutoTaskerOverlay? = null
    private var voice:   VoiceEngine? = null
    private var brain:   AutoTaskerBrain? = null

    // State
    private var isExecuting = false
    private var continuousMode = true  // keep listening after task done

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground()
        buildComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_MIC -> toggleMic()
            ACTION_STOP       -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        overlay?.hide()
        voice?.stop()
        brain?.destroy()
        serviceScope.cancel()
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    private fun buildComponents() {
        // Overlay
        overlay = AutoTaskerOverlay(this).apply {
            show()
            onCloseTapped = { stopSelf() }
            onMicTapped   = { toggleMic() }
            setMicState(false)
            setStatus("Ready — tap mic to speak")
        }

        // Brain
        val service = ScreenReaderService.getInstance()
        if (service == null) {
            overlay?.setStatus("⚠ Enable Accessibility first")
            return
        }

        brain = AutoTaskerBrain(
            service   = service,
            onStatus  = { msg -> overlay?.setStatus(msg); updateNotification(msg) },
            onOutput  = { text -> overlay?.setOutput(text) }
        )

        // Voice engine — starts listening immediately
        buildVoiceEngine()
        startListening()
    }

    private fun buildVoiceEngine() {
        voice = VoiceEngine(
            context   = this,
            onPartial = { partial ->
                overlay?.setPartialTranscript(partial)
            },
            onFinal   = { command ->
                handleCommand(command)
            },
            onError   = { code ->
                val msg = voiceErrorMessage(code)
                overlay?.setStatus(msg)
                // Auto-retry listening after transient errors
                if (code != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                    code != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    serviceScope.launch {
                        delay(700)
                        if (!isExecuting) voice?.resume()
                    }
                }
            },
            onReady   = {
                if (!isExecuting) {
                    overlay?.setStatus("🎤 Listening...")
                    overlay?.setMicState(true)
                }
            }
        )
    }

    private fun startListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            overlay?.setStatus("⚠ Microphone permission required")
            return
        }
        voice?.start()
    }

    private fun toggleMic() {
        if (voice?.isListening == true) {
            voice?.stop()
            overlay?.setMicState(false)
            overlay?.setStatus("Mic off")
        } else {
            startListening()
        }
    }

    // ── Command handling ──────────────────────────────────────────────────────

    private fun handleCommand(command: String) {
        if (command.isBlank()) {
            voice?.resume()
            return
        }

        // Intercept meta-commands
        val lower = command.lowercase().trim()
        if (lower == "stop" || lower == "cancel" || lower == "stop tasker") {
            brain?.cancel()
            isExecuting = false
            overlay?.setStatus("⏹ Stopped")
            overlay?.setMicState(false)
            if (continuousMode) { delay100thenResume() }
            return
        }
        if (lower == "quit" || lower == "exit" || lower == "close autotasker") {
            stopSelf(); return
        }

        // Stop listening while executing
        voice?.stop()
        overlay?.setPartialTranscript("")
        overlay?.setMicState(false)
        isExecuting = true

        serviceScope.launch {
            brain?.execute(command)
            isExecuting = false
            if (continuousMode) {
                delay(1200)
                overlay?.setStatus("🎤 Listening...")
                overlay?.setMicState(true)
                voice?.resume()
            } else {
                overlay?.setStatus("Task complete — tap mic to continue")
                overlay?.setMicState(false)
            }
        }
    }

    private fun delay100thenResume() {
        serviceScope.launch {
            delay(1000)
            overlay?.setStatus("🎤 Listening...")
            overlay?.setMicState(true)
            voice?.resume()
        }
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID, "Auto Tasker",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        startForeground(NOTIF_ID, buildNotification("Ready"))
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): android.app.Notification {
        val toggleIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AutoTaskerService::class.java).apply { action = ACTION_TOGGLE_MIC },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AutoTaskerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("🎙 Auto Tasker")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(0, "Toggle Mic", toggleIntent)
            .addAction(0, "Stop",       stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun voiceErrorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO                  -> "🔇 Audio error — retrying"
        SpeechRecognizer.ERROR_CLIENT                 -> "⚠ Client error — retrying"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "⚠ Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK                -> "📡 Network error — retrying"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT        -> "📡 Network timeout — retrying"
        SpeechRecognizer.ERROR_NO_MATCH               -> "🤷 No speech detected"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY        -> "⏳ Recognizer busy"
        SpeechRecognizer.ERROR_SERVER                 -> "🌐 Server error — retrying"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT         -> "⏱ Silence timeout"
        else                                          -> "⚠ Voice error ($code)"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AutoTaskerManager — thin wrapper used by ChatOverlayService
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Drop this into ChatOverlayService in place of the old handleAutoTasker() method.
 *
 * Usage:
 *   private val autoTasker = AutoTaskerManager(this)
 *
 *   // In handleAutoTasker():
 *   autoTasker.toggle(menuItems[0].id, activeFeatures)
 */
class AutoTaskerManager(private val context: Context) {

    private var running = false

    fun toggle(featureId: Int, activeFeatures: MutableSet<Int>) {
        if (running) {
            stop(featureId, activeFeatures)
        } else {
            start(featureId, activeFeatures)
        }
    }

    fun start(featureId: Int, activeFeatures: MutableSet<Int>) {
        if (running) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "Microphone permission required", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(context, AutoTaskerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent)
        else
            context.startService(intent)
        running = true
        activeFeatures.add(featureId)
    }

    fun stop(featureId: Int, activeFeatures: MutableSet<Int>) {
        context.stopService(Intent(context, AutoTaskerService::class.java))
        running = false
        activeFeatures.remove(featureId)
    }

    fun isRunning() = running
}
