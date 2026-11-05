package app.pocketagent.android

import app.pocketagent.android.agent.*
import app.pocketagent.android.data.*
import app.pocketagent.android.net.*
import app.pocketagent.android.tools.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

private class SteeringClient(
    val script: suspend FlowCollector<LlmEvent>.(Int, List<ApiMessage>) -> Unit,
) : LlmClient {
    val requests = mutableListOf<List<ApiMessage>>()
    override fun stream(config: LlmConfig, systemPrompt: String, messages: List<ApiMessage>, tools: List<ToolSpec>) = flow {
        val snapshot = messages.toList()
        requests += snapshot
        script(requests.size, snapshot)
    }
    override suspend fun probe(config: LlmConfig): String? = null
}

class TurnSteeringTest {
    private val provider = Provider(
        name = "test", kind = ProviderKind.CHAT_COMPLETIONS,
        baseUrl = "https://example.invalid", model = "deepseek-test", autoApprove = true,
    )
    private val history = listOf(ApiMessage("user", "original task"))

    @Test fun `acknowledgement never removes a later arrival and capacity is bounded`() {
        val box = TurnSteering()
        val a = ChatMessage(role = Role.USER, text = "a")
        val b = ChatMessage(role = Role.USER, text = "b")
        assertTrue(box.offer(a))
        val sent = box.peek()
        assertTrue(box.offer(b))
        assertEquals(listOf(a), box.acknowledge(sent.map { it.id }.toSet()))
        assertEquals(listOf(b), box.peek())
        repeat(7) { assertTrue(box.offer(ChatMessage(role = Role.USER, text = "$it"))) }
        assertFalse(box.offer(ChatMessage(role = Role.USER, text = "overflow")))
        assertTrue(box.offer(b))
        assertEquals(8, box.drain().size)
        assertTrue(box.pending.value.isEmpty())
    }

    @Test fun `a thinking request is cancelled and multiple follow ups including images steer this same step`() = runTest {
        val box = TurnSteering()
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val client = SteeringClient { round, messages ->
            if (round == 1) {
                try {
                    emit(LlmEvent.ReasoningDelta("old reasoning"))
                    emit(LlmEvent.TextDelta("obsolete GOAL_DONE"))
                    started.complete(Unit)
                    awaitCancellation()
                } finally { cancelled = true }
            } else {
                assertEquals(listOf("original task", "new direction", "see image"), messages.map { it.content })
                assertEquals(listOf("data:image/png;base64,AAAA"), messages.last().images)
                emit(LlmEvent.TextDelta("adjusted answer"))
            }
        }
        val events = mutableListOf<AgentEvent>()
        val task = async {
            AgentRunner(ToolRegistry(emptyList()), AutoApproveGate) { client }
                .run(provider.copy(maxSteps = 1), "k", history, steering = box) { events += it }
        }
        started.await()
        box.offer(ChatMessage(role = Role.USER, text = "new direction"))
        box.offer(ChatMessage(role = Role.USER, text = "see image", images = listOf("data:image/png;base64,AAAA")))
        val result = task.await()
        assertTrue(cancelled)
        assertEquals("adjusted answer", result.text)
        assertEquals(1, result.steps)
        assertEquals(2, events.filterIsInstance<AgentEvent.FollowUpsApplied>().single().messages.size)
        assertTrue(box.peek().isEmpty())
    }

    @Test fun `an executing command finishes once but the next stale command is skipped before follow up`() = runTest {
        val box = TurnSteering()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var executions = 0
        val tool = object : Tool {
            override val spec = ToolSpec("shell_exec", "test", "{}")
            override suspend fun execute(argumentsJson: String): ToolOutcome {
                executions++
                started.complete(Unit)
                finish.await()
                return ToolOutcome.Ok("completed command")
            }
        }
        val client = SteeringClient { round, messages ->
            if (round == 1) {
                repeat(2) { emit(LlmEvent.ToolCallStarted(it, "c$it", "shell_exec")) }
            } else {
                assertEquals(listOf("user", "assistant", "tool", "tool", "user"), messages.map { it.role })
                assertEquals("completed command", messages[2].content)
                assertTrue(messages[3].content.contains("CANCELLED"))
                assertEquals("change direction", messages.last().content)
                emit(LlmEvent.TextDelta("done with correction"))
            }
        }
        val task = async {
            AgentRunner(ToolRegistry(listOf(tool)), AutoApproveGate) { client }
                .run(provider, "k", history, steering = box) {}
        }
        started.await()
        box.offer(ChatMessage(role = Role.USER, text = "change direction"))
        yield()
        assertFalse(task.isCompleted)
        assertEquals(1, executions)
        finish.complete(Unit)
        assertEquals("done with correction", task.await().text)
        assertEquals(1, executions)
    }

