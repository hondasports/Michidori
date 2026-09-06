# Development process

Michidori は Windows 11 + Android Emulator + Codex を主開発ループとし、Pixel 10 Pro 実機はハードウェア依存検証に集中させる。

## Local loop

```text
IMPLEMENT
  ↓
./gradlew test / lint / assembleDebug
  ↓
Emulator boot
  ↓
adb install
  ↓
Launch app
  ↓
Inject GPS / sensors
  ↓
Exercise recording flow
  ↓
Inspect logcat / files / DB / screenshots
  ↓
VERIFY
  ↓
Fix only observed gaps
```

## Emulator responsibilities

原則 Emulator で検証するもの:

- Compose UI
- CameraX Preview / basic VideoCapture
- recording state machine
- segment rotation
- ring buffer retention
- manual SAVE
- Foreground Service lifecycle
- GPS injection / route playback
- accelerometer / gyro injection
- metadata synchronization
- Room / SQLite
- event detection logic
- failure UI
- E2E automation

## Pixel 10 Pro responsibilities

実機必須または実機優先:

- 4K/30fps H.265 stability
- 1080p/60fps stability
- 1x / 0.5x lens behavior
- real camera quality
- prolonged recording
- screen-off recording
- USB charging while recording
- thermal throttling
- actual battery drain
- real GPS noise
- real vibration / IMU characteristics
- ARCore Depth quality
- ML Kit performance
- Gemini Nano / AICore

## Performance-first experiment policy

初期段階は全部入りを許容する。

1. 機能を有効化する
2. measurable telemetry を取る
3. 実機で継続録画する
4. bottleneck を特定する
5. 問題がある機能だけ削る / 周期を下げる / event-driven にする

推測だけで品質を下げない。

## Suggested verification order

1. `./gradlew lint`
2. affected unit tests
3. recording / telemetry contract tests
4. Emulator functional flow
5. Emulator injected GPS / IMU scenarios
6. Pixel targeted scenario
7. prolonged Pixel run when recording or performance behavior changed

高コストな実機長時間検証は、関連しない docs/UI-only 変更では要求しない。

## E2E scenarios

最低限自動化したいシナリオ:

### Normal ring recording

- start recording
- create multiple short test segments
- exceed retention limit
- verify oldest unprotected segment is removed

### Manual event

- start recording
- inject telemetry
- press SAVE
- verify event is created
- verify associated segments become protected

### Hard brake

- start normal telemetry
- inject deceleration pattern
- verify HARD_BRAKE candidate
- verify false-positive guard where possible

### GPS loss

- record normally
- remove location updates
- verify video recording continues
- verify GPS state is represented as unavailable rather than fabricated

### Thermal degradation

Where injectable/testable, simulate degraded thermal state. Otherwise unit-test policy and verify on Pixel.

## Evidence

各変更で残すべき Evidence は task の AC / IV に必要な範囲だけにする。

例:

- Gradle command + result
- test name + result
- Emulator scenario + expected/actual
- Pixel model / Android version / quality profile
- recording duration
- thermal status
- dropped frames / errors

同じ content に対して同じ検証を理由なく繰り返さない。
