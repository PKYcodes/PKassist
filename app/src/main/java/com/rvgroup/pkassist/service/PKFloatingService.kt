package com.rvgroup.pkassist.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.rvgroup.pkassist.MainActivity
import com.rvgroup.pkassist.R
import com.rvgroup.pkassist.engine.CommandProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class PKFloatingService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var isRunning = false
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private lateinit var commandProcessor: CommandProcessor
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var partialWakeLock: PowerManager.WakeLock? = null

    // Continuous 24/7 Background & Lock-Screen Listening State
    private var alwaysListeningEnabled = true
    private var isCurrentlyListening = false
    private var isExpanded = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        commandProcessor = CommandProcessor(applicationContext)
        tts = TextToSpeech(this, this)
        acquireBackgroundWakeLock()
        startForeground(2002, buildForegroundNotification())
        setupFloatingOverlay()
        setupSpeechRecognizer()
        // Start continuous background & lock-screen listening automatically
        startListeningLoop(delayMs = 600)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Keeps the CPU & microphone active even when the screen is off or locked so user can speak
     * "Hey PK" or "Unlock phone 1234" on the lock screen.
     */
    private fun acquireBackgroundWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            partialWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pkassist:AlwaysOnVoiceLock").apply {
                acquire()
            }
        } catch (_: Exception) {
        }
    }

    private fun buildForegroundNotification(): Notification {
        val channelId = "pkassist_gemini_glass_service"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "PKassist Gemini Glass & Lock-Screen Voice",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("PKassist Ultra (24/7 Voice & Lock-Screen Active)")
            .setContentText("Say 'Hey PK' or 'Unlock phone' anytime, or tap the Glass Orb")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupFloatingOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        @Suppress("DEPRECATION")
        val lockFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            lockFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 40
        }

        val bubbleContainer = floatingView!!.findViewById<View>(R.id.bubbleContainer)
        val expandedPanel = floatingView!!.findViewById<View>(R.id.expandedPanel)
        val btnMinimize = floatingView!!.findViewById<Button>(R.id.btnPopupMinimize)
        val btnAlwaysToggle = floatingView!!.findViewById<Button>(R.id.btnPopupAlwaysToggle)
        val tvAlwaysBadge = floatingView!!.findViewById<TextView>(R.id.tvAlwaysListenBadge)
        val btnPopupRun = floatingView!!.findViewById<Button>(R.id.btnPopupRun)
        val btnPopupMic = floatingView!!.findViewById<Button>(R.id.btnPopupMic)
        val btnQuickUnlock = floatingView!!.findViewById<Button>(R.id.btnQuickUnlock)
        val btnQuickRead = floatingView!!.findViewById<Button>(R.id.btnQuickRead)
        val btnQuickSendTap = floatingView!!.findViewById<Button>(R.id.btnQuickSendTap)
        val etPopupCommand = floatingView!!.findViewById<EditText>(R.id.etPopupCommand)
        val tvPopupStatus = floatingView!!.findViewById<TextView>(R.id.tvPopupStatus)

        fun setExpanded(expand: Boolean) {
            isExpanded = expand
            @Suppress("DEPRECATION")
            if (expand) {
                bubbleContainer.visibility = View.GONE
                expandedPanel.visibility = View.VISIBLE
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                params.x = 0
                params.y = 36
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            } else {
                expandedPanel.visibility = View.GONE
                bubbleContainer.visibility = View.VISIBLE
                params.gravity = Gravity.TOP or Gravity.END
                params.x = 20
                params.y = 360
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            }
            windowManager?.updateViewLayout(floatingView, params)
        }

        // Start minimized on edge, ready to pop up at bottom like Gemini
        setExpanded(false)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubbleContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - initialTouchX) < 15 && abs(event.rawY - initialTouchY) < 15) {
                        setExpanded(true)
                        startListeningLoop(delayMs = 100)
                    }
                    true
                }
                else -> false
            }
        }

        btnMinimize.setOnClickListener { setExpanded(false) }

        btnAlwaysToggle.setOnClickListener {
            alwaysListeningEnabled = !alwaysListeningEnabled
            tvAlwaysBadge.text = if (alwaysListeningEnabled) {
                "🟢 Background & Lock-Screen Listening ON"
            } else {
                "⚪ Manual Mic Mode (Tap ✨🎤 to speak)"
            }
            if (alwaysListeningEnabled) startListeningLoop(100)
        }

        fun executePopupCommand(cmd: String) {
            tvPopupStatus.text = "⚡ Executing: $cmd"
            // Minimize sheet before performing screen gestures/taps on underlying app
            setExpanded(false)
            serviceScope.launch {
                delay(350)
                val prefs = getSharedPreferences("pkassist_prefs", MODE_PRIVATE)
                val apiKey = prefs.getString("gemini_api_key", "") ?: ""
                val savedPin = prefs.getString("voice_unlock_pin", "") ?: ""
                val res = commandProcessor.process(cmd, apiKey, savedPin)
                tvPopupStatus.text = "✨ ${res.spokenReply}"
                tts?.speak(res.spokenReply, TextToSpeech.QUEUE_FLUSH, null, "pk_glass_utt")
            }
        }

        btnPopupRun.setOnClickListener {
            val cmd = etPopupCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                etPopupCommand.setText("")
                executePopupCommand(cmd)
            }
        }

        btnPopupMic.setOnClickListener {
            tvPopupStatus.text = "✨ Listening... Boliye!"
            startListeningLoop(delayMs = 50)
        }

        btnQuickUnlock.setOnClickListener {
            executePopupCommand("unlock phone")
        }

        btnQuickRead.setOnClickListener {
            executePopupCommand("screen padho")
        }

        btnQuickSendTap.setOnClickListener {
            executePopupCommand("tap send")
        }

        windowManager?.addView(floatingView, params)
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isCurrentlyListening = true
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isCurrentlyListening = false
                }
                override fun onError(error: Int) {
                    isCurrentlyListening = false
                    if (alwaysListeningEnabled) {
                        startListeningLoop(delayMs = 1200)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!partial.isNullOrBlank() && isExpanded) {
                        floatingView?.findViewById<TextView>(R.id.tvPopupStatus)?.text = "🗣️ $partial..."
                    }
                }
                override fun onResults(results: Bundle?) {
                    isCurrentlyListening = false
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()

                    if (!spoken.isNullOrBlank()) {
                        handleContinuousSpokenInput(spoken)
                    }
                    if (alwaysListeningEnabled) {
                        startListeningLoop(delayMs = 1500)
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    /**
     * Handles voice input in 3 contexts:
     * 1. Phone is locked & user speaks their saved PIN or says "unlock..." -> unlocks the device immediately!
     * 2. Glass popup is expanded -> executes any command spoken directly.
     * 3. Running in background -> triggers if user says wake word ("PK", "Hey PK", "Hello PK", "Unlock", "Call", "WhatsApp", "Torch", "Alarm").
     */
    private fun handleContinuousSpokenInput(spoken: String) {
        val lower = spoken.lowercase(Locale.ROOT)
        val prefs = getSharedPreferences("pkassist_prefs", MODE_PRIVATE)
        val savedPin = prefs.getString("voice_unlock_pin", "") ?: ""
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isDeviceLocked = km.isKeyguardLocked

        // If phone is locked and user spoke their PIN or "unlock" / "kholo" / "password"
        if (isDeviceLocked && (
                (savedPin.isNotEmpty() && lower.contains(savedPin.lowercase(Locale.ROOT))) ||
                lower.contains("unlock") || lower.contains("lock kholo") || lower.contains("password") || lower.contains("pin ")
            )
        ) {
            serviceScope.launch {
                val res = commandProcessor.process("unlock phone $spoken", apiKey, savedPin)
                tts?.speak(res.spokenReply, TextToSpeech.QUEUE_FLUSH, null, "pk_unlock")
            }
            return
        }

        // Check if popup is open OR user spoke a wake-word / direct action trigger in background
        val hasWakeTrigger = isExpanded ||
            lower.startsWith("pk ") || lower.startsWith("hey pk") || lower.startsWith("hello pk") ||
            lower.startsWith("ok pk") || lower.contains("unlock") || lower.contains("whatsapp") ||
            lower.contains("call ") || lower.contains("call lagao") || lower.contains("torch") ||
            lower.contains("flashlight") || lower.contains("alarm")

        if (hasWakeTrigger) {
            val cleanedCmd = spoken
                .replace(Regex("^(hey pk|hello pk|ok pk|pk)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifEmpty { spoken }

            floatingView?.findViewById<TextView>(R.id.tvPopupStatus)?.text = "🗣️ "$cleanedCmd""
            if (isExpanded) {
                floatingView?.findViewById<Button>(R.id.btnPopupMinimize)?.performClick()
            }
            serviceScope.launch {
                delay(300)
                val res = commandProcessor.process(cleanedCmd, apiKey, savedPin)
                floatingView?.findViewById<TextView>(R.id.tvPopupStatus)?.text = "✨ ${res.spokenReply}"
                tts?.speak(res.spokenReply, TextToSpeech.QUEUE_FLUSH, null, "pk_bg_cmd")
            }
        }
    }

    private fun startListeningLoop(delayMs: Long) {
        mainHandler.postDelayed({
            if (!isRunning || isCurrentlyListening) return@postDelayed
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer?.startListening(intent)
            } catch (_: Exception) {
            }
        }, delayMs)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
        }
    }

    override fun onDestroy() {
        isRunning = false
        alwaysListeningEnabled = false
        mainHandler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        speechRecognizer?.destroy()
        tts?.shutdown()
        try {
            if (partialWakeLock?.isHeld == true) partialWakeLock?.release()
        } catch (_: Exception) {
        }
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
        super.onDestroy()
    }
}
