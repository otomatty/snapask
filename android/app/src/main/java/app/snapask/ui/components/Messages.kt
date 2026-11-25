package app.snapask.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapask.data.Author
import app.snapask.data.ChatMessage
import app.snapask.data.Source
import coil.compose.AsyncImage
import java.io.File
import java.net.URI

/** 質問は右寄せの吹き出し、回答は幅いっぱいの本文として表示する。 */
@Composable
fun MessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    inProgress: Boolean = false,
) {
    when (message.author) {
        Author.USER -> Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Author.ASSISTANT -> Column(modifier = modifier.fillMaxWidth()) {
            RichText(text = message.text, sources = message.sources)
            if (!inProgress && message.text.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                if (message.sources.isNotEmpty()) {
                    SourceList(message.sources)
                } else {
                    Text(
                        text = "この回答にはWebの出典が付いていません。内容は別の方法でも確かめてください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 回答の根拠にしたページの一覧。タップで開く。 */
@Composable
fun SourceList(sources: List<Source>, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "出典",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        for (source in sources) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        try {
                            uriHandler.openUri(source.url)
                        } catch (e: Exception) {
                            // 開けるアプリがない場合は何もしない
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = source.number.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = hostOf(source.url),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun hostOf(url: String): String {
    val host = try {
        URI(url).host
    } catch (e: Exception) {
        null
    }
    return host?.removePrefix("www.") ?: url
}

/** スクショの表示。expanded でないときは上の部分だけを見せ、タップで全体を表示する。 */
@Composable
fun ScreenshotImage(
    file: File,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    AsyncImage(
        model = file,
        contentDescription = "スクリーンショット",
        contentScale = if (expanded) ContentScale.Fit else ContentScale.Crop,
        alignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = if (expanded) 460.dp else 132.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onToggle),
    )
}
