package roro.stellar.manager.hernas

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Registry of pending install/uninstall confirmations. The notification actions
 * (HernasConfirmReceiver) resolve the deferred; the relay service awaits it.
 * Fail-closed: no answer = denied.
 */
object HernasConfirmation {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun newRequest(jobId: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        pending[jobId]?.complete(false) // replace a stale request for the same job
        pending[jobId] = deferred
        return deferred
    }

    fun resolve(jobId: String, allowed: Boolean) {
        pending.remove(jobId)?.complete(allowed)
    }

    fun abort(jobId: String) {
        pending.remove(jobId)?.complete(false)
    }
}
