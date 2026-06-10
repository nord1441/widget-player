# Shuffleプレイヤー再実装 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** スペック `docs/superpowers/specs/2026-06-10-shuffle-player-design.md` に基づき、ウィジェット主体・m3uベースの最小Android音楽プレイヤーをゼロから実装する。

**Architecture:** 純Kotlinの`:core`モジュール(パース・順序・分類ロジック、TDD)と、薄いAndroidシェル`:app`(Media3 MediaSessionService、SAF、ウィジェット、Compose設定画面)。ウィジェット操作はMedia3の`MediaButtonReceiver`+`onPlaybackResumption`パターンで、フォアグラウンド管理をMedia3に委譲する。

**Tech Stack:** Kotlin 2.0 / AGP 8.7 / Media3 1.5 (ExoPlayer+MediaSessionService+HLS) / Jetpack Compose / Room / kotlinx.serialization / JUnit 5

**前提:**
- 作業ブランチ: `claude/shuffle-player-rewrite`(チェックアウト済み)
- 実機 Unihertz A024 (Android 16 / API 36) がadb接続済み。シリアル `00028258I000050`
- `local.properties` は作業ツリーに存在(SDKパス設定済み)
- 旧実装はブランチ `claude/android-shuffle-player-HMbJ7` にあり、Gradleラッパー・アイコン素材はそこから流用する
- 実機検証で「ユーザー操作が必要」と記した箇所は、ユーザーに依頼して結果を確認すること

---

## Task 1: プロジェクトスキャフォールド(2モジュール)

**Files:**
- Restore from old branch: `gradlew`, `gradlew.bat`, `gradle/wrapper/`, `gradle.properties`, `.gitignore`, `app/src/main/res/drawable/`, `app/src/main/res/mipmap-anydpi-v26/`, `app/src/main/res/values/colors.xml`
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`
- Create: `core/build.gradle.kts`, `core/src/test/kotlin/com/example/shuffleplayer/core/SmokeTest.kt`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/example/shuffleplayer/MainActivity.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`

- [ ] **Step 1: 旧ブランチからツール・素材を復元**

```bash
git checkout claude/android-shuffle-player-HMbJ7 -- gradlew gradlew.bat gradle/wrapper gradle.properties .gitignore \
  app/src/main/res/drawable app/src/main/res/mipmap-anydpi-v26 app/src/main/res/values/colors.xml
chmod +x gradlew
```

- [ ] **Step 2: `settings.gradle.kts` を作成**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ShufflePlayer"
include(":app")
include(":core")
```

- [ ] **Step 3: `gradle/libs.versions.toml` を作成(全文)**

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.27"
coreKtx = "1.13.1"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.10.01"
media3 = "1.5.0"
room = "2.6.1"
kotlinxSerialization = "1.7.3"
junitJupiter = "5.11.3"
junitPlatformLauncher = "1.11.3"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-compose-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
androidx-media3-exoplayer-hls = { group = "androidx.media3", name = "media3-exoplayer-hls", version.ref = "media3" }
androidx-media3-session = { group = "androidx.media3", name = "media3-session", version.ref = "media3" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
junit-jupiter = { group = "org.junit.jupiter", name = "junit-jupiter", version.ref = "junitJupiter" }
junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version.ref = "junitPlatformLauncher" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 4: ルート `build.gradle.kts` を作成**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 5: `core/build.gradle.kts` を作成**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

- [ ] **Step 6: `core/src/test/kotlin/com/example/shuffleplayer/core/SmokeTest.kt` を作成(配線確認用、後のタスクで削除)**

```kotlin
package com.example.shuffleplayer.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

class SmokeTest {
    @Test
    fun junitRuns() {
        assertTrue(true)
    }
}
```

- [ ] **Step 7: `app/build.gradle.kts` を作成**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.shuffleplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.shuffleplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
```

- [ ] **Step 8: `app/src/main/AndroidManifest.xml` を作成(最終形。サービス・レシーバ・権限を最初から宣言しておく)**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.ShufflePlayer"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>

            <!-- m3uファイル -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:mimeType="audio/x-mpegurl" />
                <data android:mimeType="audio/mpegurl" />
                <data android:mimeType="application/vnd.apple.mpegurl" />
                <data android:mimeType="application/x-mpegurl" />
            </intent-filter>

            <!-- 音声・動画ファイル -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:mimeType="audio/*" />
                <data android:mimeType="video/*" />
            </intent-filter>

            <!-- ストリーミングURL -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="http" />
                <data android:scheme="https" />
            </intent-filter>
        </activity>

        <service
            android:name=".playback.PlaybackService"
            android:exported="true"
            android:foregroundServiceType="mediaPlayback">
            <intent-filter>
                <action android:name="androidx.media3.session.MediaSessionService" />
                <action android:name="android.media.browse.MediaBrowserService" />
            </intent-filter>
        </service>

        <receiver
            android:name="androidx.media3.session.MediaButtonReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MEDIA_BUTTON" />
            </intent-filter>
        </receiver>

        <receiver
            android:name=".widget.PlayerWidgetProvider"
            android:exported="true">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_info" />
        </receiver>

    </application>

</manifest>
```

注意: この時点では `.playback.PlaybackService`・`.widget.PlayerWidgetProvider`・`@xml/widget_info` が未実装なのでビルドが通らない。このタスクでは **`<service>`要素と`.widget.PlayerWidgetProvider`の`<receiver>`要素を一時的にコメントアウト**し、Task 10(サービス)とTask 12(ウィジェット)でコメントを外すこと。`androidx.media3.session.MediaButtonReceiver`はライブラリ由来のクラスなのでコメントアウト不要。

- [ ] **Step 9: `app/src/main/res/values/strings.xml` を作成**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Shuffle Player</string>
    <string name="no_source">ソース未設定</string>
    <string name="playback_unavailable">再生不可: 接続を確認</string>
    <string name="notification_channel_name">再生</string>
</resources>
```

- [ ] **Step 10: `app/src/main/res/values/themes.xml` を作成**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ShufflePlayer" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 11: `app/src/main/kotlin/com/example/shuffleplayer/MainActivity.kt` を作成(暫定)**

```kotlin
package com.example.shuffleplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Text("Shuffle Player")
            }
        }
    }
}
```

- [ ] **Step 12: ビルドとテスト実行**

```bash
./gradlew :core:test :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`、SmokeTest 1件パス

- [ ] **Step 13: 実機インストールと起動確認**

```bash
./gradlew :app:installDebug
adb shell am start -n com.example.shuffleplayer/.MainActivity
adb shell "sleep 2; dumpsys activity activities | grep -c shuffleplayer"
```
Expected: インストール成功、最後のコマンドが1以上(Activityがフォアグラウンド)

- [ ] **Step 14: Commit**

```bash
git add -A
git commit -m "Scaffold two-module project (:core pure Kotlin + :app Android shell)"
```

---

## Task 2: M3uParser (:core, TDD)

**Files:**
- Create: `core/src/main/kotlin/com/example/shuffleplayer/core/M3u.kt`
- Test: `core/src/test/kotlin/com/example/shuffleplayer/core/M3uParserTest.kt`
- Delete: `core/src/test/kotlin/com/example/shuffleplayer/core/SmokeTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.example.shuffleplayer.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class M3uParserTest {

    @Test
    fun `素のパス列をエントリにする`() {
        val entries = M3uParser.parse("a.mp3\nb.mp3\n")
        assertEquals(listOf("a.mp3", "b.mp3"), entries.map { it.location })
        assertNull(entries[0].label)
    }

    @Test
    fun `EXTINFのラベルと長さを拾う`() {
        val text = """
            #EXTM3U
            #EXTINF:213,Artist - Title
            song.mp3
        """.trimIndent()
        val entries = M3uParser.parse(text)
        assertEquals(1, entries.size)
        assertEquals("song.mp3", entries[0].location)
        assertEquals("Artist - Title", entries[0].label)
        assertEquals(213, entries[0].durationSeconds)
    }

    @Test
    fun `EXTINFは直後のエントリにだけ効く`() {
        val text = "#EXTINF:10,One\none.mp3\ntwo.mp3"
        val entries = M3uParser.parse(text)
        assertEquals("One", entries[0].label)
        assertNull(entries[1].label)
    }

    @Test
    fun `BOMとCRLFと空行とコメントを無視する`() {
        val text = "\uFEFF#EXTM3U\r\n\r\n# comment\r\nsong.mp3\r\n"
        val entries = M3uParser.parse(text)
        assertEquals(listOf("song.mp3"), entries.map { it.location })
    }

    @Test
    fun `EXTINFの小数・負の長さを許容する`() {
        assertEquals(123, M3uParser.parse("#EXTINF:123.5,X\na.mp3")[0].durationSeconds)
        assertEquals(-1, M3uParser.parse("#EXTINF:-1,X\na.mp3")[0].durationSeconds)
    }

    @Test
    fun `EXTINFにカンマが無くても落ちない`() {
        val entries = M3uParser.parse("#EXTINF:99\na.mp3")
        assertEquals(99, entries[0].durationSeconds)
        assertNull(entries[0].label)
    }

    @Test
    fun `空テキストは空リスト`() {
        assertEquals(emptyList<M3uEntry>(), M3uParser.parse(""))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

```bash
./gradlew :core:test
```
Expected: FAIL — `Unresolved reference: M3uParser`(コンパイルエラー)

- [ ] **Step 3: 最小実装を書く — `core/src/main/kotlin/com/example/shuffleplayer/core/M3u.kt`**

```kotlin
package com.example.shuffleplayer.core

data class M3uEntry(
    val location: String,
    val label: String? = null,
    val durationSeconds: Int? = null,
)

object M3uParser {
    private const val EXTINF = "#EXTINF:"

