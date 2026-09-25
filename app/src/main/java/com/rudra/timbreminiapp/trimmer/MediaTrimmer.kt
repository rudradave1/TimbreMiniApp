package com.rudra.timbreminiapp.trimmer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.math.max

private const val TAG = "MediaTrimmer"

class MediaTrimmer(private val context: Context) {

    data class TrimResult(
        val publicUri: Uri,
        val displayName: String,
        val pathDescription: String,
        val sizeBytes: Long
    )

    suspend fun trimMedia(
        sourceUri: Uri,
        startMs: Long,
        endMs: Long,
        isVideo: Boolean
    ): Result<TrimResult> = withContext(Dispatchers.IO) {
        try {
            // A content URI isn't a path FFmpeg can open, so resolve it to a
            // local cache copy first; the probe below needs a real file too.
            val inputTemp = copyToTemp(sourceUri) ?: throw TrimError.UnreadableSource()
            try {
                val sourceMime = runCatching {
                    context.contentResolver.getType(sourceUri)
                }.getOrNull()

                val mediaInfo = runCatching {
                    FFprobeKit.getMediaInformation(inputTemp.absolutePath)?.mediaInformation
                }.getOrNull()

                // Prefer the probed container; when the probe fails, fall back
                // to the MIME reported by the picker instead of guessing.
                val formatName = mediaInfo?.format?.lowercase().orEmpty()
                val (ext, mime) = if (formatName.isBlank() && sourceMime != null) {
                    deriveExtensionAndMimeFromMime(sourceMime, isVideo)
                } else {
                    deriveExtensionAndMime(formatName, isVideo)
                }

                val durationMs = max(0, endMs - startMs)
                if (durationMs < 1000L) throw TrimError.InvalidRange()

                val startSec = startMs / 1000.0
                val durationSec = durationMs / 1000.0
                val outTemp = File(context.cacheDir, "trimmed_out_${System.currentTimeMillis()}.$ext")

                // Stream copy (-c copy) is instant and lossless, but the cut
                // snaps to the nearest keyframe since nothing is re-encoded.
                // -ss before -i does a fast seek instead of decoding the whole
                // file, and +faststart relocates the moov atom so mp4/m4a/mov
                // play while still downloading.
                val cmd = buildTrimCommand(
                    input = inputTemp.absolutePath,
                    output = outTemp.absolutePath,
                    startSec = startSec,
                    durationSec = durationSec,
                    ext = ext
                )

                when (val outcome = executeFfmpeg(cmd)) {
                    is FfmpegOutcome.Success -> Unit
                    is FfmpegOutcome.Cancelled -> {
                        outTemp.delete()
                        throw TrimError.Cancelled()
                    }
                    is FfmpegOutcome.Failed -> {
                        outTemp.delete()
                        Log.e(TAG, "FFmpeg exit ${outcome.exitCode}: ${outcome.detail}")
                        throw TrimError.ConversionFailed(outcome.detail)
                    }
                }

                if (!outTemp.exists() || outTemp.length() == 0L) {
                    outTemp.delete()
                    throw TrimError.EmptyOutput()
                }

                try {
                    val result = saveToMediaStore(outTemp, mime, ext, isVideo)
                    Result.success(result)
                } finally {
                    outTemp.delete()
                }
            } finally {
                inputTemp.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private sealed class FfmpegOutcome {
        data object Success : FfmpegOutcome()
        data object Cancelled : FfmpegOutcome()
        data class Failed(val exitCode: Long, val detail: String?) : FfmpegOutcome()
    }

    private suspend fun executeFfmpeg(cmd: String): FfmpegOutcome =
        suspendCancellableCoroutine { cont ->
            val session: FFmpegSession = FFmpegKit.executeAsync(
                cmd,
                { s ->
                    if (cont.isActive) {
                        cont.resume(
                            when {
                                ReturnCode.isSuccess(s.returnCode) -> FfmpegOutcome.Success
                                ReturnCode.isCancel(s.returnCode) -> FfmpegOutcome.Cancelled
                                else -> FfmpegOutcome.Failed(
                                    exitCode = s.returnCode.value.toLong(),
                                    detail = s.failStackTrace
                                )
                            }
                        )
                    }
                }
            )
            cont.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
            }
        }

    private fun copyToTemp(sourceUri: Uri): File? {
        return try {
            val extHint = context.contentResolver.getType(sourceUri)?.let { mime ->
                when {
                    mime.startsWith("video/") -> "mp4"
                    mime.startsWith("audio/") -> "m4a"
                    else -> null
                }
            }
            val temp = File(context.cacheDir, "input_temp_${System.currentTimeMillis()}${extHint?.let { ".$it" } ?: ""}")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                temp.outputStream().use { out -> input.copyTo(out) }
            }
            if (temp.exists() && temp.length() > 0) temp else null
        } catch (_: Exception) {
            null
        }
    }

    private fun saveToMediaStore(
        tempFile: File,
        mime: String,
        ext: String,
        isVideo: Boolean
    ): TrimResult {
        val displayName = "trimmed_${System.currentTimeMillis()}.$ext"
        // Q+ publishes the file to shared storage via MediaStore with no
        // permissions asked; below Q we write to the app's own external
        // files dir instead and share it through a FileProvider.
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.SIZE, tempFile.length())
                    if (isVideo) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/TimbreMiniApp")
                    } else {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/TimbreMiniApp")
                    }
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val itemUri = context.contentResolver.insert(collection, values)
                    ?: throw IllegalStateException("MediaStore insert returned null")
                val outStream = openOutputStreamWithRetry(itemUri)
                    ?: throw IllegalStateException("Couldn't open output stream for $itemUri")
                outStream.use { stream ->
                    FileInputStream(tempFile).use { input -> input.copyTo(stream) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
                val pathDesc = if (isVideo) "Movies/TimbreMiniApp/$displayName" else "Music/TimbreMiniApp/$displayName"
                TrimResult(itemUri, displayName, pathDesc, tempFile.length())
            } else {
                val subDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
                val outDir = File(context.getExternalFilesDir(subDir), "TimbreMiniApp")
                if (!outDir.exists()) outDir.mkdirs()
                val outFile = File(outDir, displayName)
                FileInputStream(tempFile).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
                val shareUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    outFile
                )
                TrimResult(shareUri, displayName, outFile.absolutePath, outFile.length())
            }
        } catch (e: Exception) {
            throw TrimError.StorageWriteFailed(e)
        }
    }

    private fun openOutputStreamWithRetry(itemUri: Uri): OutputStream? {
        // Samsung's MediaProvider can briefly hand out a dead stream right
        // after insert() on Android 13/14, so give the file a moment and try
        // again before giving up.
        repeat(3) { attempt ->
            val stream = runCatching {
                context.contentResolver.openOutputStream(itemUri, "w")
            }.getOrNull()
            if (stream != null) return stream
            if (attempt < 2) {
                Log.w(TAG, "MediaStore write-open failed on attempt ${attempt + 1}, retrying")
                Thread.sleep(75L)
            }
        }
        return null
    }
}

