package com.michidori.app.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.michidori.app.recording.DashcamEvent

enum class ExplanationSource {
    GEMINI_NANO,
    LOCAL_FALLBACK,
}

data class EventExplanation(
    val eventId: String,
    val text: String,
    val source: ExplanationSource,
    val statusMessage: String,
)

/** Foreground-only event description. It never receives raw video or location data. */
class GeminiEventExplainer {
    private val generativeModel = runCatching { Generation.getClient() }.getOrNull()

    suspend fun explain(event: DashcamEvent): EventExplanation {
        val model = generativeModel
            ?: return fallback(event, "Gemini Nano clientを初期化できへん")
        val status = runCatching { model.checkStatus() }.getOrNull()
        if (status != FeatureStatus.AVAILABLE) {
            val message = when (status) {
                FeatureStatus.DOWNLOADABLE -> "Gemini Nanoが未ダウンロード"
                FeatureStatus.DOWNLOADING -> "Gemini Nanoを準備中"
                FeatureStatus.UNAVAILABLE -> "この端末ではGemini Nanoが利用できへん"
                else -> "Gemini Nanoの状態を確認できへん"
            }
            return fallback(event, message)
        }

        val response = runCatching {
            model.generateContent(
                "これは車載カメラが検出したイベント候補や。確定事実や安全判断として扱わず、" +
                    "短い日本語で記録上の観察メモを1〜2文だけ書いて。" +
                    "type=${event.type}, severity=${event.severity}, confidence=${event.confidence}, " +
                    "source=${event.source}, details=${event.details.orEmpty()}",
            )
        }.getOrNull()
        val text = response?.candidates?.firstOrNull()?.text?.trim().orEmpty()
        return if (text.isNotBlank()) {
            EventExplanation(
                eventId = event.id,
                text = text,
                source = ExplanationSource.GEMINI_NANO,
                statusMessage = "Gemini Nanoで生成（候補メモ）",
            )
        } else {
            fallback(event, "Gemini Nanoの応答が空やった")
        }
    }

    private fun fallback(event: DashcamEvent, reason: String): EventExplanation = EventExplanation(
        eventId = event.id,
        text = localText(event),
        source = ExplanationSource.LOCAL_FALLBACK,
        statusMessage = reason,
    )

    private fun localText(event: DashcamEvent): String = when (event.type) {
        "HARD_BRAKE" -> "急減速の候補。IMUの値を根拠にした記録メモで、実際の危険や原因を確定するものやないで。"
        "HARD_ACCELERATION" -> "急加速の候補。IMUの値を根拠にした記録メモで、実際の状況を確定するものやないで。"
        "SHARP_TURN" -> "急な旋回の候補。回転センサーの値を根拠にした記録メモやで。"
        "IMPACT" -> "衝撃の候補。加速度の急変を検出しただけで、接触や事故を確定するものやないで。"
        "FRONT_APPROACH" -> "前方物体が接近した可能性の候補。Depth/TTC推定値を含むため、validityとconfidenceを確認してな。"
        "MANUAL_SAVE" -> "ユーザーが手動保存したイベントやで。"
        else -> "イベント候補 ${event.type} のローカル記録メモやで。"
    }
}
