# Motion Android

v0.2 — a small Android alternative to the Linux `motion` daemon. It turns a phone (for example a spare one) into a motion-triggered video camera with a live browser stream.

## Features
- **Motion detection:** compares the luma of consecutive frames, downscaled to about 320x240. A pixel counts as changed if its brightness differs by more than 24 levels, with a simple despeckle filter. Motion fires when changed pixels >= threshold.
- **Recording:** MP4 (H.264), with no audio, recorded through Camera2 + MediaCodec + MediaMuxer.
- **Pre-capture / post-capture / event gap**, which work like `motion`'s `pre_capture`, `post_capture` and `event_gap`:
  - The encoder runs all the time. A ring buffer keeps the last *pre-capture* ms of encoded video, starting at a keyframe (I-frame every 1 s), so each clip starts before the motion.
  - Frames are written while motion continues and for *post-capture* ms after the last motion.
  - The file stays open until *event gap* seconds pass with no motion. New motion inside the gap continues the **same file**, again with pre-capture. As in `motion`, the quiet part of the gap is skipped, so the video jumps over the idle time.
- **Watermark:** the camera name and `YYYY-MM-DD HH:MM:SS` in white with a black outline, bottom-left. It is burned into the recorded video and the stream.
- **Rotation:** Auto (default) reads how the phone is held when you press Start and keeps it until Stop, so a clip never flips halfway. Manual 0°/90°/180°/270° (clockwise on the camera picture; 90° = portrait on most back cameras) overrides it, e.g. for a phone on a mount or lying flat. Frames are turned upright before encoding, so clips, the stream and the watermark are all upright; 90°/270° swap width and height (640x480 becomes 480x640). Motion detection is unaffected.
- **Maximum clip length:** default 10 minutes, editable. When a clip reaches it, the file is closed at the next keyframe and a new clip starts immediately with the same event, so no frames are lost.
- **Filenames:** `<CameraName>_YYYY_MM_DD_HH-MM-SS.mp4`, using the event start time, e.g. `Front_Door_2026_10_12_09-13-45.mp4`. Spaces become underscores.
- **Live stream:** MJPEG over HTTP, with no password and multiple clients allowed.
- **Background:** a foreground service (type camera) with a partial wake lock and a Wi-Fi lock. It keeps running with the screen off. Use Start/Stop in the app or Stop in the notification.

## Defaults
| Setting | Default | Range |
|---|---|---|
| Camera name | Front_Door | |
| Camera | back | front optional |
| Resolution | 640x480 | 320x240, 640x480, 1280x720, 1920x1080 (only the ones the camera and encoder support) |
| Frame rate | 30 fps | 10/15/24/30/60, falls back to the closest the camera supports |
| Bitrate | 1500 kbps | 500-8000 |
| Threshold | 1000 changed pixels | |
| Analysis rate | 5 fps | 1-15 |
| Pre-capture | 1000 ms | 0-10000 |
| Post-capture | 500 ms | 0-30000 |
| Event gap | 30 s | 0-300 |
| Rotation | Auto | Auto, 0°, 90°, 180°, 270° |
| Maximum clip length | 10 min | 1-120 |
| Stream port | 8081 | 1024-65535 |
| Storage limit | 2048 MB | oldest clips are deleted beyond this |

## Where clips are saved
- **Android 10+:** `Movies/MotionAndroid` through MediaStore. Clips show up in the gallery, and no storage permission is needed.
- **Android 8-9:** `Android/data/com.jorteron.motionandroid/files/Movies/MotionAndroid` (app storage, no permission needed; removed on uninstall).
- **Any version:** Settings → Pick folder lets you choose any folder, such as an SD card, through the system picker.

## Viewing the stream
Press Start, then open the URL shown on the main screen, e.g. `http://192.168.1.5:8081`, in a browser on the same Wi-Fi.
- `/` or `/stream`: the live MJPEG stream (about 5 fps)
- `/snapshot.jpg`: the current frame
In VLC, use Media → Open Network Stream → `http://IP:8081/stream`.

## Notes
- In Settings, use "Battery optimisation" to exempt the app, so aggressive battery savers don't kill it.
- Frames are processed on the CPU. 640x480 at 30 fps is the target; 1080p/60 may drop frames on slower phones.

## Build
```
JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug
```
Java only: AGP 8.5.2, Gradle 8.7, minSdk 26, targetSdk 34, no external libraries.