    fun parse(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var label: String? = null
        var duration: Int? = null
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith(EXTINF, ignoreCase = true) -> {
                    val body = line.substring(EXTINF.length)
                    val comma = body.indexOf(',')
                    val durationPart = if (comma >= 0) body.take(comma) else body
                    duration = durationPart.trim().toDoubleOrNull()?.toInt()
                    label = if (comma >= 0) body.substring(comma + 1).trim().ifEmpty { null } else null
                }
                line.startsWith("#") -> Unit
                else -> {
                    entries += M3uEntry(line, label, duration)
                    label = null
                    duration = null
                }
            }
        }
        return entries
    }
}
```

- [ ] **Step 4: テストがパスすることを確認、SmokeTest削除**

```bash
rm core/src/test/kotlin/com/example/shuffleplayer/core/SmokeTest.kt
./gradlew :core:test
```
Expected: PASS(7件)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add M3uParser with EXTINF, BOM, CRLF handling (TDD)"
```

---

## Task 3: Locations / EntryResolver (:core, TDD)

**Files:**
- Create: `core/src/main/kotlin/com/example/shuffleplayer/core/Locations.kt`
- Test: `core/src/test/kotlin/com/example/shuffleplayer/core/LocationsTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.example.shuffleplayer.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class LocationsTest {

    @Test
    fun `locationの分類`() {
        assertEquals(LocationKind.HTTP_URL, Locations.classify("http://host/a.mp3"))
        assertEquals(LocationKind.HTTP_URL, Locations.classify("HTTPS://host/a.mp3"))
        assertEquals(LocationKind.CONTENT_URI, Locations.classify("content://authority/doc/1"))
        assertEquals(LocationKind.ABSOLUTE_PATH, Locations.classify("/storage/emulated/0/a.mp3"))
        assertEquals(LocationKind.RELATIVE_PATH, Locations.classify("sub/a.mp3"))
        assertEquals(LocationKind.RELATIVE_PATH, Locations.classify("a.mp3"))
    }

    @Test
    fun `相対パスの結合`() {
        assertEquals("Music/sub/a.mp3", Locations.joinRelative("Music", "sub/a.mp3"))
        assertEquals("Music/a.mp3", Locations.joinRelative("Music", "./a.mp3"))
        assertEquals("a.mp3", Locations.joinRelative("Music", "../a.mp3"))
        assertEquals("Music/bar/x.mp3", Locations.joinRelative("Music/foo", "../bar/x.mp3"))
        assertEquals("a.mp3", Locations.joinRelative("", "a.mp3"))
        assertEquals("x.mp3", Locations.joinRelative("Music", "../../x.mp3"))
    }

    @Test
    fun `バックスラッシュ区切りの相対パスを受け付ける`() {
        assertEquals("Music/sub/a.mp3", Locations.joinRelative("Music", "sub\\a.mp3"))
    }

    @Test
    fun `EntryResolverはhttpとcontentを素通しし絶対パスをfileにする`() {
        val resolver = EntryResolver { null }
        val tracks = resolver.resolve(
            listOf(
                M3uEntry("http://host/s.mp3", "Stream"),
                M3uEntry("content://auth/doc/1"),
                M3uEntry("/storage/emulated/0/a.mp3"),
            )
        )
        assertEquals(
            listOf("http://host/s.mp3", "content://auth/doc/1", "file:///storage/emulated/0/a.mp3"),
            tracks.map { it.uri },
        )
        assertEquals("Stream", tracks[0].label)
    }

    @Test
    fun `EntryResolverは相対パスをPathResolverに委譲し解決不能はスキップ`() {
        val resolver = EntryResolver { rel -> if (rel == "ok.mp3") "content://auth/tree/ok" else null }
        val tracks = resolver.resolve(listOf(M3uEntry("ok.mp3"), M3uEntry("ng.mp3")))
        assertEquals(listOf("content://auth/tree/ok"), tracks.map { it.uri })
    }
}
```

注意: `Track` はTask 8で定義するが、このタスクで先に最小定義する(`Locations.kt`内ではなく専用ファイルに置く。Step 3参照)。

- [ ] **Step 2: テストが失敗することを確認**

```bash
./gradlew :core:test
```
Expected: FAIL — `Unresolved reference: Locations`

- [ ] **Step 3: 最小実装 — まず `core/src/main/kotlin/com/example/shuffleplayer/core/Model.kt` に `Track` の最小定義**

```kotlin
package com.example.shuffleplayer.core

data class Track(val uri: String, val label: String? = null)
```

(Task 8でこのファイルに `@Serializable` と他モデルを追加する)

次に `core/src/main/kotlin/com/example/shuffleplayer/core/Locations.kt`:

```kotlin
package com.example.shuffleplayer.core

enum class LocationKind { ABSOLUTE_PATH, RELATIVE_PATH, HTTP_URL, CONTENT_URI }

object Locations {
    fun classify(location: String): LocationKind = when {
        location.startsWith("http://", ignoreCase = true) ||
            location.startsWith("https://", ignoreCase = true) -> LocationKind.HTTP_URL
        location.startsWith("content://") -> LocationKind.CONTENT_URI
        location.startsWith("/") -> LocationKind.ABSOLUTE_PATH
        else -> LocationKind.RELATIVE_PATH
    }

    /** "Music/foo" + "../bar/x.mp3" → "Music/bar/x.mp3"。区切りは / と \ の両方を許容 */
    fun joinRelative(baseDir: String, relative: String): String {
        val segments = baseDir.split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in relative.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
                else -> segments.add(seg)
            }
        }
        return segments.joinToString("/")
    }
}

/** 相対locationを再生可能なURI文字列に解決する。解決不能ならnull */
fun interface PathResolver {
    fun resolveRelative(relative: String): String?
}

class EntryResolver(private val pathResolver: PathResolver) {
    /** 解決不能なエントリは結果から落とす(失敗即スキップの第一段) */
    fun resolve(entries: List<M3uEntry>): List<Track> = entries.mapNotNull { entry ->
        when (Locations.classify(entry.location)) {
            LocationKind.HTTP_URL, LocationKind.CONTENT_URI -> Track(entry.location, entry.label)
            LocationKind.ABSOLUTE_PATH -> Track("file://" + entry.location, entry.label)
            LocationKind.RELATIVE_PATH ->
                pathResolver.resolveRelative(entry.location)?.let { Track(it, entry.label) }
        }
    }
}
```

- [ ] **Step 4: テストがパスすることを確認**

```bash
./gradlew :core:test
```
Expected: PASS(11件)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add location classification, relative path join, entry resolver (TDD)"
```

---

## Task 4: MediaFileFilter (:core, TDD)

**Files:**
- Create: `core/src/main/kotlin/com/example/shuffleplayer/core/MediaFileFilter.kt`
- Test: `core/src/test/kotlin/com/example/shuffleplayer/core/MediaFileFilterTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.example.shuffleplayer.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class MediaFileFilterTest {

    @Test
    fun `音声と動画の拡張子をメディアと判定する`() {
        for (name in listOf("a.mp3", "b.M4A", "c.flac", "d.opus", "e.wav", "f.mp4", "g.mkv", "h.webm")) {
            assertTrue(MediaFileFilter.isMedia(name), name)
        }
    }

    @Test
    fun `メディア以外とプレイリストはメディアでない`() {
        for (name in listOf("cover.jpg", "list.m3u", "list.m3u8", "note.txt", "noext")) {
            assertFalse(MediaFileFilter.isMedia(name), name)
        }
    }

    @Test
    fun `プレイリスト判定`() {
        assertTrue(MediaFileFilter.isPlaylist("list.m3u"))
        assertTrue(MediaFileFilter.isPlaylist("list.M3U8"))
        assertFalse(MediaFileFilter.isPlaylist("a.mp3"))
    }
}
```

- [ ] **Step 2: テストが失敗することを確認**

```bash
./gradlew :core:test
```
Expected: FAIL — `Unresolved reference: MediaFileFilter`

- [ ] **Step 3: 最小実装 — `core/src/main/kotlin/com/example/shuffleplayer/core/MediaFileFilter.kt`**

```kotlin
package com.example.shuffleplayer.core

object MediaFileFilter {
    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "mka")
    private val videoExtensions = setOf("mp4", "mkv", "webm", "mov", "avi")
    private val playlistExtensions = setOf("m3u", "m3u8")

    fun isMedia(fileName: String): Boolean =
        extensionOf(fileName).let { it in audioExtensions || it in videoExtensions }

    fun isPlaylist(fileName: String): Boolean = extensionOf(fileName) in playlistExtensions

    private fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()
}
```

- [ ] **Step 4: テストがパス確認 → Commit**

```bash
./gradlew :core:test
git add -A
git commit -m "Add media file extension filter (TDD)"
```
Expected: PASS(14件)

---

## Task 5: ShuffleOrder (:core, TDD)

**Files:**
- Create: `core/src/main/kotlin/com/example/shuffleplayer/core/ShuffleOrder.kt`
- Test: `core/src/test/kotlin/com/example/shuffleplayer/core/ShuffleOrderTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.example.shuffleplayer.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals

class ShuffleOrderTest {

    @Test
    fun `全indexを一度ずつ含む順列を返す`() {
        val order = ShuffleOrder.order(10, seed = 42L)
        assertEquals((0 until 10).toSet(), order.toSet())
        assertEquals(10, order.size)
    }

    @Test
    fun `同じsizeとseedなら同じ順序(再起動後の再現性)`() {
        assertEquals(ShuffleOrder.order(50, 7L), ShuffleOrder.order(50, 7L))
    }

    @Test
    fun `異なるseedなら(ほぼ確実に)異なる順序`() {
        assertNotEquals(ShuffleOrder.order(50, 1L), ShuffleOrder.order(50, 2L))
    }

