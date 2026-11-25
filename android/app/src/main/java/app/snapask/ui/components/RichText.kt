package app.snapask.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.snapask.data.Source

/**
 * 回答の文章を表示する。
 * 対応する書式は、段落・箇条書き・太字・リンク・出典番号([1] など)だけに絞っている。
 * 出典番号はタップすると元のページを開く。
 */
@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    sources: List<Source> = emptyList(),
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant
    val sourceByNumber = remember(sources) { sources.associateBy { it.number } }
    val blocks = remember(text) { parseBlocks(text) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (block in blocks) {
            when (block) {
                is TextBlock.Heading -> Text(
                    text = styled(block.text, sourceByNumber, linkColor, codeBackground),
                    style = style.copy(fontWeight = FontWeight.SemiBold),
                    color = color,
                )

                is TextBlock.Paragraph -> Text(
                    text = styled(block.text, sourceByNumber, linkColor, codeBackground),
                    style = style,
                    color = color,
                )

                is TextBlock.Bullet -> Row {
                    Text(
                        text = block.marker,
                        style = style,
                        color = markerColor,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = styled(block.text, sourceByNumber, linkColor, codeBackground),
                        style = style,
                        color = color,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private sealed interface TextBlock {
    data class Paragraph(val text: String) : TextBlock
    data class Heading(val text: String) : TextBlock
    data class Bullet(val marker: String, val text: String) : TextBlock
}

private val NUMBERED = Regex("""^(\d{1,2})[.)]\s+(.*)$""")

private val INLINE = Regex(
    """\*\*(.+?)\*\*|\[(\d+)\]|\[([^\]\n]+)\]\((https?://[^)\s]+)\)|`([^`\n]+)`""",
)

private fun parseBlocks(text: String): List<TextBlock> {
    val blocks = mutableListOf<TextBlock>()
    val paragraph = StringBuilder()

    fun flush() {
        if (paragraph.isNotBlank()) blocks.add(TextBlock.Paragraph(paragraph.toString().trim()))
        paragraph.clear()
    }

    for (raw in text.lines()) {
        val line = raw.trim()
        val numbered = NUMBERED.find(line)
        when {
            line.isEmpty() -> flush()

            line.startsWith("#") -> {
                flush()
                blocks.add(TextBlock.Heading(line.trimStart('#').trim()))
            }

            line.startsWith("- ") || line.startsWith("* ") -> {
                flush()
                blocks.add(TextBlock.Bullet("•", line.substring(2).trim()))
            }

            line.startsWith("・") -> {
                flush()
                blocks.add(TextBlock.Bullet("•", line.substring(1).trim()))
            }

            numbered != null -> {
                flush()
                blocks.add(TextBlock.Bullet(numbered.groupValues[1] + ".", numbered.groupValues[2]))
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    flush()
    return blocks
}

private fun styled(
    text: String,
    sources: Map<Int, Source>,
    linkColor: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE.findAll(text)) {
        append(text.substring(cursor, match.range.first))
        cursor = match.range.last + 1

        val bold = match.groupValues[1]
        val citation = match.groupValues[2]
        val linkText = match.groupValues[3]
        val linkUrl = match.groupValues[4]
        val code = match.groupValues[5]

        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                append(bold)
            }

            citation.isNotEmpty() -> {
                val source = citation.toIntOrNull()?.let { sources[it] }
                if (source != null) {
                    val citationStyle = SpanStyle(
                        color = linkColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        baselineShift = BaselineShift.Superscript,
                    )
                    withLink(LinkAnnotation.Url(source.url, TextLinkStyles(style = citationStyle))) {
                        append("[$citation]")
                    }
                } else {
                    append(match.value)
                }
            }

            linkUrl.isNotEmpty() -> {
                val linkStyle = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                withLink(LinkAnnotation.Url(linkUrl, TextLinkStyles(style = linkStyle))) {
                    append(linkText)
                }
            }

            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                append(code)
            }

            else -> append(match.value)
        }
    }
    append(text.substring(cursor))
}
