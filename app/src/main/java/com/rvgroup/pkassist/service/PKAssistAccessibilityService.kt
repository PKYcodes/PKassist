package com.rvgroup.pkassist.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.Locale

class PKAssistAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PKAssistAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * Unlocks the phone when the user speaks their PIN/Password (even when screen is off or locked):
     * 1. Wakes up the screen using PowerManager WakeLock
     * 2. Swipes up on the lock screen to reveal the PIN pad / Password input
     * 3. Enters each digit of the PIN on the Keyguard keypad (or sets text in password field) and taps Enter/OK
     */
    suspend fun unlockDeviceWithPin(pinOrPassword: String): Boolean {
        val cleanPin = pinOrPassword.trim()
        if (cleanPin.isEmpty()) return false

        // Step 1: Wake up screen if off
        wakeUpScreen()
        delay(400)

        // Step 2: Swipe up from bottom of lock screen to reveal PIN/Password pad
        performSwipeGesture(scrollDown = true)
        delay(650)

        val root = rootInActiveWindow
        if (root != null) {
            // Check if there is an editable password input box on the lock screen
            val editable = findFirstEditableNode(root)
            if (editable != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanPin)
                }
                editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                delay(250)
                clickElementByText("OK") || clickElementByText("Enter") || clickElementByText("Done")
                return true
            }
        }

        // Step 3: Tap each digit on the lock screen PIN keypad
        var anyDigitClicked = false
        for (ch in cleanPin) {
            if (!ch.isWhitespace()) {
                val clicked = clickPinDigit(ch.toString())
                if (clicked) anyDigitClicked = true
                delay(180)
            }
        }

        // Step 4: Tap Enter / OK / Tick button on Keyguard
        delay(200)
        clickKeyguardEnter()
        return anyDigitClicked
    }

    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "pkassist:WakeUnlockLock"
            )
            wl.acquire(5000L)
        } catch (_: Exception) {
        }
    }

    private fun clickPinDigit(digit: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // Try standard Android SystemUI keyguard button IDs first (e.g. com.android.systemui:id/key1)
        val targets = listOf("id/key$digit", "id/digit_$digit", digit)
        val node = findPinNode(root, digit, targets)
        if (node != null) {
            return clickNodeOrParent(node)
        }
        return false
    }

    private fun findPinNode(node: AccessibilityNodeInfo?, digit: String, idPatterns: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

        if (text == digit || text.startsWith("$digit\n") || desc == digit ||
            idPatterns.any { viewId.endsWith(it) }
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findPinNode(node.getChild(i), digit, idPatterns)
            if (found != null) return found
        }
        return null
    }

    private fun clickKeyguardEnter(): Boolean {
        val root = rootInActiveWindow ?: return false
        val enterTargets = listOf("key_enter", "enter", "ok", "done", "confirm", "submit")
        val node = findMatchingNode(root, enterTargets)
        return if (node != null) clickNodeOrParent(node) else false
    }

    /**
     * Reads all visible text on the current screen so the AI knows what is on screen or can read it aloud.
     */
    fun getScreenTextSummary(): String {
        val root = rootInActiveWindow ?: return ""
        val snippets = mutableListOf<String>()
        collectVisibleTexts(root, snippets)
        return snippets.distinct().take(25).joinToString(" | ")
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null || out.size >= 35) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank() && t.length in 2..120) {
            out.add(t)
        }
        for (i in 0 until node.childCount) {
            collectVisibleTexts(node.getChild(i), out)
        }
    }

    fun typeTextInFocusedField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (targetNode == null || !targetNode.isEditable) {
            targetNode = findFirstEditableNode(root)
        }
        if (targetNode == null) return false

        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clickElementByText(rawLabel: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleaned = cleanButtonQuery(rawLabel)
        if (cleaned.isEmpty()) return false

        val synonyms = buildSynonyms(cleaned)
        val match = findMatchingNode(root, synonyms)
        if (match != null) {
            return clickNodeOrParent(match)
        }
        return false
    }

    suspend fun searchInCurrentApp(query: String): Boolean {
        clickElementByText("Search")
        delay(500)
        val typed = typeTextInFocusedField(query)
        delay(500)
        // Also try clicking the first matching search result
        clickElementByText(query)
        return typed
    }

    fun scrollScreen(forward: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root)
        if (scrollable != null) {
            val action = if (forward) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (scrollable.performAction(action)) return true
        }
        return performSwipeGesture(forward)
    }

    fun triggerGlobalAction(actionName: String): Boolean {
        return when (actionName.uppercase(Locale.ROOT)) {
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "LOCK_SCREEN" -> if (android.os.Build.VERSION.SDK_INT >= 28) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else false
            "SCREENSHOT" -> if (android.os.Build.VERSION.SDK_INT >= 28) {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else false
            else -> false
        }
    }

    private fun cleanButtonQuery(raw: String): String {
        var s = raw.lowercase(Locale.ROOT).trim()
        val fillers = listOf(
            "on the ", "on ", "the ", "button", "batan", "btn", "icon",
            "par", "per", "pe", "ko", "mein", "me", "tap", "click", "dabao", "press", "karo", "kar", "do"
        )
        for (f in fillers) {
            s = s.replace(Regex("\\b$f\\b"), " ")
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        if (s == "sand" || s == "saint" || s == "sent") s = "send"
        if (s == "sarch" || s == "surch") s = "search"
        return s
    }

    private fun buildSynonyms(target: String): List<String> {
        val list = mutableListOf(target)
        when (target) {
            "send" -> list.addAll(listOf("send", "bheje", "submit", "post", "reply"))
            "search" -> list.addAll(listOf("search", "find", "khoje", "query"))
            "back" -> list.addAll(listOf("back", "navigate up", "close"))
        }
        return list.distinct()
    }

    private fun findMatchingNode(node: AccessibilityNodeInfo?, targets: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.lowercase(Locale.ROOT) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT) ?: ""
        val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT) ?: ""

        for (t in targets) {
            if (text.contains(t) || desc.contains(t) || viewId.endsWith("id/$t") || viewId.contains("_$t")) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val found = findMatchingNode(node.getChild(i), targets)
            if (found != null) return found
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            val path = Path().apply {
                moveTo(rect.exactCenterX(), rect.exactCenterY())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 80)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    fun performSwipeGesture(scrollDown: Boolean): Boolean {
        val path = Path().apply {
            if (scrollDown) {
                moveTo(540f, 1650f)
                lineTo(540f, 450f)
            } else {
                moveTo(540f, 550f)
                lineTo(540f, 1600f)
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 280)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findFirstScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
