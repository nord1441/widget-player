# Shuffleプレイヤー 再実装設計書 (2026-06-10)

## 背景

本リポジトリには2026-05の設計書(`docs/design.md` @ 旧ブランチ `claude/android-shuffle-player-HMbJ7`)に基づく実装が存在したが、実機(Unihertz A024 / Android 16)で再生とソース読み込みに不具合があった。本書は元設計を吟味し直し、弱点対策とテスト可能な構造を織り込んだ上でゼロから再実装するための設計書である。

## このアプリの位置付け(元設計から不変)

ファイルシステム(m3uとメディアファイル)を真実とする、ウィジェット主体の最小限プレイヤー。iPod shuffle的な使用感を目指す。他アプリの存在を一切知らない純粋な再生機。m3uや音声ファイルがどこから来たかには関心を持たない。

- ウィジェット = メインUI。アプリ本体は設定画面のみ
- m3u一本槍。ライブラリ管理機能は持たない
- 動画ファイルも音声トラックだけ再生
- 失敗即スキップ。ストリーミング切れやファイル不在で止まらない

## 元設計からの主な変更点(吟味の結果)

1. **テスト可能なコアの分離**: 再生順序・パース・エラー分類など全ロジックを純Kotlinモジュール`:core`に分離しTDDで実装。前回はAndroid依存と密結合で単体テスト不能だった
2. **フォアグラウンド5秒ルールの明文化**: サービスは起動即`startForeground()`、ソース復元は非同期。前回はここでタイムアウトクラッシュが発生した
3. **`DocumentFile`走査の廃止**: `DocumentsContract`子要素クエリ(1ディレクトリ1クエリ)で走査。`DocumentFile`はファイルごとにIPCが走り大フォルダで数十秒かかる
4. **解決済みプレイリストのキャッシュ**: フォルダ走査結果をJSONファイルに保存し、ウィジェット押下→即再生を実現。再走査は再生開始後に裏で実行
5. **オーディオフォーカス/becoming-noisy対応の追加**: 元設計に欠けていた
6. **スコープ削減**: 4x2ウィジェットのアートワーク+シークバー、エラーログ除外リストは作らない

## スコープ

### 含む

- m3uファイル/フォルダ(SAF)/HTTP(S)ストリーミングの再生(全て主用途)
- 動画ファイルの音声のみ再生(安全装置として)
- ウィジェット 1x1 / 2x1 / 4x1(リサイズで切替)
- shuffle / repeat(off・one・all)、再起動後のシャッフル順再現
- 失敗即スキップ + 連続5回失敗ガード + エラーログ(Room、直近100件)
- 設定画面(ソース選択・shuffle/repeat・エラーログ閲覧・ウィジェット追加導線)
- ACTION_VIEW受領(m3u / 音声 / 動画 / http(s) URL)
- メディア通知(MediaSession経由でロック画面・Bluetooth操作が自動で付く)

### 含まない

- アートワーク表示、シークバー、除外リスト、ライブラリ管理、検索、イコライザ等一切

## 技術スタック

- Kotlin / minSdk 26 / targetSdk 35、AGP 8.7系、Kotlin 2.0系(旧実装のバージョンカタログを踏襲)
- AndroidX Media3 1.5(ExoPlayer + MediaSessionService + HLS)
- 設定画面はJetpack Compose(Material3)
- SharedPreferences(状態)+ JSONファイル(プレイリストキャッシュ)+ Room(エラーログのみ)
- JSONシリアライズは kotlinx.serialization(`:core`が純KotlinのためGson/Moshi等のリフレクション系より適合)
- パッケージ/applicationId: `com.example.shuffleplayer`(実機の旧版を上書き更新できる)

## モジュール構成

```
:core … 純Kotlin (kotlin("jvm"))。Android SDK依存ゼロ。JUnitでTDD
:app  … Androidシェル。Media3 / ウィジェット / SAF / Room / Compose
```

### :core コンポーネント

