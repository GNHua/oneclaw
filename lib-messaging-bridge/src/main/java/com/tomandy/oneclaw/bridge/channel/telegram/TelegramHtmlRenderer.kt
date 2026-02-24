package com.tomandy.oneclaw.bridge.channel.telegram

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Converts standard markdown to Telegram HTML using commonmark AST.
 *
 * Telegram HTML supports a limited tag set: <b>, <i>, <code>, <pre>,
 * <a href="">, <s>, <blockquote>. Only &, <, > need escaping in regular text.
 */
object TelegramHtmlRenderer {

    private val parser: Parser = Parser.builder()
        .extensions(listOf(StrikethroughExtension.create()))
        .build()

    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        val document = parser.parse(markdown)
        val visitor = TelegramHtmlVisitor()
        document.accept(visitor)
        return visitor.result().trimEnd()
    }

    internal fun escapeHtml(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private class TelegramHtmlVisitor : AbstractVisitor() {
        private val sb = StringBuilder()
        private var orderedListCounter = 0

        fun result(): String = sb.toString()

        // -- Block nodes --

        override fun visit(document: Document) {
            visitChildren(document)
        }

        override fun visit(heading: Heading) {
            sb.append("<b>")
            visitChildren(heading)
            sb.append("</b>")
            appendBlockSeparator(heading)
        }

        override fun visit(paragraph: Paragraph) {
            visitChildren(paragraph)
            appendBlockSeparator(paragraph)
        }

        override fun visit(blockQuote: BlockQuote) {
            sb.append("<blockquote>")
            // Render children, then strip trailing whitespace before closing tag
            val startLen = sb.length
            visitChildren(blockQuote)
            // Remove trailing newlines inside blockquote
            while (sb.length > startLen && sb.last() == '\n') {
                sb.deleteCharAt(sb.length - 1)
            }
            sb.append("</blockquote>")
            appendBlockSeparator(blockQuote)
        }

        override fun visit(bulletList: BulletList) {
            visitChildren(bulletList)
            if (bulletList.parent is Document || bulletList.parent is BlockQuote) {
                appendBlockSeparator(bulletList)
            }
        }

        override fun visit(orderedList: OrderedList) {
            val prevCounter = orderedListCounter
            orderedListCounter = orderedList.startNumber
            visitChildren(orderedList)
            orderedListCounter = prevCounter
            if (orderedList.parent is Document || orderedList.parent is BlockQuote) {
                appendBlockSeparator(orderedList)
            }
        }

        override fun visit(listItem: ListItem) {
            val parent = listItem.parent
            if (parent is OrderedList) {
                sb.append("${orderedListCounter}. ")
                orderedListCounter++
            } else {
                sb.append("\u2022 ")
            }
            // Render list item children inline (skip paragraph wrapper)
            var child = listItem.firstChild
            while (child != null) {
                if (child is Paragraph) {
                    visitChildren(child)
                } else {
                    child.accept(this)
                }
                child = child.next
            }
            sb.append("\n")
        }

        override fun visit(fencedCodeBlock: FencedCodeBlock) {
            val lang = fencedCodeBlock.info ?: ""
            if (lang.isNotEmpty()) {
                sb.append("<pre><code class=\"language-$lang\">")
            } else {
                sb.append("<pre>")
            }
            sb.append(escapeHtml(fencedCodeBlock.literal?.trimEnd('\n') ?: ""))
            if (lang.isNotEmpty()) {
                sb.append("</code></pre>")
            } else {
                sb.append("</pre>")
            }
            appendBlockSeparator(fencedCodeBlock)
        }

        override fun visit(indentedCodeBlock: IndentedCodeBlock) {
            sb.append("<pre>")
            sb.append(escapeHtml(indentedCodeBlock.literal?.trimEnd('\n') ?: ""))
            sb.append("</pre>")
            appendBlockSeparator(indentedCodeBlock)
        }

        override fun visit(thematicBreak: ThematicBreak) {
            sb.append("\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500")
            appendBlockSeparator(thematicBreak)
        }

        override fun visit(hardLineBreak: HardLineBreak) {
            sb.append("\n")
        }

        override fun visit(softLineBreak: SoftLineBreak) {
            sb.append("\n")
        }

        // -- Inline nodes --

        override fun visit(text: Text) {
            sb.append(escapeHtml(text.literal))
        }

        override fun visit(strongEmphasis: StrongEmphasis) {
            sb.append("<b>")
            visitChildren(strongEmphasis)
            sb.append("</b>")
        }

        override fun visit(emphasis: Emphasis) {
            sb.append("<i>")
            visitChildren(emphasis)
            sb.append("</i>")
        }

        override fun visit(code: Code) {
            sb.append("<code>")
            sb.append(escapeHtml(code.literal))
            sb.append("</code>")
        }

        override fun visit(link: Link) {
            sb.append("<a href=\"${link.destination ?: ""}\">")
            visitChildren(link)
            sb.append("</a>")
        }

        override fun visit(image: Image) {
            // Degrade to link
            sb.append("<a href=\"${image.destination ?: ""}\">")
            val before = sb.length
            visitChildren(image)
            // Fallback text if alt was empty
            if (sb.length == before) {
                sb.append("image")
            }
            sb.append("</a>")
        }

        override fun visit(customNode: org.commonmark.node.CustomNode) {
            if (customNode is Strikethrough) {
                sb.append("<s>")
                visitChildren(customNode)
                sb.append("</s>")
            } else {
                visitChildren(customNode)
            }
        }

        // -- Helpers --

        private fun appendBlockSeparator(node: org.commonmark.node.Node) {
            if (node.next != null) {
                sb.append("\n")
                if (node.parent is Document || node.parent is BlockQuote) {
                    sb.append("\n")
                }
            }
        }
    }
}
