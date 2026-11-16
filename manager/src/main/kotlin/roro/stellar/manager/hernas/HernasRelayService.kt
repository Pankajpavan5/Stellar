package roro.stellar.manager.hernas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import roro.stellar.manager.R

/**
 * Foreground service that connects the Stellar manager to the Hernas MCP relay:
 * long-polls /api/device/poll for jobs, validates every command against the
 * allowlist (HernasCommandGuard), executes it privileged via the Stellar server,
 * and posts the result to /api/device/result.
 *
 * All credentials come from build config (HernasConfig); the service refuses to
 * run if the relay is disabled or unconfigured.
 */
class HernasRelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS) // must exceed the server's long-poll window
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = "application/json; charset=utf-8".toMediaType()

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!HernasConfig.isConfigured()) {
            Log.e(TAG, "Hernas relay not configured (enabled/token/deviceId/endpoint); not starting")
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        scope.launch { pollLoop() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle("Hernas relay")
            .setContentText("Listening for device control jobs (allowlist enforced)")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Long-poll loop: server holds each request until a job is ready or 204. */
    private suspend fun pollLoop() {
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            try {
                val job = pollOnce()
                backoffMs = INITIAL_BACKOFF_MS // reset only after a clean exchange
                if (job != null) handleJob(job)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                val jitter = (backoffMs * 0.2 * Random.nextDouble()).toLong()
                kotlinx.coroutines.delay(backoffMs + jitter)
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            }
        }
    }

    /** Hernas' real wire schema (verified from hernas-mcp src/server.ts): snake_case. */
    private data class HernasJob(
        @com.google.gson.annotations.SerializedName("job_id") val jobId: String? = null,
        val command: String? = null,
        @com.google.gson.annotations.SerializedName("timeout_ms") val timeoutMs: Long? = null,
    )

    /** Returns the next job, or null on 204 (nothing to do). Throws on errors (drives backoff). */
    private fun pollOnce(): HernasJob? {
        val url = "${HernasConfig.endpoint}/api/device/poll" +
            "?device=${urlEncode(HernasConfig.deviceId)}" +
            "&name=${urlEncode(Build.MODEL ?: "unknown")}" +
            "&sdk=${Build.VERSION.SDK_INT}"
        val request = Request.Builder()
            .url(url)
            .header("x-hernas-relay-token", HernasConfig.relayToken)
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            when (response.code) {
                204 -> return null
                200 -> {
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return null
                    val job = gson.fromJson(body, HernasJob::class.java)
                    if (job?.jobId.isNullOrBlank() || job?.command.isNullOrBlank()) {
                        throw IllegalStateException("malformed job payload")
                    }
                    return job
                }

                else -> throw IllegalStateException("poll HTTP ${response.code}")
            }
        }
    }

    private suspend fun handleJob(job: HernasJob) {
        // Doze freezes apps between maintenance windows mid-job; hold a bounded
        // wake lock so poll→execute→result-post can't be suspended apart.
        val pm = getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stellar:hernas_job")
        wakeLock.acquire(HernasExecutor.MAX_TIMEOUT_MS + 15_000L)
        try {
            handleJobLocked(job)
        } finally {
            wakeLock.release()
        }
    }

    private suspend fun handleJobLocked(job: HernasJob) {
        val id = job.jobId!!
        val raw = job.command!!
        val decision = HernasCommandGuard.decide(raw)

        val result = when (decision) {
            is HernasCommandGuard.Decision.Rejected -> {
                audit(id, "rejected", raw, null)
                HernasExecutor.Result(HernasExecutor.EXIT_REJECTED, "", "rejected: ${decision.reason}")
            }

            is HernasCommandGuard.Decision.Allowed -> {
                val confirmed = if (HernasConfig.requireConfirmation && decision.needsConfirmation) {
                    audit(id, "confirming", decision.description, null)
                    askUserConfirmation(id, decision.description)
                } else true

                if (!confirmed) {
                    audit(id, "denied", decision.description, null)
                    HernasExecutor.Result(HernasExecutor.EXIT_REJECTED, "", "user denied or confirmation timed out")
                } else {
                    audit(id, "executing", decision.description, null)
                    val started = System.currentTimeMillis()
                    val r = HernasExecutor.execute(decision.steps, HernasExecutor.clampTimeout(job.timeoutMs ?: 90_000L))
                    audit(id, "done", decision.description, "exit=${r.exitCode} ms=${System.currentTimeMillis() - started}")
                    postResult(id, r, System.currentTimeMillis() - started)
                    return
                }
            }
        }

        postResult(id, result, 0L)
    }

    /** Post a confirmation notification; Allow/Deny actions resolve the deferred. */
    private suspend fun askUserConfirmation(jobId: String, detail: String): Boolean {
        val deferred = HernasConfirmation.newRequest(jobId)
        val allowIntent = Intent(this, HernasConfirmReceiver::class.java).apply {
            action = HernasConfirmReceiver.ACTION_ALLOW
            putExtra(HernasConfirmReceiver.EXTRA_JOB_ID, jobId)
        }
        val denyIntent = Intent(this, HernasConfirmReceiver::class.java).apply {
            action = HernasConfirmReceiver.ACTION_DENY
            putExtra(HernasConfirmReceiver.EXTRA_JOB_ID, jobId)
        }
        val allowPi = PendingIntent.getBroadcast(this, jobId.hashCode(), allowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val denyPi = PendingIntent.getBroadcast(this, jobId.hashCode() + 1, denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stellar)
            .setContentTitle("Hernas: allow privileged action?")
            .setContentText(detail)
            .setOngoing(true)
            .addAction(0, "Allow", allowPi)
            .addAction(0, "Deny", denyPi)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(jobId.hashCode() and 0x7fffffff, notification)

        val allowed = withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) { deferred.await() } ?: false
        HernasConfirmation.abort(jobId)
        getSystemService(NotificationManager::class.java)
            .cancel(jobId.hashCode() and 0x7fffffff)
        return allowed
    }

    /** Hernas' real wire schema (verified from hernas-mcp src/server.ts): snake_case. */
    private fun postResult(jobId: String, result: HernasExecutor.Result, durationMs: Long) {
        val payload = gson.toJson(
            mapOf(
                "job_id" to jobId,
                "device" to HernasConfig.deviceId,
                "exit_code" to result.exitCode,
                "stdout" to result.stdout.take(HernasExecutor.MAX_OUTPUT_BYTES),
                "stderr" to result.stderr.take(HernasExecutor.MAX_OUTPUT_BYTES),
                "duration_ms" to durationMs,
            )
        )
        val request = Request.Builder()
            .url("${HernasConfig.endpoint}/api/device/result")
            .header("x-hernas-relay-token", HernasConfig.relayToken)
            .post(payload.toRequestBody(json))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "result POST for $jobId got HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "result POST for $jobId failed", e)
        }
    }

    /** Audit line with redacted args: logs op/decision/exit only, never raw command text. */
    private fun audit(jobId: String, event: String, detail: String?, extra: String?) {
        if (!HernasConfig.auditLog) return
        val safeDetail = detail?.replace(Regex("^([a-z]+) .*"), "$1") // op only; args redacted
        Log.i(TAG_AUDIT, "job=$jobId event=$event detail=${safeDetail ?: "n/a"} ${extra ?: ""}")
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        const val TAG = "HernasRelay"
        const val TAG_AUDIT = "HernasAudit"

        const val CHANNEL_ID = "hernas_relay"
        const val NOTIFICATION_ID = 1448

        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val CONFIRMATION_TIMEOUT_MS = 60_000L

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hernas relay",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, HernasRelayService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HernasRelayService::class.java))
        }
    }
}
