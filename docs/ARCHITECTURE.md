# TombadPlayer Architecture

## Main Goal
An Android-native media player capable of playing video and audio
from independent sources, with separate audio/video control and synchronization.

## Core Playback
- Video-only playback from the selected video source.
- Audio-only playback from the selected audio source.
- No unintended mixing of source audio tracks.
- Support for direct media URLs and compatible HLS streams.
- Native Media3 playback on Android.
- FFmpeg and MPV as optional supporting engines.

## Synchronization
- Manual audio delay and advance.
- Automatic audio/video synchronization.
- Continuous drift correction.
- User-controlled synchronization offset.

## Subtitles
- External subtitle files and URLs.
- Arabic and RTL subtitle support.
- Subtitle timing, size, color and background controls.

## Supporting Systems
- Network handling and reconnection.
- M3U and JSON playlists.
- Secure URL validation.
- Notifications and playback events.
- Extensible plugin interfaces.
- Local configuration and user profiles.

## Testing
- Playback compatibility.
- Independent audio/video selection.
- Synchronization accuracy.
- Subtitle rendering.
- Network interruption and recovery.

## Development Rules
- Android is the primary application.
- Do not remove existing working features.
- Do not claim compatibility before practical testing.
- Live playback must not depend on first downloading the entire stream.
- Keep local data and private configuration out of public Git repositories.
