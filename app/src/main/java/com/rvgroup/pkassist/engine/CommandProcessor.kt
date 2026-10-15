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
import android.provider.MediaStore
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

    suspend fun process(
        rawCommand: String,
        apiKey: String,
        savedPin: String = ""
    ): AssistantResult = withContext(Dispatchers.IO) {
        val text = rawCommand.trim()
        if (text.isEmpty()) {
            return@withContext AssistantResult("Kripya koi command bolein ya type karein.", "Offline NLU")
        }

        // 0. Check for Voice PIN / Password Device Unlock first!
        val unlockReply = tryVoiceUnlockCommand(text, savedPin)
        if (unlockReply != null) {
            return@withContext AssistantResult(unlockReply, "Voice Unlock")
        }

        // 1. Check for direct WhatsApp message automation
        val waDirect = tryWhatsAppDirectCommand(text)
        if (waDirect != null) {
            return@withContext AssistantResult(waDirect, "WhatsApp Auto")
        }

        // 2. Check for multi-step chained commands ("open WhatsApp and search Rajesh")
        val steps = splitMultiStepCommands(text)
        if (steps.size > 1) {
            val replies = mutableListOf<String>()
            for ((index, step) in steps.withIndex()) {
                val stepReply = tryOfflineCommand(step.trim())
                if (stepReply != null) {
                    replies.add(stepReply)
                }
                if (index < steps.size - 1) {
                    delay(1400)
                }
            }
            if (replies.isNotEmpty()) {
                return@withContext AssistantResult(replies.joinToString(" → "), "Multi-Step Engine")
            }
        }

        // 3. Single-step Extreme Natural Hinglish / Offline NLU Engine
        val offlineMatch = tryOfflineCommand(text)
        if (offlineMatch != null) {
            return@withContext AssistantResult(offlineMatch, "Smart NLU")
        }

        // 4. Online Gemini / Keyless AI with Live Screen Context
        if (isOnline()) {
            val screenSummary = PKAssistAccessibilityService.instance?.getScreenTextSummary() ?: ""
            if (apiKey.startsWith("AIza")) {
                val geminiReply = callGeminiWithScreenContext(text, screenSummary, apiKey, savedPin)
                if (geminiReply != null) {
                    return@withContext AssistantResult(geminiReply, "Gemini Glass AI")
                }
            }
            val freeAiReply = callFreeOnlineAssistant(text, screenSummary)
            if (freeAiReply != null) {
                return@withContext AssistantResult(freeAiReply, "Online AI")
            }
        }

        return@withContext AssistantResult(
            "Phone abhi offline hai. Offline mode me aap Voice Unlock, Call, WhatsApp message, Alarm, App Open, Screen Read, Typing, Click, Torch, Volume, ya Battery bol sakte hain.",
            "Offline NLU"
        )
    }

    /**
     * Handles Voice Unlock:
     * e.g. "unlock phone", "unlock 1234", "mera password 2580 hai", "lock kholo"
     */
    private suspend fun tryVoiceUnlockCommand(input: String, savedPin: String): String? {
        val lower = input.lowercase(Locale.ROOT).trim()
        val isUnlockIntent = lower.contains("unlock") ||
            lower.contains("lock kholo") ||
            lower.contains("phone kholo") ||
            lower.startsWith("password ") ||
            lower.startsWith("pin ")

        if (!isUnlockIntent) return null

        val acc = PKAssistAccessibilityService.instance
            ?: return "Voice Unlock ke liye pehle PKassist Screen Engine (Accessibility) on karein."

        // Extract spoken digits/password from command, or fall back to user's saved PIN
        val spokenDigits = input.filter { it.isDigit() }
        val pinToUse = when {
            spokenDigits.isNotEmpty() -> spokenDigits
            savedPin.isNotEmpty() -> savedPin
            else -> ""
        }

        if (pinToUse.isEmpty()) {
            return "Kripya app ke top bar me '🔐 PIN' button daba kar apna Lock-Screen PIN save karein, ya 'Unlock 1234' bolein."
        }

        val ok = acc.unlockDeviceWithPin(pinToUse)
        return if (ok) {
            "Phone wake karke PIN enter kar diya gaya hai — Device Unlocked!"
        } else {
            "Screen wake aur swipe kar diya gaya hai."
        }
    }

    private fun splitMultiStepCommands(input: String): List<String> {
        return input.split(Regex("\\s+(and then|and|aur fir|aur phir|aur|fir|phir|uske baad)\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private suspend fun tryWhatsAppDirectCommand(input: String): String? {
        val lower = input.lowercase(Locale.ROOT).trim()
        if (!lower.contains("whatsapp")) return null

        val hinglishRegex = Regex("^(.+?)\\s+ko\\s+whatsapp\\s+(?:par|per|pe|me|mein)?\\s*(?:message\\s+karo|msg\\s+karo|bhejo|bol do|likho)?\\s*(.*)$", RegexOption.IGNORE_CASE)
        val matchA = hinglishRegex.find(input)
        if (matchA != null) {
            val targetName = matchA.groupValues[1]
                .replace(Regex("^(whatsapp khol ke|whatsapp par)\\s+", RegexOption.IGNORE_CASE), "")
                .trim()
            val msgRaw = matchA.groupValues[2]
                .replace(Regex("^(ki|ke)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*(likhkar|likh kar|bolkar|bhej do|kar do)$", RegexOption.IGNORE_CASE), "")
                .trim()
            return sendWhatsAppAutomated(targetName, msgRaw.ifEmpty { "Hi" })
        }

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
            openAppByName("whatsapp")
            delay(1500)
            PKAssistAccessibilityService.instance?.searchInCurrentApp(target)
            delay(800)
            if (message.isNotEmpty()) {
                PKAssistAccessibilityService.instance?.typeTextInFocusedField(message)
                delay(400)
                PKAssistAccessibilityService.instance?.clickElementByText("Send")
            }
            return "WhatsApp khol kar '$target' search karke '$message' type kar diya hai."
        }
    }

    private suspend fun tryOfflineCommand(input: String): String? {
        val lower = input.lowercase(Locale.ROOT).trim()

        // 1. Screen Reader ("screen padho", "read screen", "screen par kya likha hai")
        if (lower.contains("screen padho") || lower.contains("read screen") || lower.contains("screen par kya")) {
            val summary = PKAssistAccessibilityService.instance?.getScreenTextSummary()
            return if (!summary.isNullOrBlank()) {
                "Screen par yeh likha hai: $summary"
            } else {
                "Screen padhne ke liye Accessibility Engine on karein."
            }
        }

        // 2. Lock Screen ("lock phone", "screen lock karo", "phone band karo")
        if (lower == "lock phone" || lower.contains("screen lock") || lower == "phone lock karo") {
            val ok = PKAssistAccessibilityService.instance?.triggerGlobalAction("LOCK_SCREEN") ?: false
            return if (ok) "Phone lock kar diya gaya hai." else "Screen lock ke liye Accessibility on karein."
        }

        // 3. Alarm & Timer ("alarm lagao 2:40", "subah 6 baje ka alarm", "5 minute ka timer")
        if (lower.contains("alarm")) {
            val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje)?")
            val match = timeRegex.find(lower)
            if (match != null) {
                var hour = match.groupValues[1].toIntOrNull() ?: 7
                val minute = match.groupValues[2].toIntOrNull() ?: 0
                val suffix = match.groupValues[3]
                if ((suffix == "pm" || lower.contains("shaam") || lower.contains("raat")) && hour < 12) hour += 12
                if (suffix == "am" && hour == 12) hour = 0

                return try {
                    val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(AlarmClock.EXTRA_MESSAGE, "PKassist Ultra Alarm")
                        putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(alarmIntent)
                    String.format(Locale.ROOT, "%02d:%02d ka alarm set kar diya gaya hai.", hour, minute)
                } catch (_: Exception) {
                    "Clock app me alarm set karne me dikkat aayi."
                }
            }
        }

        if (lower.contains("timer")) {
            val num = Regex("(\\d+)").find(lower)?.groupValues?.get(1)?.toIntOrNull() ?: 5
            val seconds = if (lower.contains("second")) num else num * 60
            return try {
                val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(timerIntent)
                "$num minute ka timer chalu kar diya hai."
            } catch (_: Exception) {
                "Timer chalu karne me dikkat aayi."
            }
        }

        // 4. Button / Element Click & Tap
        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ") ||
            lower.startsWith("dabao ") ||
            Regex(".*\\b(button|batan|icon)\\s+(par|per|pe)\\s+(tap|click|type|press|dabao).*").matches(lower) ||
            Regex(".*\\b(par|per|pe)\\s+(tap|click)\\s+(karo|kar|do)$").matches(lower)
        ) {
            val ok = PKAssistAccessibilityService.instance?.clickElementByText(lower) ?: false
            return if (ok) "Screen par button click kar diya gaya hai."
            else "Woh button screen par nahi mila."
        }

        // 5. Typing automation
        if (lower.startsWith("type ") || lower.startsWith("likho ") || lower.startsWith("write ") ||
            lower.endsWith(" type karo") || lower.endsWith(" likh do")
        ) {
            val content = input
                .replace(Regex("^(type|likho|write)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(type karo|likh do|likho)$", RegexOption.IGNORE_CASE), "")
                .trim()
            val ok = PKAssistAccessibilityService.instance?.typeTextInFocusedField(content) ?: false
            return if (ok) "Maine '$content' type kar diya hai."
            else "Screen par koi input box active nahi mila."
        }

        // 6. Camera / Selfie
        if (lower.contains("selfie lo") || lower.contains("photo khicho") || lower.contains("take photo")) {
            return try {
                val camIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(camIntent)
                "Camera khol diya gaya hai."
            } catch (_: Exception) {
                openAppByName("camera")
            }
        }

        // 7. YouTube & Google Search
        if (lower.contains("youtube par ") || lower.contains("play on youtube ") || lower.startsWith("youtube ")) {
            val query = lower
                .replace("youtube par ", "")
                .replace("play on youtube ", "")
                .removePrefix("youtube ")
                .replace(Regex("\\s*(chala do|chalao|search karo|play karo|lagao)$"), "")
                .trim()
            if (query.isEmpty() || query == "kholo" || query == "open") {
                return openAppByName("youtube")
            }
            val ytIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(ytIntent)
                "YouTube par '$query' chala diya hai."
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
            return if (ok) "'$query' search kar diya hai."
            else "Search box nahi mila."
        }

        // 8. Scroll & Navigation
        if (lower.contains("scroll down") || lower.contains("neeche") || lower.contains("niche")) {
            PKAssistAccessibilityService.instance?.scrollScreen(true)
            return "Screen neeche scroll kar di."
        }
        if (lower.contains("scroll up") || lower.contains("upar scroll") || lower.contains("upar karo")) {
            PKAssistAccessibilityService.instance?.scrollScreen(false)
            return "Screen upar scroll kar di."
        }
        if (lower == "go home" || lower.contains("home chalo") || lower == "home screen") {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("HOME")
            return "Home screen par aa gaye."
        }
        if (lower == "go back" || lower.contains("peeche chalo") || lower == "back" || lower == "back karo") {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("BACK")
            return "Back kar diya."
        }
        if (lower.contains("recent apps") || lower.contains("recents")) {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("RECENTS")
            return "Recent apps khol diye."
        }
        if (lower.contains("screenshot")) {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("SCREENSHOT")
            return "Screenshot le liya gaya hai."
        }
        if (lower.contains("notification")) {
            PKAssistAccessibilityService.instance?.triggerGlobalAction("NOTIFICATIONS")
            return "Notifications khol diye hain."
        }

        // 9. Volume Control (Full, Mute, Up, Down)
        if (lower.contains("volume") || lower.contains("awaz") || lower.contains("aawaz") || lower.contains("mute")) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return when {
                lower.contains("full") || lower.contains("max") || lower.contains("100") -> {
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                    "Volume full (100%) kar diya hai."
                }
                lower.contains("mute") || lower.contains("band") || lower.contains("zero") -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    "Awaz mute kar di gayi hai."
                }
                lower.contains("kam") || lower.contains("down") || lower.contains("dhire") -> {
                    am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "Volume kam kar diya hai."
                }
                else -> {
                    am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "Volume badha diya hai."
                }
            }
        }

        // 10. Flashlight / Torch (Natural Hinglish phrases)
        if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("flash light") || lower.contains("batti")) {
            val turnOff = lower.contains("off") || lower.contains("band") || lower.contains("bujha")
            return toggleFlashlight(!turnOff)
        }

        // 11. Phone Calls
        if (lower.startsWith("call ") || lower.contains("ko call") || lower.contains("par call") || lower.contains("phone lagao") || lower.contains("number lagao")) {
            val target = lower
                .removePrefix("call ")
                .replace(Regex("\\s*(ko|par|pe)?\\s*(call|phone|number)\\s*(lagao|karo|laga do|mila do)?"), "")
                .trim()
            return makePhoneCall(target)
        }

        // 12. SMS Messages
        if (lower.startsWith("send sms to ") || lower.startsWith("send message to ") || lower.startsWith("sms ")) {
            val cleaned = input.replace(Regex("^(send sms to|send message to|sms)\\s+", RegexOption.IGNORE_CASE), "")
            val parts = cleaned.split(" ", limit = 2)
            if (parts.size == 2) {
                return sendSmsMessage(parts[0], parts[1])
            }
        }

        // 13. Open Any Installed App
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.endsWith(" kholo") ||
            lower.endsWith(" open karo") || lower.endsWith(" chalu karo") || lower.endsWith(" chala do")
        ) {
            val appName = lower
                .removePrefix("open ")
                .removePrefix("launch ")
                .removeSuffix(" open karo")
                .removeSuffix(" chalu karo")
                .removeSuffix(" chala do")
                .removeSuffix(" kholo")
                .trim()
            return openAppByName(appName)
        }

        // 14. Battery, Time, Date, Wi-Fi, Bluetooth, Hotspot, Settings
        if (lower.contains("battery") || lower.contains("charge kitna")) {
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
        if (lower.contains("hotspot")) {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Hotspot aur network settings khol di hain."
        }

        return null
    }

    private suspend fun callGeminiWithScreenContext(
        userCommand: String,
        screenSummary: String,
        apiKey: String,
        savedPin: String
    ): String? {
        val models = listOf(
            BuildConfig.PRIMARY_GEMINI_MODEL,
            BuildConfig.FALLBACK_GEMINI_MODEL,
            "gemini-2.0-flash",
            "gemini-1.5-flash"
        )

        val systemInstruction = """
            You are PKassist Ultra (Gemini Glass Edition), an advanced Android AI Assistant powered by RV GROUP.
            Current visible screen text: [$screenSummary]
            Respond ONLY with valid JSON:
            {
              "action": "NONE" | "UNLOCK" | "CALL" | "WHATSAPP" | "OPEN_APP" | "TYPE_TEXT" | "CLICK_TEXT" | "ALARM" | "FLASHLIGHT_ON" | "FLASHLIGHT_OFF" | "HOME" | "BACK" | "SCROLL_DOWN" | "SCROLL_UP",
              "target": "contact/phone/app/button/PIN/time (HH:MM) if applicable",
              "content": "message or text to type if applicable",
              "reply": "natural, smart Hinglish/Hindi/English spoken reply"
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
                    executeStructuredAction(action, target, content, savedPin)
                    return reply
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun callFreeOnlineAssistant(userCommand: String, screenSummary: String): String? {
        return try {
            val ctx = if (screenSummary.isNotBlank()) " (Screen context: ${screenSummary.take(150)})" else ""
            val prompt = "You are PKassist Ultra by RV GROUP. Answer concisely in Hinglish in 1-2 sentences: $userCommand$ctx"
            val url = "https://text.pollinations.ai/${URLEncoder.encode(prompt, "UTF-8")}"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.trim()?.takeIf { it.isNotEmpty() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun executeStructuredAction(action: String, target: String, content: String, savedPin: String) {
        when (action.uppercase(Locale.ROOT)) {
            "UNLOCK" -> PKAssistAccessibilityService.instance?.unlockDeviceWithPin(target.ifEmpty { savedPin })
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
            "SCROLL_DOWN" -> PKAssistAccessibilityService.instance?.scrollScreen(true)
            "SCROLL_UP" -> PKAssistAccessibilityService.instance?.scrollScreen(false)
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
        val knownPackages = mapOf(
            "whatsapp" to "com.whatsapp",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "instagram" to "com.instagram.android",
            "insta" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "fb" to "com.facebook.katana",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "camera" to "com.android.camera",
            "calculator" to "com.google.android.calculator",
            "settings" to "com.android.settings"
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
