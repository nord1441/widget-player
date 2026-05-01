# Android音楽プレイヤー (Shuffleプレイヤー) 設計書

## このアプリの位置付け

ファイルシステム(m3uとメディアファイル)を真実とする、ウィジェット主体の最小限プレイヤー。iPod shuffle的な使用感を目指す。**他アプリの存在を一切知らない**純粋な再生機。

m3uや音声ファイルがどこから来たかには関心を持たない。手動で置こうが、別アプリ(同期デーモン等)が置こうが、同じように扱う。

## コンセプト

- **ウィジェット = メインUI**。アプリ本体は設定画面のみ
- **m3u一本槍**。ライブラリ管理機能は持たない
- **動画ファイルも音声トラックだけ再生**(shuffle中に動画が混ざっても安全)
- **失敗即スキップ**。ストリーミング切れやファイル不在で止まらない

## 技術スタック

- **言語**: Kotlin
- **UI**: 設定画面はComposeでもXMLでも可(規模が小さいので好みで)
- **再生**: AndroidX Media3 (ExoPlayer + MediaSessionService)
- **永続化**: SharedPreferences(現在のソース、再生位置等)+ Room(エラーログのみ)
- **最小API**: 26 (Android 8.0)
  - `requestPinAppWidget`が26+
  - Media3は21+だが、API分離(`READ_MEDIA_AUDIO`等)が33+のため分岐は必要

## 起動経路

1. **`ACTION_VIEW` インテント** — 外部から.m3uや音声/動画ファイル、URLを受領
   - インテントフィルタ: `audio/*`、`video/*`、`audio/x-mpegurl`、`application/vnd.apple.mpegurl`、`http`/`https`スキーム
2. **設定画面からのソース選択** — SAFでm3uファイル or フォルダ選択
3. **ウィジェットのplayボタン** — 直近のソースを復元して再生

## 永続化する状態 (これだけ)

- 最後に開いたソースのURI(m3uファイル or tree URI)
- 現在のトラックindex
- 現在のトラックの再生位置 (ms)
- shuffleのon/off
- リピートモード(off / one / all)
- shuffle有効時の順序(seedまたはindex配列)

## アーキテクチャ

### コアコンポーネント

```
┌─────────────────────────────────────────┐
│  AppWidgetProvider (1x1, 2x1, 4x1, 4x2) │
│  └─ PendingIntentでServiceにコマンド   │
└────────────────┬────────────────────────┘
                 │
┌────────────────▼────────────────────────┐
│  PlaybackService (MediaSessionService)  │
│  ├─ ExoPlayer                            │
│  ├─ MediaSession                         │
│  └─ メディア通知                         │
└────────────────┬────────────────────────┘
                 │
        ┌────────┴────────┐
        │                 │
┌───────▼──────┐  ┌──────▼──────────┐
│  M3uParser   │  │  SourceLoader   │
│              │  │  (SAF/直URI)    │
└──────────────┘  └─────────────────┘
```

### m3uパーサ仕様

- `#EXTINF:duration,Artist - Title` 形式のメタを拾う
- 本体行を以下に分類:
  - 絶対パス(`/storage/...`)
  - 相対パス → m3u自体のURIから解決
  - HTTP/HTTPS URL → そのまま渡す
  - `content://` URI → そのまま渡す
- `#EXTM3U` ヘッダの有無は問わない
- 空行・`#`で始まるその他コメントは無視
- 文字コードはUTF-8優先、BOM処理あり

### フォルダソース

- `ACTION_OPEN_DOCUMENT_TREE` でtree URI取得、`takePersistableUriPermission`で永続化
- `DocumentFile.fromTreeUri` で再帰走査
- 拡張子でメディア判定(対応拡張子はホワイトリスト方式)
- ソート: ファイル名昇順 or shuffle時はそれを元配列としてシャッフル

## 動画ファイルの扱い

ExoPlayerの`TrackSelectionParameters`で動画トラックを完全に無効化:

```kotlin
player.trackSelectionParameters = player.trackSelectionParameters
    .buildUpon()
    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
    .build()
```

これでmp4/mkv/webm等を食わせても音声だけ再生される。Surfaceは要らない。
HLS(.m3u8)動画ストリームも同様に音声だけ拾える。

## ウィジェット仕様

### サイズ展開 (resizeMode指定で可変)

- **1x1**: play/pause のみ
- **2x1**: prev / play-pause / next
- **4x1**: 上記 + トラック名表示
- **4x2**: 上記 + アートワーク + シークバー(任意)

### 操作と通信

- ボタン押下 → `PendingIntent` で直接 `MediaSessionService` にコマンド送信
- `MediaController` の都度bindは避ける(レイテンシ削減)
- 状態変化時は `AppWidgetManager.updateAppWidget()` で再描画
  - 再生状態、トラック変更時にService側からトリガー

