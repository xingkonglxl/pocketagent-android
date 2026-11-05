package app.pocketagent.android.agent

import app.pocketagent.android.data.Provider
import app.pocketagent.android.data.ChatMessage
import app.pocketagent.android.data.ProviderKind
import app.pocketagent.android.net.ApiMessage
import app.pocketagent.android.net.ApiToolCall
import app.pocketagent.android.net.ChatCompletionsClient
import app.pocketagent.android.net.LlmClient
import app.pocketagent.android.net.LlmConfig
import app.pocketagent.android.net.LlmEvent
import app.pocketagent.android.net.encodeDeepSeekReasoning
import app.pocketagent.android.net.ResponsesClient
import app.pocketagent.android.tools.Tool
import app.pocketagent.android.tools.ToolOutcome
import app.pocketagent.android.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class PendingToolCall(
    val index: Int,
    val id: String,
    val name: String,
    val argumentsBuffer: StringBuilder = StringBuilder(),
) {
    fun arguments(): String = argumentsBuffer.toString().ifBlank { "{}" }
}

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class ReasoningItem(val json: String) : AgentEvent
    data class ToolStarted(val callId: String, val name: String, val arguments: String) : AgentEvent
    data class ToolWaitingApproval(val callId: String, val name: String, val arguments: String) : AgentEvent
    data class ToolDenied(val callId: String, val name: String) : AgentEvent
    data class ToolFinished(
        val callId: String,
        val name: String,
        val ok: Boolean,
        val output: String,
        val durationMs: Long,
    ) : AgentEvent
    data class StepStarted(val step: Int) : AgentEvent
    data class Usage(val input: Int, val output: Int) : AgentEvent
    data class Notice(val text: String) : AgentEvent
    data class FollowUpsApplied(val messages: List<ChatMessage>) : AgentEvent
}

data class AgentResult(
    val text: String,
    val apiMessages: List<ApiMessage>,
    val tokensIn: Int,
    val tokensOut: Int,
    val steps: Int,
    val stoppedByLimit: Boolean,
    val reasoningItem: String? = null,
)

private data class PreparedToolCall(
    val call: PendingToolCall,
    val tool: Tool? = null,
    val immediateError: String? = null,
    val denied: Boolean = false,
    /** delegate_task：由主循环内部起一个只读子 agent，不经过 ToolRegistry。 */
    val delegate: Boolean = false,
)

private data class ToolExecution(
    val outcome: ToolOutcome,
    val elapsedMs: Long,
)

/**
 * Codex 式的最小 harness：模型 → 工具调用 → 审批 → 执行 → 结果回灌 → 继续，
 * 直到模型不再调工具或到达步数上限。DeepSeek 与 Codex 只在协议层不同。
 */
