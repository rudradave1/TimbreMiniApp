package com.rudra.timbreminiapp.trimmer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlin.math.max

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
            val inputTemp = copyToTemp(sourceUri) ?: return@withContext Result.failure(
                IllegalStateException("Failed to read input file")
            )

            val mediaInfo = runCatching {
                FFprobeKit.getMediaInformation(inputTemp.absolutePath)?.mediaInformation
            }.getOrNull()

            val formatName = mediaInfo?.format?.lowercase() ?: ""
            val (ext, mime) = deriveExtensionAndMime(formatName, isVideo)

            val durationMs = max(0, endMs - startMs)
            if (durationMs < 1000L) {
                inputTemp.delete()
                return@withContext Result.failure(IllegalArgumentException("Invalid trim range"))
            }

            val startSec = (startMs / 1000.0)
            val durationSec = (durationMs / 1000.0)
            val outTemp = File(context.cacheDir, "trimmed_out_${System.currentTimeMillis()}.$ext")

            // Stream copy (-c copy) is instant and lossless, but the cut snaps
            // to the nearest keyframe since nothing is re-encoded. -ss before
            // -i does a fast seek instead of decoding the whole file, and
            // +faststart relocates the moov atom so mp4/m4a/mov play while
            // still downloading.
            val cmd = buildTrimCommand(
                input = inputTemp.absolutePath,
                output = outTemp.absolutePath,
                startSec = startSec,
                durationSec = durationSec,
                ext = ext
            )

            val sessionResult = executeFfmpeg(cmd)
            inputTemp.delete()

            if (!sessionResult.success) {
                outTemp.delete()
                return@withContext Result.failure(
                    sessionResult.error ?: IllegalStateException("FFmpeg trim failed")
                )
            }

            if (!outTemp.exists() || outTemp.length() == 0L) {
                outTemp.delete()
                return@withContext Result.failure(IllegalStateException("Trimmed file is empty"))
            }

            val public = saveToMediaStore(outTemp, mime, ext, isVideo)
            outTemp.delete()

            public.fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeFfmpeg(cmd: String): FfmpegExecResult =
        suspendCancellableCoroutine { cont ->
            val session: FFmpegSession = FFmpegKit.executeAsync(
                cmd,
                { s ->
                    if (cont.isActive) {
                        val rc = s.returnCode
                        if (ReturnCode.isSuccess(rc)) {
                            cont.resume(FfmpegExecResult(true, null))
                        } else {
                            val exitCode = s.returnCode.value
                            val detail = s.failStackTrace
                            val message = when {
                                ReturnCode.isCancel(s.returnCode) -> "Trim cancelled"
                                !detail.isNullOrBlank() -> detail.takeLast(300)
                                else -> "FFmpeg exited with code $exitCode"
                            }
                            cont.resume(FfmpegExecResult(false, IllegalStateException(message)))
                        }
                    }
                }
            )
            cont.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
            }
        }

    private data class FfmpegExecResult(
        val success: Boolean,
        val error: Throwable?
    )

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
    ): Result<TrimResult> {
        val displayName = "trimmed_${System.currentTimeMillis()}.$ext"
        return try {
            // Q+ publishes the file to shared storage via MediaStore with no
            // permissions asked; below Q we write to the app's own external
            // files dir instead and share it through a FileProvider.
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
                    ?: return Result.failure(IllegalStateException("Failed to create media entry"))
                val outStream = context.contentResolver.openOutputStream(itemUri, "w")
                    ?: return Result.failure(IllegalStateException("Failed to open output stream"))
                outStream.use { stream ->
                    FileInputStream(tempFile).use { input -> input.copyTo(stream) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(itemUri, values, null, null)
                val pathDesc = if (isVideo) "Movies/TimbreMiniApp/$displayName" else "Music/TimbreMiniApp/$displayName"
                Result.success(TrimResult(itemUri, displayName, pathDesc, tempFile.length()))
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
                Result.success(TrimResult(shareUri, displayName, outFile.absolutePath, outFile.length()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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