## メディア通知

`MediaSessionService`が出すフォアグラウンド通知。これは**もう一つのリモコン**として位置付ける。
ロック画面・通知シェード・Bluetoothリモコン・Android Auto等からの操作経路が自動で得られる。

アートワーク取得失敗時のフォールバック画像を1枚同梱する。

## エラーハンドリング

### スキップ動作

`Player.Listener#onPlayerError` で `PlaybackException` を捕捉:

1. エラーログをRoomに記録
2. `player.seekToNext()` → `player.prepare()` → `player.play()`
3. 連続失敗カウンタをインクリメント

### 連続失敗ガード

- 連続5回失敗で停止し、通知に「再生不可: ネットワーク確認してください」等を表示
- 1回でも成功したらカウンタリセット

### エラーログ (Room 1テーブル)

```
table: playback_errors
- id: Long (PK, autoincrement)
- timestamp: Long
- uri: String
- track_label: String?  (m3uの#EXTINFから)
- error_code: Int       (PlaybackException.errorCode)
- error_category: String (IO / DECODER / PARSING / OTHER)
- error_message: String
```

- 直近100件でローテーション(古いものから削除)
- 設定画面から閲覧可能
- 長押しで「このURIを今後スキップ」(除外リスト機能、必要なら別テーブル)

### エラー分類のマッピング

- `ERROR_CODE_IO_*` → "IO" (ネットワーク/ファイル不在)
- `ERROR_CODE_DECODER_*` → "DECODER" (コーデック非対応)
- `ERROR_CODE_PARSING_*` → "PARSING" (壊れたファイル/プレイリスト)
- その他 → "OTHER"

## 設定画面

最小限の項目のみ:

1. **現在のソース表示 + 変更ボタン**
   - .m3uファイル選択 / フォルダ選択 / クリア
2. **shuffle on/off**
3. **リピートモード** (off / one / all)
4. **エラーログ閲覧**
   - 一覧表示、エラー種別ごとに色分け
   - 長押しで除外リスト追加(オプション)
5. **ウィジェット追加導線**
   - `requestPinAppWidget()` でワンタップ追加(API 26+)
   - 初回起動時にも案内

## 権限

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<!-- POST_NOTIFICATIONSはAPI 33+ -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

ファイルアクセスはSAF経由のみとする方針なら`READ_MEDIA_*`系は不要。シンプルさ優先でこの方針を推奨。

## 想定される実装上の罠

- **m3u内の相対パス解決**: m3u自体がcontent:// URIの場合、`DocumentFile`でparent取得して結合する必要がある。`File`の感覚で書くとハマる
- **ストリーミング失敗のリトライ**: スリープ復帰時にコネクション切れる。ExoPlayerのデフォルト再試行で大体は復帰するが、長時間切断後は手動で`prepare()`が必要なケースあり
- **ウィジェット更新の頻度**: 再生位置を秒単位で更新するとバッテリー食う。トラック変更・状態変化時のみ更新で十分
- **shuffle再現性**: アプリ再起動後に同じシャッフル順を維持するなら、ExoPlayerの内部shuffleではなく自前でindex配列を作って永続化
- **無音動画ファイル**: 音声トラック持たないmp4は無音再生される。連続失敗ガードに引っかからないので注意(実用上は無視可)

## ディレクトリ構成案

```
app/src/main/
├── kotlin/com/example/shuffleplayer/
│   ├── MainActivity.kt          # 設定画面のみ
│   ├── playback/
│   │   ├── PlaybackService.kt   # MediaSessionService
│   │   ├── M3uParser.kt
│   │   ├── SourceLoader.kt
│   │   └── ErrorHandler.kt
│   ├── widget/
│   │   ├── PlayerWidgetProvider.kt
│   │   └── WidgetUpdater.kt
│   ├── data/
│   │   ├── Prefs.kt
│   │   ├── ErrorLogDb.kt
│   │   └── ErrorLogDao.kt
│   └── settings/
│       ├── SettingsScreen.kt
│       └── ErrorLogScreen.kt
└── res/
    ├── layout/                  # ウィジェットレイアウト各サイズ
    └── xml/
        └── widget_info.xml      # AppWidgetProviderInfo
```

## 開発の進め方提案

1. PlaybackServiceとM3uParserから着手。コマンドラインから.m3u食わせて再生確認
2. 設定画面で手動ソース選択+再生UIを暫定で作る(後で削除)
3. ウィジェット(2x1)を最小実装、PendingIntent経由で操作確認
4. エラーハンドリング(スキップ + ログ)
5. ウィジェットのサイズバリエーション、メタ表示
6. shuffle/repeat
7. 仕上げ(通知アイコン、フォールバック画像、UI整え)
