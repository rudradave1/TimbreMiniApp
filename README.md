# Timbre Mini App

A small Android media trimmer: pick an audio or video file, choose a start/end
range on a slider, and cut it with FFmpeg. Built in Kotlin with ViewBinding,
a ViewModel + StateFlow, and ExoPlayer for preview playback.

## How it works

- The selected file is copied to a cache dir so FFmpeg gets a real path.
- FFprobe reads the container format to pick the output extension/mime; if the
  probe fails the picker's MIME type is used instead.
- FFmpeg runs with `-c copy`, so the cut is instant and lossless but snaps to
  the nearest keyframe (nothing is re-encoded). `-ss` before `-i` seeks fast
  instead of decoding the whole file. Both patterns come from the [FFmpeg
  seeking wiki](https://trac.ffmpeg.org/wiki/Seeking).
- Failures surface as friendly, localized messages; the raw FFmpeg detail is
  logged under the `MediaTrimmer` tag.
- On Android 10+ the result is published to `Movies/TimbreMiniApp` or
  `Music/TimbreMiniApp` via MediaStore; below that it goes to the app's
  external files dir and is shared through a FileProvider.
- After trimming you can preview in-app, share via the system sheet, or open
  the result in any app (gallery, file manager, video player) with
  "Open with…".
- No storage permission is required on any version: video is picked with the
  system photo picker, audio with `GetContent`, and the result is written to a
  MediaStore row the app owns.

## Build

```
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Unit tests (container→extension mapping, FFmpeg command building):

```
./gradlew testDebugUnitTest
```

## Device & OS notes

> **Not tested on a physical Samsung phone.** All verification was done on
> arm64 Android emulators (API 33/35). Nothing here has been exercised on
> actual Samsung hardware.

- **Scoped storage (Android 10+).** Writing our own MediaStore rows with the
  [`IS_PENDING`](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_PENDING)
  lifecycle is Google's [recommended save
  flow](https://developer.android.com/training/data-storage/shared/media), so
  no `READ_MEDIA_*` or `WRITE_EXTERNAL_STORAGE` permission is needed.
- **Picking media.** Video comes from the [system photo
  picker](https://developer.android.com/training/data-storage/shared/photopicker)
  (`PickVisualMedia`), audio from [`GetContent`](https://developer.android.com/training/data-storage/shared/documents-files).
- **Below Android 10.** The clip is written to the app's [external files
  dir](https://developer.android.com/training/data-storage/app-specific) and
  shared via [FileProvider](https://developer.android.com/training/sharing/send).
- **Samsung One UI (13/14).** Community reports describe `openOutputStream`
  failing right after `insert()` on some Samsung devices. The [contract
  does throw](https://developer.android.com/reference/android/content/ContentResolver#openOutputStream(android.net.Uri, java.lang.String))
  if the row's file isn't ready yet, which fits the classic
  [insert → openOutputStream → clear IS_PENDING](https://stackoverflow.com/questions/61763931)
  pattern. I retry that open 3 times defensively; it is not reproduced or
  validated on Samsung hardware. (See the callout above.)
- **FFmpeg build.** Uses `dev.ffmpegkit-maintained:ffmpeg-kit-free-81`, the
  maintained fork of
  [FFmpegKit (archived, 2025)](https://github.com/arthenica/ffmpeg-kit) from
  [ffmpegkit-maintained](https://github.com/ffmpegkit-maintained/ffmpeg). The
  free tier ships **arm64-v8a only**, which is fine for real phones and
  Apple-Silicon emulators, not Intel x86 emulators.
- **No background services.** Trimming runs in a coroutine, so there is no
  Android 14 foreground-service-type restriction.
- **Rotation.** Not locked; the player and any in-flight export survive
  rotation and fold/split via `configChanges`.
- Requires Android 8.0 (API 26)+.