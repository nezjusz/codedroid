package io.github.nezjusz.codedroid.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as CmText
import org.commonmark.parser.Parser

/** Tag used for link destinations, read back via `TextLayoutResult.getStringAnnotations`. */
const val LINK_TAG = "codedroid:link"

/**
 * Inline markdown → [AnnotatedString].
 *
 * A whole run is built as one annotated string instead of nested `Text` composables so that line
 * wrapping, selection and tap position resolution all behave like ordinary body text.
 */
object MarkdownInline {

    fun codeStyle(background: Color, foreground: Color): SpanStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = background,
        color = foreground,
    )

    fun render(
        nodes: Iterable<Node>,
        linkColor: Color,
        codeStyle: SpanStyle,
    ): AnnotatedString = buildAnnotatedString {
        for (node in nodes) appendNode(node, linkColor, codeStyle)
    }

    /** Children of [node] as a list; CommonMark exposes only a linked list. */
    fun childList(node: Node): List<Node> = generateSequence(node.firstChild) { it.next }.toList()

    private fun AnnotatedString.Builder.appendNode(
        node: Node,
        linkColor: Color,
        codeStyle: SpanStyle,
        inherited: SpanStyle? = null,
    ) {
        when (node) {
            is CmText -> append(node.literal)

            is Code -> {
                withStyle(inherited ?: SpanStyle()) {
                    pushStyle(codeStyle)
                    append(node.literal)
                    pop()
                }
            }

            is Emphasis -> withStyle(inherited ?: SpanStyle()) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendChildren(node, linkColor, codeStyle)
                pop()
            }

            is StrongEmphasis -> withStyle(inherited ?: SpanStyle()) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendChildren(node, linkColor, codeStyle)
                pop()
            }

            is Strikethrough -> withStyle(inherited ?: SpanStyle()) {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendChildren(node, linkColor, codeStyle)
                pop()
            }

            is Link -> withStyle(inherited ?: SpanStyle()) {
                pushStringAnnotation(LINK_TAG, node.destination)
                pushStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                    )
                )
                appendChildren(node, linkColor, codeStyle)
                pop()
                pop()
            }

            // No image loading: the caption and the URL are the useful part of a reference.
            is Image -> append(node.destination.orEmpty())

            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')

            else -> appendChildren(node, linkColor, codeStyle)
        }
    }

    private fun AnnotatedString.Builder.appendChildren(
        node: Node,
        linkColor: Color,
        codeStyle: SpanStyle,
    ) {
        var child = node.firstChild
        while (child != null) {
            appendNode(child, linkColor, codeStyle)
            child = child.next
        }
    }
}

/** Plain text of a subtree, with inline markup discarded. Used for collapsed previews. */
fun Node.plainText(): String = buildString {
    fun walk(node: Node) {
        when (node) {
            is CmText -> append(node.literal)
            is Code -> append(node.literal)
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            else -> node.firstChild?.let { walk(it) }
        }
    }
    var node = firstChild
    while (node != null) {
        walk(node)
        node = node.next
    }
}

/** A table flattened into rows of already-styled text. */
data class MarkdownTable(
    val header: List<AnnotatedString>,
    val rows: List<List<AnnotatedString>>,
)

/**
 * Shared parser.
 *
 * GFM tables and strikethrough are enabled because agents emit them, and autolink so a bare URL
 * becomes a link instead of reading as prose.
 */
object Markdown {

    val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                org.commonmark.ext.gfm.tables.TablesExtension.create(),
                org.commonmark.ext.gfm.strikethrough.StrikethroughExtension.create(),
                org.commonmark.ext.autolink.AutolinkExtension.create(),
            )
        )
        .build()

    fun parse(source: String): Node = parser.parse(source)
}
