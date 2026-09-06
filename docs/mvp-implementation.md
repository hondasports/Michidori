# MVP implementation

このリポジトリには、Michidori の最初の動く録画縦切りを実装している。映像と telemetry は端末内に分離保存し、両方を `SystemClock.elapsedRealtimeNanos()` 系の単調時刻で結ぶ。

## 実装済みの範囲

- Jetpack Compose の走行用画面
- CameraX `Preview` / `VideoCapture`
- `RecordingService` による Foreground Service 録画
- HD優先・SDフォールバック、約1分のセグメント分割
- 未保護セグメントを最大10本保持するリング
- `SAVE` で `MANUAL_SAVE` イベントを書き、イベント前後の完成済みセグメントを保護
- GPS、加速度、ジャイロ、回転ベクトルのローカル telemetry
- GPS unavailable 時も映像録画を継続する degraded path
- リング保持、イベント保護、timestamp、telemetry serialization の JVM テスト

動画は `files/recordings/*.mp4`、セグメント索引は `segments.tsv`、イベントは `events.tsv`、telemetry は `files/telemetry/telemetry.ndjson` に保存する。外部サービスへの送信・共有・エクスポートは実装しておらず、Androidのcloud backup/端末転送からも除外している。

## Verification contract

| ID | 内容 | Evidence |
| --- | --- | --- |
| AC01 | debug APKをビルドして起動 | `lint`, `testDebugUnitTest`, `assembleDebug` が PASS |
| AC02 | CameraX Preview と Service所有の録画 | Pixel 10 Pro で `READY → REC`、mp4生成を確認 |
| AC03 | 約1分セグメントと10本リング | `SegmentStoreTest` の保持/削除テスト |
| AC04 | SAVEと保護 | `SegmentStoreTest` の保護/再読込テスト、Pixel UIで `PROTECTED=1` |
| AC05 | telemetryと単調時刻 | `TelemetryStoreTest`、`MonotonicTimestampNormalizerTest` |
| AC06 | REC・時間・速度・GPS・件数表示 | Pixel UI dump で `REC`, `GPS OK`, 速度、件数を確認 |
| AC07 | GPS unavailableでも録画継続 | `TelemetryCollector` の権限/失敗分岐、Pixel実機のGPS表示経路 |
| IV01–IV04 | 録画保全、時刻基準、privacy、scope | 本文の保存モデルと manifest、レビューで確認 |

## 開発・検証コマンド

Windowsでは、依存解決のキャッシュ差分を避ける必要がある環境では任意の作業用 Gradle user home を指定する。

```powershell
$taskGradleHome = 'C:\path\to\gradle-home'
.\gradlew.bat --gradle-user-home $taskGradleHome lint testDebugUnitTest assembleDebug
```

Pixel 10 Pro のワイヤレスデバッグは、ADBのmDNSに出た接続先を使う。

```powershell
adb mdns services
adb connect <device-ip>:<tls-port>
adb -s <device-ip>:<tls-port> install -r app\build\outputs\apk\debug\app-debug.apk
```

実機で確認した最小フローは、カメラ/位置情報を許可して起動、`録画開始`、数秒後に `SAVE`、`停止`。確認結果は、Foreground Service、`REC`、`GPS OK`、速度表示、telemetry件数増加、`PROTECTED=1`、mp4/ts/ndjsonファイル生成が揃うことやった。

## 意図的な未実装

4K/30fps H.265、1080p/60fps、レンズ切替、ARCore Depth、ML Kit/LiteRT、Gemini Nano、自動イベント検知、thermal policy、長時間録画は次の計測sliceで扱う。Pixel 10 Pro の実測なしに初期品質を削らない方針やけど、今回の既定品質はエミュレータ互換性を優先してHD/SD fallbackにしている。

このMVPは安全運転支援や法的証拠を保証するものではない。推定値・GPS・映像は走行状況の後解析用として扱う。
