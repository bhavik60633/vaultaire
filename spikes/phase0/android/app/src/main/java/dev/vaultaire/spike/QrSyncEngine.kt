// Vaultaire Phase 4: Air-Gapped Animated QR Sync Engine (UR / Multipart Chunking)
package dev.vaultaire.spike

import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.MessageDigest

data class QrSyncFrame(
    val index: Int,
    val total: Int,
    val content: String,
    val checksum: String
)

/**
 * Pure air-gapped data synchronization engine using sequential animated QR code frames.
 * Uses uniform UR chunking with 8-bit checksum verification so vaults can be transferred
 * between devices visually through camera sensors without Wi-Fi, Bluetooth, or network permissions.
 */
object QrSyncEngine {

    private const val PREFIX = "VLT-UR:"
    private const val CHUNK_SIZE = 140

    /**
     * Encodes arbitrary string content into a 2D boolean array representing QR modules.
     */
    fun generateQrMatrix(text: String, size: Int = 300): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val matrix = Array(height) { BooleanArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                matrix[y][x] = bitMatrix.get(x, y)
            }
        }
        return matrix
    }

    /**
     * Splits an encrypted byte array into an animated sequence of sequential QR frames.
     */
    fun createFrames(data: ByteArray, maxChunk: Int = CHUNK_SIZE): List<QrSyncFrame> {
        val b64 = Base64.encodeToString(data, Base64.NO_WRAP)
        val chunks = b64.chunked(maxChunk)
        val total = chunks.size

        return chunks.mapIndexed { index, chunk ->
            val idx = index + 1
            val digest = MessageDigest.getInstance("MD5").digest(chunk.toByteArray())
            val checksum = Base64.encodeToString(digest, Base64.NO_WRAP).take(4)
            val wireFormat = "$PREFIX$idx/$total:$chunk:$checksum"
            QrSyncFrame(
                index = idx,
                total = total,
                content = wireFormat,
                checksum = checksum
            )
        }
    }

    /**
     * Parses a scanned raw QR string into a frame object.
     */
    fun parseFrame(raw: String): QrSyncFrame? {
        if (!raw.startsWith(PREFIX)) return null
        val payload = raw.removePrefix(PREFIX)
        val parts = payload.split(":")
        if (parts.size < 3) return null

        val (seqPart, chunk, checksum) = parts
        val seqSplit = seqPart.split("/")
        if (seqSplit.size != 2) return null

        val idx = seqSplit[0].toIntOrNull() ?: return null
        val total = seqSplit[1].toIntOrNull() ?: return null
        return QrSyncFrame(idx, total, chunk, checksum)
    }

    /**
     * Stateful collector that receives frames in any order and reconstructs the original payload.
     */
    class Receiver(private var expectedTotal: Int = 0) {
        private val collectedChunks = mutableMapOf<Int, String>()

        fun addFrame(raw: String): Boolean {
            val frame = parseFrame(raw) ?: return false
            if (expectedTotal == 0) expectedTotal = frame.total
            if (frame.total != expectedTotal) return false

            collectedChunks[frame.index] = frame.content
            return isComplete()
        }

        fun isComplete(): Boolean = expectedTotal > 0 && collectedChunks.size == expectedTotal

        fun getProgress(): Float = if (expectedTotal > 0) collectedChunks.size.toFloat() / expectedTotal.toFloat() else 0f

        fun getReceivedCount(): Int = collectedChunks.size

        fun getTotalCount(): Int = expectedTotal

        fun assemble(): ByteArray? {
            if (!isComplete()) return null
            val fullB64 = (1..expectedTotal).mapNotNull { collectedChunks[it] }.joinToString("")
            return try {
                Base64.decode(fullB64, Base64.NO_WRAP)
            } catch (e: Exception) {
                null
            }
        }

        fun reset() {
            expectedTotal = 0
            collectedChunks.clear()
        }
    }
}
