package eu.kanade.tachiyomi.data.coil

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.CbzCrypto
import eu.kanade.tachiyomi.util.storage.CbzCrypto.getCoverStream
import mihon.core.common.archive.archiveReader
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.BufferedInputStream

/**
 * A [Decoder] that uses built-in [ImageDecoder] to decode images that is not supported by the system.
 *
 * MihonSY: when [Options.enhanced] is set (Lanczos3 enhancement enabled), this decoder is used for
 * ALL image formats, decodes at a higher resolution than the view size so the enhancer works on
 * real source detail, then runs the (pure-CPU) Lanczos3 resampler synchronously. No disk cache, no
 * threading — the simplest possible pipeline.
 */
class TachiyomiImageDecoder(private val resources: ImageSource, private val options: Options) : Decoder {
    private val context = Injekt.get<Application>()

    override suspend fun decode(): DecodeResult {
        // SY -->
        var coverStream: BufferedInputStream? = null
        if (resources.sourceOrNull()?.peek()?.use { CbzCrypto.detectCoverImageArchive(it.inputStream()) } == true) {
            if (resources.source().peek().use { ImageUtil.findImageType(it.inputStream()) == null }) {
                coverStream = UniFile.fromFile(resources.file().toFile())
                    ?.archiveReader(context = context)
                    ?.getCoverStream()
            }
        }
        val decoder = resources.sourceOrNull()?.use {
            coverStream.use { coverStream ->
                ImageDecoder.newInstance(coverStream ?: it.inputStream(), options.cropBorders, displayProfile)
            }
        }
        // SY <--

        check(decoder != null && decoder.width > 0 && decoder.height > 0) { "Failed to initialize decoder" }

        val srcWidth = decoder.width
        val srcHeight = decoder.height

        val dstWidth = options.size.widthPx(options.scale) { srcWidth }
        val dstHeight = options.size.heightPx(options.scale) { srcHeight }

        // MihonSY: enhancement no longer runs inside the decoder. The reader shows the
        // original immediately and runs Lanczos3 asynchronously (ReaderPageImageView),
        // so the decoder is back to a plain decode at the requested view size. No 2x
        // decode, no in-decoder enhancement — keeps decode fast and never blocks the UI.
        val sampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        )

        var bitmap = decoder.decode(sampleSize = sampleSize)
        decoder.recycle()

        check(bitmap != null) { "Failed to decode image" }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            options.bitmapConfig == Bitmap.Config.HARDWARE &&
            ImageUtil.canUseHardwareBitmap(bitmap)
        ) {
            try {
                val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
                if (hwBitmap != null) {
                    bitmap.recycle()
                    bitmap = hwBitmap
                }
            } catch (e: Throwable) {
                // Keep the software bitmap on failure.
            }
        }

        return DecodeResult(
            image = bitmap.asImage(),
            isSampled = sampleSize > 1,
        )
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            // MihonSY: this decoder handles system-unsupported formats (AVIF/JXL/HEIF)
            // and CBZ cover archives. Standard JPEG/PNG/WebP pages are decoded by the
            // system/SSIV path; enhancement runs asynchronously in ReaderPageImageView.
            return if (options.customDecoder || isApplicable(result.source.source())) {
                TachiyomiImageDecoder(result.source, options)
            } else {
                null
            }
        }

        private fun isApplicable(source: BufferedSource): Boolean {
            val type = source.peek().inputStream().buffered().use { stream ->
                ImageUtil.findImageType(stream)
            }
            // SY -->
            source.peek().inputStream().use { stream ->
                if (CbzCrypto.detectCoverImageArchive(stream)) return true
            }
            // SY <--
            return when (type) {
                ImageUtil.ImageType.AVIF, ImageUtil.ImageType.JXL -> true
                ImageUtil.ImageType.HEIF -> Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                else -> false
            }
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()
    }

    companion object {
        var displayProfile: ByteArray? = null
    }
}
