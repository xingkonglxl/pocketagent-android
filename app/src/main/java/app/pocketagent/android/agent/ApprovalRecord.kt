package app.pocketagent.android.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 桌面 Codex 的审批复核结果（`{"outcome":"allow","rationale":"..."}`）会随上下文同步到手机。
 * 它既不该当正文显示，也不该回灌给模型（模型会把它当成自己上一轮的回答去模仿）。
 */
data class ApprovalRecord(
    val outcome: String,
    val rationale: String,
    val raw: String,
) {
    val allowed: Boolean
        get() = outcome.equals("allow", true) || outcome.equals("approved", true)

    companion object {
        private val OUTCOMES = setOf(
            "allow", "allowed", "approved", "deny", "denied", "reject", "rejected",
        )
        private val RECORD_KEYS = setOf(
            "outcome", "rationale", "risk_level", "user_authorization",
        )

        fun parse(text: String): ApprovalRecord? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{") || !trimmed.contains("\"outcome\"")) return null
            val obj = runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            if (obj != null) {
                val outcome = (obj["outcome"] as? JsonPrimitive)?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: return null
                if (outcome.lowercase() !in OUTCOMES) return null
                if (!obj.keys.all { it in RECORD_KEYS }) return null
                val rationale = (obj["rationale"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                return ApprovalRecord(outcome, rationale, trimmed)
            }
            // 同步过来的记录可能已被截断（JSON 不完整），退回正则抽取
            val outcome = Regex("\"outcome\"\\s*:\\s*\"([^\"]+)\"").find(trimmed)
                ?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
            if (outcome.lowercase() !in OUTCOMES) return null
            val rationale = Regex("\"rationale\"\\s*:\\s*\"([^\"]*)").find(trimmed)
                ?.groupValues?.getOrNull(1).orEmpty()
            return ApprovalRecord(outcome, rationale, trimmed)
        }
    }
}
