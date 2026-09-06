# Product concept

## Goal

Michidori は Pixel 10 Pro を車載ドラレコとして活用し、映像と端末センサーを統合して走行状況を記録・解析する個人用 Android アプリである。

単なる録画アプリではなく、次を同じ時系列として扱う。

- 映像
- 位置
- 速度
- 方向
- 加速度
- 回転
- 推定深度
- AI 検出結果
- 手動 / 自動イベント

## MVP

最初の到達点は以下。

1. CameraX で安定して録画できる
2. 約 10 分のリング録画が動く
3. GPS / accelerometer / gyroscope が保存される
4. 動画と telemetry が timestamp で同期する
5. プレビュー上に速度・録画状態・GPS 状態を表示する
6. 手動 SAVE で前後クリップを保護できる
7. Foreground Service で画面 OFF 中も録画を継続できる
8. Emulator で主要フローを自動検証できる

## Full-feature experiment

初期フェーズでは以下も積極的に試す。

- 4K/30fps H.265
- 1080p/60fps 切替
- 1x / 0.5x レンズ切替
- ARCore Depth
- ML Kit object detection / tracking
- LiteRT の交通向けカスタムモデル
- 急減速 / 急旋回 / 衝撃イベント検知
- TTC 等の推定値
- Gemini Nano によるイベント後処理・説明生成
- thermal state に応じた品質フォールバック

## Recording policy

- 動画は 1 分前後の小さなセグメントへ分割する
- 通常は直近約 10 分だけ保持する
- 保護済みセグメントはリング削除対象から外す
- イベント保存では前後クリップをまとめて 1 イベントとして扱う
- 元動画を正本とし、通常時はメタ情報を映像へ焼き込まない
- エクスポート時のみ速度・日時等を焼き込む方式を許容する

## Telemetry

候補データ:

- monotonic timestamp
- epoch timestamp
- latitude / longitude
- speed
- bearing
- altitude
- location accuracy
- accelerometer XYZ
- gyroscope XYZ
- rotation vector
- battery state
- thermal state
- detected object metadata
- estimated depth
- TTC / closing speed
- event type / severity / confidence

## Event philosophy

事故判定そのものを AI に委ねない。イベント候補は GPS・IMU を主に用い、映像 AI は意味付け・補助証拠として扱う。

例:

- HARD_BRAKE
- HARD_ACCELERATION
- SHARP_TURN
- IMPACT
- MANUAL_SAVE
- FRONT_APPROACH

## UI

運転中の常時表示は最小限とする。

- REC 状態
- 録画時間
- 速度
- GPS 状態
- SAVE ボタン

加速度や AI 結果はイベント時だけ一時表示する。

## Non-goals for early phases

- 法的証拠能力の保証
- ADAS としての安全制御
- 自動ブレーキ等の車両制御
- クラウド必須設計
- Android Auto を初期要件にすること

## Optimization strategy

最初は機能を広く載せる。その後 Pixel 10 Pro 実機で次を測定し、削減対象を決める。

- thermal throttling
- dropped frames
- encoder failure
- CameraX stability
- battery drain
- memory
- CPU / GPU load
- storage throughput
- GPS / sensor quality

推測で早期に削らず、計測結果を根拠に軽量化する。