| コンポーネント | 責務 |
|---|---|
| `M3uParser` | m3uテキスト → `List<M3uEntry>`(location文字列、#EXTINFのラベル・長さ)。#EXTM3U有無不問、BOM除去、CRLF対応、空行・コメント無視 |
| `EntryResolver` | locationを分類: 絶対パス / 相対パス / http(s) / content://。相対パスは注入された`PathResolver`インターフェースで解決(content://実装は:app側、file実装は純Kotlin) |
| `MediaFileFilter` | 拡張子ホワイトリスト判定。音声: mp3, m4a, aac, ogg, opus, flac, wav, mka / 動画: mp4, mkv, webm, mov, avi。**フォルダ走査ではこの音声+動画のみ採用**(フォルダ内の.m3u/.m3u8はスキップ。プレイリスト拡張子はACTION_VIEW受領時の種別判定にのみ使用) |
| `ShuffleOrder` | シードからの順列生成。`originalIndex ⇄ shuffledIndex`変換。トラック数とシードが同じなら同一順序(再起動後の再現性) |
| `FailureGuard` | 連続失敗カウンタ。5回で`shouldStop=true`、成功でリセット |
| `ErrorClassifier` | エラーコード整数 → IO / DECODER / PARSING / OTHER。PlaybackExceptionのコード帯域(2xxx=IO, 3xxx=PARSING, 4xxx=DECODER)で分類 |
| `PlayerState` | 永続化状態のモデル: sourceUri, sourceType(M3U/FOLDER/SINGLE), trackIndex, positionMs, shuffleEnabled, shuffleSeed, repeatMode |
| `Playlist` / `Track` | 解決済みトラックリストのモデル(uri文字列 + ラベル)とJSONシリアライズ(キャッシュ用) |

### :app コンポーネント

| コンポーネント | 責務 |
|---|---|
| `PlaybackService` | MediaSessionService。ExoPlayer保持、コア配線、コマンド受領、状態保存、ウィジェット更新トリガー |
| `SourceRepository` | ソース解決の入口。キャッシュ読み込み、m3u再パース、フォルダ再走査、キャッシュ書き戻し |
| `FolderScanner` | `DocumentsContract.buildChildDocumentsUriUsingTree`による再帰走査。ファイル名昇順ソート |
| `M3uLoader` | content URIからm3uテキスト読み出し + content://用`PathResolver`実装(tree URIの親子関係から相対解決) |
| `PlayerWidgetProvider` | AppWidgetProvider。`onAppWidgetOptionsChanged`でセル幅に応じレイアウト切替 |
| `WidgetRenderer` | 永続化状態 + 再生状態 → RemoteViews構築・更新 |
| `MainActivity` | Compose設定画面 + ACTION_VIEW受領 |
| `Prefs` | SharedPreferencesラッパ(`PlayerState`の読み書き) |
| `ErrorLogDb` / `ErrorLogDao` | Room 1テーブル、直近100件ローテーション |

## 再生フロー

### ウィジェットからのコールドスタート(最重要経路)

Media3公式の「再生再開(playback resumption)」パターンを使う。自前のフォアグラウンド管理を持たず、5秒ルール対応をMedia3に委譲する(前回はここを自前管理してタイムアウトクラッシュした)。

```
ウィジェットボタン → PendingIntent.getBroadcast(ACTION_MEDIA_BUTTON + KeyEvent)
  → androidx.media3.session.MediaButtonReceiver(サービス起動とフォアグラウンド化はMedia3が管理)
  → セッションが空なら MediaSession.Callback.onPlaybackResumption が呼ばれる
      1. PlaylistCacheを読み即プレイリスト構築 → 保存済みindex/positionから再開
      2. フォルダソースの場合のみ裏で再走査 → 差分があればプレイリスト差し替え
         (再生中トラックは維持、消えていたら次へ)
  キャッシュ不在(初回)のみ走査完了を待ってから再生
```

この経路はBluetoothヘッドセットからの再生再開でも同一コードが使われる(一石二鳥)。

### コマンド経路

- ウィジェット → `MediaButtonReceiver`へのKeyEventブロードキャスト: PLAY_PAUSE / NEXT / PREV(MediaControllerバインドはしない)
- 通知・ロック画面・Bluetooth → Media3 MediaSessionが自動処理
- 設定画面 → MediaController(画面表示中のみbind)。ソース設定・shuffle/repeat変更はカスタムSessionCommandで送る

### ExoPlayer設定(再生堅牢化)

```kotlin
ExoPlayer.Builder(context)
  .setAudioAttributes(AudioAttributes(USAGE_MEDIA, CONTENT_TYPE_MUSIC), /* handleAudioFocus = */ true)
  .setHandleAudioBecomingNoisy(true)       // イヤホン抜きで一時停止
  .setWakeMode(C.WAKE_MODE_NETWORK)        // ストリーミング中のスリープ対策
  .build()
player.trackSelectionParameters = … .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true) … // 動画は音声のみ
```

- cleartext HTTP許可(`usesCleartextTraffic="true"`)— 自宅サーバ等のHTTP配信用
- shuffleはExoPlayer内蔵シャッフルを使わず、`:core`の`ShuffleOrder`で並べた順にMediaItemを投入(再現性の確保)。shuffle切替時は新しい順序でキューを組み直し、再生中トラックはそのまま継続する
- repeat(off/one/all)はExoPlayerの`repeatMode`をそのまま使う(自前実装しない)。repeat=offで最終トラックのエラー時は次が無いため停止する

## エラーハンドリング

`Player.Listener.onPlayerError`で捕捉:

1. `ErrorClassifier`で分類し、Roomへ記録(timestamp, uri, track_label, error_code, error_category, error_message)。挿入後100件超過分を古い順に削除
2. `FailureGuard.onFailure()` → 継続なら `seekToNext()` → `prepare()` → `play()`
3. 5回連続失敗で停止。通知とウィジェットに「再生不可: 接続を確認」表示
4. トラックがSTATE_READYに達したら`FailureGuard.onSuccess()`でリセット

## 永続化

| データ | 保存先 | 保存タイミング |
|---|---|---|
| `PlayerState`(ソースURI・種別、index、位置、shuffle、シード、repeat) | SharedPreferences | 一時停止時 / トラック遷移時 / onDestroy時(秒間ポーリングしない) |
| 解決済み`Playlist`(JSON) | `filesDir/playlist_cache.json` | ソース設定時 / 再走査完了時 |
| エラーログ | Room | エラー発生時 |

tree URI・m3u URIは`takePersistableUriPermission`で永続化する。

## ウィジェット仕様

- 1つの`AppWidgetProvider`、`resizeMode="horizontal|vertical"`。`onAppWidgetOptionsChanged`の`minWidth`セル数でレイアウト選択:
  - 1セル: play/pause
  - 2-3セル: prev / play-pause / next
  - 4セル以上: 上記 + トラック名(#EXTINFラベル優先、無ければファイル名)
- 描画は常に「SharedPreferencesの状態 + サービスから渡される再生中フラグ」から構築。サービス死亡中もボタンと最後のトラック名を正しく表示
- 更新トリガー: 再生/一時停止、トラック遷移、エラー停止、ソース変更時のみ(秒単位更新はしない)
- 連続失敗停止時はトラック名欄にエラーメッセージ表示

## 設定画面(Compose 2画面)

**メイン画面:**
1. 現在のソース表示(種別アイコン + 表示名)+「m3u選択」「フォルダ選択」「クリア」
2. shuffleトグル / repeatモード選択(off・one・all)
3. 「ウィジェットを追加」ボタン(`requestPinAppWidget`)
4. エラーログ画面への導線

**エラーログ画面:** 直近100件を新しい順、カテゴリで色分け(IO=橙、DECODER=紫、PARSING=赤、OTHER=灰)。

**ACTION_VIEW受領:** m3u(各種MIME)/ audio/* / video/* / http(s)を受領 → ソースとして保存 → サービス起動して即再生 → 設定画面表示。

## m3u内パスエントリの解決と権限(元設計の重大な穴の修正)

SAFで選んだm3uは**単一ドキュメント許可**しか持たないため、m3u内の相対パス・絶対パスが指す兄弟ファイルはSAFだけでは読めない。元設計の「READ_MEDIA_*不要」はm3u主用途と両立しない。対策:

- **絶対パス**(`/storage/...`)→ `file://` URIとして再生。`READ_MEDIA_AUDIO/VIDEO`(API 33+)または`READ_EXTERNAL_STORAGE`(26-32)が必要
- **相対パス** → m3uのdocId(例 `primary:Music/list.m3u`)から親ディレクトリを取り、純Kotlinの`joinRelative`で結合:
  - プロバイダが`com.android.externalstorage.documents`かつroot=`primary`なら `/storage/emulated/0/<path>` の`file://`に解決(要・上記権限)
  - それ以外は同一プロバイダの兄弟docId `content://` URIを構築して試行(アクセス不可ならエラー→スキップで自然に処理)
- 権限はm3uソースにパスエントリが含まれる場合のみ設定画面から要求する(フォルダ/ストリーミング用途では要求しない)
- 解決できない・読めないトラックは失敗即スキップ+エラーログという既定動作に乗せる

## マニフェスト要点

- 権限: FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, INTERNET, WAKE_LOCK, POST_NOTIFICATIONS, READ_MEDIA_AUDIO / READ_MEDIA_VIDEO(33+)/ READ_EXTERNAL_STORAGE(maxSdkVersion=32)
- `PlaybackService`: `foregroundServiceType="mediaPlayback"`, MediaSessionServiceインテントフィルタ
- `androidx.media3.session.MediaButtonReceiver`をMEDIA_BUTTONインテントフィルタ付きで宣言

## テスト戦略

| 対象 | 方法 |
|---|---|
| `:core`全コンポーネント | TDD(JUnit 5、JVM)。境界ケース: BOM付きm3u、CRLF、#EXTINF欠落、相対パス(`../`含む)、URLエンコード済みパス、空プレイリスト、1曲のみ、シャッフル再現性、FailureGuard全遷移、シリアライズ往復 |
| `:app`アダプタ | 実装ステップごとの実機検証チェックポイント(adb install → ウィジェット操作 / `am start-foreground-service` / `dumpsys media_session` / logcat確認)。計装テストは持たない |

実機: Unihertz A024 / Android 16 (API 36) がadb接続済み。

## 実装順序(概要 — 詳細は実装計画で)

1. プロジェクトスキャフォールド(2モジュール、ビルド確認、実機インストール確認)
2. `:core`をTDDで完成(パーサ → リゾルバ → シャッフル/リピート → ガード/分類 → 状態/キャッシュモデル)
3. PlaybackService最小再生(ローカルm3u直再生、実機で音が出ることを確認)
4. ソース読み込み(SAF m3u → フォルダ走査 → キャッシュ → HTTP)
5. ウィジェット(2x1 → サイズバリエーション)
6. エラーハンドリング + Roomログ
7. 設定画面 + ACTION_VIEW
8. 仕上げ(通知アイコン、文言、リリースビルド)

各ステップ末にビルド + 実機検証を行い、コミットする。
