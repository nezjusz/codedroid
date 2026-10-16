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
import androidx.compose.ui.unit.TextUnit
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Block
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
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

    /**
 * Style for an inline code span.
 *
 * Scaled down from the surrounding text. Monospace at body size is the widest thing on the
 * screen, so a path written inline ran past the right edge; a code span is meant to sit *within*
 * a sentence, and it reads better slightly smaller anyway.
 */
fun codeStyle(background: Color, foreground: Color, baseSize: TextUnit): SpanStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = baseSize * INLINE_CODE_SCALE,
        background = background,
        color = foreground,
    )

/** Inline code relative to body text. Small enough to fit a path, large enough to stay legible. */
private const val INLINE_CODE_SCALE = 0.9f

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

            // A single newline inside a paragraph is a line break, not a space. Agent output reads the way
            // it would be typed into a terminal, where a lone newline separates lines of thought;
            // collapsing these to spaces is what produced the wall of text.
            is SoftLineBreak -> append('\n')
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
    // `walk` is passed in rather than called directly because the two are mutually recursive and
    // Kotlin local functions cannot reference each other by name.
    fun walkSiblings(first: Node?, walk: (Node) -> Unit) {
        var child = first
        var isFirst = true
        while (child != null) {
            // Sibling blocks are separate lines. Without this, two paragraphs join as "onetwo"
            // and a three-item list as "firstsecondthird".
            if (!isFirst && child is Block) append('\n')
            walk(child)
            isFirst = false
            child = child.next
        }
    }

    fun walk(node: Node) {
        when (node) {
            is CmText -> append(node.literal)
            is Code -> append(node.literal)
            // Code blocks keep their body in `literal` rather than as child text nodes, so
            // without this a fenced block collapses to nothing at all.
            is FencedCodeBlock -> append(node.literal)
            is IndentedCodeBlock -> append(node.literal)
            is SoftLineBreak -> append('\n')
            is HardLineBreak -> append('\n')
            else -> walkSiblings(node.firstChild, ::walk)
        }
    }

    walkSiblings(firstChild, ::walk)
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
 *
 * Soft line breaks are deliberately left for [MarkdownInline] to render as newlines. CommonMark
 * folds them into spaces, which is right for prose and wrong for a model transcript: the newline is
 * how the answer was laid out. `hardbreaks` exists on the HTML renderer, not here, because this app
 * builds its own annotated string rather than emitting HTML.
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

    fun parse(source: String): Node {
        val document = parser.parse(source)
        // A body with no real newline anywhere has had its line breaks escaped upstream: the
        // paragraph break I wrote arrives as the two characters backslash and `n` and prints
        // between the sentences. Deciding this once, for the whole message, is what keeps a
        // genuine `C:\new` in ordinary multi-line prose from being rewritten.
        if ('\n' !in source) unescapeTextNodes(document)
        return document
    }

    /**
     * Rewrites escaped line breaks in prose text nodes, in place.
     *
     * Only `Text` nodes are touched. Inline code and code blocks keep their body in a `literal`
     * that CommonMark does not model as text, so a JSON snippet or a regex that legitimately
     * contains `\n` survives untouched.
     */
    private fun unescapeTextNodes(node: Node) {
        if (node is CmText) {
            val literal = node.literal
            if ('\\' in literal) {
                node.literal = literal.replace("\\n", "\n").replace("\\t", "\t")
            }
            return
        }
        var child = node.firstChild
        while (child != null) {
            unescapeTextNodes(child)
            child = child.next
        }
    }
}
