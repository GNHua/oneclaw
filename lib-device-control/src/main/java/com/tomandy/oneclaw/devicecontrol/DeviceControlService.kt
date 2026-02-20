package com.tomandy.oneclaw.devicecontrol

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class DeviceControlService : AccessibilityService() {

    companion object {
        private const val TAG = "DeviceControlService"
        private const val DOUBLE_CLICK_WINDOW_MS = 400L
    }

    private var lastVolumeDownUpTime = 0L
    private var pendingAbort = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        DeviceControlManager.registerService(this)
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used -- we read the tree on demand via getScreenContent()
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        DeviceControlManager.unregisterService()
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (lastVolumeDownUpTime > 0 &&
                        event.eventTime - lastVolumeDownUpTime <= DOUBLE_CLICK_WINDOW_MS
                    ) {
                        pendingAbort = true
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (pendingAbort) {
                        pendingAbort = false
                        lastVolumeDownUpTime = 0L
                        DeviceControlManager.abortAllExecutions()
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        return true
                    }
                    lastVolumeDownUpTime = event.eventTime
                }
            }
        }
        return false
    }

    // -- Screen reading --

    fun getScreenContent(): String {
        val root = rootInActiveWindow ?: return "(no active window)"
        val result = AccessibilityTreeParser.parse(root)
        root.recycle()
        return result.text
    }

    // -- Tap by selector --

    fun performTap(text: String?, contentDescription: String?, resourceId: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val node = findNode(root, text, contentDescription, resourceId) ?: return false
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                || findClickableParent(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            node.recycle()
            return result
        } finally {
            root.recycle()
        }
    }

    // -- Tap by coordinates --

    fun performTapAtCoordinates(x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    // -- Set text --

    fun performSetText(text: String, targetHint: String?): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val editNode = if (targetHint != null) {
                findEditableByHint(root, targetHint)
            } else {
                findFocusedEditable(root)
            } ?: return false

            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            editNode.recycle()
            return result
        } finally {
            root.recycle()
        }
    }

    // -- Scroll / Swipe --

    fun performSwipe(direction: String): Boolean {
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                val action = when (direction.lowercase()) {
                    "up", "down" -> {
                        if (direction.lowercase() == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }
                    "left" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "right" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> {
                        scrollable.recycle()
                        root.recycle()
                        return false
                    }
                }
                val result = scrollable.performAction(action)
                scrollable.recycle()
                root.recycle()
                return result
            }
            root.recycle()
        }
        // Fallback: gesture-based swipe
        return dispatchSwipeGesture(direction)
    }

    // -- Private helpers --

    private fun findNode(
        root: AccessibilityNodeInfo,
        text: String?,
        contentDescription: String?,
        resourceId: String?
    ): AccessibilityNodeInfo? {
        if (resourceId != null) {
            val nodes = root.findAccessibilityNodeInfosByViewId(resourceId)
            if (!nodes.isNullOrEmpty()) return nodes[0]
        }
        if (text != null) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.text?.toString()?.contains(text, ignoreCase = true) == true ||
                        node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
                    ) {
                        return node
                    }
                }
                return nodes[0]
            }
        }
        if (contentDescription != null) {
            val nodes = root.findAccessibilityNodeInfosByText(contentDescription)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.contentDescription?.toString()?.contains(contentDescription, ignoreCase = true) == true) {
                        return node
                    }
                }
            }
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        while (current != null) {
            if (current.isClickable) return current
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return null
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        focused?.recycle()
        return findFirstEditable(root)
    }

    private fun findEditableByHint(root: AccessibilityNodeInfo, hint: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(hint)
        if (nodes != null) {
            for (node in nodes) {
                if (node.isEditable) return node
                node.recycle()
            }
        }
        return findFirstEditable(root)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun dispatchSwipeGesture(direction: String): Boolean {
        val displayMetrics = resources.displayMetrics
        val w = displayMetrics.widthPixels
        val h = displayMetrics.heightPixels
        val cx = w / 2f
        val cy = h / 2f
        val dist = h / 4f

        val path = Path()
        when (direction.lowercase()) {
            "up" -> {
                path.moveTo(cx, cy + dist)
                path.lineTo(cx, cy - dist)
            }
            "down" -> {
                path.moveTo(cx, cy - dist)
                path.lineTo(cx, cy + dist)
            }
            "left" -> {
                path.moveTo(cx + dist, cy)
                path.lineTo(cx - dist, cy)
            }
            "right" -> {
                path.moveTo(cx - dist, cy)
                path.lineTo(cx + dist, cy)
            }
            else -> return false
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
