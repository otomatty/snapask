package app.snapask.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * スクショ画像の置き場所。
 * 共有で受け取った直後は pending/ に置き、メモとして保存したら shots/ に移す。
 * 保存しなかったものは破棄する。
 */
class ImageStore(context: Context) {
    private val resolver = context.contentResolver
    private val pendingDir = File(context.filesDir, "pending")
    private val shotsDir = File(context.filesDir, "shots")

    fun pendingFile(name: String): File = File(pendingDir, name)

    /**
     * 共有された画像をアプリ内にコピーする。
     * 送信量を抑えるため、長辺を MAX_EDGE に縮めて JPEG にする。読めなければ null。
     */
    suspend fun importShared(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            if (longEdge <= 0) return@withContext null

            var sample = 1
            while (longEdge / (sample * 2) >= MAX_EDGE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return@withContext null

            val decodedLongEdge = max(decoded.width, decoded.height)
            val bitmap = if (decodedLongEdge > MAX_EDGE) {
                val scale = MAX_EDGE.toFloat() / decodedLongEdge
                Bitmap.createScaledBitmap(
                    decoded,
                    max(1, (decoded.width * scale).roundToInt()),
                    max(1, (decoded.height * scale).roundToInt()),
                    true,
                )
            } else {
                decoded
            }

            pendingDir.mkdirs()
            val file = File(pendingDir, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            file
        } catch (e: Exception) {
            null
        }
    }

    /** メモとして残す画像を shots/ に移す。 */
    suspend fun keep(pending: File): File? = withContext(Dispatchers.IO) {
        if (!pending.exists()) return@withContext null
        shotsDir.mkdirs()
        val target = File(shotsDir, pending.name)
        if (pending.renameTo(target)) {
            target
        } else {
            pending.copyTo(target, overwrite = true)
            pending.delete()
            target
        }
    }

    fun discard(file: File?) {
        file?.delete()
    }

    /** 異常終了などで残った、保存されなかった画像を片付ける。 */
    suspend fun clearStalePending() {
        withContext(Dispatchers.IO) {
            val threshold = System.currentTimeMillis() - STALE_MILLIS
            val files = pendingDir.listFiles() ?: emptyArray()
            for (file in files) {
                if (file.lastModified() < threshold) file.delete()
            }
        }
    }

    private companion object {
        const val MAX_EDGE = 1568
        const val JPEG_QUALITY = 82
        const val STALE_MILLIS = 24 * 60 * 60 * 1000L
    }
}