    @Test fun `new requirements dismiss an obsolete approval without executing the tool`() = runTest {
        val box = TurnSteering()
        val gate = UiApprovalGate()
        var executions = 0
        val tool = object : Tool {
            override val spec = ToolSpec("shell_exec", "test", "{}")
            override suspend fun execute(argumentsJson: String): ToolOutcome {
                executions++
                return ToolOutcome.Ok("must not run")
            }
        }
        val client = SteeringClient { round, _ ->
            if (round == 1) emit(LlmEvent.ToolCallStarted(0, "cmd", "shell_exec"))
            else emit(LlmEvent.TextDelta("changed"))
        }
        val task = async {
            AgentRunner(ToolRegistry(listOf(tool)), gate) { client }
                .run(provider.copy(autoApprove = false), "k", history, steering = box) {}
        }
        gate.pending.first { it != null }
        box.offer(ChatMessage(role = Role.USER, text = "skip that operation"))
        assertEquals("changed", task.await().text)
        assertNull(gate.pending.value)
        assertEquals(0, executions)
    }

    @Test fun `follow up during limit wrap up discards the obsolete completion marker`() = runTest {
        val box = TurnSteering()
        val wrapping = CompletableDeferred<Unit>()
        val tool = object : Tool {
            override val spec = ToolSpec("fs_read", "test", "{}")
            override suspend fun execute(argumentsJson: String) = ToolOutcome.Ok("result")
        }
        val client = SteeringClient { round, messages ->
            when (round) {
                1 -> {
                    emit(LlmEvent.TextDelta("old plan GOAL_DONE"))
                    emit(LlmEvent.ToolCallStarted(0, "read", "fs_read"))
                }
                2 -> {
                    emit(LlmEvent.TextDelta("obsolete wrap GOAL_DONE"))
                    wrapping.complete(Unit)
                    awaitCancellation()
                }
                else -> {
                    assertTrue(messages.any { it.role == "user" && it.content == "correct the answer" })
                    emit(LlmEvent.TextDelta("corrected wrap"))
                }
            }
        }
        val task = async {
            AgentRunner(ToolRegistry(listOf(tool)), AutoApproveGate) { client }
                .run(provider.copy(maxSteps = 1), "k", history, steering = box) {}
        }
        wrapping.await()
        box.offer(ChatMessage(role = Role.USER, text = "correct the answer"))
        val result = task.await()
        assertEquals("corrected wrap", result.text)
        assertTrue(result.stoppedByLimit)
        assertEquals(3, client.requests.size)
    }

    @Test fun `persisted segments preserve tools then follow up order and exclude obsolete draft from final answer`() = runTest {
        val box = TurnSteering()
        val follow = ChatMessage(role = Role.USER, text = "new instruction")
        val tool = object : Tool {
            override val spec = ToolSpec("fs_read", "test", "{}")
            override suspend fun execute(argumentsJson: String): ToolOutcome {
                box.offer(follow)
                return ToolOutcome.Ok("file contents")
            }
        }
        val client = SteeringClient { round, _ ->
            if (round == 1) {
                emit(LlmEvent.TextDelta("old draft GOAL_DONE"))
                emit(LlmEvent.ToolCallStarted(0, "read", "fs_read"))
            } else emit(LlmEvent.TextDelta("new final"))
        }
        val persisted = mutableListOf(ChatMessage(role = Role.USER, text = "original task"))
        val engine = ChatEngine(AgentRunner(ToolRegistry(listOf(tool)), AutoApproveGate) { client })
        val result = engine.runOnce(provider, "k", persisted.toList(), steering = box,
            onSteeringApplied = { persisted.addAll(it) },
        ) { live ->
            persisted.removeAll { it.id == live.id }
            persisted += live
        }
        persisted.removeAll { it.id == result.id }
        persisted += result
        assertEquals("new final", result.text)
        val api = MessageMapper.toApiMessages(persisted)
        assertEquals(listOf("user", "assistant", "tool", "user", "assistant"), api.map { it.role })
        assertEquals("new instruction", api[3].content)
        assertFalse(api.any { it.content.contains("GOAL_DONE") })
    }
}
