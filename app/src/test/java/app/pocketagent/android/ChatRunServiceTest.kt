package app.pocketagent.android

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ChatRunServiceTest {
    private class ServiceContext : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        var starts = 0
        var stops = 0
        var rejectStart = false

        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName {
            if (rejectStart) throw IllegalStateException("background start rejected")
            starts++
            return ComponentName(this, ChatRunService::class.java)
        }

        override fun stopService(service: Intent): Boolean {
            stops++
            return true
        }
    }

    @Test
    fun `sync completion keeps the concurrent chat protected`() {
        val context = ServiceContext()
        val chat = ChatRunService.start(context, "chat")
        val sync = ChatRunService.start(context, "sync")
        try {
            sync.close()
            sync.close()
            assertEquals(1, context.starts)
            assertEquals(0, context.stops)
        } finally {
            sync.close()
            chat.close()
        }
        assertEquals(1, context.stops)
        chat.close()
        assertEquals(1, context.stops)
    }

    @Test
    fun `a task cancelled before its coroutine starts releases protection`() = runBlocking {
        val context = ServiceContext()
        val parent = Job().apply { cancel() }
        val lease = ChatRunService.start(context)
        val task = CoroutineScope(parent).launch { error("cancelled task must not execute") }
        task.invokeOnCompletion { lease.close() }
        task.join()
        assertEquals(1, context.starts)
        assertEquals(1, context.stops)
    }

    @Test
    fun `a rejected start does not keep later tasks alive forever`() {
        val context = ServiceContext()
        context.rejectStart = true
        val rejected = ChatRunService.start(context)
        context.rejectStart = false
        val active = ChatRunService.start(context)
        active.close()
        rejected.close()
        assertEquals(1, context.stops)
    }
}
