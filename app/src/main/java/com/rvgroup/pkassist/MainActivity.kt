package com.rvgroup.pkassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.rvgroup.pkassist.update.UpdateManager
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var updateManager: UpdateManager
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        commandProcessor = CommandProcessor(this)
        updateManager = UpdateManager(this)
        tts = TextToSpeech(this, this)

        binding.tvVersionFooter.text = "v${BuildConfig.VERSION_NAME} • GitHub Auto-Update Active (PKYcodes/PKassist)"

        requestEssentialPermissions()
        setupSpeechRecognizer()
        setupListeners()

        // Automatically check GitHub Releases on launch
        checkForGitHubUpdate(silentIfUpToDate = true)
    }

    override fun onResume() {
        super.onResume()
        val accActive = PKAssistAccessibilityService.isRunning()
        binding.tvAccessibilityStatus.text = if (accActive) {
            "✅ Accessibility Active (Auto-Typing & Screen Control Ready)"
        } else {
            "Enable Accessibility for auto-typing & screen control"
        }
        binding.btnEnableAccessibility.text = if (accActive) "Settings" else "Enable"
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

        binding.btnApiKey.setOnClickListener {
            showApiKeyDialog()
        }

        binding.btnCheckUpdate.setOnClickListener {
            checkForGitHubUpdate(silentIfUpToDate = false)
        }
    }

    private fun handleUserCommand(command: String) {
        appendChat("You: $command")
        val apiKey = getSavedApiKey()
        lifecycleScope.launch {
            val result = commandProcessor.process(command, apiKey)
            val tag = if (result.usedOnlineGemini) "[Gemini Lite]" else "[Offline Engine]"
            appendChat("PKassist $tag: ${result.spokenReply}\n")
            speakOut(result.spokenReply)
        }
    }

    private fun checkForGitHubUpdate(silentIfUpToDate: Boolean) {
        lifecycleScope.launch {
            val release = updateManager.checkForUpdates()
            if (release != null) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("New Update v${release.versionName} Available!")
                    .setMessage("A new release was pushed to PKYcodes/PKassist.\n\n${release.releaseNotes}")
                    .setPositiveButton("Download & Install") { _, _ ->
                        updateManager.downloadAndInstallApk(release)
                        Toast.makeText(this@MainActivity, "Downloading APK...", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Later", null)
                    .show()
            } else if (!silentIfUpToDate) {
                Toast.makeText(
                    this@MainActivity,
                    "PKassist v${BuildConfig.VERSION_NAME} is up to date!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = "Paste free Gemini API Key (AI Studio)"
            setText(getSavedApiKey())
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini Free-Tier API Key")
            .setMessage("Uses low-demand gemini-2.5-flash-lite / gemini-2.0-flash-lite for high free quota.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                getSharedPreferences("pkassist_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("gemini_api_key", input.text.toString().trim())
                    .apply()
                Toast.makeText(this, "API Key Saved!", Toast.LENGTH_SHORT).show()
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
                    binding.btnMic.text = "Listening..."
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
            // Supports offline speech recognition when language pack is downloaded on device
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
            Manifest.permission.SEND_SMS,
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
