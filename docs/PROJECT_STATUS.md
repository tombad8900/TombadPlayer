# TombadPlayer — Project Status

## 1. Project Overview
TombadPlayer is an Android-native media player project.
Its main purpose is to play video from one complete video stream
and audio from another complete video stream simultaneously.

Both input URLs are full media streams containing video and audio.
The application selects:
- Video track only from Source 1.
- Audio track only from Source 2.

This is not a video-only URL plus an audio-only URL architecture.

## 2. Android Application
- Package: com.tombadplayer
- Application: TombadPlayer
- Native Android interface.
- AndroidX Media3 ExoPlayer.
- No WebView-based playback.
- Minimum SDK: 23.
- Compile SDK: 36.
- Target SDK: 35.
- Java source compatibility: 17.

## 3. Playback Engine
Dependencies:
- androidx.media3:media3-exoplayer:1.11.1
- androidx.media3:media3-exoplayer-hls:1.11.1
- androidx.media3:media3-ui:1.11.1

Two independent ExoPlayer instances are used.

Video player:
- Audio track disabled.
- Displays video through PlayerView.

Audio player:
- Video track disabled.
- Plays audio independently.

HLS streams are supported through Media3 HLS.

## 4. Current User Interface
The PlayerActivity interface includes:
- Video source URL field.
- Audio source URL field.
- File selection for each source.
- Optional HTTP URL permission.
- Original source swap button.
- Independent video source switch button.
- Independent audio source switch button.
- Playback synchronization controls.
- Delay adjustment.
- Synchronization step selection.
- Playback speed control.
- Playback and source status information.

## 5. Source Switching
Three switching controls currently exist:

1. Original "⇄ تبديل" button:
   Swaps the two URL input fields.

2. "تبديل الصورة":
   Switches the active video player between the two original session URLs.
   It does not intentionally change the active audio source.

3. "تبديل الصوت":
   Switches the active audio player between the two original session URLs.
   It does not intentionally change the active video source.

The two independent switches use the original URLs saved when playback starts.

## 6. Synchronization
Current synchronization features:
- Manual audio delay adjustment.
- Positive delay and negative delay.
- Adjustable delay steps: 10 ms, 100 ms, 1000 ms.
- Zero-delay reset.
- Playback speed presets.
- Position synchronization for non-live media.
- Live offset comparison when Media3 provides usable live offset data.
- Audio playback speed adjustments for synchronization.
- Seek-based correction in supported non-live situations.
- Video seek listener to synchronize audio for non-live media.

Independent live streams may have different timelines.
Therefore, accurate synchronization is not guaranteed for every IPTV source.

## 7. Network Recovery
Current recovery features:
- ConnectivityManager network callback.
- Detection of network availability and loss.
- Independent recovery flags for audio and video.
- Separate retry tasks for each player.
- Retry backoff.
- Maximum retry count in the current implementation.
- Handling of Media3 errors.
- Recovery attempt for streams behind the live window.
- Recovery when the activity returns to the foreground.

These mechanisms require testing under real weak-network conditions.
They do not guarantee recovery from expired stream tokens or unavailable providers.

## 8. Settings and Persistence
SharedPreferences stores:
- Video URL.
- Audio URL.
- HTTP permission.
- Audio delay.
- Synchronization step.

Selected document URIs can receive persistent read permission.

## 9. Python and FFmpeg Prototype
The repository also contains an earlier Python/Flask prototype.
It is not the final Android playback interface.

FFmpeg was tested with separate video and audio inputs.
Stream-copy merging and positive/negative timestamp offsets were tested.

FFmpeg and MPV are supporting development tools, not the final application UI.

## 10. Current Development Environment
Development is performed on Android using Termux.
No desktop computer is required.

Known environment:
- Java 21 installed.
- Gradle 9.9.0.
- Android SDK under ~/android-sdk.
- Build tools 36.0.0.
- Android project under ~/TombadPlayer/android.

Build command:
gradle assembleDebug --console=plain

APK destination:
~/storage/downloads/TombadPlayer.apk

## 11. Project Structure
The project contains separate areas for:
- Android application.
- Playback engines.
- Synchronization.
- Subtitle handling.
- Network and HLS handling.
- Playlists.
- Security.
- Plugins.
- Notifications.
- Configuration.
- Tests.
- Documentation.
- Python prototype.

## 12. Remaining Work
Planned or requiring further testing:
- Test independent source switching during real playback.
- Test audio/video synchronization on real live IPTV streams.
- Improve weak-network buffering and recovery based on real test results.
- Improve synchronization across independent live timelines.
- Add and test Arabic subtitle support.
- Test different HLS providers and stream formats.
- Improve error reporting and recovery behavior.
- Validate performance and stability on the target Android device.

## 13. Development Rules
- Keep TombadPlayer separate from CinemaStream projects.
- Preserve existing working features.
- Prefer native Android Media3 playback.
- Test actual stream compatibility before claiming universal support.
- Do not assume every M3U8 stream is live.
- Both input sources are complete video streams containing audio and video.
