package app.pocketagent.android.agent

import app.pocketagent.android.data.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Messages remain pending until the current harness actually accepts them. */
class TurnSteering {
    private val lock = Any()
    private val state = MutableStateFlow<List<ChatMessage>>(emptyList())
    val pending: StateFlow<List<ChatMessage>> = state.asStateFlow()

    fun offer(message: ChatMessage): Boolean = synchronized(lock) {
        if (state.value.any { it.id == message.id }) return@synchronized true
        if (state.value.size >= MAX_PENDING) return@synchronized false
        state.value = state.value + message
        true
    }

    fun peek(): List<ChatMessage> = synchronized(lock) { state.value.toList() }

    fun drain(): List<ChatMessage> = synchronized(lock) {
        state.value.also { state.value = emptyList() }
    }

    fun acknowledge(ids: Set<String>): List<ChatMessage> = synchronized(lock) {
        val applied = state.value.filter { it.id in ids }
        state.value = state.value.filterNot { it.id in ids }
        applied
    }

    fun clear() = synchronized(lock) { state.value = emptyList() }

    companion object {
        const val MAX_PENDING = 8
    }
}
