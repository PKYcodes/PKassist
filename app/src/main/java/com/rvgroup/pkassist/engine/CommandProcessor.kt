package com.rvgroup.pkassist.engine

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import com.rvgroup.pkassist.BuildConfig
import com.rvgroup.pkassist.service.PKAssistAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AssistantResult(
    val spokenReply: String,
    val engineTag: String
)

class CommandProcessor(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun process(rawCommand: String, apiKey: String): AssistantResult = withContext(Dispatchers.IO) {
        val text = rawCommand.trim()
        if (text.isEmpty()) {
            return@withContext AssistantResult("Kripya koi command bolein ya type karein.", "Offline NLU")
        }

        // 1. Check for direct WhatsApp message pattern first (before splitting by "and")
        val waDirect = tryWhatsAppDirectCommand(text)
        if (waDirect != null) {
            return@withContext AssistantResult(waDirect, "Smart Action")
        }

        // 2. Check for multi-step chained commands: e.g. "open WhatsApp and search Rajesh"
        val steps = splitMultiStepCommands(text)
        if (steps.size > 1) {
            val replies = mutableListOf<String>()
            for ((index, step) in steps.withIndex()) {
                val stepReply = tryOfflineCommand(step.trim())
                if (stepReply != null) {
                    replies.add(stepReply)
                }
                if (index < steps.size - 1) {
                    // Wait for app launch or UI transition before executing the next step
                    delay(1400)
                }
            }
            if (replies.isNotEmpty()) {
                return@withContext AssistantResult(replies.joinToString(" → "), "Multi-Step Engine")
            }
        }

        // 3. Single-step Smart Offline/Hinglish NLU Engine
        val offlineMatch = tryOfflineCommand(text)
        if (offlineMatch != null) {
            return@withContext AssistantResult(offlineMatch, "Offline NLU")
        }

        // 4. Online AI Execution (Gemini API if valid AIza* key, plus keyless free AI fallback)
        if (isOnline()) {
            if (apiKey.startsWith("AIza")) {
                val geminiReply = callGeminiWithFallback(text, apiKey)
                if (geminiReply != null) {
                    return@withContext AssistantResult(geminiReply, "Gemini Lite")
                }
            }
            // Keyless free AI fallback so online questions work even if user hasn't set an AIzaSy key!
            val freeAiReply = callFreeOnlineAssistant(text)
            if (freeAiReply != null) {
                val note = if (apiKey.isNotBlank() && !apiKey.startsWith("AIza")) {
                    " (Note: Google AI Studio key 'AIzaSy...' se shuru hoti hai)"
                } else ""
                return@withContext AssistantResult(freeAiReply + note, "Online AI")
            }
        }

        return@withContext AssistantResult(
            "Command '$text' ke liye phone abhi offline hai. Offline mode me aap Call, WhatsApp message, Alarm, App Open, Search, Typing, Click, Flashlight, Volume, ya Battery bol sakte hain.",
            "Offline NLU"
        )
    }

    /**
     * Splits compound commands like "open WhatsApp and search Rajesh" or "WhatsApp kholo aur Rajesh search karo"
     */
    private fun splitMultiStepCommands(input: String): List<String> {
        return input.split(Regex("\\s+(and then|and|aur fir|aur phir|aur|fir|phir|uske baad)\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Handles Hinglish/English WhatsApp messaging commands:
     * - "Rajesh ko WhatsApp per message karo hay likhkar"
     * - "whatsapp message to Rajesh hello"
     * - "send whatsapp to 9876543210 namaste"
     */
    private suspend fun tryWhatsAppDirectCommand(input: String): String? {
        val lower = input.lowercase(Locale.ROOT).trim()
        if (!lower.contains("whatsapp")) return null

        // Pattern A: "<name> ko whatsapp par/per message karo <msg> likhkar" or "<name> ko whatsapp par <msg> bhejo"
        val hinglishRegex = Regex("^(.+?)\\s+ko\\s+whatsapp\\s+(?:par|per|pe|me|mein)?\\s*(?:message\\s+karo|msg\\s+karo|bhejo|likho)?\\s*(.*)$", RegexOption.IGNORE_CASE)
        val matchA = hinglishRegex.find(input)
        if (matchA != null) {
            val targetName = matchA.groupValues[1].trim()
            val msgRaw = matchA.groupValues[2]
                .replace(Regex("\\s*(likhkar|likh kar|bolkar|bhej do|kar do)$", RegexOption.IGNORE_CASE), "")
                .trim()
            return sendWhatsAppAutomated(targetName, msgRaw.ifEmpty { "Hi" })
        }

        // Pattern B: "send whatsapp to <name> <msg>" or "whatsapp <name> <msg>"
        val engRegex = Regex("^(?:send\\s+)?whatsapp\\s+(?:message\\s+)?(?:to\\s+)?([a-zA-Z0-9+]+)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val matchB = engRegex.find(input)
        if (matchB != null && !lower.startsWith("open whatsapp")) {
            val targetName = matchB.groupValues[1].trim()
            val msgRaw = matchB.groupValues[2].trim()
            return sendWhatsAppAutomated(targetName, msgRaw)
        }

        return null
    }

    private suspend fun sendWhatsAppAutomated(target: String, message: String): String {
        val number = if (target.any { it.isDigit() }) {
            target.filter { it.isDigit() || it == '+' }
        } else {
            lookupContactNumber(target)
        }

        if (!number.isNullOrBlank()) {
            val formattedPhone = if (number.length == 10 && !number.startsWith("+")) "91$number" else number.removePrefix("+")
            return try {
                val url = "https://api.whatsapp.com/send?phone=$formattedPhone&text=${URLEncoder.encode(message, "UTF-8")}"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                delay(1800)
                PKAssistAccessibilityService.instance?.clickElementByText("Send")
                "WhatsApp par $target ko '$message' bhej diya gaya hai."
            } catch (_: Exception) {
                "WhatsApp open karne me dikkat aayi."
            }
        } else {
            // Contact number not found or user only has name inside WhatsApp -> Open WhatsApp, search name, type msg
            openAppByName("whatsapp")
            delay(1500)
            PKAssistAccessibilityService.instance?.searchInCurrentApp(target)
            return "WhatsApp khol kar '$target' search kar diya hai."
        }
    }

    private suspend fun tryOfflineCommand(input: String): String? {
        val lower = input.lowercase(Locale.ROOT).trim()

        // 1. Alarm setting: "alarm lagao 2:40", "set alarm for 6:30 am", "5 baje ka alarm lagao"
        if (lower.contains("alarm")) {
            val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
            val match = timeRegex.find(lower)
            if (match != null) {
                var hour = match.groupValues[1].toIntOrNull() ?: 7
                val minute = match.groupValues[2].toIntOrNull() ?: 0
                val ampm = match.groupValues[3]
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0

                return try {
                    val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, "PKassist Alarm")
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(alarmIntent)
                    String.format(Locale.ROOT, "%02d:%02d ka alarm set kar diya gaya hai.", hour, minute)
                } catch (_: Exception) {
                    "Phone ke Clock app me alarm set karne ki permission nahi mili."
                }
            }
        }

        // 2. Button / Element Click & Tap (Handles both prefix "tap on send button" and suffix "send button per tap/type karo")
        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ") ||
            lower.endsWith(" पर टैप करो") || lower.endsWith(" पर क्लिक करो") ||
            Regex(".*\\b(button|batan|icon)\\s+(par|per|pe)\\s+(tap|click|type|press).*").matches(lower) ||
            Regex(".*\\b(par|per|pe)\\s+(tap|click)\\s+karo$").matches(lower)
        ) {
            val ok = PKAssistAccessibilityService.instance?.clickElementByText(lower) ?: false
            return if (ok) {
                "Screen button par tap kar diya gaya hai."
            } else {
                "Is screen par woh button nahi mila. Agar dusre app par click karwana hai to Floating Popup (💬) use karein."
            }
        }

        // 3. Typing automation: "type hello", "likho namaste", "hello type karo"
        if (lower.startsWith("type ") || lower.startsWith("likho ") || lower.startsWith("write ") || lower.endsWith(" type karo") || lower.endsWith(" likho")) {
            val content = input
                .replace(Regex("^(type|likho|write)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(type karo|likho)$", RegexOption.IGNORE_CASE), "")
                .trim()
            val ok = PKAssistAccessibilityService.instance?.typeTextInFocusedField(content) ?: false
            return if (ok) "Maine '$content' type kar diya hai."
            else "Koi text box active nahi mila ya Accessibility Control off hai."
        }

        // 4. Search inside current app or YouTube/Google: "search Rajesh", "Rajesh search karo", "youtube par X chala do"
        if (lower.contains("youtube par ") || lower.contains("play on youtube ") || lower.startsWith("youtube search ")) {
            val query = lower
                .replace("youtube par ", "")
                .replace("play on youtube ", "")
                .replace("youtube search ", "")
                .replace(Regex("\\s*(chala do|chalao|search karo|play karo)$"), "")
                .trim()
            val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(ytIntent)
                "YouTube par '$query' search kar diya hai."
            } catch (_: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${URLEncoder.encode(query, "UTF-8")}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                "YouTube par '$query' khol diya hai."
            }
        }

        if (lower.startsWith("google par ") || lower.startsWith("search on google ")) {
            val query = lower.removePrefix("google par ").removePrefix("search on google ").replace("search karo", "").trim()
            val gIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(gIntent)
            return "Google par '$query' search kar diya hai."
        }

        if (lower.startsWith("search ") || lower.endsWith(" search karo") || lower.endsWith(" khojo")) {
            val query = input
                .replace(Regex("^search\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(search karo|khojo)$", RegexOption.IGNORE_CASE), "")
                .trim()
            val ok = PKAssistAccessibilityService.instance?.searchInCurrentApp(query) ?: false
            return if (ok) "'$query' search kar diya gaya hai."
            else "Current screen par search box nahi mila."
        }

        // 5. Scroll & Global Screen Navigation
        if (lower.contains("scroll down") || lower.contains("neeche scroll") || lower.contains("niche karo")) {
            PKAssistAccessibilityService.instance?.scrollScreen(true)
            return "Screen neeche scroll kar di."
        }
        if (lower.contains("scroll up") || lower.contains("upar scroll") || lower.contains("upar karo")) {
            PKAssistAccessibilityService.instance?.scrollScreen(false)
            return "Screen upar scroll kar di."
        }
        if (lower == "go home" || lower == "home chalo" || lower == "home screen") {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("HOME")
            return "Home screen par aa gaye."
        }
        if (lower == "go back" || lower == "peeche chalo" || lower == "back" || lower == "back karo") {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("BACK")
            return "Back kar diya."
        }
        if (lower.contains("screenshot")) {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("SCREENSHOT")
            return "Screenshot le liya gaya hai."
        }
        if (lower.contains("notification")) {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("NOTIFICATIONS")
            return "Notifications panel khol diya hai."
        }

        // 6. Volume Control
        if (lower.contains("volume badhao") || lower.contains("volume up") || lower.contains("awaz badhao")) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            return "Volume badha diya hai."
        }
        if (lower.contains("volume kam") || lower.contains("volume down") || lower.contains("awaz kam")) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            return "Volume kam kar diya hai."
        }

        // 7. Flashlight / Torch
        if (lower.contains("flashlight on") || lower.contains("torch on") || lower.contains("torch jala") || lower.contains("light jala")) {
            return toggleFlashlight(true)
        }
        if (lower.contains("flashlight off") || lower.contains("torch off") || lower.contains("torch band") || lower.contains("light band")) {
            return toggleFlashlight(false)
        }

        // 8. Phone Calls
        if (lower.startsWith("call ") || lower.contains("ko call") || lower.contains("par call") || lower.contains("phone lagao")) {
            val target = lower
                .removePrefix("call ")
                .replace(Regex("\\s*(ko|par|pe)?\\s*(call|phone)\\s*(lagao|karo|laga do)?"), "")
                .trim()
            return makePhoneCall(target)
        }

        // 9. SMS Messages (Uses Intent + Accessibility Auto-Send so Play Protect never blocks the APK)
        if (lower.startsWith("send sms to ") || lower.startsWith("send message to ") || lower.startsWith("sms ")) {
            val cleaned = input.replace(Regex("^(send sms to|send message to|sms)\\s+", RegexOption.IGNORE_CASE), "")
            val parts = cleaned.split(" ", limit = 2)
            if (parts.size == 2) {
                return sendSmsMessage(parts[0], parts[1])
            }
        }

        // 10. Open Any Installed App ("open whatsapp", "whatsapp kholo", "chrome open karo")
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.endsWith(" kholo") || lower.endsWith(" open karo") || lower.endsWith(" chalu karo")) {
            val appName = lower
                .removePrefix("open ")
                .removePrefix("launch ")
                .removeSuffix(" open karo")
                .removeSuffix(" chalu karo")
                .removeSuffix(" kholo")
                .trim()
            return openAppByName(appName)
        }

        // 11. Battery, Time, Date, Wi-Fi, Bluetooth
        if (lower.contains("battery")) {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return "Aapke phone ki battery abhi $level% hai."
        }
        if (lower.contains("time") || lower.contains("samay") || lower.contains("kitne baje")) {
            val now = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            return "Abhi samay $now hua hai."
        }
        if (lower.contains("date") || lower.contains("tarikh") || lower.contains("aaj kya din")) {
            val today = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
            return "Aaj $today hai."
        }
        if (lower.contains("wifi") || lower.contains("wi-fi")) {
            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Wi-Fi settings khol di hain."
        }
        if (lower.contains("bluetooth")) {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Bluetooth settings khol di hain."
        }

        return null
    }

    private suspend fun callGeminiWithFallback(userCommand: String, apiKey: String): String? {
        val models = listOf(
            BuildConfig.PRIMARY_GEMINI_MODEL,
            BuildConfig.FALLBACK_GEMINI_MODEL,
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        )

        val systemInstruction = """
            You are PKassist Pro, an Android AI Assistant powered by RV GROUP.
            Respond ONLY with valid JSON:
            {
              "action": "NONE" | "CALL" | "WHATSAPP" | "OPEN_APP" | "TYPE_TEXT" | "CLICK_TEXT" | "ALARM" | "FLASHLIGHT_ON" | "FLASHLIGHT_OFF" | "HOME" | "BACK",
              "target": "contact/phone/app/button/time (HH:MM) if applicable",
              "content": "message or text to type if applicable",
              "reply": "helpful Hinglish/Hindi/English spoken reply"
            }
        """.trimIndent()

        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(
                            JSONObject().put("text", "$systemInstruction\n\nUser: $userCommand")
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

                val parsedReply = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) null
                    else {
                        val bodyStr = response.body?.string() ?: ""
                        val rootJson = JSONObject(bodyStr)
                        val textResponse = rootJson
                            .getJSONArray("candidates")
                            .getJSONObject(0)
                            .getJSONObject("content")
                            .getJSONArray("parts")
                            .getJSONObject(0)
                            .getString("text")
                        JSONObject(textResponse)
                    }
                }

                if (parsedReply != null) {
                    val action = parsedReply.optString("action", "NONE")
                    val target = parsedReply.optString("target", "")
                    val content = parsedReply.optString("content", "")
                    val reply = parsedReply.optString("reply", "Ho gaya!")
                    executeStructuredAction(action, target, content)
                    return reply
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Free keyless online AI fallback so general questions work out-of-the-box even without an AIzaSy key.
     */
    private fun callFreeOnlineAssistant(userCommand: String): String? {
        return try {
            val prompt = "You are PKassist Pro, a helpful Hinglish AI phone assistant by RV GROUP. Answer concisely in 1-2 sentences: $userCommand"
            val url = "https://text.pollinations.ai/${URLEncoder.encode(prompt, "UTF-8")}"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.trim()?.takeIf { it.isNotEmpty() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun executeStructuredAction(action: String, target: String, content: String) {
        when (action.uppercase(Locale.ROOT)) {
            "CALL" -> makePhoneCall(target)
            "WHATSAPP" -> sendWhatsAppAutomated(target, content)
            "OPEN_APP" -> openAppByName(target)
            "TYPE_TEXT" -> PKAssistAccessibilityService.instance?.typeTextInFocusedField(content.ifEmpty { target })
            "CLICK_TEXT" -> PKAssistAccessibilityService.instance?.clickElementByText(target)
            "ALARM" -> tryOfflineCommand("alarm lagao $target")
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
            return "Contact '$target' ka number phone me nahi mila."
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

    private suspend fun sendSmsMessage(target: String, message: String): String {
        val number = if (target.any { it.isDigit() }) {
            target.filter { it.isDigit() || it == '+' }
        } else {
            lookupContactNumber(target)
        } ?: return "Contact '$target' ka number nahi mila."

        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(smsIntent)
        delay(1200)
        PKAssistAccessibilityService.instance?.clickElementByText("Send")
        return "$target ko SMS bhej diya gaya hai."
    }

    private fun openAppByName(appName: String): String {
        val cleaned = appName.lowercase(Locale.ROOT).trim()
        // Built-in package shortcuts for instant matching
        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera"
        )
        val pm = context.packageManager
        knownPackages[cleaned]?.let { pkg ->
            pm.getLaunchIntentForPackage(pkg)?.let { intent ->
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "$appName open kar diya gaya hai."
            }
        }

        val packages = pm.getInstalledApplications(0)
        val matched = packages.firstOrNull { appInfo ->
            val label = pm.getApplicationLabel(appInfo).toString().lowercase(Locale.ROOT)
            label == cleaned || label.contains(cleaned)
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