    @Test
    fun `境界 - 空と1件`() {
        assertEquals(emptyList<Int>(), ShuffleOrder.order(0, 1L))
        assertEquals(listOf(0), ShuffleOrder.order(1, 1L))
    }
}
```

- [ ] **Step 2: 失敗確認**

```bash
./gradlew :core:test
```
Expected: FAIL — `Unresolved reference: ShuffleOrder`

- [ ] **Step 3: 最小実装 — `core/src/main/kotlin/com/example/shuffleplayer/core/ShuffleOrder.kt`**

```kotlin
package com.example.shuffleplayer.core

import kotlin.random.Random

object ShuffleOrder {
    /** 0..size-1 の順列。同じ size + seed なら常に同一(再起動後の再現性) */
    fun order(size: Int, seed: Long): List<Int> = (0 until size).shuffled(Random(seed))
}
```

- [ ] **Step 4: パス確認 → Commit**

```bash
./gradlew :core:test
git add -A
git commit -m "Add seeded reproducible shuffle order (TDD)"
```
Expected: PASS(18件)

---

## Task 6: FailureGuard (:core, TDD)

**Files:**
- Create: `core/src/main/kotlin/com/example/shuffleplayer/core/FailureGuard.kt`
- Test: `core/src/test/kotlin/com/example/shuffleplayer/core/FailureGuardTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.example.shuffleplayer.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class FailureGuardTest {

    @Test
    fun `5回連続失敗で停止を指示する`() {
        val guard = FailureGuard()
        repeat(4) { assertFalse(guard.onFailure()) }
        assertTrue(guard.onFailure())
    }

    @Test
    fun `成功でカウンタがリセットされる`() {
        val guard = FailureGuard()
        repeat(4) { guard.onFailure() }
        guard.onSuccess()
        repeat(4) { assertFalse(guard.onFailure()) }
        assertTrue(guard.onFailure())
    }

    @Test
    fun `上限はコンストラクタで変えられる`() {
        val guard = FailureGuard(limit = 2)
        assertFalse(guard.onFailure())
        assertTrue(guard.onFailure())
    }
}
```

- [ ] **Step 2: 失敗確認**

```bash
./gradlew :core:test
```
Expected: FAIL — `Unresolved reference: FailureGuard`

- [ ] **Step 3: 最小実装 — `core/src/main/kotlin/com/example/shuffleplayer/core/FailureGuard.kt`**

```kotlin
package com.example.shuffleplayer.core

/** 連続失敗ガード。limit回連続で失敗したら停止を指示する */
class FailureGuard(private val limit: Int = 5) {
    private var consecutiveFailures = 0

    /** @return 停止すべきなら true */
    fun onFailure(): Boolean {
        consecutiveFailures++
        return consecutiveFailures >= limit
    }

    fun onSuccess() {
        consecutiveFailures = 0
    }
}
```

- [ ] **Step 4: パス確認 → Commit**

```bash
./gradlew :core:test
git add -A
git commit -m "Add consecutive failure guard (TDD)"
```
Expected: PASS(21件)

---

## Task 7: ErrorClassifier (:core, TDD)

**Files:**
- Create: `core/src/main/kotlin/com/example/shuffleplayer/core/ErrorClassifier.kt`
- Test: `core/src/test/kotlin/com/example/shuffleplayer/core/ErrorClassifierTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.example.shuffleplayer.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ErrorClassifierTest {

    @Test
    fun `PlaybackExceptionのコード帯域で分類する`() {
        // Media3 PlaybackException: 2xxx=IO, 3xxx=PARSING, 4xxx=DECODER
        assertEquals(ErrorCategory.IO, ErrorClassifier.classify(2001))       // IO_NETWORK_CONNECTION_FAILED
        assertEquals(ErrorCategory.IO, ErrorClassifier.classify(2005))       // IO_FILE_NOT_FOUND
        assertEquals(ErrorCategory.PARSING, ErrorClassifier.classify(3001))  // PARSING_CONTAINER_MALFORMED
        assertEquals(ErrorCategory.DECODER, ErrorClassifier.classify(4001))  // DECODER_INIT_FAILED
        assertEquals(ErrorCategory.OTHER, ErrorClassifier.classify(1000))    // UNSPECIFIED
        assertEquals(ErrorCategory.OTHER, ErrorClassifier.classify(5001))    // AUDIO_TRACK系
    }
}
```

- [ ] **Step 2: 失敗確認**

```bash
./gradlew :core:test
```
Expected: FAIL — `Unresolved reference: ErrorClassifier`

- [ ] **Step 3: 最小実装 — `core/src/main/kotlin/com/example/shuffleplayer/core/ErrorClassifier.kt`**

```kotlin
package com.example.shuffleplayer.core

enum class ErrorCategory { IO, PARSING, DECODER, OTHER }

object ErrorClassifier {
    /** Media3 PlaybackException.errorCode の帯域: 2xxx=IO, 3xxx=PARSING, 4xxx=DECODER */
    fun classify(errorCode: Int): ErrorCategory = when (errorCode) {
        in 2000..2999 -> ErrorCategory.IO
        in 3000..3999 -> ErrorCategory.PARSING
        in 4000..4999 -> ErrorCategory.DECODER
        else -> ErrorCategory.OTHER
    }
}
```

- [ ] **Step 4: パス確認 → Commit**

```bash
./gradlew :core:test
git add -A
git commit -m "Add playback error classifier (TDD)"
```
Expected: PASS(22件)

---

## Task 8: モデルとJSONシリアライズ (:core, TDD)

**Files:**
- Modify: `core/src/main/kotlin/com/example/shuffleplayer/core/Model.kt`
- Test: `core/src/test/kotlin/com/example/shuffleplayer/core/ModelTest.kt`

- [ ] **Step 1: 失敗するテストを書く**

```kotlin
package com.example.shuffleplayer.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull

class ModelTest {

    @Test
    fun `PlaylistのJSON往復`() {
        val playlist = Playlist(
            listOf(
                Track("content://auth/doc/1", "Song A"),
                Track("http://host/stream", null),
            )
        )
        assertEquals(playlist, Playlist.fromJson(playlist.toJson()))
    }

    @Test
    fun `壊れたJSONはnull`() {
        assertNull(Playlist.fromJson("{broken"))
        assertNull(Playlist.fromJson(""))
    }

    @Test
    fun `PlayerStateのデフォルト値`() {
        val state = PlayerState()
        assertNull(state.sourceUri)
        assertEquals(0, state.trackIndex)
        assertEquals(0L, state.positionMs)
        assertEquals(false, state.shuffleEnabled)
        assertEquals(RepeatMode.OFF, state.repeatMode)
    }
}
```

- [ ] **Step 2: 失敗確認**

```bash
./gradlew :core:test
```
Expected: FAIL — `Unresolved reference: Playlist`

- [ ] **Step 3: `Model.kt` を完成形にする(全文置き換え)**

```kotlin
package com.example.shuffleplayer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Track(val uri: String, val label: String? = null)

@Serializable
data class Playlist(val tracks: List<Track> = emptyList()) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): Playlist? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

enum class SourceType { M3U, FOLDER, SINGLE }

enum class RepeatMode { OFF, ONE, ALL }

/** SharedPreferencesに永続化する再生状態。trackIndexは再生キュー(shuffle適用後)上のindex */
data class PlayerState(
    val sourceUri: String? = null,
    val sourceType: SourceType = SourceType.M3U,
    val trackIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val shuffleSeed: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
)
```

- [ ] **Step 4: パス確認 → Commit**

```bash
./gradlew :core:test
git add -A
git commit -m "Add playlist/state models with JSON round-trip (TDD)"
```
Expected: PASS(25件)。これで `:core` は完成。

---

## Task 9: Prefs と PlaylistCacheStore (:app)

**Files:**
- Create: `app/src/main/kotlin/com/example/shuffleplayer/data/Prefs.kt`
- Create: `app/src/main/kotlin/com/example/shuffleplayer/data/PlaylistCacheStore.kt`

薄いIOラッパのため単体テストは書かない(以降のタスクの実機検証でカバー)。

- [ ] **Step 1: `Prefs.kt` を作成**

```kotlin
package com.example.shuffleplayer.data

import android.content.Context
import androidx.core.content.edit
import com.example.shuffleplayer.core.PlayerState
import com.example.shuffleplayer.core.RepeatMode
import com.example.shuffleplayer.core.SourceType

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    var state: PlayerState
        get() = PlayerState(
            sourceUri = sp.getString("sourceUri", null),
            sourceType = SourceType.valueOf(sp.getString("sourceType", SourceType.M3U.name)!!),
            trackIndex = sp.getInt("trackIndex", 0),
            positionMs = sp.getLong("positionMs", 0L),
            shuffleEnabled = sp.getBoolean("shuffleEnabled", false),
            shuffleSeed = sp.getLong("shuffleSeed", 0L),
            repeatMode = RepeatMode.valueOf(sp.getString("repeatMode", RepeatMode.OFF.name)!!),
        )
        set(value) = sp.edit {
            putString("sourceUri", value.sourceUri)
            putString("sourceType", value.sourceType.name)
            putInt("trackIndex", value.trackIndex)
            putLong("positionMs", value.positionMs)
            putBoolean("shuffleEnabled", value.shuffleEnabled)
            putLong("shuffleSeed", value.shuffleSeed)
            putString("repeatMode", value.repeatMode.name)
        }

    /** ウィジェット描画用(サービス死亡中も最後の表示を保つ) */
    var lastTrackTitle: String?
        get() = sp.getString("lastTrackTitle", null)
        set(value) = sp.edit { putString("lastTrackTitle", value) }

    var lastIsPlaying: Boolean
        get() = sp.getBoolean("lastIsPlaying", false)
        set(value) = sp.edit { putBoolean("lastIsPlaying", value) }

    /** 連続失敗停止時のメッセージ。null なら正常 */
    var widgetError: String?
        get() = sp.getString("widgetError", null)
        set(value) = sp.edit { putString("widgetError", value) }
}
```

- [ ] **Step 2: `PlaylistCacheStore.kt` を作成**

```kotlin
package com.example.shuffleplayer.data

