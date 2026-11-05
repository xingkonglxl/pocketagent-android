package app.pocketagent.android.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.pocketagent.android.net.ToolSpec

/**
 * 桌面 Codex 的 update_plan：把多步任务拆成计划并跟踪状态。
 * 手机端之前没有这个工具，长任务容易中途跑偏；这里补齐同样的语义，
 * 计划本身会作为工具结果留在上下文里，后续步骤照着执行。
 */
class UpdatePlanTool : Tool {

    override val spec = ToolSpec(
        name = "update_plan",
        description = "为多步任务维护一份执行计划（最多 12 步）。" +
            "每一步给 step 和 status（pending / in_progress / completed）；" +
            "同一时间只应有一个 in_progress。简单的一步任务不要调用。",
        parametersJson = """
        {
          "type": "object",
          "properties": {
            "explanation": {"type": "string", "description": "可选：为什么这样拆"},
            "plan": {
              "type": "array",
              "maxItems": 12,
              "items": {
                "type": "object",
                "properties": {
                  "step": {"type": "string"},
                  "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]}
                },
                "required": ["step", "status"]
              }
            }
          },
          "required": ["plan"]
        }
        """.trimIndent(),
    )

    override suspend fun execute(argumentsJson: String): ToolOutcome {
        val root = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }.getOrNull()
            ?: return ToolOutcome.Error("参数必须是 JSON 对象")
        val plan = root["plan"] as? JsonArray
            ?: return ToolOutcome.Error("缺少 plan 数组")
        if (plan.isEmpty()) return ToolOutcome.Error("plan 不能为空")
        if (plan.size > 12) return ToolOutcome.Error("plan 最多 12 步，收到 ${plan.size}")

        val rendered = StringBuilder()
        var inProgress = 0
        plan.forEachIndexed { i, item ->
            val obj = item as? JsonObject ?: return ToolOutcome.Error("第 ${i + 1} 步必须是对象")
            val step = obj["step"]?.jsonPrimitive?.content?.trim().orEmpty()
            val status = obj["status"]?.jsonPrimitive?.content?.trim()?.lowercase().orEmpty()
            if (step.isEmpty()) return ToolOutcome.Error("第 ${i + 1} 步缺少 step")
            if (status !in setOf("pending", "in_progress", "completed")) {
                return ToolOutcome.Error("第 ${i + 1} 步 status 非法：$status")
            }
            if (status == "in_progress") inProgress++
            val mark = when (status) {
                "completed" -> "[x]"
                "in_progress" -> "[>]"
                else -> "[ ]"
            }
            rendered.append(i + 1).append(". ").append(mark).append(' ').append(step).append('\n')
        }
        if (inProgress > 1) {
            return ToolOutcome.Error("同一时间只能有 1 个 in_progress，收到 $inProgress")
        }
        val explanation = root["explanation"]?.jsonPrimitive?.content?.trim().orEmpty()
        val head = if (explanation.isEmpty()) "计划已更新：" else "计划已更新（$explanation）："
        return ToolOutcome.Ok(head + "\n" + rendered.toString().trimEnd())
    }
}
