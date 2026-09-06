# Architecture

## Overview

```text
CameraX
├─ Preview ───────────────→ Compose overlay
├─ VideoCapture ──────────→ Segment recorder ─→ Ring buffer
└─ ImageAnalysis ─────────→ ML Kit / LiteRT
                                  │
ARCore Depth ─────────────────────┤
GPS ──────────────────────────────┤
Accelerometer / Gyro / Rotation ──┤
Battery / Thermal ────────────────┘
                                  │
                                  ▼
                         Telemetry repository
                                  │
                       elapsedRealtimeNanos
                         ┌────────┴────────┐
                         ▼                 ▼
                    Event detector      Player
                         │                 │
                         ▼                 ▼
                  Protected clips     UI overlay
```

## Modules

### recording

責務:

- CameraX VideoCapture
- recording session lifecycle
- segment rotation
- ring retention
- protected segment handling
- encoder / camera error recovery

### camera-ui

責務:

- CameraX Preview
- Compose overlay
- REC / speed / GPS indicator
- manual SAVE
- lens / quality selection

### telemetry

責務:

- location
- accelerometer
- gyroscope
- rotation vector
- battery / thermal
- timestamp normalization
- persistence

### events

責務:

- manual save
- hard braking
- acceleration
- turn / rotation
- impact
- optional front-approach heuristics
- event severity / confidence

### vision

責務:

- CameraX ImageAnalysis
- ML Kit
- LiteRT custom model
- detection / tracking metadata

### depth

責務:

- ARCore Depth
- estimated object distance
- depth quality / validity handling

Depth は安全制御には使用せず、記録・イベント解析用の推定メタデータとして扱う。

### ai

責務:

- Gemini Nano / AICore integration
- event後の画像・メタ情報要約

常時 LLM 推論は初期設計の必須条件にしない。イベント駆動を優先する。

## Time model

映像と telemetry の結合キーは wall clock ではなく monotonic time とする。

基準:

- `SystemClock.elapsedRealtimeNanos()`
- `SensorEvent.timestamp`
- `Location.elapsedRealtimeNanos`

人間向け日時表示のため epoch timestamp も記録する。

```text
recording_start_elapsed_ns
       + relative playback position
       = telemetry query timestamp
```

## Persistence model

概念モデル:

```text
RecordingSession
  └─ RecordingSegment[]
        ├─ videoUri
        ├─ startElapsedNs
        ├─ endElapsedNs
        ├─ protected
        └─ quality profile

TelemetrySample
  ├─ elapsedNs
  ├─ location
  ├─ imu
  ├─ depth
  └─ device state

DashcamEvent
  ├─ elapsedNs
  ├─ type
  ├─ severity
  ├─ confidence
  └─ protected segment references
```

高頻度 IMU の永続化形式は、実測後に Room row 単位・batch・binary/protobuf 等を比較して決める。

## Recording quality

初期候補:

- HIGH: 4K / 30fps / H.265
- BALANCED: 1080p / 60fps / H.265
- ECO: 1080p / 30fps / H.265

thermal state 等を根拠に quality downgrade を可能にする設計にしておく。

## Foreground execution

録画中は Foreground Service を中心に以下を所有する。

- camera recording
- location
- sensor collection
- ring buffer lifecycle

Activity / Compose UI は表示・操作層として扱い、Activity の破棄が即録画停止にならない構成を目指す。

## Failure handling

最低限区別する。

- camera unavailable
- encoder finalize error
- storage insufficient
- location unavailable
- sensor unavailable
- thermal degradation
- depth unavailable
- ML unavailable

AI / Depth は degraded mode を許容するが、録画保存失敗は主要障害として扱う。

## Security / privacy

- 初期はローカル保存を基本とする
- 不要なネットワーク送信をしない
- 位置情報を含むため共有・エクスポートは明示操作に限定する
- secret / credential を録画メタデータへ混入させない
