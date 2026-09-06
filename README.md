# Michidori

Pixel 10 Pro を車載して使う、個人向けスマートドラレコ実験アプリ。

「Michi（道） + Dori（鳥）」と「道撮り」を掛けた名前で、映像だけでなく GPS・加速度・ジャイロ・深度・オンデバイス AI を同じ時間軸で記録し、走行イベントを後から解析できることを目指す。

## Product direction

初期段階では機能を絞り込みすぎず、Pixel 10 Pro で使える要素を広く載せる。

- 4K/30fps を第一候補とした CameraX 録画
- 1 分前後の短いセグメントによる約 10 分のリングバッファ
- 手動保存・急減速・衝撃などのイベント時に前後クリップを保護
- GPS / speed / bearing / accuracy
- accelerometer / gyroscope / rotation vector
- battery / thermal state
- ARCore Depth API による推定深度
- ML Kit / LiteRT による軽量な映像解析
- Gemini Nano を用いたイベント後処理の検討
- カメラプレビュー上へのメタ情報オーバーレイ
- 元動画と telemetry は分離保存し、共通 timestamp で同期

実機計測後に CPU / GPU / thermal / dropped frames / battery / storage / camera stability を確認し、負荷の高い処理を削減・間引き・イベント駆動化して軽量化する。

## Recording model

通常録画は長時間ファイル 1 本ではなく、短いセグメントを循環保持する。

```text
clip_001.mp4
clip_002.mp4
...
clip_010.mp4
```

新しいセグメントを追加したら、保護されていない最古セグメントを削除する。イベント発生時は事故・ヒヤリハット前後のセグメントを通常削除対象から外す。

## Telemetry synchronization

動画・GPS・IMU・AI イベントは別々に保存し、`SystemClock.elapsedRealtimeNanos()` 系の単調増加時刻を共通基準として同期する。

```text
Video    ─────────────────────────>
GPS        ●────●────●────●
Accel    ::::::::::::::::::::::::::
Gyro     ::::::::::::::::::::::::::
Depth        ●──●──●──●
Event              ★
```

表示用の日時は epoch time を別途保持する。

## Development environment

主開発環境は Windows 11。

- Android Studio
- Android SDK / Emulator
- adb
- JDK
- Gradle
- Codex
- Git

まず Emulator 上で UI・録画制御・リングバッファ・GPS/センサー注入・イベント検出・Foreground Service・E2E を高速に回し、Pixel 10 Pro は実機依存検証の最終ゲートとして使う。

## Target stack

- Kotlin
- Jetpack Compose
- CameraX
- Foreground Service
- Fused Location Provider
- SensorManager
- Room / SQLite（telemetry / event metadata）
- ARCore Depth API
- ML Kit / LiteRT
- Gemini Nano / AICore（対応範囲のみ）

## Docs

- [Product concept](docs/product.md)
- [Architecture](docs/architecture.md)
- [Development process](docs/development-process.md)
- [MVP implementation](docs/mvp-implementation.md)
- [Full-feature implementation](docs/full-feature-implementation.md)
- [Agent loop](.loop/README.md)
- [Agent OS](.loop/AGENT_OS.md)

## Android Auto

個人利用では最終段階の実験機能として検討する。一般公開を行う場合は Android Auto の対応カテゴリ制約を踏まえ、機能を除外する可能性が高い。

## Principle

> 最初に最適化しすぎない。Pixel 10 Pro でできることを広く載せ、実測してから削る。
