package com.sernheise.app.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MediaUtils {

    enum class MediaType { IMAGE, VIDEO, UNKNOWN }

    /**
     * A video's decoded buffer size plus the clockwise rotation (0/90/180/270) needed to
     * display it upright. [naturalWidth]/[naturalHeight] are the raw decoder dimensions
     * (as MediaCodec/MediaPlayer hand them over, *before* rotation is applied).
     *
     * This is the single place rotation metadata is read from -- both the live preview
     * (EditorActivity) and the exporter (VideoExporter) call this same function, which is
     * what guarantees preview and export always agree on orientation.
     */
    data class VideoGeometry(val naturalWidth: Int, val naturalHeight: Int, val rotationDegrees: Int)

    /**
     * Rotation is read primarily from [MediaExtractor]'s own track format (`KEY_ROTATION`,
     * populated by the demuxer from the container's display-matrix metadata) -- this is the
     * exact same value MediaCodec/decode will see, so it can't disagree with how the file is
     * actually decoded. [MediaMetadataRetriever] is used only to fill in whatever the
     * extractor pass doesn't provide (width/height, or rotation on the rare container where
     * the extractor doesn't surface it). If either path fails outright (corrupt file, a
     * container/codec this device has no support for, etc.) this falls back to sane
     * defaults rather than throwing, since geometry lookup must never crash the import flow.
     */
    fun readVideoGeometry(context: Context, uri: Uri): VideoGeometry {
        var extractorWidth: Int? = null
        var extractorHeight: Int? = null
        var extractorRotation: Int? = null

        try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (!mime.startsWith("video/")) continue

                    if (format.containsKey(MediaFormat.KEY_WIDTH)) extractorWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) extractorHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) extractorRotation = format.getInteger(MediaFormat.KEY_ROTATION)
                    break
                }
            } finally {
                extractor.release()
            }
        } catch (e: Exception) {
            // Some containers/codecs aren't demuxable on every device -- MediaMetadataRetriever
            // below is a separate, independent code path and often still succeeds.
        }

        var retrieverWidth: Int? = null
        var retrieverHeight: Int? = null
        var retrieverRotation: Int? = null
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retrieverWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                retrieverHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                retrieverRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            // If this also fails, the coerced defaults below still let import proceed instead
            // of crashing; the user will see a black/blank preview rather than a force-close.
        }

        val width = extractorWidth ?: retrieverWidth ?: 1920
        val height = extractorHeight ?: retrieverHeight ?: 1080
        val rotation = extractorRotation ?: retrieverRotation ?: 0
        val normalizedRotation = ((rotation % 360) + 360) % 360
        return VideoGeometry(width, height, normalizedRotation)
    }

    fun typeOf(context: Context, uri: Uri): MediaType {
        val mime = context.contentResolver.getType(uri) ?: return MediaType.UNKNOWN
        return when {
            mime.startsWith("image/") -> MediaType.IMAGE
            mime.startsWith("video/") -> MediaType.VIDEO
            else -> MediaType.UNKNOWN
        }
    }

    /** Loads a full-resolution, EXIF-rotation-corrected bitmap -- use for export only. */
    fun loadOrientedBitmap(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val original = resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input) ?: throw IllegalArgumentException("Could not decode image")
        }
        return applyExifRotation(context, uri, original)
    }

    /**
     * Loads a downsampled (max [maxDimension] on the long side), EXIF-rotation-corrected
     * bitmap -- use for the live preview, where full sensor resolution buys nothing (the
     * screen can't show it) but costs real decode time and memory on modern 12+ MP photos.
     */
    fun loadPreviewBitmap(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input -> BitmapFactory.decodeStream(input, null, bounds) }

        var sampleSize = 1
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (longSide / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val sampled = resolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
                ?: throw IllegalArgumentException("Could not decode image")
        }
        return applyExifRotation(context, uri, sampled)
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val resolver = context.contentResolver
        val rotationDegrees = resolver.openInputStream(uri).use { input ->
            try {
                val exif = ExifInterface(input!!)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Exception) {
                0f
            }
        }

        if (rotationDegrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun timestampedFileName(prefix: String, extension: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${prefix}_$stamp.$extension"
    }

    /** Saves [bitmap] as a JPEG into the shared Pictures/Sernheise collection. Returns the resulting Uri. */
    fun saveImageToGallery(context: Context, bitmap: Bitmap, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sernheise")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 99, out)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    /** Copies a finished export video file into the shared Movies/Sernheise collection. */
    fun publishVideoToGallery(context: Context, sourceFile: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Sernheise")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            sourceFile.inputStream().use { it.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }
}
