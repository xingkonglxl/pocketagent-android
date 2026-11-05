package app.pocketagent.android.agent

/**
 * 上下文压缩的分段策略：纯函数，方便单测。
 * 长会话按行切段，每段不超过 [chunkChars]。超过 [maxChunks] 段时保留开头
 * 的目标/约束和最近决策，避免只截末尾导致摘要忘记任务初衷。
 */
object CompactPlanner {

    fun split(transcript: String, chunkChars: Int = 16_000, maxChunks: Int = 24): List<String> {
        if (transcript.isBlank()) return emptyList()
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        transcript.lineSequence().forEach { line ->
            if (buf.length + line.length + 1 > chunkChars && buf.isNotEmpty()) {
                chunks += buf.toString()
                buf.setLength(0)
            }
            if (line.length > chunkChars) {
                // 单行超长（比如一大段代码）：硬切
                var rest = line
                while (rest.length > chunkChars) {
                    chunks += rest.take(chunkChars)
                    rest = rest.drop(chunkChars)
                }
                buf.append(rest).append('\n')
            } else {
                buf.append(line).append('\n')
            }
        }
        if (buf.isNotEmpty()) chunks += buf.toString()
        if (chunks.size <= maxChunks) return chunks

        val head = if (maxChunks <= 1) 1 else maxOf(1, maxChunks / 4)
        val tail = (maxChunks - head).coerceAtLeast(0)
        val result = chunks.take(head).toMutableList()
        if (tail > 0) {
            result += "〔中间省略 ${chunks.size - head - tail} 段较早的细节；请保留开头目标和最近决策〕"
            result += chunks.takeLast(tail)
        }
        return result
    }
}