import android.content.Context
import com.example.shuffleplayer.core.Playlist
import java.io.File

/** 解決済みプレイリストのファイルキャッシュ(コールドスタート時の即時再生用) */
class PlaylistCacheStore(context: Context) {
    private val file = File(context.filesDir, "playlist_cache.json")

    fun load(): Playlist? =
        runCatching { if (file.exists()) Playlist.fromJson(file.readText()) else null }.getOrNull()

    fun save(playlist: Playlist) {
        runCatching { file.writeText(playlist.toJson()) }
    }

    fun clear() {
        file.delete()
    }
}
```

- [ ] **Step 3: ビルド確認 → Commit**

```bash
./gradlew :app:assembleDebug
git add -A
git commit -m "Add Prefs and playlist cache store"
```
Expected: BUILD SUCCESSFUL

---

## Task 10: PlaybackService 最小再生(実機で音を出す)

**Files:**
- Create: `app/src/main/kotlin/com/example/shuffleplayer/playback/PlaybackService.kt`
- Create: `app/src/main/res/raw/test_tone.wav`(生成)
- Modify: `app/src/main/AndroidManifest.xml`(サービス宣言のコメントアウト解除)
- Modify: `app/src/main/kotlin/com/example/shuffleplayer/MainActivity.kt`(暫定の再生ボタン)

- [ ] **Step 1: テストトーンを生成**

```bash
mkdir -p app/src/main/res/raw
python3 - <<'EOF'
import wave, math, struct
w = wave.open('app/src/main/res/raw/test_tone.wav', 'w')
w.setparams((1, 2, 44100, 0, 'NONE', 'not compressed'))
frames = b''.join(
    struct.pack('<h', int(12000 * math.sin(2 * math.pi * 440 * i / 44100)))
    for i in range(44100 * 3))
w.writeframes(frames)
w.close()
print('ok')
EOF
```
Expected: `ok`、3秒の440Hzトーン生成

- [ ] **Step 2: `PlaybackService.kt` を作成(最小形。以降のタスクで拡張)**

```kotlin
package com.example.shuffleplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.shuffleplayer.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
```

- [ ] **Step 3: マニフェストのサービス宣言とMediaButtonReceiver宣言のコメントアウトを解除**(Task 1 Step 8の注意参照。`.widget.PlayerWidgetProvider` はまだコメントのまま)

- [ ] **Step 4: `MainActivity.kt` に暫定再生ボタンを付ける(全文置き換え。Task 15で本実装に置き換える)**

```kotlin
package com.example.shuffleplayer

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.shuffleplayer.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Button(onClick = { playTestTone() }) {
                    Text("テストトーン再生")
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({ controller = future.get() }, MoreExecutors.directExecutor())
        }
    }

    override fun onStop() {
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    private fun playTestTone() {
        val c = controller ?: return
        c.setMediaItem(MediaItem.fromUri("android.resource://$packageName/raw/test_tone"))
        c.prepare()
        c.play()
    }
}
```

- [ ] **Step 5: ビルド・インストール・実機検証**

```bash
./gradlew :app:installDebug
adb shell am start -n com.example.shuffleplayer/.MainActivity
```
画面の「テストトーン再生」ボタンをadbでタップ(画面中央付近。座標は `adb shell wm size` で調整):
```bash
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | grep -o 'text="テストトーン再生"[^>]*bounds="[^"]*"'
# boundsの中心座標を計算して:
adb shell input tap <x> <y>
sleep 2
adb shell dumpsys media_session | grep -B2 -A8 shuffleplayer | grep "state=PlaybackState"
```
Expected: `state=PlaybackState {state=3` を含む(state=3 = PLAYING)。通知シェードにメディア通知が出る。**ユーザー確認: トーンが聞こえること**

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add minimal PlaybackService with MediaSession, verify audio on device"
```

---

## Task 11: ソース読み込み(M3uLoader / FolderScanner / SourceRepository + カスタムコマンド)

**Files:**
- Create: `app/src/main/kotlin/com/example/shuffleplayer/playback/M3uLoader.kt`
- Create: `app/src/main/kotlin/com/example/shuffleplayer/playback/FolderScanner.kt`
- Create: `app/src/main/kotlin/com/example/shuffleplayer/playback/SourceRepository.kt`
- Create: `app/src/main/kotlin/com/example/shuffleplayer/playback/PlaybackCommands.kt`
- Modify: `app/src/main/kotlin/com/example/shuffleplayer/playback/PlaybackService.kt`
- Modify: `app/src/main/kotlin/com/example/shuffleplayer/MainActivity.kt`(暫定ピッカー)

- [ ] **Step 1: `FolderScanner.kt` を作成(DocumentsContract直接クエリ — DocumentFile不使用)**

```kotlin
package com.example.shuffleplayer.playback

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.example.shuffleplayer.core.MediaFileFilter
import com.example.shuffleplayer.core.Playlist
import com.example.shuffleplayer.core.Track

/**
 * SAFツリーを再帰走査してメディアファイルを集める。
 * DocumentFileはファイル1個ごとにIPCが走り大フォルダで極端に遅いため、
 * DocumentsContractの子要素クエリ(1ディレクトリ1クエリ)を直接使う。
 */
class FolderScanner(private val contentResolver: ContentResolver) {

    fun scan(treeUri: Uri): Playlist {
        val found = mutableListOf<Track>()
        scanChildren(treeUri, DocumentsContract.getTreeDocumentId(treeUri), found)
        return Playlist(found.sortedBy { it.label ?: "" })
    }

    private fun scanChildren(treeUri: Uri, documentId: String, out: MutableList<Track>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        runCatching {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        scanChildren(treeUri, id, out)
                    } else if (MediaFileFilter.isMedia(name)) {
                        out += Track(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                            label = name.substringBeforeLast('.'),
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: `M3uLoader.kt` を作成**

```kotlin
package com.example.shuffleplayer.playback

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.example.shuffleplayer.core.EntryResolver
import com.example.shuffleplayer.core.Locations
import com.example.shuffleplayer.core.M3uParser
import com.example.shuffleplayer.core.PathResolver
import com.example.shuffleplayer.core.Playlist

/** m3uファイルを読み、エントリを再生可能URIに解決する */
class M3uLoader(private val contentResolver: ContentResolver) {

    fun load(m3uUri: Uri): Playlist {
        val text = runCatching {
            contentResolver.openInputStream(m3uUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return Playlist()
        val entries = M3uParser.parse(text)
        return Playlist(EntryResolver(pathResolverFor(m3uUri)).resolve(entries))
    }

    /**
     * 相対パス解決方針(スペック「m3u内パスエントリの解決と権限」):
     * - file:// の m3u → 親ディレクトリと単純結合
     * - ExternalStorageProvider の primary ボリューム → /storage/emulated/0 の実パスに解決
     *   (READ_MEDIA_AUDIO/VIDEO 権限で読める)
     * - その他のプロバイダ → 同一プロバイダの兄弟docIdを構築して試行
     */
    private fun pathResolverFor(m3uUri: Uri): PathResolver {
        if (m3uUri.scheme == "file") {
            val baseDir = m3uUri.path?.substringBeforeLast('/', "") ?: ""
            return PathResolver { rel -> "file:///" + Locations.joinRelative(baseDir, rel) }
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(m3uUri) }.getOrNull()
            ?: return PathResolver { null }
        val colon = documentId.indexOf(':')
        if (colon < 0) return PathResolver { null }
        val root = documentId.substring(0, colon)
        val dirPath = documentId.substring(colon + 1).substringBeforeLast('/', "")
        val authority = m3uUri.authority ?: return PathResolver { null }

        if (authority == "com.android.externalstorage.documents" && root == "primary") {
            return PathResolver { rel ->
                "file:///storage/emulated/0/" + Locations.joinRelative(dirPath, rel)
            }
        }
        return PathResolver { rel ->
            val childId = "$root:" + Locations.joinRelative(dirPath, rel)
            DocumentsContract.buildDocumentUri(authority, childId).toString()
        }
    }
}
```

- [ ] **Step 3: `SourceRepository.kt` を作成**

```kotlin
package com.example.shuffleplayer.playback

import android.content.Context
import android.net.Uri
import com.example.shuffleplayer.core.PlayerState
import com.example.shuffleplayer.core.Playlist
import com.example.shuffleplayer.core.SourceType
import com.example.shuffleplayer.core.Track
import com.example.shuffleplayer.data.PlaylistCacheStore
import com.example.shuffleplayer.data.Prefs

/** ソース解決の入口。Prefsとプレイリストキャッシュを所有する */
class SourceRepository(
    private val context: Context,
    val prefs: Prefs,
    private val cache: PlaylistCacheStore,
) {

    /** ソースを設定し解決済みプレイリストを返す。状態はindex 0・新シードにリセット */
    fun setSource(uri: Uri, type: SourceType): Playlist {
        val playlist = resolve(uri, type)
        prefs.state = prefs.state.copy(
            sourceUri = uri.toString(),
            sourceType = type,
            trackIndex = 0,
            positionMs = 0L,
            shuffleSeed = System.currentTimeMillis(),
        )
        prefs.widgetError = null
        cache.save(playlist)
        return playlist
    }

    fun clearSource() {
        prefs.state = PlayerState()
        prefs.lastTrackTitle = null
        prefs.lastIsPlaying = false
        prefs.widgetError = null
        cache.clear()
    }

    /** 再開用: キャッシュ優先、無ければ再解決(初回のみ遅い) */
    fun loadCachedOrResolve(): Playlist? {
        val state = prefs.state
        val uri = state.sourceUri?.let(Uri::parse) ?: return null
        cache.load()?.takeIf { it.tracks.isNotEmpty() }?.let { return it }
        return resolve(uri, state.sourceType).also { cache.save(it) }
    }

    /** フォルダソースのみ再走査してキャッシュ更新。それ以外はnull */
    fun rescanFolder(): Playlist? {
        val state = prefs.state
        if (state.sourceType != SourceType.FOLDER) return null
        val uri = state.sourceUri?.let(Uri::parse) ?: return null
        return FolderScanner(context.contentResolver).scan(uri).also { cache.save(it) }
    }

    fun cachedPlaylist(): Playlist? = cache.load()

    private fun resolve(uri: Uri, type: SourceType): Playlist = when (type) {
        SourceType.M3U -> M3uLoader(context.contentResolver).load(uri)
        SourceType.FOLDER -> FolderScanner(context.contentResolver).scan(uri)
        SourceType.SINGLE -> Playlist(listOf(Track(uri.toString(), uri.lastPathSegment)))
    }
}
```

- [ ] **Step 4: `PlaybackCommands.kt` を作成(カスタムコマンド定数)**

```kotlin
package com.example.shuffleplayer.playback

object PlaybackCommands {
    const val SET_SOURCE = "com.example.shuffleplayer.SET_SOURCE"
    const val CLEAR_SOURCE = "com.example.shuffleplayer.CLEAR_SOURCE"
    const val SET_SHUFFLE = "com.example.shuffleplayer.SET_SHUFFLE"
    const val SET_REPEAT = "com.example.shuffleplayer.SET_REPEAT"

    const val KEY_URI = "uri"
    const val KEY_TYPE = "type"          // SourceType.name
    const val KEY_ENABLED = "enabled"
    const val KEY_MODE = "mode"          // RepeatMode.name
}
```

- [ ] **Step 5: `PlaybackService.kt` にソース処理を追加(全文置き換え)**

```kotlin
package com.example.shuffleplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.example.shuffleplayer.MainActivity
import com.example.shuffleplayer.core.PlayerState
import com.example.shuffleplayer.core.Playlist
import com.example.shuffleplayer.core.RepeatMode
import com.example.shuffleplayer.core.ShuffleOrder
import com.example.shuffleplayer.core.SourceType
import com.example.shuffleplayer.core.Track
import com.example.shuffleplayer.data.PlaylistCacheStore
import com.example.shuffleplayer.data.Prefs
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var prefs: Prefs
    private lateinit var sourceRepository: SourceRepository
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        sourceRepository = SourceRepository(this, prefs, PlaylistCacheStore(this))
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            }
        player.addListener(playerListener)
        val sessionActivity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        saveState()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- キュー構築 ----

    private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(label ?: uri.substringAfterLast('/'))
                .build()
        )
        .build()

    /** shuffle設定に従い再生キュー(MediaItem列)を作る */
    private fun buildQueue(playlist: Playlist, state: PlayerState): List<MediaItem> {
        val order =
            if (state.shuffleEnabled) ShuffleOrder.order(playlist.tracks.size, state.shuffleSeed)
            else playlist.tracks.indices.toList()
        return order.map { playlist.tracks[it].toMediaItem() }
    }

    private fun RepeatMode.toPlayerValue(): Int = when (this) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

    private fun startPlayback(playlist: Playlist, state: PlayerState, startIndex: Int, positionMs: Long) {
        if (playlist.tracks.isEmpty()) return
        val items = buildQueue(playlist, state)
        player.setMediaItems(items, startIndex.coerceIn(0, items.size - 1), positionMs)
        player.repeatMode = state.repeatMode.toPlayerValue()
        player.prepare()
        player.play()
    }

    // ---- 状態保存 ----

    private fun saveState() {
        if (player.mediaItemCount == 0) return
        prefs.state = prefs.state.copy(
            trackIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0L),
        )
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveState()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveState()
        }
    }

    // ---- カスタムコマンド ----

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(PlaybackCommands.SET_SOURCE, Bundle.EMPTY))
                    .add(SessionCommand(PlaybackCommands.CLEAR_SOURCE, Bundle.EMPTY))
                    .add(SessionCommand(PlaybackCommands.SET_SHUFFLE, Bundle.EMPTY))
                    .add(SessionCommand(PlaybackCommands.SET_REPEAT, Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlaybackCommands.SET_SOURCE -> {
                    val uri = Uri.parse(args.getString(PlaybackCommands.KEY_URI) ?: return err())
                    val type = SourceType.valueOf(args.getString(PlaybackCommands.KEY_TYPE) ?: return err())
                    serviceScope.launch {
                        val playlist = withContext(Dispatchers.IO) {
                            sourceRepository.setSource(uri, type)
                        }
                        startPlayback(playlist, prefs.state, startIndex = 0, positionMs = 0L)
                    }
                }
                PlaybackCommands.CLEAR_SOURCE -> {
                    player.stop()
                    player.clearMediaItems()
                    sourceRepository.clearSource()
                    stopSelf()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        private fun err(): ListenableFuture<SessionResult> =
            Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
    }
}
```

(SET_SHUFFLE / SET_REPEAT のハンドリングはTask 15で追加する)

- [ ] **Step 6: `MainActivity.kt` を暫定ピッカー付きに置き換え(全文)**

```kotlin
package com.example.shuffleplayer

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.shuffleplayer.core.SourceType
import com.example.shuffleplayer.playback.PlaybackCommands
import com.example.shuffleplayer.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Column(Modifier.padding(24.dp)) {
                    val pickM3u = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri -> uri?.let { onSourcePicked(it, SourceType.M3U) } }
                    val pickFolder = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree()
                    ) { uri -> uri?.let { onSourcePicked(it, SourceType.FOLDER) } }

                    Button(onClick = { pickM3u.launch(arrayOf("*/*")) }) { Text("m3u選択") }
                    Button(onClick = { pickFolder.launch(null) }) { Text("フォルダ選択") }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({ controller = future.get() }, MoreExecutors.directExecutor())
        }
    }

    override fun onStop() {
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    private fun onSourcePicked(uri: Uri, type: SourceType) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val args = Bundle().apply {
            putString(PlaybackCommands.KEY_URI, uri.toString())
            putString(PlaybackCommands.KEY_TYPE, type.name)
        }
        controller?.sendCustomCommand(SessionCommand(PlaybackCommands.SET_SOURCE, Bundle.EMPTY), args)
    }
}
```

- [ ] **Step 7: テストメディアを実機に配置**

```bash
mkdir -p /tmp/shuffletest/sub
python3 - <<'EOF'
import wave, math, struct
for i, (freq, path) in enumerate([(440, '/tmp/shuffletest/tone_a.wav'),
                                  (550, '/tmp/shuffletest/tone_b.wav'),
                                  (660, '/tmp/shuffletest/sub/tone_c.wav')]):
    w = wave.open(path, 'w')
    w.setparams((1, 2, 44100, 0, 'NONE', 'not compressed'))
    w.writeframes(b''.join(struct.pack('<h', int(12000*math.sin(2*math.pi*freq*t/44100))) for t in range(44100*5)))
    w.close()
