package com.rvgroup.pkassist.service

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
import android.os.IBinder
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
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private lateinit var commandProcessor: CommandProcessor
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        commandProcessor = CommandProcessor(applicationContext)
        tts = TextToSpeech(this, this)
        startForeground(2002, buildForegroundNotification())
        setupFloatingOverlay()
        setupSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildForegroundNotification(): Notification {
        val channelId = "pkassist_floating_service"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "PKassist Background Assistant",
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
            .setContentTitle("PKassist Pro Active in Background")
            .setContentText("Tap floating PK bubble anytime to voice-control any app")
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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        val bubbleContainer = floatingView!!.findViewById<View>(R.id.bubbleContainer)
        val expandedPanel = floatingView!!.findViewById<View>(R.id.expandedPanel)
        val btnMinimize = floatingView!!.findViewById<Button>(R.id.btnPopupMinimize)
        val btnPopupRun = floatingView!!.findViewById<Button>(R.id.btnPopupRun)
        val btnPopupMic = floatingView!!.findViewById<Button>(R.id.btnPopupMic)
        val etPopupCommand = floatingView!!.findViewById<EditText>(R.id.etPopupCommand)
        val tvPopupStatus = floatingView!!.findViewById<TextView>(R.id.tvPopupStatus)

        fun setExpanded(expand: Boolean) {
            if (expand) {
                bubbleContainer.visibility = View.GONE
                expandedPanel.visibility = View.VISIBLE
                params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                expandedPanel.visibility = View.GONE
                bubbleContainer.visibility = View.VISIBLE
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            windowManager?.updateViewLayout(floatingView, params)
        }

        // Make bubble draggable + tap to expand
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
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - initialTouchX) < 15 && abs(event.rawY - initialTouchY) < 15) {
                        setExpanded(true)
                    }
                    true
                }
                else -> false
            }
        }

        btnMinimize.setOnClickListener { setExpanded(false) }

        btnPopupRun.setOnClickListener {
            val cmd = etPopupCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                etPopupCommand.setText("")
                // Collapse panel first so PKassist can interact with the underlying app window!
                setExpanded(false)
                serviceScope.launch {
                    delay(350)
                    val apiKey = getSharedPreferences("pkassist_prefs", MODE_PRIVATE)
                        .getString("gemini_api_key", "") ?: ""
                    val res = commandProcessor.process(cmd, apiKey)
                    tts?.speak(res.spokenReply, TextToSpeech.QUEUE_FLUSH, null, "pk_popup")
                }
            }
        }

        btnPopupMic.setOnClickListener {
            tvPopupStatus.text = "🎤 Listening... Boliye!"
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            speechRecognizer?.startListening(intent)
        }

        windowManager?.addView(floatingView, params)
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    floatingView?.findViewById<TextView>(R.id.tvPopupStatus)?.text =
                        "Dobara 🎤 dabayein ya type karein."
                }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        floatingView?.findViewById<TextView>(R.id.tvPopupStatus)?.text = " Command: $spoken"
                        // Collapse popup so underlying app receives typing/click actions
                        floatingView?.findViewById<Button>(R.id.btnPopupMinimize)?.performClick()
                        serviceScope.launch {
                            delay(350)
                            val apiKey = getSharedPreferences("pkassist_prefs", MODE_PRIVATE)
                                .getString("gemini_api_key", "") ?: ""
                            val res = commandProcessor.process(spoken, apiKey)
                            tts?.speak(res.spokenReply, TextToSpeech.QUEUE_FLUSH, null, "pk_popup_voice")
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
        }
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        speechRecognizer?.destroy()
        tts?.shutdown()
        if (floatingView != null) {
            windowManager?.removeView(floatingView)
            floatingView = null
        }
        super.onDestroy()
    }
}
