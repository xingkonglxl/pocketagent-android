package app.pocketagent.android

import app.pocketagent.android.agent.ApprovalRequest
import app.pocketagent.android.agent.UiApprovalGate
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalQueueTest {
    @Test
    fun `two chats asking at once are serialized`() = runTest {
        val gate = UiApprovalGate()
        var first: Boolean? = null
        var second: Boolean? = null

        val job1 = launch { first = gate.request(ApprovalRequest("c1", "fs_write", "{}")) }
        testScheduler.runCurrent()
        assertEquals("c1", gate.pending.value?.callId)

        val job2 = launch { second = gate.request(ApprovalRequest("c2", "http_get", "{}")) }
        testScheduler.runCurrent()
        // 第二个还在排队，界面上仍然是第一个
        assertEquals("c1", gate.pending.value?.callId)

        gate.answer(true)
        job1.join()
        testScheduler.runCurrent()

        assertEquals("c2", gate.pending.value?.callId)
        gate.answer(false)
        job2.join()

        assertEquals(true, first)
        assertEquals(false, second)
        assertNull(gate.pending.value)
    }
}