print('ok')
EOF
cat > /tmp/shuffletest/list.m3u <<'EOF'
#EXTM3U
#EXTINF:5,Tone A
tone_a.wav
#EXTINF:5,Tone B
tone_b.wav
#EXTINF:5,Tone C
sub/tone_c.wav
EOF
adb push /tmp/shuffletest /sdcard/Music/shuffletest
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/shuffletest
```
Expected: push成功

- [ ] **Step 8: ビルド・インストール・実機検証(ユーザー操作が必要)**

```bash
./gradlew :app:installDebug
adb shell am start -n com.example.shuffleplayer/.MainActivity
```
**ユーザーに依頼:**
1. 「フォルダ選択」→ `Music/shuffletest` を選択 → 3トーンが順に再生されること(サブフォルダの tone_c も含む)
2. 「m3u選択」→ `Music/shuffletest/list.m3u` を選択 → 再生されること(相対パス解決の確認。権限ダイアログが未実装のため、再生されない場合は `adb shell pm grant com.example.shuffleplayer android.permission.READ_MEDIA_AUDIO` を実行してから再試行。本対応はTask 15)

確認コマンド:
```bash
adb shell dumpsys media_session | grep -B2 -A8 shuffleplayer | grep "state=PlaybackState"
```
Expected: `state=3`(PLAYING)

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "Add source loading: m3u loader, DocumentsContract folder scanner, custom commands"
```

---

## Task 12: ウィジェット

**Files:**
- Create: `app/src/main/res/xml/widget_info.xml`
- Create: `app/src/main/res/layout/widget_1x1.xml`, `widget_2x1.xml`, `widget_4x1.xml`
- Create: `app/src/main/kotlin/com/example/shuffleplayer/widget/PlayerWidgetProvider.kt`
- Create: `app/src/main/kotlin/com/example/shuffleplayer/widget/WidgetRenderer.kt`
- Modify: `app/src/main/AndroidManifest.xml`(ウィジェットreceiverのコメント解除)
- Modify: `app/src/main/kotlin/com/example/shuffleplayer/playback/PlaybackService.kt`(更新トリガー)

- [ ] **Step 1: `widget_info.xml` を作成**

```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="40dp"
    android:minHeight="40dp"
    android:targetCellWidth="2"
    android:targetCellHeight="1"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen"
    android:initialLayout="@layout/widget_2x1"
    android:updatePeriodMillis="0" />
```

- [ ] **Step 2: レイアウト3種を作成**