internal fun deriveExtensionAndMime(formatName: String, isVideo: Boolean): Pair<String, String> {
    return when {
        formatName.contains("matroska") && isVideo -> "mkv" to "video/x-matroska"
        formatName.contains("matroska") && !isVideo -> "mka" to "audio/x-matroska"
        formatName.contains("webm") && isVideo -> "webm" to "video/webm"
        formatName.contains("webm") && !isVideo -> "webm" to "audio/webm"
        formatName.contains("mp3") || formatName.startsWith("mp3") -> "mp3" to "audio/mpeg"
        formatName.contains("ogg") -> "ogg" to (if (isVideo) "video/ogg" else "audio/ogg")
        formatName.contains("wav") -> "wav" to "audio/x-wav"
        formatName.contains("flac") -> "flac" to "audio/flac"
        formatName.contains("aac") -> "aac" to "audio/aac"
        formatName.contains("mp4") || formatName.contains("mov") || formatName.contains("m4a") -> {
            if (isVideo) "mp4" to "video/mp4" else "m4a" to "audio/mp4"
        }
        else -> if (isVideo) "mp4" to "video/mp4" else "m4a" to "audio/mp4"
    }
}

internal fun deriveExtensionAndMimeFromMime(mime: String, isVideo: Boolean): Pair<String, String> {
    return when (mime.lowercase()) {
        "video/mp4", "video/x-m4v" -> "mp4" to "video/mp4"
        "video/x-matroska" -> "mkv" to "video/x-matroska"
        "video/webm" -> "webm" to "video/webm"
        "video/quicktime" -> "mov" to "video/quicktime"
        "audio/mpeg", "audio/mp3" -> "mp3" to "audio/mpeg"
        "audio/mp4", "audio/x-m4a" -> "m4a" to "audio/mp4"
        "audio/x-matroska" -> "mka" to "audio/x-matroska"
        "audio/ogg", "application/ogg" -> "ogg" to "audio/ogg"
        "audio/wav", "audio/x-wav" -> "wav" to "audio/x-wav"
        "audio/flac" -> "flac" to "audio/flac"
        "audio/aac" -> "aac" to "audio/aac"
        else -> if (isVideo) "mp4" to "video/mp4" else "m4a" to "audio/mp4"
    }
}

internal fun buildTrimCommand(
    input: String,
    output: String,
    startSec: Double,
    durationSec: Double,
    ext: String
): String {
    return buildString {
        append("-y ")
        append("-ss ").append(startSec).append(" ")
        append("-i ").append(quotePath(input)).append(" ")
        append("-t ").append(durationSec).append(" ")
        append("-c copy ")
        append("-avoid_negative_ts make_zero ")
        if (ext == "mp4" || ext == "m4a" || ext == "mov") {
            append("-movflags +faststart ")
        }
        append(quotePath(output))
    }
}

internal fun quotePath(path: String): String {
    if (path.contains(' ') || path.contains('\'') || path.contains('"')) {
        return "'" + path.replace("'", "'\\''") + "'"
    }
    return path
}