# Timbre Mini App

A small Android media trimmer: pick an audio or video file, choose a start/end
range on a slider, and cut it with FFmpeg. Built in Kotlin with ViewBinding,
a ViewModel + StateFlow, and ExoPlayer for preview playback.

## How it works

- The selected file is copied to a cache dir so FFmpeg gets a real path.
- FFprobe reads the container format to pick the right output extension/mime;
  if the probe fails the picker's MIME type is used instead.
- FFmpeg runs with `-c copy` (stream copy): instant and lossless, but the cut
  snaps to the nearest keyframe since nothing is re-encoded. The stream keeps
  its original codec, so playback behaves like the source file.
- Failure branches throw typed `TrimError`s which are mapped to friendly,
  localized messages in the UI; the raw FFmpeg detail is logged.
- On Android 10+ the result is published to `Movies/TimbreMiniApp` or
  `Music/TimbreMiniApp` via MediaStore (no permissions needed). Below that it
  goes to the app's external files dir and is shared through a FileProvider.
- No `WRITE_EXTERNAL_STORAGE` permission is required on any version; media is
  picked with `GetContent` / the system photo picker.
- The screen rotates freely; the player and any in-flight export survive
  rotation and fold/split changes via `configChanges`.

## Build

```
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Unit tests (container→extension mapping, FFmpeg command building, error
mapping):

```
./gradlew testDebugUnitTest
```

## Device & OS notes

- **Android 13/14 / scoped storage.** Media is picked with the system photo
  picker (`GetContent` falls back to the system document picker on older
  Samsung devices) and written back through `MediaStore` rows the app owns —
  no `READ_MEDIA_*` or `WRITE_EXTERNAL_STORAGE` permission is needed on any
  version.
- **Samsung One UI (Android 13/14).** Samsung's `MediaProvider` can briefly
  hand out a dead stream immediately after `insert()`, a known issue where the
  next `openOutputStream` throws. `MediaTrimmer` retries that open 3 times with
  a short delay and logs under the `MediaTrimmer` tag (Logcat) if it still
  fails. Verified on arm64 emulators; no physical Samsung was available, so the
  retry path is diagnostic rather than device-tested.
- **FFmpeg ABI.** FFmpeg is bundled via `dev.ffmpegkit-maintained:
  ffmpeg-kit-free-81` (LGPL). The free tier ships **arm64-v8a only**, so it
  runs on any real phone — including all current Samsung Snapdragon/Exynos
  devices — and on Apple-Silicon emulators, but not on Intel x86 emulators.
- **No background services.** Trimming runs in a coroutine, not a foreground
  service, so there is no Android 14 foreground-service-type restriction.
- **Large screens.** Rotation is not locked so the app re-flows on landscape,
  foldables, and split-screen instead of being forced to portrait.
- Requires Android 8.0 (API 26)+.