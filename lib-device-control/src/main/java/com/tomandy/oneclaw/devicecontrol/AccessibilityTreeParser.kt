package com.tomandy.oneclaw.devicecontrol

import android.view.accessibility.AccessibilityNodeInfo

object AccessibilityTreeParser {

    data class ParseResult(
        val text: String,
        val nodeCount: Int
    )

    fun parse(root: AccessibilityNodeInfo?): ParseResult {
        if (root == null) return ParseResult("(empty screen)", 0)
        val sb = StringBuilder()
        var index = 0
        index = appendNode(sb, root, 0, index)
        return ParseResult(sb.toString().trimEnd(), index)
    }

    private fun appendNode(
        sb: StringBuilder,
        node: AccessibilityNodeInfo,
        depth: Int,
        startIndex: Int
    ): Int {
        var index = startIndex

        if (!node.isVisibleToUser) {
            return index
        }

        val className = node.className?.toString()?.substringAfterLast('.') ?: "View"
        val text = node.text?.toString()
        val contentDesc = node.contentDescription?.toString()
        val isClickable = node.isClickable
        val isCheckable = node.isCheckable
        val isChecked = node.isChecked
        val isScrollable = node.isScrollable
        val isEditable = node.isEditable
        val isFocused = node.isFocused

        val hasUsefulInfo = text != null || contentDesc != null ||
            isClickable || isCheckable || isScrollable || isEditable ||
            node.childCount == 0

        if (hasUsefulInfo) {
            val indent = "  ".repeat(depth)
            sb.append("$indent[$index] $className")

            if (text != null) {
                sb.append(" \"${truncate(text, 80)}\"")
            }
            if (contentDesc != null && contentDesc != text) {
                sb.append(" (desc: \"${truncate(contentDesc, 80)}\")")
            }

            val flags = mutableListOf<String>()
            if (isClickable) flags.add("clickable")
            if (isCheckable) flags.add("checkable")
            if (isChecked) flags.add("checked")
            if (isScrollable) flags.add("scrollable")
            if (isEditable) flags.add("editable")
            if (isFocused) flags.add("focused")
            if (flags.isNotEmpty()) {
                sb.append(" {${flags.joinToString(", ")}}")
            }

            val resourceId = node.viewIdResourceName
            if (resourceId != null) {
                sb.append(" id:$resourceId")
            }

            sb.append("\n")
            index++
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            index = appendNode(sb, child, if (hasUsefulInfo) depth + 1 else depth, index)
            child.recycle()
        }

        return index
    }

    private fun truncate(s: String, max: Int): String {
        val single = s.replace('\n', ' ')
        return if (single.length <= max) single else single.take(max) + "..."
    }
}
