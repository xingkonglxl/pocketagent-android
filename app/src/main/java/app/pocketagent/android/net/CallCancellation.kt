package app.pocketagent.android.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import okhttp3.Call

/** Cancel socket reads when cancellation starts, rather than after the blocked job completes. */
internal suspend fun Call.cancelWithCoroutine(): Job =
    CoroutineScope(currentCoroutineContext()).launch(
        Dispatchers.Unconfined,
        start = CoroutineStart.UNDISPATCHED,
    ) {
        try {
            awaitCancellation()
        } finally {
            this@cancelWithCoroutine.cancel()
        }
    }