`app/src/main/res/layout/widget_1x1.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:gravity="center"
    android:orientation="horizontal">

    <ImageButton
        android:id="@+id/btn_play_pause"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:background="?android:attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/app_name"
        android:src="@drawable/ic_play" />
</LinearLayout>
```

`app/src/main/res/layout/widget_2x1.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:gravity="center"
    android:orientation="horizontal">

    <ImageButton
        android:id="@+id/btn_prev"
        android:layout_width="0dp"
        android:layout_height="40dp"
        android:layout_weight="1"
        android:background="?android:attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/app_name"
        android:src="@drawable/ic_prev" />

    <ImageButton
        android:id="@+id/btn_play_pause"
        android:layout_width="0dp"
        android:layout_height="48dp"
        android:layout_weight="1"
        android:background="?android:attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/app_name"
        android:src="@drawable/ic_play" />

    <ImageButton
        android:id="@+id/btn_next"
        android:layout_width="0dp"
        android:layout_height="40dp"
        android:layout_weight="1"
        android:background="?android:attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/app_name"
        android:src="@drawable/ic_next" />
</LinearLayout>
```

`app/src/main/res/layout/widget_4x1.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@drawable/widget_bg"
    android:gravity="center_vertical"
    android:orientation="vertical"
    android:padding="4dp">

    <TextView
        android:id="@+id/track_title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:ellipsize="marquee"
        android:gravity="center"
        android:singleLine="true"
        android:textColor="@android:color/white"
        android:textSize="13sp" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:orientation="horizontal">

        <ImageButton
            android:id="@+id/btn_prev"
            android:layout_width="0dp"
            android:layout_height="40dp"
            android:layout_weight="1"
            android:background="?android:attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/app_name"
            android:src="@drawable/ic_prev" />

        <ImageButton
            android:id="@+id/btn_play_pause"
            android:layout_width="0dp"
            android:layout_height="44dp"
            android:layout_weight="1"
            android:background="?android:attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/app_name"
            android:src="@drawable/ic_play" />

        <ImageButton
            android:id="@+id/btn_next"
            android:layout_width="0dp"
            android:layout_height="40dp"
            android:layout_weight="1"
            android:background="?android:attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/app_name"
            android:src="@drawable/ic_next" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 3: `WidgetRenderer.kt` を作成**

```kotlin
package com.example.shuffleplayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.media3.session.MediaButtonReceiver
import com.example.shuffleplayer.R
import com.example.shuffleplayer.data.Prefs

/**
 * ウィジェット描画。状態は常にPrefs(永続)から読む。
 * サービス側は Prefs を更新してから renderAll を呼ぶ。
 */
object WidgetRenderer {

    fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
        for (id in ids) render(context, manager, id)
    }

    fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val prefs = Prefs(context)
        val title = prefs.widgetError
            ?: prefs.lastTrackTitle
            ?: context.getString(R.string.no_source)
        val isPlaying = prefs.lastIsPlaying

        val cells = widthCells(manager, widgetId)
        val layout = when {
            cells <= 1 -> R.layout.widget_1x1
            cells <= 3 -> R.layout.widget_2x1
            else -> R.layout.widget_4x1
        }
        val views = RemoteViews(context.packageName, layout)
        views.setImageViewResource(
            R.id.btn_play_pause,
            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        views.setOnClickPendingIntent(
            R.id.btn_play_pause,
            mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
        )
        if (layout != R.layout.widget_1x1) {
            views.setOnClickPendingIntent(
                R.id.btn_prev, mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
            )
            views.setOnClickPendingIntent(
                R.id.btn_next, mediaButtonIntent(context, KeyEvent.KEYCODE_MEDIA_NEXT),
            )
        }
        if (layout == R.layout.widget_4x1) {
            views.setTextViewText(R.id.track_title, title)
        }
        manager.updateAppWidget(widgetId, views)
    }

    private fun widthCells(manager: AppWidgetManager, widgetId: Int): Int {
        val minWidth = manager.getAppWidgetOptions(widgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        return ((minWidth + 30) / 70).coerceAtLeast(1)
    }

    /** Media3のMediaButtonReceiver宛にメディアキーを送るPendingIntent */
    private fun mediaButtonIntent(context: Context, keyCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        return PendingIntent.getBroadcast(
            context, keyCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
```

- [ ] **Step 4: `PlayerWidgetProvider.kt` を作成**

```kotlin
package com.example.shuffleplayer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetRenderer.render(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        WidgetRenderer.render(context, appWidgetManager, appWidgetId)
    }
}
```

- [ ] **Step 5: マニフェストの `.widget.PlayerWidgetProvider` receiver宣言のコメント解除**

- [ ] **Step 6: `PlaybackService.kt` の `playerListener` を更新(ウィジェット更新トリガー追加)**

`playerListener` を以下に置き換え、`onCreate` の末尾に `updateWidgets()` を追加:

```kotlin
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveState()
            updateWidgets()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveState()
            updateWidgets()
        }
    }

    private fun updateWidgets() {
        prefs.lastIsPlaying = player.isPlaying
        player.mediaMetadata.title?.toString()?.let { prefs.lastTrackTitle = it }
        WidgetRenderer.renderAll(this)
    }
```

import追加: `import com.example.shuffleplayer.widget.WidgetRenderer`

- [ ] **Step 7: ビルド・インストール・実機検証(ユーザー操作が必要)**

```bash
./gradlew :app:installDebug
```
**ユーザーに依頼:** ホーム画面長押し→ウィジェット→Shuffle Playerを2x1で配置。
1. 再生中にウィジェットの一時停止→再生アイコンに変わること
2. next/prevでトラックが変わること
3. 4セル幅にリサイズ→トラック名が表示されること

確認コマンド(タップ後):
```bash
adb shell dumpsys media_session | grep -B2 -A8 shuffleplayer | grep state=
```
Expected: 操作に応じて state=3(playing)/ state=2(paused)が切り替わる

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "Add resizable widget with MediaButtonReceiver commands"
```

---

## Task 13: コールドスタート復帰(onPlaybackResumption + 裏の再走査)

**Files:**
- Modify: `app/src/main/kotlin/com/example/shuffleplayer/playback/PlaybackService.kt`

- [ ] **Step 1: `SessionCallback` に `onPlaybackResumption` を追加**

`SessionCallback` クラス内に追加:

```kotlin
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                val playlist = withContext(Dispatchers.IO) { sourceRepository.loadCachedOrResolve() }
                val state = prefs.state
                if (playlist == null || playlist.tracks.isEmpty()) {
                    future.setException(IllegalStateException("no source"))
                } else {
                    val items = buildQueue(playlist, state)
                    player.repeatMode = state.repeatMode.toPlayerValue()
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            items,
                            state.trackIndex.coerceIn(0, items.size - 1),
                            state.positionMs,
                        )
                    )
                    rescanFolderInBackground()
                }
            }
            return future
        }
```

import追加: `import com.google.common.util.concurrent.SettableFuture`

- [ ] **Step 2: 裏の再走査をサービスに追加(クラス本体に追加)**

```kotlin
    /** フォルダソースを裏で再走査し、内容が変わっていたらキューを差し替える(再生中トラックは維持) */
    private fun rescanFolderInBackground() {
        serviceScope.launch {
            val updated = withContext(Dispatchers.IO) { sourceRepository.rescanFolder() } ?: return@launch
            val state = prefs.state
            val newItems = buildQueue(updated, state)
            val currentUris = (0 until player.mediaItemCount)
                .map { player.getMediaItemAt(it).localConfiguration?.uri?.toString() }
            if (currentUris == newItems.map { it.localConfiguration?.uri?.toString() }) return@launch

            val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
            val keepIndex = newItems.indexOfFirst { it.localConfiguration?.uri?.toString() == currentUri }
            if (keepIndex >= 0) {
                player.setMediaItems(newItems, keepIndex, player.currentPosition)
            } else {
                player.setMediaItems(newItems)
            }
            player.prepare()
        }
    }
```

- [ ] **Step 3: ビルド・インストール・実機検証(コールドスタートの核心テスト)**

```bash
./gradlew :app:installDebug
# フォルダソースを設定し再生中の状態から:
adb shell am force-stop com.example.shuffleplayer
sleep 2
```
**ユーザーに依頼:** ウィジェットの再生ボタンをタップ。
Expected: **アプリプロセス死亡状態から再生が再開する**(前回トラック・前回位置から)。クラッシュしない。

```bash
adb shell dumpsys media_session | grep -B2 -A8 shuffleplayer | grep state=
adb logcat -d | grep -iE "ForegroundServiceDidNotStartInTime|ANR" | tail -5
```
Expected: state=3、タイムアウト例外なし

- [ ] **Step 4: フォルダ内容変更の追従確認**

```bash
adb shell cp /sdcard/Music/shuffletest/tone_a.wav /sdcard/Music/shuffletest/tone_d.wav
adb shell am force-stop com.example.shuffleplayer
```
**ユーザーに依頼:** ウィジェットで再生再開 → 数秒後に next を数回タップ。
Expected: tone_d が再生リストに含まれている(4曲になっている)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add cold-start playback resumption with background folder rescan"
```

---

## Task 14: エラーハンドリング(分類・Roomログ・失敗ガード)

**Files:**
- Create: `app/src/main/kotlin/com/example/shuffleplayer/data/ErrorLogDb.kt`
- Modify: `app/src/main/kotlin/com/example/shuffleplayer/playback/PlaybackService.kt`

- [ ] **Step 1: `ErrorLogDb.kt` を作成(entity + dao + db)**

```kotlin
package com.example.shuffleplayer.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playback_errors")
data class PlaybackError(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val uri: String,
    @ColumnInfo(name = "track_label") val trackLabel: String?,
    @ColumnInfo(name = "error_code") val errorCode: Int,
    @ColumnInfo(name = "error_category") val errorCategory: String,
    @ColumnInfo(name = "error_message") val errorMessage: String,
)

