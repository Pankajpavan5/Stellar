package roro.stellar.manager.hernas

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives Allow/Deny actions from the confirmation notification for
 * install/uninstall jobs. Deny (or notification dismissal, or silence) fails closed.
 */
class HernasConfirmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        val allowed = intent.action == ACTION_ALLOW
        HernasConfirmation.resolve(jobId, allowed)
        Log.i(HernasRelayService.TAG_AUDIT, "confirmation job=$jobId allowed=$allowed")
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.cancel(jobId.hashCode() and 0x7fffffff)
    }

    companion object {
        const val ACTION_ALLOW = "roro.stellar.manager.hernas.action.CONFIRM_ALLOW"
        const val ACTION_DENY = "roro.stellar.manager.hernas.action.CONFIRM_DENY"
        const val EXTRA_JOB_ID = "hernas_job_id"
    }
}
