package pl.mczark.invoicescanner.extract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wyciąga tekst z załączników: PDF z warstwą tekstową czytany jest przez PdfBox,
 * a skany (PDF bez tekstu, zdjęcia) przechodzą przez OCR ML Kit.
 */
class AttachmentTextExtractor(private val context: Context) {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun extract(bytes: ByteArray, mimeType: String?, filename: String?): String =
        when (kindOf(mimeType, filename)) {
            Kind.PDF -> extractPdf(bytes)
            Kind.IMAGE -> extractImage(bytes)
            Kind.OTHER -> ""
        }

    private suspend fun extractPdf(bytes: ByteArray): String {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                PDDocument.load(bytes).use { document ->
                    PDFTextStripper().apply { sortByPosition = true }.getText(document)
                }
            }.onFailure { Log.w(TAG, "PdfBox nie odczytał pliku", it) }.getOrDefault("")
        }
        // Mało tekstu = najpewniej zeskanowany dokument, więc rozpoznajemy go z obrazu.
        return if (text.count { it.isLetterOrDigit() } >= MIN_TEXT_CHARS) text else ocrPdf(bytes)
    }

    private suspend fun ocrPdf(bytes: ByteArray): String {
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("invoice", ".pdf", context.cacheDir).apply { writeBytes(bytes) }
        }
        return try {
            val pages = withContext(Dispatchers.IO) { renderPages(file) }
            pages.map { bitmap ->
                try {
                    recognize(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }.joinToString("\n")
        } catch (e: Exception) {
            Log.w(TAG, "OCR PDF nie powiódł się", e)
            ""
        } finally {
            file.delete()
        }
    }

    private fun renderPages(file: File): List<Bitmap> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                (0 until minOf(renderer.pageCount, MAX_OCR_PAGES)).map { index ->
                    renderer.openPage(index).use { page ->
                        val scale = minOf(OCR_SCALE, MAX_BITMAP_SIDE.toFloat() / maxOf(page.width, page.height))
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt(),
                            (page.height * scale).toInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }

    private suspend fun extractImage(bytes: ByteArray): String {
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            ?: return ""
        return try {
            recognize(bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "OCR obrazu nie powiódł się", e)
            ""
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun recognize(bitmap: Bitmap): String =
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text

    enum class Kind { PDF, IMAGE, OTHER }

    companion object {
        private const val TAG = "AttachmentExtractor"
        private const val MIN_TEXT_CHARS = 60
        private const val MAX_OCR_PAGES = 3
        private const val OCR_SCALE = 2f
        private const val MAX_BITMAP_SIDE = 2400

        fun kindOf(mimeType: String?, filename: String?): Kind {
            val mime = mimeType.orEmpty().lowercase()
            val name = filename.orEmpty().lowercase()
            return when {
                mime == "application/pdf" || name.endsWith(".pdf") -> Kind.PDF
                mime.startsWith("image/") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") -> Kind.IMAGE
                else -> Kind.OTHER
            }
        }
    }
}
