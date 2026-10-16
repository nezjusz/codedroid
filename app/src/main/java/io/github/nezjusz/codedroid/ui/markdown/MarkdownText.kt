package io.github.nezjusz.codedroid.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nezjusz.codedroid.ui.theme.LocalStatusColors
import io.github.nezjusz.codedroid.ui.theme.MonoSmallStyle
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.ListBlock as ListBlockNode
import org.commonmark.node.ListItem
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.ThematicBreak

/**
 * Renders CommonMark as Compose.
 *
 * Block structure is handled by composition (so lists nest, quotes indent, code scrolls) while the
 * inline run inside each block is a single annotated string.
 */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = MaterialTheme.typography.bodyMedium.fontSize,
    lineHeight: androidx.compose.ui.unit.TextUnit = MaterialTheme.typography.bodyMedium.lineHeight,
) {
    // Parsing is the expensive part and this recomposes on every streamed delta.
    val document = remember(source) { Markdown.parse(source) }
    MarkdownBlocks(document, modifier, color, fontSize, lineHeight)
}

@Composable
private fun MarkdownBlocks(
    node: org.commonmark.node.Node,
    modifier: Modifier,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var block = node.firstChild
        var first = true
        while (block != null) {
            if (!first) Spacer(Modifier.height(6.dp))
            first = false
            MarkdownBlock(block, color, fontSize, lineHeight)
            block = block.next
        }
    }
}

@Composable
private fun MarkdownBlock(
    node: org.commonmark.node.Node,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
) {
    when (node) {
        is Paragraph -> InlineBlock(
            node = node,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = FontWeight.Normal,
        )

        is Heading -> InlineBlock(
            node = node,
            color = color,
            fontSize = headingSize(node.level, fontSize),
            lineHeight = lineHeight,
            fontWeight = FontWeight.Bold,
        )

        is FencedCodeBlock -> CodeBlock(node.info?.trim()?.takeIf { it.isNotEmpty() }, node.literal)

        is IndentedCodeBlock -> CodeBlock(null, node.literal)

        is BlockQuote -> BlockQuoteBlock(node, color, fontSize, lineHeight)

        is BulletList -> ListBlock(node, ordered = false, color, fontSize, lineHeight)

        is OrderedList -> ListBlock(node, ordered = true, color, fontSize, lineHeight)

        is TableBlock -> TableBlockView(node, color, fontSize, lineHeight)

        is ThematicBreak -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        // Keep the text, drop the tags: agent output sometimes contains raw HTML.
        is HtmlBlock -> if (node.literal.isNotBlank()) {
            Text(
                text = node.literal,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> node.firstChild?.let { MarkdownBlock(it, color, fontSize, lineHeight) }
    }
}

private fun headingSize(level: Int, base: androidx.compose.ui.unit.TextUnit) = when (level) {
    1 -> base * 1.5f
    2 -> base * 1.3f
    3 -> base * 1.15f
    else -> base * 1.05f
}

/**
 * One block of inline text.
 *
 * Links are tappable by resolving the tap against the laid-out text rather than relying on
 * `ClickableText`, which no longer exists in this Compose version.
 */
@Composable
private fun InlineBlock(
    node: org.commonmark.node.Node,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
) {
    val status = LocalStatusColors.current
    val codeStyle = remember(status) {
        MarkdownInline.codeStyle(status.codeBackground, status.codeForeground)
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(node, linkColor, codeStyle) {
        MarkdownInline.render(MarkdownInline.childList(node), linkColor, codeStyle)
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val uriHandler = LocalUriHandler.current

    Text(
        text = annotated,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (annotated.getStringAnnotations(LINK_TAG, 0, annotated.length).isEmpty()) {
                    Modifier
                } else {
                    Modifier.pointerInput(annotated) {
                        detectTapGestures { position ->
                            val result = layout ?: return@detectTapGestures
                            val offset = result.getOffsetForPosition(position)
                            val url = annotated
                                .getStringAnnotations(LINK_TAG, offset, offset)
                                .firstOrNull()
                                ?.item
                            if (url != null) runCatching { uriHandler.openUri(url) }
                        }
                    }
                }
            ),
    )
}

@Composable
private fun CodeBlock(language: String?, code: String) {
    val status = LocalStatusColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(status.codeBackground),
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                style = MonoSmallStyle.copy(color = status.running),
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp),
            )
        }
        // Long lines scroll rather than wrapping: wrapped code is harder to read than scrolled code.
        Text(
            text = code,
            style = MonoSmallStyle.copy(color = status.codeForeground),
            softWrap = false,
            overflow = TextOverflow.Visible,
            maxLines = 200,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun BlockQuoteBlock(
    node: BlockQuote,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
) {
    // IntrinsicSize.Min lets the accent bar match whatever height the quote's content resolves to.
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Spacer(Modifier.width(10.dp))
        MarkdownBlocks(
            node = node,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = fontSize,
            lineHeight = lineHeight,
        )
    }
}

@Composable
private fun ListBlock(
    node: ListBlockNode,
    ordered: Boolean,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
) {
    val start = (node as? OrderedList)?.startNumber ?: 1
    val tight = node.isTight()

    Column(verticalArrangement = Arrangement.spacedBy(if (tight) 2.dp else 8.dp)) {
        var item = node.firstChild
        var index = 0
        while (item != null) {
            if (item is ListItem) {
                val marker = if (ordered) "${start + index}." else "•"
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        text = marker,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        modifier = Modifier.width((marker.length * 2 + 2).dp),
                    )
                    if (tight) {
                        // Tight items are a single paragraph; render inline to keep them compact.
                        item.firstChild?.let { paragraph ->
                            InlineBlock(paragraph, color, fontSize, lineHeight, FontWeight.Normal)
                        }
                    } else {
                        MarkdownBlocks(
                            node = item,
                            modifier = Modifier.weight(1f),
                            color = color,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                        )
                    }
                }
                index++
            }
            item = item.next
        }
    }
}