@Dao
interface ErrorLogDao {
    @Insert
    suspend fun insert(error: PlaybackError)

    /** 直近100件を残して古いものを削除 */
    @Query("DELETE FROM playback_errors WHERE id NOT IN (SELECT id FROM playback_errors ORDER BY id DESC LIMIT 100)")
    suspend fun prune()

    @Query("SELECT * FROM playback_errors ORDER BY id DESC")
    fun recent(): Flow<List<PlaybackError>>
}

@Database(entities = [PlaybackError::class], version = 1, exportSchema = false)
abstract class ErrorLogDb : RoomDatabase() {
    abstract fun dao(): ErrorLogDao

    companion object {
        @Volatile
        private var instance: ErrorLogDb? = null

        fun get(context: Context): ErrorLogDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, ErrorLogDb::class.java, "errorlog.db"
            ).build().also { instance = it }
        }
    }
}
```

- [ ] **Step 2: `PlaybackService` にエラー処理を追加**

クラスにフィールド追加:

```kotlin
    private val failureGuard = FailureGuard()
    private val errorLogDao by lazy { ErrorLogDb.get(this).dao() }
```

import追加:
```kotlin
import androidx.media3.common.PlaybackException
import com.example.shuffleplayer.R
import com.example.shuffleplayer.core.ErrorClassifier
import com.example.shuffleplayer.core.FailureGuard
import com.example.shuffleplayer.data.ErrorLogDb
import com.example.shuffleplayer.data.PlaybackError
```

`playerListener` を以下に置き換え:

```kotlin
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveState()
            updateWidgets()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            saveState()
            updateWidgets()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                failureGuard.onSuccess()
                if (prefs.widgetError != null) {
                    prefs.widgetError = null
                    updateWidgets()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem
            val category = ErrorClassifier.classify(error.errorCode)
            serviceScope.launch(Dispatchers.IO) {
                errorLogDao.insert(
                    PlaybackError(
                        timestamp = System.currentTimeMillis(),
                        uri = item?.localConfiguration?.uri?.toString() ?: "",
                        trackLabel = item?.mediaMetadata?.title?.toString(),
                        errorCode = error.errorCode,
                        errorCategory = category.name,
                        errorMessage = error.message ?: "",
                    )
                )
                errorLogDao.prune()
            }
            val shouldStop = failureGuard.onFailure()
            if (shouldStop) {
                player.pause()
                prefs.widgetError = getString(R.string.playback_unavailable)
                updateWidgets()
            } else if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                player.stop()
            }
        }
    }
```

- [ ] **Step 3: ビルド・インストール・実機検証(壊れたエントリでスキップ確認)**

```bash
cat > /tmp/shuffletest/broken.m3u <<'EOF'
#EXTINF:5,Missing
missing_file.wav
#EXTINF:5,Tone A
tone_a.wav
#EXTINF:5,Also Missing
nothere.wav
#EXTINF:5,Tone B
tone_b.wav
EOF
adb push /tmp/shuffletest/broken.m3u /sdcard/Music/shuffletest/broken.m3u
./gradlew :app:installDebug
adb shell am start -n com.example.shuffleplayer/.MainActivity
```
**ユーザーに依頼:** 「m3u選択」→ `broken.m3u` を選択。
Expected: 欠落ファイルを即スキップして Tone A → Tone B と再生が続く(止まらない)

確認:
```bash
adb shell "run-as com.example.shuffleplayer ls databases/" 2>/dev/null || echo "(dbはエラーログ画面実装後にUIで確認)"
adb shell dumpsys media_session | grep -B2 -A8 shuffleplayer | grep state=
```
Expected: state=3(再生継続)

- [ ] **Step 4: 連続失敗ガードの確認**

全エントリが欠落のm3uを作って選択:
```bash
python3 -c "print('\n'.join(f'gone_{i}.wav' for i in range(8)))" > /tmp/shuffletest/allbroken.m3u
adb push /tmp/shuffletest/allbroken.m3u /sdcard/Music/shuffletest/
```
**ユーザーに依頼:** `allbroken.m3u` を選択。
Expected: 5トラック試行後に停止し、4セルウィジェットに「再生不可: 接続を確認」が表示される(無限ループしない)

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add error skip, consecutive failure guard, Room error log"
```

---

## Task 15: 設定画面の本実装(ソース表示・shuffle/repeat・エラーログ・権限・ウィジェット追加)

**Files:**
- Create: `app/src/main/kotlin/com/example/shuffleplayer/settings/SettingsScreen.kt`
- Create: `app/src/main/kotlin/com/example/shuffleplayer/settings/ErrorLogScreen.kt`
- Modify: `app/src/main/kotlin/com/example/shuffleplayer/MainActivity.kt`(本実装に置き換え)
- Modify: `app/src/main/kotlin/com/example/shuffleplayer/playback/PlaybackService.kt`(SET_SHUFFLE/SET_REPEAT)

- [ ] **Step 1: `PlaybackService` の `onCustomCommand` に shuffle/repeat 処理を追加**

`when (customCommand.customAction)` に分岐追加:

```kotlin
                PlaybackCommands.SET_SHUFFLE -> {
                    val enabled = args.getBoolean(PlaybackCommands.KEY_ENABLED)
                    applyShuffle(enabled)
                }
                PlaybackCommands.SET_REPEAT -> {
                    val mode = RepeatMode.valueOf(args.getString(PlaybackCommands.KEY_MODE) ?: return err())
                    prefs.state = prefs.state.copy(repeatMode = mode)
                    player.repeatMode = mode.toPlayerValue()
                }
```

クラス本体にメソッド追加:

```kotlin
    /** shuffle切替: 新しい順序でキューを組み直す。再生中トラックはそのまま継続 */
    private fun applyShuffle(enabled: Boolean) {
        val newState = prefs.state.copy(
            shuffleEnabled = enabled,
            shuffleSeed = if (enabled) System.currentTimeMillis() else prefs.state.shuffleSeed,
        )
        prefs.state = newState
        val playlist = sourceRepository.cachedPlaylist() ?: return
        if (player.mediaItemCount == 0 || playlist.tracks.isEmpty()) return
        val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString()
        val items = buildQueue(playlist, newState)
        val keepIndex = items.indexOfFirst { it.localConfiguration?.uri?.toString() == currentUri }
            .coerceAtLeast(0)
        player.setMediaItems(items, keepIndex, player.currentPosition)
        player.prepare()
    }
```

- [ ] **Step 2: `SettingsScreen.kt` を作成**

```kotlin
package com.example.shuffleplayer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.shuffleplayer.core.PlayerState
import com.example.shuffleplayer.core.RepeatMode

@Composable
fun SettingsScreen(
    state: PlayerState,
    sourceLabel: String,
    onPickM3u: () -> Unit,
    onPickFolder: () -> Unit,
    onClearSource: () -> Unit,
    onShuffleChange: (Boolean) -> Unit,
    onRepeatChange: (RepeatMode) -> Unit,
    onAddWidget: () -> Unit,
    onOpenErrorLog: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ソース", style = MaterialTheme.typography.titleMedium)
        Text(sourceLabel, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPickM3u) { Text("m3u選択") }
            Button(onClick = onPickFolder) { Text("フォルダ選択") }
            OutlinedButton(onClick = onClearSource) { Text("クリア") }
        }

        HorizontalDivider()

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("シャッフル", style = MaterialTheme.typography.titleMedium)
            Switch(checked = state.shuffleEnabled, onCheckedChange = onShuffleChange)
        }

        Text("リピート", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in RepeatMode.entries) {
                FilterChip(
                    selected = state.repeatMode == mode,
                    onClick = { onRepeatChange(mode) },
                    label = {
                        Text(
                            when (mode) {
                                RepeatMode.OFF -> "オフ"
                                RepeatMode.ONE -> "1曲"
                                RepeatMode.ALL -> "全曲"
                            }
                        )
                    },
                )
            }
        }

        HorizontalDivider()

        Button(onClick = onAddWidget) { Text("ウィジェットを追加") }
        TextButton(onClick = onOpenErrorLog) { Text("エラーログを見る") }
    }
}
```

- [ ] **Step 3: `ErrorLogScreen.kt` を作成**

