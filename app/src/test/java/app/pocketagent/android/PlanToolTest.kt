package app.pocketagent.android

import app.pocketagent.android.tools.ToolOutcome
import app.pocketagent.android.tools.UpdatePlanTool
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** 桌面 Codex 的 update_plan 等价物：多步任务靠它跟踪进度。 */
class PlanToolTest {

    private fun text(outcome: ToolOutcome): String = when (outcome) {
        is ToolOutcome.Ok -> outcome.text
        is ToolOutcome.Error -> outcome.message
    }

    @Test
    fun `renders a plan with status marks`() = runTest {
        val out = UpdatePlanTool().execute(
            """{"explanation":"分三步","plan":[
                 {"step":"读文件","status":"completed"},
                 {"step":"改配置","status":"in_progress"},
                 {"step":"验证","status":"pending"}]}""",
        )
        val t = text(out)
        assertTrue(t, out is ToolOutcome.Ok)
        assertTrue(t, t.contains("1. [x] 读文件"))
        assertTrue(t, t.contains("2. [>] 改配置"))
        assertTrue(t, t.contains("3. [ ] 验证"))
        assertTrue(t, t.contains("分三步"))
    }

    @Test
    fun `rejects two in-progress steps and empty plans`() = runTest {
        val two = UpdatePlanTool().execute(
            """{"plan":[{"step":"a","status":"in_progress"},{"step":"b","status":"in_progress"}]}""",
        )
        assertTrue(text(two), two is ToolOutcome.Error)
        assertTrue(text(two).contains("只能有 1 个"))

        val empty = UpdatePlanTool().execute("""{"plan":[]}""")
        assertTrue(text(empty), empty is ToolOutcome.Error)
    }

    @Test
    fun `rejects invalid status and oversized plans`() = runTest {
        val bad = UpdatePlanTool().execute("""{"plan":[{"step":"a","status":"doing"}]}""")
        assertTrue(text(bad), bad is ToolOutcome.Error)

        val many = (1..13).joinToString(",") { """{"step":"s$it","status":"pending"}""" }
        val big = UpdatePlanTool().execute("""{"plan":[$many]}""")
        assertTrue(text(big), big is ToolOutcome.Error)
        assertTrue(text(big).contains("最多 12"))
    }
}
