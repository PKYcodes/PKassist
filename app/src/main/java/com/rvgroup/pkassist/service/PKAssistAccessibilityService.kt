package com.rvgroup.pkassist.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Ready to observe screen context when needed
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    /**
     * Types text directly into the currently focused input field or first editable field on screen.
     */
    fun typeTextInFocusedField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)
            ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Finds any button or view containing the target text and clicks it.
     */
    fun clickElementByText(label: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(label)
        if (matches.isNullOrEmpty()) return false

        for (node in matches) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                current = current.parent
            }
        }
        return false
    }

    /**
     * Scrolls the active scrollable container forward (down) or backward (up).
     */
    fun scrollScreen(forward: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollableNode(root) ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return scrollable.performAction(action)
    }

    fun triggerGlobalAction(actionName: String): Boolean {
        return when (actionName.uppercase()) {
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            else -> false
        }
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