class AgentRunner(
    private val registry: ToolRegistry,
    private val gate: ApprovalGate,
    private val clientFor: (ProviderKind) -> LlmClient = { kind ->
        when (kind) {
            ProviderKind.CHAT_COMPLETIONS -> ChatCompletionsClient()
            ProviderKind.RESPONSES -> ResponsesClient()
            ProviderKind.ANTHROPIC -> app.pocketagent.android.net.AnthropicClient()
        }
    },
) {
    suspend fun run(
        provider: Provider,
        apiKey: String,
        history: List<ApiMessage>,
        cacheKey: String? = null,
        historyBudgetTokens: Int? = null,
        allowSubagents: Boolean = false,
        steering: TurnSteering? = null,
        onEvent: suspend (AgentEvent) -> Unit,
    ): AgentResult {
        val client = clientFor(provider.kind)
        var config = LlmConfig(provider, apiKey, cacheKey)
        val messages = history.toMutableList()
        val toolSpecs = if (provider.toolsEnabled) {
            registry.specs() + if (allowSubagents) listOf(Subagents.spec) else emptyList()
        } else {
            emptyList()
        }
        val maxSteps = provider.maxSteps.coerceIn(1, 64)
        var tokensIn = 0
        var tokensOut = 0
        var reasoningItem: String? = null
        var webSearchCalls = 0
        // 被输出上限截断时，先把已经写出来的部分攒在这里，再让模型接着写；
        // 否则下一轮 text 会被清空，用户只拿到后半段。
        val carried = StringBuilder()
        var autoContinued = false
        suspend fun applyFollowUps(): Boolean {
            val added = steering?.drain().orEmpty()
            if (added.isEmpty()) return false
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                messages += MessageMapper.toApiMessages(added)
                carried.setLength(0)
                autoContinued = false
                reasoningItem = null
                onEvent(AgentEvent.FollowUpsApplied(added))
            }
            return true
        }

        // Only the model request is interrupted. Running tools keep their results and are
        // never replayed merely because a new user instruction arrived.
        suspend fun collectModel(flow: Flow<LlmEvent>, consume: suspend (LlmEvent) -> Unit): Boolean {
            if (steering == null) {
                flow.collect(consume)
                return false
            }
            return coroutineScope {
                if (steering.peek().isNotEmpty()) return@coroutineScope true
                val request = async { flow.collect(consume) }
                val followUp = async { steering.pending.first { it.isNotEmpty() } }
                try {
                    select {
                        followUp.onAwait { true }
                        request.onAwait { steering.peek().isNotEmpty() }
                    }
                } finally {
                    request.cancelAndJoin()
                    followUp.cancelAndJoin()
                }
            }
        }

        suspend fun requestApproval(request: ApprovalRequest): Boolean {
            if (steering == null) return gate.request(request)
            return coroutineScope {
                if (steering.peek().isNotEmpty()) return@coroutineScope false
                val approval = async { gate.request(request) }
                val followUp = async { steering.pending.first { it.isNotEmpty() } }
                try {
                    select {
                        followUp.onAwait { false }
                        approval.onAwait { it }
                    }
                } finally {
                    approval.cancelAndJoin()
                    followUp.cancelAndJoin()
                }
            }
        }
        var midTurnCompactions = 0
        val delegateCalls = java.util.concurrent.atomic.AtomicInteger()
        // 子 agent 花的是同一份额度，单独累计后并进本轮的 token 账，/context 才不会低估。
        val delegateTokensIn = java.util.concurrent.atomic.AtomicInteger()
        val delegateTokensOut = java.util.concurrent.atomic.AtomicInteger()
        fun totalTokensIn(): Int = tokensIn + delegateTokensIn.get()
        fun totalTokensOut(): Int = tokensOut + delegateTokensOut.get()
        // lazy 自带同步：两个并行 delegate 同时首次进来也只会建一个子 runner。
        val subagentRunner: AgentRunner by lazy {
            val readOnly = registry.tools.filter { it.spec.name in Subagents.readOnlyTools }
            AgentRunner(ToolRegistry(readOnly), AutoApproveGate, clientFor)
        }

        // 子 agent 只拿到只读工具，自己不能再开子 agent，避免无限递归。
        suspend fun runSubagent(call: PendingToolCall): ToolOutcome {
            val parsed = Subagents.parse(call.arguments())
                ?: return ToolOutcome.Error("delegate_task 需要非空的 task 字段")
            if (delegateCalls.getAndIncrement() >= Subagents.MAX_CALLS_PER_TURN) {
                return ToolOutcome.Error(
                    "本轮 delegate_task 已达上限（${Subagents.MAX_CALLS_PER_TURN} 次），请自己完成剩余部分。",
                )
            }
            val subProvider = provider.copy(
                systemPrompt = Subagents.systemPrompt,
                maxSteps = Subagents.MAX_STEPS,
                toolsEnabled = true,
                autoApprove = true,
                permissions = ToolPermissions.defaults,
            )
            return try {
                kotlinx.coroutines.withTimeout(Subagents.TIMEOUT_MS) {
                    val result = subagentRunner.run(
                        provider = subProvider,
                        apiKey = apiKey,
                        history = listOf(ApiMessage(role = "user", content = Subagents.prompt(parsed))),
                        historyBudgetTokens = historyBudgetTokens,
                        onEvent = {},
                    )
                    val text = result.text.trim()
                    delegateTokensIn.addAndGet(result.tokensIn)
                    delegateTokensOut.addAndGet(result.tokensOut)
                    if (text.isBlank()) {
                        ToolOutcome.Error(
                            "子 agent 跑完 ${result.steps} 步但没有给出结论；请换个更具体的任务或自己继续。",
                        )
                    } else {
                        ToolOutcome.Ok("子 agent 完成（${result.steps} 步）：\n" + Subagents.clip(text))
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                ToolOutcome.Error(
                    "子 agent 超过 ${Subagents.TIMEOUT_MS / 1000} 秒未完成，已取消。请缩小任务或自己做。",
                )
            }
        }

        // 只在完整工具批次结束后调用：此时每个 tool_calls 都已有对应结果。
        suspend fun compactMidTurnIfNeeded(phase: String) {
            val budget = historyBudgetTokens?.coerceAtLeast(1_000) ?: return
            if (!MidTurnCompactor.needsCompaction(messages, budget)) return
            if (midTurnCompactions >= 2) return
            val compacted = runCatching {
                MidTurnCompactor.compact(messages, budget)
            }.getOrNull() ?: return
            if (compacted.isEmpty() || !MidTurnCompactor.isValid(compacted)) return
            val before = MidTurnCompactor.estimateTokens(messages)
            val after = MidTurnCompactor.estimateTokens(compacted)
            if (after >= before) return
            messages.clear()
            messages.addAll(compacted)
            midTurnCompactions++
            onEvent(
                AgentEvent.Notice(
                    "$phase：工具结果已接近上下文上限，较早的工具记录已折叠为摘要" +
                        "（约 $before → $after tokens），本轮继续执行。",
                ),
            )
        }

        for (step in 1..maxSteps) {
            applyFollowUps()
            compactMidTurnIfNeeded("第 $step 步前")
            onEvent(AgentEvent.StepStarted(step))
            val text = StringBuilder()
            val reasoningText = StringBuilder()
            val calls = linkedMapOf<Int, PendingToolCall>()
            reasoningItem = null
            var finishReason: String? = null

            // 中转站偶发在思考模式下拒收历史（reasoning_text 缺失），即使我们已经回传。
            // 兜底：清掉历史里的 reasoning item 让它走降级路径，再试一次，避免把 500 抛给用户。
            var demotedHistory = false
            // 有些中转站/模型不接受我们挑的思考档位（effort=max、thinking 等），
            // 报错一次就退回服务端默认再试，别让整轮对话直接失败。
            var demotedEffort = false
            while (true) {
            text.setLength(0)
            reasoningText.setLength(0)
            calls.clear()
            reasoningItem = null
            finishReason = null
            try {
            val interrupted = collectModel(client.stream(config, provider.systemPrompt, messages, toolSpecs)) { event ->
                when (event) {
                    is LlmEvent.TextDelta -> {
                        text.append(event.text)
                        onEvent(AgentEvent.TextDelta(event.text))
                    }
                    is LlmEvent.ReasoningDelta -> {
                        reasoningText.append(event.text)
                        onEvent(AgentEvent.ReasoningDelta(event.text))
                    }
                    is LlmEvent.ReasoningItem -> {
                        reasoningItem = event.json
                        onEvent(AgentEvent.ReasoningItem(event.json))
                    }
                    is LlmEvent.ToolCallStarted -> {
                        val existing = calls[event.index]
                        calls[event.index] = PendingToolCall(
                            index = event.index,
                            id = event.id.ifBlank { existing?.id ?: "call_${step}_${event.index}" },
                            name = event.name,
                            argumentsBuffer = existing?.argumentsBuffer ?: StringBuilder(),
                        )
                    }
                    is LlmEvent.ToolCallArguments -> {
                        calls.getOrPut(event.index) {
                            PendingToolCall(event.index, "call_${step}_${event.index}", "")
                        }.argumentsBuffer.append(event.delta)
                    }
                    is LlmEvent.Usage -> {
                        event.input?.let { tokensIn += it }
                        event.output?.let { tokensOut += it }
                        onEvent(AgentEvent.Usage(event.input ?: 0, event.output ?: 0))
                    }
                    is LlmEvent.Finished -> finishReason = event.reason
                }
            }
            if (interrupted) {
                if (!applyFollowUps()) onEvent(AgentEvent.FollowUpsApplied(emptyList()))
                continue
            }
            } catch (e: app.pocketagent.android.net.LlmException) {
                // 中转站仍然拒收历史时，清掉 reasoning 让它走降级路径再试一次
                if (!demotedHistory && text.isEmpty() && calls.isEmpty() &&
                    e.message.orEmpty().contains("reasoning_text")
                ) {
                    demotedHistory = true
                    for (i in messages.indices) {
                        val m = messages[i]
                        if (m.role == "assistant") messages[i] = m.copy(reasoningItem = null)
                    }
                    onEvent(AgentEvent.Notice("中转站拒绝了思考内容，已降低历史细节后重试"))
                    continue
                }
                val lower = e.message.orEmpty().lowercase()
                val effortRejected = config.provider.reasoningEffort != null && listOf(
                    "effort", "thinking", "budget_tokens", "reasoning",
                ).any { lower.contains(it) }
                if (!demotedEffort && text.isEmpty() && calls.isEmpty() && effortRejected) {
                    demotedEffort = true
                    config = config.copy(provider = config.provider.copy(reasoningEffort = null))
                    onEvent(AgentEvent.Notice("这个来源不接受当前思考档位，已按服务端默认重试"))
                    continue
                }
                throw e
            }
            break
            }

            if (reasoningItem == null && provider.kind == ProviderKind.CHAT_COMPLETIONS) {
                reasoningItem = encodeDeepSeekReasoning(reasoningText.toString())
            }

            if (calls.isEmpty()) {
                if (finishReason == "length" || finishReason == "max_tokens") {
                    if (text.isNotBlank() && !autoContinued && step < maxSteps) {
                        autoContinued = true
                        carried.append(text)
                        messages += ApiMessage(
                            role = "assistant",
                            content = text.toString(),
                            reasoningItem = reasoningItem,
                        )
                        messages += ApiMessage(
                            role = "user",
                            content = "（系统提示）上一条回答被输出上限截断。请从中断处直接继续写，" +
                                "不要重复已经写过的内容，也不要重新开头。",
                        )
                        onEvent(AgentEvent.Notice("回答被截断，正在自动接着写…"))
                        continue
                    }
                    onEvent(
                        AgentEvent.Notice(
                            "模型达到输出上限，回答被截断；可以让我继续，或把任务拆小一点。",
                        ),
                    )
                }
                return AgentResult(
                    text = carried.toString() + text.toString(),
                    apiMessages = messages,
                    tokensIn = totalTokensIn(),
                    tokensOut = totalTokensOut(),
                    steps = step,
                    stoppedByLimit = false,
                    reasoningItem = reasoningItem,
                )
            }

            val assistantCalls = calls.values.map { ApiToolCall(it.id, it.name, it.arguments()) }
            messages += ApiMessage(
                role = "assistant",
                content = text.toString(),
                toolCalls = assistantCalls,
                reasoningItem = reasoningItem,
            )

            val prepared = mutableListOf<PreparedToolCall>()
            for (call in calls.values) {
                if (steering?.peek()?.isNotEmpty() == true) {
                    prepared += PreparedToolCall(call, immediateError = STEERED_TOOL_RESULT)
                    continue
                }
                if (allowSubagents && call.name == Subagents.TOOL_NAME) {
                    onEvent(AgentEvent.ToolStarted(call.id, call.name, call.arguments()))
                    val subArguments = call.arguments()
                    // 子 agent 只用只读工具；读权限都没放行时才需要一次人工确认。
                    val allowed = provider.autoApprove || ToolPermissions.READ in provider.permissions
                    val approved = allowed || run {
                        onEvent(AgentEvent.ToolWaitingApproval(call.id, call.name, subArguments))
                        requestApproval(
                            ApprovalRequest(
                                callId = call.id,
                                toolName = call.name,
                                argumentsJson = subArguments,
                                sessionAllowable = false,
                            ),
                        )
                    }
                    if (!approved) {
                        prepared += if (steering?.peek()?.isNotEmpty() == true) {
                            PreparedToolCall(call, immediateError = STEERED_TOOL_RESULT)
                        } else PreparedToolCall(call, denied = true)
                        continue
                    }
                    prepared += PreparedToolCall(call, delegate = true)
                    continue
                }
                val tool = registry.find(call.name)
                if (tool == null) {
                    val msg = "ERROR: 未知工具 ${call.name}。只能调用本轮 tools 里声明过的工具。"
                    prepared += PreparedToolCall(call, immediateError = msg)
                    continue
                }

                // 搜索预算不能只写在提示词里：模型曾经在一个问题里连续发 22 次搜索。
                // 达到预算后把错误回灌给模型，逼它打开已有权威链接或明确给出未核实项。
                if (call.name == "web_search" && webSearchCalls >= MAX_WEB_SEARCH_CALLS) {
                    val msg = "ERROR: 本轮 web_search 预算已用尽（最多 $MAX_WEB_SEARCH_CALLS 次）。" +
                        "不要再换关键词搜索；请用 http_get 打开已返回的权威链接，" +
                        "或基于现有证据作答并明确哪些内容尚未核实。"
                    prepared += PreparedToolCall(call, immediateError = msg)
                    continue
                }
                if (call.name == "web_search") webSearchCalls++

                onEvent(AgentEvent.ToolStarted(call.id, call.name, call.arguments()))
                // 权限按类别放行（见 ToolPermissions），用户可以在 /permissions 里逐类开关。
                // 默认只放行只读与联网检索；写、删、执行命令仍逐次确认。
                // 能改远端/删文件的操作即便开了 autoApprove 也仍然确认，防止网页提示注入。
                val category = ToolPermissions.categoryOf(call.name)
                val allowedByPolicy = category != null && category in provider.permissions
                val arguments = call.arguments()
                // 危险工具和敏感路径统一走 ToolPermissions，避免两套规则漂移。
                val perCallApproval = ToolPermissions.requiresPerCallApproval(call.name, arguments)
                val approved = if (
                    !perCallApproval && (allowedByPolicy || provider.autoApprove)
                ) {
                    true
                } else {
                    onEvent(AgentEvent.ToolWaitingApproval(call.id, call.name, arguments))
                    requestApproval(
                        ApprovalRequest(
                            callId = call.id,
                            toolName = call.name,
                            argumentsJson = arguments,
                            sessionAllowable = ToolPermissions.isSessionAllowable(call.name, arguments),
                        ),
                    )
                }

                if (!approved) {
                    prepared += if (steering?.peek()?.isNotEmpty() == true) {
                        PreparedToolCall(call, immediateError = STEERED_TOOL_RESULT)
                    } else PreparedToolCall(call, tool = tool, denied = true)
                    continue
                }

                prepared += PreparedToolCall(call, tool = tool)
            }

            suspend fun executePrepared(value: PreparedToolCall): ToolExecution {
                if (steering?.peek()?.isNotEmpty() == true) {
                    return ToolExecution(ToolOutcome.Error(STEERED_TOOL_RESULT), 0)
                }
                val started = System.currentTimeMillis()
                val outcome = try {
                    when {
                        value.delegate -> runSubagent(value.call)
                        value.tool != null -> value.tool.execute(value.call.arguments())
                        else -> ToolOutcome.Error("工具没有准备好：${value.call.name}")
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    ToolOutcome.Error(e.message ?: "工具执行时抛出异常")
                }
                return ToolExecution(outcome, System.currentTimeMillis() - started)
            }

            val executions = arrayOfNulls<ToolExecution>(prepared.size)
            val parallel = prepared.indices.filter { index ->
                val item = prepared[index]
                val category = ToolPermissions.categoryOf(item.call.name)
                item.tool != null && !item.denied && item.immediateError == null &&
                    item.call.name != "update_plan" &&
                    !ToolPermissions.requiresPerCallApproval(item.call.name, item.call.arguments()) &&
                    (category == ToolPermissions.READ || category == ToolPermissions.WEB)
            }
            if (parallel.isNotEmpty()) {
                val permits = Semaphore(MAX_PARALLEL_READ_TOOLS)
                coroutineScope {
                    parallel.map { index ->
                        async {
                            permits.withPermit {
                                index to executePrepared(prepared[index])
                            }
                        }
                    }.awaitAll().forEach { (index, result) -> executions[index] = result }
                }
            }

            val delegated = prepared.indices.filter { prepared[it].delegate && !prepared[it].denied }
            if (delegated.isNotEmpty()) {
                val permits = Semaphore(Subagents.MAX_PARALLEL)
                coroutineScope {
                    delegated.map { index ->
                        async {
                            permits.withPermit {
                                index to executePrepared(prepared[index])
                            }
                        }
                    }.awaitAll().forEach { (index, result) -> executions[index] = result }
                }
            }

            prepared.indices.filterNot { it in parallel || it in delegated }.forEach { index ->
                val item = prepared[index]
                if (item.tool != null && !item.denied && item.immediateError == null) {
                    executions[index] = executePrepared(item)
                }
            }

            val pendingImages = mutableListOf<ApiMessage>()
            prepared.forEachIndexed { index, item ->
                val call = item.call
                item.immediateError?.let { msg ->
                    messages += ApiMessage(role = "tool", content = msg, toolCallId = call.id)
                    onEvent(AgentEvent.ToolFinished(call.id, call.name, false, msg, 0))
                    return@forEachIndexed
                }
                if (item.denied) {
                    val msg = "DENIED: 用户拒绝了这次工具调用，不要假装它已经执行。"
                    messages += ApiMessage(role = "tool", content = msg, toolCallId = call.id)
                    onEvent(AgentEvent.ToolDenied(call.id, call.name))
                    return@forEachIndexed
                }
                val execution = checkNotNull(executions[index])
                val outcome = execution.outcome
                val (ok, output) = when (outcome) {
                    is ToolOutcome.Ok -> true to outcome.text
                    is ToolOutcome.Error -> false to outcome.message
                }
                val fedBack = when {
                    !ok -> "ERROR: $output"
                    output.isBlank() -> "(工具执行成功，没有输出)"
                    else -> output
                }
                messages += ApiMessage(role = "tool", content = fedBack, toolCallId = call.id)
                // view_image 这类工具会把图片一起带回来：作为一条 user 图片消息回灌，
                // 三种协议都认这种形式（桌面 Codex 的 view_image 也是把图塞回上下文）。
                val images = (outcome as? ToolOutcome.Ok)?.images.orEmpty()
                if (images.isNotEmpty()) {
                    pendingImages += ApiMessage(
                        role = "user",
                        content = "（上面 ${call.name} 读到的图片）",
                        images = images,
                    )
                }
                onEvent(AgentEvent.ToolFinished(call.id, call.name, ok, fedBack, execution.elapsedMs))
            }
            // 图片统一放在该批全部 tool 结果之后，保证 assistant→tool 连续，
            // 也便于中段压缩把整个工具轮当作原子单位。
            messages += pendingImages
            if (applyFollowUps()) text.setLength(0)

            if (step == maxSteps) {
                // 步数用尽时别只丢一句提示：再给模型一次「只用已有结果作答」的机会，
                // 否则用户看到的就是一个空气泡（桌面 Codex 不会这样收尾）。
                onEvent(AgentEvent.Notice("已达到最大步数（$maxSteps），正在基于已有结果收尾"))
                compactMidTurnIfNeeded("收尾请求前")
                val wrap = try {
                    var interrupted: Boolean
                    var wrapped: String
                    do {
                    val sb = StringBuilder()
                    interrupted = collectModel(client.stream(
                        config,
                        provider.systemPrompt,
                        messages + ApiMessage(
                            role = "user",
                            content = "（系统提示）工具调用步数已用完。请立刻基于上面已经拿到的工具结果给出最终回答，" +
                                "不要再调用工具；已经查不到的就明确说明查不到，并给出下一步建议。",
                        ),
                        emptyList(),
                    )) { event ->
                        when (event) {
                            is LlmEvent.TextDelta -> {
                                sb.append(event.text)
                                onEvent(AgentEvent.TextDelta(event.text))
                            }
                            is LlmEvent.ReasoningItem -> reasoningItem = event.json
                            is LlmEvent.Usage -> {
                                event.input?.let { tokensIn += it }
                                event.output?.let { tokensOut += it }
                            }
                            else -> Unit
                        }
                    }
                    if (interrupted) {
                        if (!applyFollowUps()) onEvent(AgentEvent.FollowUpsApplied(emptyList()))
                        text.setLength(0)
                    }
                    wrapped = sb.toString()
                    } while (interrupted)
                    wrapped
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) { "" }
                val merged = if (text.isBlank()) wrap else text.toString() + "\n\n" + wrap
                if (wrap.isBlank() && text.isBlank()) {
                    onEvent(AgentEvent.Notice("收尾时模型没有返回内容，请再发一次或减少工具步数限制"))
                }
                return AgentResult(
                    text = merged,
                    apiMessages = messages,
                    tokensIn = totalTokensIn(),
                    tokensOut = totalTokensOut(),
                    steps = step,
                    stoppedByLimit = true,
                    reasoningItem = reasoningItem,
                )
            }
        }

        return AgentResult("", messages, totalTokensIn(), totalTokensOut(), maxSteps, true, reasoningItem)
    }

    private companion object {
        const val MAX_WEB_SEARCH_CALLS = 3
        const val MAX_PARALLEL_READ_TOOLS = 4
        const val STEERED_TOOL_RESULT = "CANCELLED: 用户补充了新要求；此项尚未开始，已跳过。请先阅读新的用户消息再决定后续操作。"
    }
}

