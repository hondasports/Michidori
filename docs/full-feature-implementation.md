# Full-feature implementation

正本ドキュメントの Full-feature experiment を、録画本体を優先する degraded-first の構成で実装している。映像は `files/recordings/clip_*.mp4` を正本とし、推定系は映像へ依存する別ログとして保存する。

## 実装範囲

- CameraX の `HIGH`（4K/30fps/H.265）、`BALANCED`（1080p/60fps/H.265）、`ECO`（1080p/30fps/H.265）を capability 順に選択する。H.265、品質、0.5x lens が利用できない場合は H.264、低い品質、main lens へ fallback し、要求 profile・実際の CameraX quality・適用結果と理由を `segments.tsv` に残す。0.5x はlogical back camera配下のCamera2 physical camera infosを焦点距離で選び、`CameraSelector.Builder.setPhysicalCameraId()` と `Camera2Interop.Extender.setPhysicalCameraId()` をPreview・VideoCapture・ImageAnalysisへ適用して、全use caseを同じ超広角sensorへbindする。
- battery level、charging、battery temperature、PowerManager thermal status を telemetry の monotonic sample に添付する。熱が `MODERATE` 以上なら HIGH→BALANCED、`SEVERE` 以上なら ECO へ下げ、セグメント境界で録画を継続したまま再bindする。
- `TYPE_LINEAR_ACCELERATION` と gyroscope から `HARD_BRAKE`、`HARD_ACCELERATION`、`SHARP_TURN`、`IMPACT` の候補を検出する。線形加速度が無い端末では重力込み加速度による方向性イベントを無効化し、誤検知を避ける。候補には severity、confidence、source、根拠値を付け、cooldown で重複を抑える。
- CameraX `ImageAnalysis` と ML Kit Object Detection STREAM_MODE で、複数物体・classification・tracking ID を扱う。分析 use case の bind に失敗しても `Preview`/`VideoCapture` だけへ戻して録画を続ける。
- LiteRT は `assets/traffic_model.tflite` が存在する場合に固定の `[1, featureCount] -> [1, 4]` 契約で接続できる adapter として実装した。現在はモデル asset を同梱しておらず、UI と `traffic.ndjson` に `MODEL_MISSING` を記録する。shape / inference エラーも `ERROR` の degraded 状態にする。
- ARCore の availability を確認し、SharedCamera/GL lifecycle が渡された場合だけ optional Session を開始して中央 depth pixel の距離、validity、confidence を `depth.ndjson` へ保存する。現行のCameraX recorderでは安全な degraded境界を選び、invalid sampleを残して録画を止めない。
- tracking ID、連続した valid depth、単調時刻が揃う時だけ closing speed と TTC を計算する。無効時は TTC/速度を `null` として `ttc.ndjson` へ保存し、`FRONT_APPROACH` は短い TTC の候補イベントとして扱う。
- Gemini Nano/AICore はイベント履歴の foreground UI の「説明」ボタンからだけ呼び出す。`AVAILABLE` でない場合はモデルの自動 download をせず、ローカル定型説明へ fallback する。映像・位置ログを prompt へ渡さない。
- イベント履歴、ローカル再生（Media3 ExoPlayer）、明示 export/share を実装した。共有は元動画のコピーと `metadata sidecar` の `ACTION_SEND_MULTIPLE` だけで、canonical clip は変更しない。

## 保存ファイル

| ファイル | 内容 |
| --- | --- |
| `recordings/segments.tsv` | 完成済み segment、要求 profile、実際の quality、codec、lens、保護状態 |
| `recordings/events.tsv` | manual / motion / TTC event の monotonic 時刻、severity、confidence、source、根拠 |
| `recordings/vision.ndjson` | ML Kit の tracking ID、label、box、confidence |
| `recordings/traffic.ndjson` | LiteRT status とローカルモデルの予測 |
| `recordings/depth.ndjson` | ARCore depth 距離、validity、confidence |
| `recordings/ttc.ndjson` | closing speed、TTC、validity、理由 |
| `recordings/event_analysis.tsv` | Gemini Nano または local fallback のイベント説明 |
| `recordings/exports/` | 明示 export 時だけ作る共有用 video copy と metadata sidecar |
| `telemetry/telemetry.ndjson` | GPS / IMU / rotation / battery / thermal |

