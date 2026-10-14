package com.rvgroup.pkassist.engine

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import com.rvgroup.pkassist.BuildConfig
import com.rvgroup.pkassist.service.PKAssistAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AssistantResult(
    val spokenReply: String,
    val usedOnlineGemini: Boolean
)

class CommandProcessor(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun process(rawCommand: String, apiKey: String): AssistantResult = withContext(Dispatchers.IO) {
        val text = rawCommand.trim()
        if (text.isEmpty()) {
            return@withContext AssistantResult("Kripya koi command bolein ya type karein.", false)
        }

        // Step 1: Fast Offline Execution for deterministic device commands (works 100% offline & zero latency)
        val offlineMatch = tryOfflineCommand(text)
        if (offlineMatch != null) {
            return@withContext AssistantResult(offlineMatch, false)
        }

        // Step 2: If Online and Gemini API Key is configured, use free-tier low-demand Gemini Flash-Lite
        if (isOnline() && apiKey.isNotBlank()) {
            val geminiResult = callGeminiWithFallback(text, apiKey)
            if (geminiResult != null) {
                return@withContext AssistantResult(geminiResult, true)
            }
        }

        // Step 3: Offline fallback message
        return@withContext AssistantResult(
            "Abhi phone offline hai ya Gemini API key set nahi hai. Offline mode me aap Call, SMS, App Open, Typing, Flashlight, Battery, ya Time commands de sakte hain.",
            false
        )
    }

    /**
     * Handles English, Hindi, and Hinglish phone commands completely offline.
     */
    private fun tryOfflineCommand(input: String): String? {
        val lower = input.lowercase(Locale.getDefault())

        // 1. Typing automation: "type hello world" or "write namaste"
        if (lower.startsWith("type ") || lower.startsWith("likho ") || lower.startsWith("write ")) {
            val content = input.substringAfter(" ").trim()
            val ok = PKAssistAccessibilityService.instance?.typeTextInFocusedField(content) ?: false
            return if (ok) "Maine '$content' type kar diya hai."
            else "Typing ke liye kripya upar 'Enable' button daba kar PKassist Accessibility Service on karein."
        }

        // 2. Click on screen element: "click Send" / "tap OK"
        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("dabao ")) {
            val target = input.substringAfter(" ").trim()
            val ok = PKAssistAccessibilityService.instance?.clickElementByText(target) ?: false
            return if (ok) "'$target' par click kar diya."
            else "Screen par '$target' nahi mila ya Accessibility Service off hai."
        }

        // 3. Scroll & Navigation commands
        if (lower.contains("scroll down") || lower.contains("neeche scroll")) {
            PKAssistAccessibilityService.instance?.scrollScreen(true)
            return "Screen neeche scroll kar di."
        }
        if (lower.contains("scroll up") || lower.contains("upar scroll")) {
            PKAssistAccessibilityService.instance?.scrollScreen(false)
            return "Screen upar scroll kar di."
        }
        if (lower == "go home" || lower == "home chalo") {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("HOME")
            return "Home screen par aa gaye."
        }
        if (lower == "go back" || lower == "peeche chalo" || lower == "back") {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("BACK")
            return "Back kar diya."
        }

        // 4. Flashlight control
        if (lower.contains("flashlight on") || lower.contains("torch on") || lower.contains("torch jala")) {
            return toggleFlashlight(true)
        }
        if (lower.contains("flashlight off") || lower.contains("torch off") || lower.contains("torch band")) {
            return toggleFlashlight(false)
        }

        // 5. Phone Calls: "call Rahul", "Rahul ko call lagao", "call 9876543210"
        if (lower.startsWith("call ") || lower.contains("ko call lagao") || lower.contains("par call")) {
            val target = lower
                .removePrefix("call ")
                .replace("ko call lagao", "")
                .replace("par call lagao", "")
                .replace("par call karo", "")
                .trim()
            return makePhoneCall(target)
        }

        // 6. SMS / Message: "send sms to 9876543210 hello" or "message Rahul hello"
        if (lower.startsWith("send sms to ") || lower.startsWith("send message to ") || lower.startsWith("message ")) {
            val cleaned = input.replace(Regex("^(send sms to|send message to|message)\\s+", RegexOption.IGNORE_CASE), "")
            val parts = cleaned.split(" ", limit = 2)
            if (parts.size == 2) {
                return sendSmsMessage(parts[0], parts[1])
            }
        }

        // 7. Open Apps: "open whatsapp", "whatsapp kholo", "launch youtube"
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.endsWith(" kholo")) {
            val appName = lower
                .removePrefix("open ")
                .removePrefix("launch ")
                .removeSuffix(" kholo")
                .trim()
            return openAppByName(appName)
        }

        // 8. Battery & Time & Wi-Fi Settings
        if (lower.contains("battery")) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return "Aapke phone ki battery abhi $level% hai."
        }
        if (lower.contains("time") || lower.contains("samay") || lower.contains("kitne baje")) {
            val now = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            return "Abhi samay $now hua hai."
        }
        if (lower.contains("wifi settings") || lower.contains("wi-fi")) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return "Wi-Fi settings khol di hain."
        }

        return null
    }

    /**
     * Calls low-demand free-tier Gemini models (gemini-2.5-flash-lite -> gemini-2.0-flash-lite -> gemini-1.5-flash).
     * Requests a structured JSON action so Gemini can both answer questions and control the phone.
     */
    private fun callGeminiWithFallback(userCommand: String, apiKey: String): String? {
        val models = listOf(
            BuildConfig.PRIMARY_GEMINI_MODEL,   // gemini-2.5-flash-lite (lowest demand, highest free quota)
            BuildConfig.FALLBACK_GEMINI_MODEL,  // gemini-2.0-flash-lite
            "gemini-1.5-flash"
        )

        val systemInstruction = """
            You are PKassist, a smart Android AI phone assistant powered by RV GROUP.
            Respond ONLY with a valid JSON object matching this exact schema:
            {
              "action": "NONE" | "CALL" | "SMS" | "OPEN_APP" | "TYPE_TEXT" | "CLICK_TEXT" | "FLASHLIGHT_ON" | "FLASHLIGHT_OFF" | "HOME" | "BACK",
              "target": "phone number, contact name, app name, or button text if applicable, else empty",
              "content": "message body or text to type if applicable, else empty",
              "reply": "helpful, friendly Hinglish/Hindi/English spoken response to the user"
            }
        """.trimIndent()

        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(
                            JSONObject().put("text", "$systemInstruction\n\nUser command: $userCommand")
                        ))
                    ))
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                        put("temperature", 0.2)
                    })
                }

                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val bodyStr = response.body?.string() ?: return@use
                    val rootJson = JSONObject(bodyStr)
                    val textResponse = rootJson
                        .getJSONArray("candidates")
                        .getJSONObject(0)
                        .getJSONObject("content")
                        .getJSONArray("parts")
                        .getJSONObject(0)
                        .getString("text")

                    val actionJson = JSONObject(textResponse)
                    val action = actionJson.optString("action", "NONE")
                    val target = actionJson.optString("target", "")
                    val content = actionJson.optString("content", "")
                    val reply = actionJson.optString("reply", "Done!")

                    executeStructuredAction(action, target, content)
                    return reply
                }
            } catch (_: Exception) {
                // Automatically try next lightweight fallback model
            }
        }
        return null
    }

    private fun executeStructuredAction(action: String, target: String, content: String) {
        when (action.uppercase()) {
            "CALL" -> makePhoneCall(target)
            "SMS" -> sendSmsMessage(target, content)
            "OPEN_APP" -> openAppByName(target)
            "TYPE_TEXT" -> PKAssistAccessibilityService.instance?.typeTextInFocusedField(content.ifEmpty { target })
            "CLICK_TEXT" -> PKAssistAccessibilityService.instance?.clickElementByText(target)
            "FLASHLIGHT_ON" -> toggleFlashlight(true)
            "FLASHLIGHT_OFF" -> toggleFlashlight(false)
            "HOME" -> PKAssistAccessibilityService.instance?.triggerGlobalAction("HOME")
            "BACK" -> PKAssistAccessibilityService.instance?.triggerGlobalAction("BACK")
        }
    }

    private fun makePhoneCall(target: String): String {
        val number = if (target.any { it.isDigit() }) {
            target.filter { it.isDigit() || it == '+' }
        } else {
            lookupContactNumber(target)
        }

        if (number.isNullOrBlank()) {
            return "Contact '$target' ka number nahi mila."
        }

        return try {
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
            "$target ($number) par call lagaya ja raha hai."
        } catch (_: SecurityException) {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(dialIntent)
            "$target ($number) dialer me khol diya hai."
        }
    }

    private fun sendSmsMessage(target: String, message: String): String {
        val number = if (target.any { it.isDigit() }) {
            target.filter { it.isDigit() || it == '+' }
        } else {
            lookupContactNumber(target)
        } ?: return "Contact '$target' ka number nahi mila."

        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager.sendTextMessage(number, null, message, null, null)
            "$target ko SMS bhej diya gaya: '$message'"
        } catch (_: Exception) {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            "SMS app me message tayyar kar diya hai."
        }
    }

    private fun openAppByName(appName: String): String {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val matched = packages.firstOrNull { appInfo ->
            val label = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.getDefault())
            label.contains(appName.lowercase(Locale.getDefault()))
        }

        if (matched != null) {
            val launchIntent = pm.getLaunchIntentForPackage(matched.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                val label = pm.getApplicationLabel(matched)
                return "$label open kar diya gaya hai."
            }
        }
        return "App '$appName' phone me installed nahi mili."
    }

    private fun toggleFlashlight(turnOn: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return "Camera flash nahi mila."
            cameraManager.setTorchMode(cameraId, turnOn)
            if (turnOn) "Flashlight on kar di gayi hai." else "Flashlight off kar di gayi hai."
        } catch (_: Exception) {
            "Flashlight control karne me error aaya."
        }
    }

    private fun lookupContactNumber(nameQuery: String): String? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$nameQuery%")
            val cursor: Cursor? = context.contentResolver.query(uri, projection, selection, args, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIdx >= 0) return it.getString(numberIdx)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