@Composable
private fun TableBlockView(
    node: TableBlock,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
) {
    val status = LocalStatusColors.current
    val codeStyle = remember(status) {
        MarkdownInline.codeStyle(status.codeBackground, status.codeForeground)
    }
    val linkColor = MaterialTheme.colorScheme.primary

    var header: List<AnnotatedString>? = null
    val rows = mutableListOf<List<AnnotatedString>>()
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is TableHead -> header = child.rows().firstOrNull()?.let { row -> row.cells() }
                ?.map { it.cell(linkColor, codeStyle) }
            is TableBody -> child.rows().forEach { row ->
                rows += row.cells().map { it.cell(linkColor, codeStyle) }
            }
        }
        child = child.next
    }

    val head = header.orEmpty()
    val allRows = if (head.isNotEmpty()) listOf(head) + rows else rows
    if (allRows.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
    ) {
        allRows.forEachIndexed { rowIndex, cells ->
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                cells.forEach { cell ->
                    Text(
                        text = cell,
                        color = color,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        fontWeight = if (rowIndex == 0 && head.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(140.dp),
                    )
                }
            }
            if (rowIndex < allRows.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Rows of a GFM table head/body; CommonMark exposes these as a linked list. */
private fun org.commonmark.node.Node.rows(): List<TableRow> =
    generateSequence(firstChild) { it.next }.filterIsInstance<TableRow>().toList()

/** Cells of a table row. */
private fun TableRow.cells(): List<TableCell> =
    generateSequence(firstChild) { it.next }.filterIsInstance<TableCell>().toList()

private fun TableCell.cell(
    linkColor: androidx.compose.ui.graphics.Color,
    codeStyle: androidx.compose.ui.text.SpanStyle,
): AnnotatedString = MarkdownInline.render(MarkdownInline.childList(this), linkColor, codeStyle)