```kotlin
package com.example.shuffleplayer.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.shuffleplayer.data.ErrorLogDao
import com.example.shuffleplayer.data.PlaybackError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val categoryColors = mapOf(
    "IO" to Color(0xFFE65100),       // 橙
    "DECODER" to Color(0xFF6A1B9A),  // 紫
    "PARSING" to Color(0xFFB71C1C),  // 赤
    "OTHER" to Color(0xFF616161),    // 灰
)

@Composable
fun ErrorLogScreen(dao: ErrorLogDao, onBack: () -> Unit) {
    val errors by dao.recent().collectAsState(initial = emptyList())
    val format = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("← 戻る") }
        if (errors.isEmpty()) {
            Text("エラーはありません", style = MaterialTheme.typography.bodyMedium)
        }
        LazyColumn {
            items(errors, key = PlaybackError::id) { error ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "${error.errorCategory}  ${format.format(Date(error.timestamp))}",
                        color = categoryColors[error.errorCategory] ?: Color.Gray,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        error.trackLabel ?: error.uri,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${error.errorCode}: ${error.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 4: `MainActivity.kt` を本実装に置き換え(全文)**

```kotlin
package com.example.shuffleplayer

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.example.shuffleplayer.core.MediaFileFilter
import com.example.shuffleplayer.core.RepeatMode
import com.example.shuffleplayer.core.SourceType
import com.example.shuffleplayer.data.ErrorLogDb
import com.example.shuffleplayer.data.Prefs
import com.example.shuffleplayer.playback.PlaybackCommands
import com.example.shuffleplayer.playback.PlaybackService
import com.example.shuffleplayer.settings.ErrorLogScreen
import com.example.shuffleplayer.settings.SettingsScreen
import com.example.shuffleplayer.widget.PlayerWidgetProvider
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingCommand: Pair<String, Bundle>? = null
    private var refreshTick = mutableIntStateOf(0)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private val m3uMimeTypes = setOf(
        "audio/x-mpegurl", "audio/mpegurl", "application/vnd.apple.mpegurl", "application/x-mpegurl",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val tick by refreshTick
                var showErrorLog by remember { mutableStateOf(false) }
                val prefs = remember(tick) { Prefs(this) }
                val state = remember(tick) { prefs.state }

                val pickM3u = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let { setSource(it, SourceType.M3U, persist = true) } }
                val pickFolder = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri -> uri?.let { setSource(it, SourceType.FOLDER, persist = true) } }

                if (showErrorLog) {
                    ErrorLogScreen(
                        dao = ErrorLogDb.get(this).dao(),
                        onBack = { showErrorLog = false },
                    )
                } else {
                    SettingsScreen(
                        state = state,
                        sourceLabel = sourceLabel(state.sourceUri, state.sourceType),
                        onPickM3u = { pickM3u.launch(arrayOf("*/*")) },
                        onPickFolder = { pickFolder.launch(null) },
                        onClearSource = {
                            sendCommand(PlaybackCommands.CLEAR_SOURCE, Bundle.EMPTY)
                            refresh()
                        },
                        onShuffleChange = { enabled ->
                            sendCommand(
                                PlaybackCommands.SET_SHUFFLE,
                                Bundle().apply { putBoolean(PlaybackCommands.KEY_ENABLED, enabled) },
                            )
                            refresh()
                        },
                        onRepeatChange = { mode ->
                            sendCommand(
                                PlaybackCommands.SET_REPEAT,
                                Bundle().apply { putString(PlaybackCommands.KEY_MODE, mode.name) },
                            )
                            refresh()
                        },
                        onAddWidget = { requestPinWidget() },
                        onOpenErrorLog = { showErrorLog = true },
                    )
                }
            }
        }
        maybeRequestNotificationPermission()
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                controller = future.get()
                pendingCommand?.let { (action, args) ->
                    pendingCommand = null
                    sendCommand(action, args)
                }
            }, MoreExecutors.directExecutor())
        }
    }

    override fun onStop() {
        controllerFuture?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    // ---- コマンド送信 ----

    private fun sendCommand(action: String, args: Bundle) {
        val c = controller
        if (c == null) {
            pendingCommand = action to args
            return
        }
        c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        // 状態変更がPrefsに反映されるのを待ってから再描画
        window.decorView.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        refreshTick.intValue++
    }

    // ---- ソース設定 ----

    private fun setSource(uri: Uri, type: SourceType, persist: Boolean) {
        if (persist) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        if (type == SourceType.M3U) maybeRequestStoragePermission()
        sendCommand(
            PlaybackCommands.SET_SOURCE,
            Bundle().apply {
                putString(PlaybackCommands.KEY_URI, uri.toString())
                putString(PlaybackCommands.KEY_TYPE, type.name)
            },
        )
    }

    private fun sourceLabel(uri: String?, type: SourceType): String {
        if (uri == null) return getString(R.string.no_source)
        val name = Uri.parse(uri).lastPathSegment ?: uri
        return when (type) {
            SourceType.M3U -> "m3u: $name"
            SourceType.FOLDER -> "フォルダ: $name"
            SourceType.SINGLE -> name
        }
    }

    // ---- ACTION_VIEW受領 ----

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val isM3u = intent.type in m3uMimeTypes ||
            (uri.scheme != "http" && uri.scheme != "https" &&
                uri.lastPathSegment?.let { MediaFileFilter.isPlaylist(it) } == true)
        val type = if (isM3u) SourceType.M3U else SourceType.SINGLE
        setSource(uri, type, persist = uri.scheme == "content")
    }

    // ---- 権限・ウィジェット ----

    private fun maybeRequestStoragePermission() {
        val permissions =
            if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            requestPermissions.launch(permissions)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun requestPinWidget() {
        val manager = getSystemService(AppWidgetManager::class.java)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(
                ComponentName(this, PlayerWidgetProvider::class.java), null, null,
            )
        }
    }
}
```

- [ ] **Step 5: ビルド・インストール・実機検証(ユーザー操作が必要)**

```bash
./gradlew :app:installDebug
adb shell am start -n com.example.shuffleplayer/.MainActivity
```
**ユーザーに依頼:**
1. 通知権限ダイアログが出る → 許可
2. フォルダソース設定 → シャッフルON → 曲順が変わる(再生中の曲は途切れない)
3. アプリを再起動(force-stop後) → ウィジェットで再生再開 → シャッフル順が同じ
4. リピート「1曲」→ 同じ曲がループする
5. broken.m3u 選択 → 「エラーログを見る」→ IO(橙)のエラーが記録されている
6. 「ウィジェットを追加」→ ピン留めダイアログが出る
7. 「クリア」→ ウィジェットが「ソース未設定」表示になり再生停止

```bash
adb shell am force-stop com.example.shuffleplayer   # 手順3用
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add full settings screen: source, shuffle/repeat, error log, permissions, pin widget"
```

---

## Task 16: ACTION_VIEW と HTTPストリーミングの実機検証

**Files:** 変更なし(検証のみ。問題が出たら修正)

- [ ] **Step 1: m3uのACTION_VIEW**

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "file:///sdcard/Music/shuffletest/list.m3u" -t "audio/x-mpegurl" \
  com.example.shuffleplayer
```
Expected: アプリが開き、list.m3u がソースになり再生が始まる(file:// m3uの相対解決の検証)

- [ ] **Step 2: 単体音声ファイルのACTION_VIEW**

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "file:///sdcard/Music/shuffletest/tone_a.wav" -t "audio/wav" \
  com.example.shuffleplayer
```
Expected: tone_a 単体がソースになり再生

- [ ] **Step 3: HTTPストリーミング(adb reverseでワークステーションから配信)**

```bash
cd /tmp/shuffletest && python3 -m http.server 8000 &
adb reverse tcp:8000 tcp:8000
adb shell am start -a android.intent.action.VIEW \
  -d "http://127.0.0.1:8000/tone_b.wav" com.example.shuffleplayer
sleep 3
adb shell dumpsys media_session | grep -B2 -A8 shuffleplayer | grep state=
```
Expected: state=3(HTTP経由で再生)

- [ ] **Step 4: ストリーミング切断→失敗ガード**

```bash
kill %1   # http.server停止
```
**ユーザーに依頼または確認:** 再生が途切れた後、エラーログにIOエラーが記録され、(単曲ソースのため次が無く)停止すること。クラッシュしないこと。
```bash
adb logcat -d | grep -i "FATAL" | tail -3
```
Expected: FATALなし

- [ ] **Step 5: 後始末とCommit(修正があれば)**

```bash
adb reverse --remove tcp:8000
git add -A
git diff --cached --quiet || git commit -m "Fix issues found in ACTION_VIEW and HTTP streaming verification"
```

---

## Task 17: 仕上げ(暫定コード削除・リリースビルド・総合検証)

**Files:**
- Delete: `app/src/main/res/raw/test_tone.wav`
- Modify: 必要に応じて文言・アイコン調整

- [ ] **Step 1: テストトーンと暫定コードの掃除**

```bash
rm app/src/main/res/raw/test_tone.wav
rmdir app/src/main/res/raw 2>/dev/null || true
grep -rn "test_tone" app/src/ && echo "REMAINS — 削除すること" || echo "clean"
```
Expected: `clean`

- [ ] **Step 2: 全テスト+リリースビルド**

```bash
./gradlew :core:test :app:assembleRelease
```
Expected: BUILD SUCCESSFUL、テスト全件パス(25件)
(署名設定が無い場合、`assembleRelease`は未署名APKを生成する。実機テストはdebugビルドで行う)

- [ ] **Step 3: 総合実機チェックリスト(ユーザーと一緒に)**

1. フォルダソース再生(サブフォルダ含む)
2. m3uソース再生(相対パス)
3. ウィジェット1x1/2x1/4x1の表示と操作
4. コールドスタート(force-stop→ウィジェット再生)
5. シャッフルON/OFF・再起動後の順序維持
6. リピート3モード
7. 通知・ロック画面からの操作
8. イヤホン(あれば)抜き取りで一時停止
9. 壊れたソースでのスキップとエラーログ
10. クリアでウィジェット「ソース未設定」

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Clean up scaffolding leftovers, verify release build"
```

- [ ] **Step 5: superpowers:finishing-a-development-branch スキルを起動してブランチの統合方法を決める**

---

## 既知のリスクと対処

- **`onPlaybackResumption`がウィジェットのPLAY_PAUSE以外(NEXT/PREV)のコールドスタートで呼ばれない**: 仕様上PLAYのみ再開対象。コールドスタート時のNEXT/PREVは無視されてよい(再生が始まってから操作する)
- **Media3 1.5の`MediaButtonReceiver`はセッション履歴に依存**: 初回インストール直後は一度設定画面から再生して履歴を作る必要がある。Task 12の検証はソース設定→再生後に行うこと
- **`adb shell input tap`での自動UIテストは画面座標依存**: uiautomator dumpでboundsを取ってから叩く。失敗したらユーザーに依頼する
- **wavファイルのMediaStore反映遅延**: `MEDIA_SCANNER_SCAN_FILE`ブロードキャストで促す。それでもfile://再生が`READ_MEDIA_AUDIO`不許可で失敗する場合は `adb shell pm grant` で先に権限付与
