package com.rvgroup.pkassist.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
     * Types text into the currently focused field or first editable input on screen.
     * If appendEnterOrClickSend is true, also attempts to click the Send/Search button afterward.
     */
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

    /**
     * Smart fuzzy click: matches visible text, contentDescription (e.g. WhatsApp's "Send" or "Search" icon),
     * or viewIdResourceName, and handles common STT misspellings like "sand" -> "send".
     */
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

    /**
     * Automates searching inside an open app (like WhatsApp or YouTube):
     * 1. Clicks the Search icon/bar
     * 2. Types the query into the editable search box
     */
    fun searchInCurrentApp(query: String): Boolean {
        clickElementByText("Search")
        Thread.sleep(450)
        return typeTextInFocusedField(query)
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
            return scrollable.performAction(action)
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
            "par", "per", "pe", "ko", "mein", "me", "tap", "click", "dabao", "press"
        )
        for (f in fillers) {
            s = s.replace(Regex("\\b$f\\b"), " ")
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        // Fix common speech-to-text phonetic confusions
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
        // Fallback: tap exact screen coordinates of the node
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

    private fun performSwipeGesture(scrollDown: Boolean): Boolean {
        val path = Path().apply {
            if (scrollDown) {
                moveTo(540f, 1500f)
                lineTo(540f, 500f)
            } else {
                moveTo(540f, 600f)
                lineTo(540f, 1500f)
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
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
