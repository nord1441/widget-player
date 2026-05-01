# widget-player

Android widget-first music player. m3u or folder source, audio-only playback
(video tracks disabled), failure-skip behavior, ipod-shuffle-style minimal UX.

See [`docs/design.md`](docs/design.md) for the full design.

## Project layout

```
app/                      Android module (Kotlin, Media3, Compose)
docs/design.md            Design document (source of truth)
gradle/libs.versions.toml Version catalog
```

## Building

The Gradle wrapper jar is not committed. Generate it once with a local Gradle:

```
gradle wrapper --gradle-version 8.10.2
./gradlew :app:assembleDebug
```

Min SDK 26, target SDK 35.
