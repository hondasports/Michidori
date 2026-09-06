# Michidori Agent OS

Agent OS は依頼を軽量分類し、必要最小の loop route を選ぶための routing layer。

```text
Request
  ↓
Effect classification
  ↓
Task type / Complexity
  ↓
Risk / Required Controls
  ↓
Route: read_only | fast | standard | deep
  ↓
.loop/process.yaml
```

Agent OS は `.loop/process.yaml` を置き換えない。Requirement / Risk / Control / Verification / Delivery の正本は process contract とする。

## Route selection

- read-only 調査 → `read_only`
- tiny / small + low risk → `fast`
- medium / independent review required → `standard`
- large / cross-cutting / multiple hardware boundaries → `deep`

route は必要最小を選び、新しい scope・risk・hardware dependency が出た時だけ昇格する。

## Android-specific routing

ハードウェア依存があるだけで deep にしない。

例:

- Compose 文言修正 → fast
- ring buffer ロジック変更 → standard
- CameraX + Foreground Service lifecycle 変更 → standard/deep depending on scope
- camera + thermal + Depth + AI を跨ぐ performance redesign → deep

## Device verifier

Pixel 10 Pro での確認は独立した「全部入りゲート」ではない。次の assertion を証明する必要がある task だけで使う。

- codec / resolution / lens
- prolonged recording
- thermal / battery
- actual GPS / IMU
- ARCore Depth
- ML / AICore

## Human Gate

R4 や device test 自体を理由に Human Gate を起動しない。

Human Gate は irreversible / sensitive external write、credential 操作、または調査後も materially 異なる仕様選択が残る場合に限定する。
