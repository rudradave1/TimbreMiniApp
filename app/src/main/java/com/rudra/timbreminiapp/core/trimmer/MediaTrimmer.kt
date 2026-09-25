package com.rudra.timbreminiapp.core.trimmer

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
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.rudra.timbreminiapp.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import kotlin.coroutines.resume

private const val TAG = "MediaTrimmer"

// FFmpeg needs real paths, so we copy then probe
class MediaTrimmer(private val context: Context) {

    data class TrimResult(
        val publicUri: Uri,
        val displayName: String,
        val mimeType: String,
        val pathDescription: String,
        val sizeBytes: Long
    )

suspend fun trimMedia(
            sourceUri: Uri,
            startMs: Long,
            endMs: Long,
            isVideo: Boolean,
            onProgress: (Float) -> Unit = {}
        ): Result<TrimResult> = withContext(Dispatchers.IO) {
            try {
                // Content URIs aren't real paths, copy to cache first
                val inputTemp = copyToTemp(sourceUri) ?: throw TrimError(R.string.error_read_failed)
                try {
                    val sourceMime = runCatching { context.contentResolver.getType(sourceUri) }.getOrNull()

                    // Probe the real container, the picker MIME is just a fallback
                    val mediaInfo = runCatching {
                        FFprobeKit.getMediaInformation(inputTemp.absolutePath)?.mediaInformation
                    }.getOrNull()
                val formatName = mediaInfo?.format?.lowercase().orEmpty()
                val (ext, mime) = if (formatName.isBlank() && sourceMime != null) {
                    deriveExtensionAndMimeFromMime(sourceMime, isVideo)
                } else {
                    deriveExtensionAndMime(formatName, isVideo)
                }

                if (endMs - startMs < 1000L) throw TrimError(R.string.error_min_length)

                val clipMs = endMs - startMs
                val startSec = startMs / 1000.0
                val durationSec = clipMs / 1000.0
                val outTemp = File(context.cacheDir, "trimmed_out_${System.currentTimeMillis()}.$ext")

                // Stream copy snaps to the nearest keyframe but is fast and lossless
                val cmd = buildTrimCommand(
                    input = inputTemp.absolutePath,
                    output = outTemp.absolutePath,
                    startSec = startSec,
                    durationSec = durationSec,
                    ext = ext
                )

                when (val outcome = executeFfmpeg(cmd, clipMs, onProgress)) {
                    is FfmpegOutcome.Success -> Unit
                    is FfmpegOutcome.Failed -> {
                        outTemp.delete()
                        Log.e(TAG, "FFmpeg exit ${outcome.exitCode}: ${outcome.detail}")
                        throw TrimError(R.string.error_unsupported, outcome.detail)
                    }
                }

                if (!outTemp.exists() || outTemp.length() == 0L) {
                    outTemp.delete()
                    // Exit 0 but no output, probably bad bounds
                    throw TrimError(R.string.error_empty_output)
                }

                try {
                    val result = try {
                        saveToMediaStore(outTemp, mime, ext, isVideo)
                    } catch (e: Exception) {
                        throw TrimError(R.string.error_storage_write, e.message)
                    }
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
        data class Failed(val exitCode: Long, val detail: String?) : FfmpegOutcome()
    }

    private suspend fun executeFfmpeg(
        cmd: String,
        clipMs: Long,
        onProgress: (Float) -> Unit
    ): FfmpegOutcome =
        suspendCancellableCoroutine { cont ->
            // Callback is global, exports are single-flight
            FFmpegKitConfig.enableStatisticsCallback { stats ->
                onProgress(((stats?.time ?: 0L).toFloat() / clipMs).coerceIn(0f, 1f))
            }
            val session: FFmpegSession = FFmpegKit.executeAsync(
                cmd,
                { s ->
                    FFmpegKitConfig.enableStatisticsCallback(null)
                    if (cont.isActive) {
                        cont.resume(
                            if (ReturnCode.isSuccess(s.returnCode)) {
                                FfmpegOutcome.Success
                            } else {
                                FfmpegOutcome.Failed(
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

        // MediaStore for Q+, FileProvider below
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.SIZE, tempFile.length())
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/TimbreMiniApp"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val itemUri = context.contentResolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            try {
                val outStream = openOutputStreamWithRetry(itemUri)
                    ?: throw IllegalStateException("Couldn't open output stream for $itemUri")
                outStream.use { stream ->
                    FileInputStream(tempFile).use { input -> input.copyTo(stream) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
            } catch (e: Exception) {
                // Don't leave an invisible IS_PENDING=1 row behind on failure.
                runCatching { context.contentResolver.delete(itemUri, null, null) }
                throw e
            }
            val pathDesc = if (isVideo) "Movies/TimbreMiniApp/$displayName" else "Music/TimbreMiniApp/$displayName"
            TrimResult(itemUri, displayName, mime, pathDesc, tempFile.length())
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
            TrimResult(shareUri, displayName, mime, outFile.absolutePath, outFile.length())
        }
    }

    private fun openOutputStreamWithRetry(itemUri: Uri): OutputStream? {
        // Dead stream right after insert on some devices, retry
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
        // FFprobe reports webm as "matroska,webm", check it first
        formatName.contains("webm") && isVideo -> "webm" to "video/webm"
        formatName.contains("webm") && !isVideo -> "webm" to "audio/webm"
        formatName.contains("matroska") && isVideo -> "mkv" to "video/x-matroska"
        formatName.contains("matroska") && !isVideo -> "mka" to "audio/x-matroska"
        formatName.contains("mp3") -> "mp3" to "audio/mpeg"
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
        // -c copy is fast but cuts on keyframes
        append("-c copy ")
        // make_zero for negative PTS, +faststart for the mp4 family
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