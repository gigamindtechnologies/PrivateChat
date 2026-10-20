package com.gigamindtechnologies.privatechat.ui

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.atomic.AtomicBoolean

/** Renders [text] as a QR code, one pixel per module (scale it up with nearest-neighbour filtering). */
fun qrImage(text: String): ImageBitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * Decodes QR codes from camera frames on the device itself (ZXing, no Google services, no
 * network). Calls [onResult] once.
 */
class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true))
    }
    private val done = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        image.use {
            if (done.get()) return
            val plane = image.planes[0] // luminance (Y) plane of YUV_420_888
            val buffer = plane.buffer.apply { rewind() }
            val rowStride = plane.rowStride
            val data = ByteArray(rowStride * image.height)
            buffer.get(data, 0, minOf(buffer.remaining(), data.size))
            val source = PlanarYUVLuminanceSource(data, rowStride, image.height, 0, 0, image.width, image.height, false)
            try {
                val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
                if (done.compareAndSet(false, true)) onResult(text)
            } catch (_: NotFoundException) {
                // no QR code in this frame
            } catch (_: Exception) {
                // unreadable frame
            } finally {
                reader.reset()
            }
        }
    }
}