全ログの結合基準は `SystemClock.elapsedRealtimeNanos()` 系の単調時刻や。epoch は表示用に別保持する。

## 検証

静的・JVM・instrumentation のコンパイル検証は次で実行する。

```powershell
.\gradlew.bat --gradle-user-home C:\Users\tatsuya\AppData\Local\Temp\MichidoriGradle lint testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin --no-daemon --console=plain
```

純粋ロジックは `MotionEventDetectorTest`、`TtcEstimatorTest`、リング・イベント・export の store tests で確認する。Emulator は本環境に emulator executable / AVD が無いため未実施とする。

Pixel 10 Pro は mDNS で `192.168.10.136:40097` に接続し、現行 debug APK で `RecordingServiceInstrumentedTest` を実行した。録画開始・約3秒継続・停止、HEVC codec、telemetry増加、invalid depth sampleを確認し、runnerのclean logに `FATAL EXCEPTION`、`ANR`、`AR_ERROR_MISSING_GL_CONTEXT` は無かった。Pixelのセンサー一覧にはlinear acceleration、gyroscope、rotation vectorが存在する。なお `HIGH` 要求時の実際のCameraX qualityは `HD (1280x720)` へfallbackしており、`actualQuality` として保存する。

実機のUI操作では、REC、GPS OK、AI READY、battery/thermal表示、H.265、0.5x物理lensの録画開始・停止を確認した。旧zoom-only経路ではPixelの `minZoomRatio=1.0` により0.5xを指定できへんかったが、現在はCamera2 physical camera infosから焦点距離 `2.02` の背面候補を選び、CameraX selectorに加えて各use caseのCamera2 interopへ物理IDを渡している。固定した机上の同一シーンで、1x→0.5x→1xのUI切替を行い、0.5xで広角になって1xで近景へ戻ることを画面で確認した。最新のUI録画とinstrumentation後のsegment metadataは `lens=ultra_wide_0_5x`、`codec=video/hevc`、`actualQuality`ありやった。

ARCore は availability probe までは実行するが、現行の録画構成では CameraX がカメラを所有しており、ARCore の通常 `Session` に必要な GL context と `SharedCamera` driver を持ってへん。そのため live Depth Session は意図的に起動せず、`DEPTH DEGRADED` と `valid=false` の sample を残す。以前の実装でこの条件を無視したときに native crash が実機で再現したため、録画保全を優先した安全な degraded 境界として固定している。live Depth を有効化するには、CameraX recorder と互換な `SharedCamera`/GL lifecycle を別途設計する必要がある。

残っている実機依存の追加計測は以下や。

- H.265 / 4K / 60fps の actual codec と segment metadata
- ML Kit frame throughput / dropped frame / recording integrity
- AICore status と foreground explanation
- thermal downgrade、battery drain、prolonged recording

## 安全・プライバシー境界

推定値は安全制御・ADAS・法的証拠として保証しない。ML Kit、LiteRT、ARCore の失敗は録画停止理由にせず、AI / Depth / TTC の値は validity と confidence を伴う候補として表示・保存する。Manifest に `INTERNET` は追加しておらず、share/export はユーザーが履歴から明示的に押した操作に限定する。

参照した API の公式資料:

- [ML Kit Object Detection and Tracking](https://developers.google.com/ml-kit/vision/object-detection/android)
- [ARCore Depth developer guide](https://developers.google.com/ar/develop/java/depth/developer-guide)
- [LiteRT on Android](https://developers.google.cn/edge/litert/android)
- [ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
- [CameraX Camera2Interop](https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2Interop)
- [CameraX CameraInfo physical cameras](https://developer.android.com/reference/androidx/camera/core/CameraInfo)
- [CameraX CameraSelector physical camera](https://developer.android.com/reference/androidx/camera/core/CameraSelector)
- [AndroidX Media3 Transformer / playback documentation](https://developer.android.com/media/media3)
