package com.rvgroup.pkassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rvgroup.pkassist.databinding.ActivityMainBinding
import com.rvgroup.pkassist.engine.CommandProcessor
import com.rvgroup.pkassist.service.PKAssistAccessibilityService
import com.rvgroup.pkassist.service.PKFloatingService
import com.rvgroup.pkassist.update.UpdateManager
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var updateManager: UpdateManager
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var torchState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        commandProcessor = CommandProcessor(this)
        updateManager = UpdateManager(this)
        tts = TextToSpeech(this, this)

        binding.tvVersionFooter.text =
            "v${BuildConfig.VERSION_NAME} • Background Popup & GitHub Auto-Update (PKYcodes/PKassist)"

        requestEssentialPermissions()
        setupSpeechRecognizer()
        setupListeners()

        // Check GitHub Releases automatically on startup
        checkForGitHubUpdate(silentIfUpToDate = true)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatusUI()
    }

    private fun updateServiceStatusUI() {
        val accActive = PKAssistAccessibilityService.isRunning()
        binding.tvAccessibilityStatus.text = if (accActive) {
            "✅ Screen Control Active"
        } else {
            "⚠️ Screen Control Off"
        }
        binding.btnEnableAccessibility.text = if (accActive) "Settings" else "Enable Control"

        val floatActive = PKFloatingService.isRunning
        binding.tvFloatingStatus.text = if (floatActive) {
            "🟢 Popup Active"
        } else {
            "💬 Floating Popup"
        }
        binding.btnToggleFloating.text = if (floatActive) "Stop Popup" else "Start Popup"
    }

    private fun setupListeners() {
        binding.btnSend.setOnClickListener {
            val cmd = binding.etCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                binding.etCommand.setText("")
                handleUserCommand(cmd)
            }
        }

        binding.btnMic.setOnClickListener {
            startVoiceListening()
        }

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnToggleFloating.setOnClickListener {
            toggleFloatingAssistant()
        }

        binding.btnApiKey.setOnClickListener {
            showApiKeyDialog()
        }

        binding.btnCheckUpdate.setOnClickListener {
            Toast.makeText(this, "Checking PKYcodes/PKassist releases...", Toast.LENGTH_SHORT).show()
            checkForGitHubUpdate(silentIfUpToDate = false)
        }

        // Quick Action Chips
        binding.chipWhatsApp.setOnClickListener {
            binding.etCommand.setText("Rajesh ko WhatsApp per message karo hay likhkar")
            binding.etCommand.setSelection(binding.etCommand.text.length)
        }
        binding.chipAlarm.setOnClickListener {
            handleUserCommand("alarm lagao 6:30")
        }
        binding.chipTorch.setOnClickListener {
            torchState = !torchState
            handleUserCommand(if (torchState) "flashlight on" else "flashlight off")
        }
        binding.chipYouTube.setOnClickListener {
            binding.etCommand.setText("YouTube par Arijit Singh songs chala do")
            binding.etCommand.setSelection(binding.etCommand.text.length)
        }
    }

    private fun toggleFloatingAssistant() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Enable 'Display Over Other Apps'")
                .setMessage("PKassist ko WhatsApp aur dusre apps ke upar Floating Popup me chalane ke liye Overlay permission on karein.")
                .setPositiveButton("Grant Permission") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val serviceIntent = Intent(this, PKFloatingService::class.java)
        if (PKFloatingService.isRunning) {
            stopService(serviceIntent)
            Toast.makeText(this, "Floating Popup Stopped", Toast.LENGTH_SHORT).show()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "PKassist Floating Bubble Started! Ab aap Home/WhatsApp par ja sakte hain.", Toast.LENGTH_LONG).show()
        }
        binding.root.postDelayed({ updateServiceStatusUI() }, 300)
    }

    private fun handleUserCommand(command: String) {
        appendChat("🧑 You: $command")
        val apiKey = getSavedApiKey()
        lifecycleScope.launch {
            val result = commandProcessor.process(command, apiKey)
            appendChat("🤖 PKassist [${result.engineTag}]: ${result.spokenReply}\n")
            speakOut(result.spokenReply)
        }
    }

    private fun checkForGitHubUpdate(silentIfUpToDate: Boolean) {
        lifecycleScope.launch {
            val release = updateManager.checkForUpdates()
            if (release != null && release.isNewer) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("🚀 New Update v${release.versionName} Available!")
                    .setMessage("Current: v${BuildConfig.VERSION_NAME} → Latest: v${release.versionName}\n\n${release.releaseNotes}")
                    .setPositiveButton("Download & Install") { _, _ ->
                        updateManager.downloadAndInstallApk(release)
                        Toast.makeText(this@MainActivity, "Downloading v${release.versionName} APK...", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Later", null)
                    .show()
            } else if (!silentIfUpToDate) {
                val latestVer = release?.versionName ?: BuildConfig.VERSION_NAME
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("PKassist Update Status")
                    .setMessage("Installed Version: v${BuildConfig.VERSION_NAME}\nLatest GitHub Release: v$latestVer\n\nAapka app latest version par hai! Phir bhi reinstall karna chahein to Re-Download dabayein.")
                    .setPositiveButton("OK", null)
                    .apply {
                        if (release != null) {
                            setNeutralButton("Re-Download APK") { _, _ ->
                                updateManager.downloadAndInstallApk(release)
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "AIzaSy... (Google AI Studio Key)"
            setText(getSavedApiKey())
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini Free-Tier API Key")
            .setMessage("Note: Google AI Studio ki key 'AIzaSy...' se shuru hoti hai (aistudio.google.com/apikey). Bina key ke bhi PKassist ka Offline NLU aur Free Online AI kaam karega!")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val entered = input.text.toString().trim()
                getSharedPreferences("pkassist_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("gemini_api_key", entered)
                    .apply()
                if (entered.isNotEmpty() && !entered.startsWith("AIza")) {
                    Toast.makeText(this, "Saved! (Tip: Valid Gemini API key 'AIza...' se start hoti hai)", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Gemini API Key Saved!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getSavedApiKey(): String {
        val prefs = getSharedPreferences("pkassist_prefs", MODE_PRIVATE)
        return prefs.getString("gemini_api_key", null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_GEMINI_KEY
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    binding.btnMic.text = "🔴 Bolें..."
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    binding.btnMic.text = "🎤 Speak"
                }
                override fun onError(error: Int) {
                    binding.btnMic.text = "🎤 Speak"
                }
                override fun onResults(results: Bundle?) {
                    binding.btnMic.text = "🎤 Speak"
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) {
                        handleUserCommand(spoken)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startVoiceListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun appendChat(line: String) {
        binding.tvChatHistory.append("\n$line")
        binding.scrollChat.post {
            binding.scrollChat.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun speakOut(message: String) {
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "pkassist_utt")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("en", "IN")
        }
    }

    private fun requestEssentialPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 200)
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
