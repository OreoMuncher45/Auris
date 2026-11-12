# Auri PORT iOS

Native iOS rebuild of Auris, independent from Android and Kotlin Multiplatform.

- Swift 5 + SwiftUI
- AVFoundation playback foundation
- MediaPlayer Lock Screen / Control Center commands
- Local audio import and persistent library
- Home, Library, Search and Settings
- iOS 16.0+

Open `Auri PORT iOS.xcodeproj` in Xcode.

## Port status
This commit establishes the native iOS app shell and local playback foundation. It does not claim full feature parity yet. FLAC decoding for arbitrary local/remote files, streaming sources/addons, lyrics, downloads, playlists, scrobbling, Listen Together, Automix, Classipod and audio analysis remain future porting milestones. AVPlayer support for HLS FLAC does not imply support for arbitrary progressive FLAC files, so FLAC will be integrated through a separately verified decoder.

No Kotlin or Android module is part of this Xcode target.
