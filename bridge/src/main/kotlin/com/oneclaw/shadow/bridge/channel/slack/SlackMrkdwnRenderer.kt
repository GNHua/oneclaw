package com.oneclaw.shadow.bridge.channel.slack

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Converts standard Markdown to Slack mrkdwn format using CommonMark AST.
 */
object SlackMrkdwnRenderer {

    private val parser: Parser = Parser.builder()
        .extensions(listOf(StrikethroughExtension.create()))
        .build()

    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        val document = parser.parse(markdown)
        val visitor = SlackMrkdwnVisitor()
        document.accept(visitor)
        return visitor.result().trimEnd()
    }

    private class SlackMrkdwnVisitor : AbstractVisitor() {
        private val sb = StringBuilder()
        private var orderedListCounter = 0
        private var inBlockQuote = false

        fun result(): String = sb.toString()

        // -- Block nodes --

        override fun visit(document: Document) {
            visitChildren(document)
        }

        override fun visit(heading: Heading) {
            sb.append("*")
            visitChildren(heading)
            sb.append("*")
            appendBlockSeparator(heading)
        }

        override fun visit(paragraph: Paragraph) {
            if (inBlockQuote) sb.append("> ")
            visitChildren(paragraph)
            appendBlockSeparator(paragraph)
        }

        override fun visit(blockQuote: BlockQuote) {
            val wasInBlockQuote = inBlockQuote
            inBlockQuote = true
            visitChildren(blockQuote)
            inBlockQuote = wasInBlockQuote
            if (blockQuote.next != null && (blockQuote.parent is Document || blockQuote.parent is BlockQuote)) {
                sb.append("\n")
            }
        }

        override fun visit(bulletList: BulletList) {
            visitChildren(bulletList)
            if (bulletList.parent is Document || bulletList.parent is BlockQuote) {
                appendBlockSeparator(bulletList)
            }
        }

        override fun visit(orderedList: OrderedList) {
            val prevCounter = orderedListCounter
            orderedListCounter = orderedList.markerStartNumber
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
            sb.append("```\n")
            sb.append(fencedCodeBlock.literal?.trimEnd('\n') ?: "")
            sb.append("\n```")
            appendBlockSeparator(fencedCodeBlock)
        }

        override fun visit(indentedCodeBlock: IndentedCodeBlock) {
            sb.append("```\n")
            sb.append(indentedCodeBlock.literal?.trimEnd('\n') ?: "")
            sb.append("\n```")
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
            sb.append(text.literal)
        }

        override fun visit(strongEmphasis: StrongEmphasis) {
            sb.append("*")
            visitChildren(strongEmphasis)
            sb.append("*")
        }

        override fun visit(emphasis: Emphasis) {
            sb.append("_")
            visitChildren(emphasis)
            sb.append("_")
        }

        override fun visit(code: Code) {
            sb.append("`")
            sb.append(code.literal)
            sb.append("`")
        }

        override fun visit(link: Link) {
            sb.append("<${link.destination ?: ""}|")
            visitChildren(link)
            sb.append(">")
        }

        override fun visit(image: Image) {
            sb.append("<${image.destination ?: ""}|")
            val before = sb.length
            visitChildren(image)
            if (sb.length == before) {
                sb.append("image")
            }
            sb.append(">")
        }

        override fun visit(customNode: CustomNode) {
            if (customNode is Strikethrough) {
                sb.append("~")
                visitChildren(customNode)
                sb.append("~")
            } else {
                visitChildren(customNode)
            }
        }

        // -- Helpers --

        private fun appendBlockSeparator(node: Node) {
            if (node.next != null) {
                sb.append("\n")
                if (node.parent is Document || node.parent is BlockQuote) {
                    sb.append("\n")
                }
            }
        }
    }
}
