package roro.stellar.manager.hernas

import android.util.Log
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import roro.stellar.Stellar
import roro.stellar.StellarRemoteProcess

/**
 * Executes allowlisted argv steps through the Stellar privileged process API
 * (`Stellar.newProcess`, running in the shell/root server process), honoring the
 * job's timeout and killing processes that run over it.
 */
object HernasExecutor {

    private const val TAG = "HernasRelay"

    const val EXIT_REJECTED = 125       // command rejected by the allowlist/confirmation
    const val EXIT_TIMEOUT = 124        // exceeded job timeoutMs, process killed
    const val EXIT_NO_SERVICE = 126     // Stellar service not running / exec failed

    const val EXIT_OK = 0

    /** Matches Hernas' server-side 512 KiB output cap. */
    const val MAX_OUTPUT_BYTES = 512 * 1024

    /** Jobs are clamped into this window regardless of what the server asks. */
    private const val MIN_TIMEOUT_MS = 1_000L
    const val MAX_TIMEOUT_MS = 90_000L

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    /** Processes of the job currently running, so the timeout path can kill them. */
    private val liveProcesses = AtomicReference<StellarRemoteProcess?>(null)

    suspend fun execute(steps: List<List<String>>, timeoutMs: Long): Result {
        val clamped = clampTimeout(timeoutMs)

        if (!Stellar.pingBinder()) {
            return Result(EXIT_NO_SERVICE, "", "Stellar service is not running; start it in Stellar Manager first")
        }

        val started = System.currentTimeMillis()
        try {
            return withTimeoutOrNull(clamped) { runSteps(steps) }
                ?: killAndReport(clamped, started)
        } catch (e: Exception) {
            Log.e(TAG, "execution failed", e)
            return Result(EXIT_NO_SERVICE, "", "execution failed: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim())
        } finally {
            liveProcesses.set(null)
        }
    }

    fun clampTimeout(timeoutMs: Long): Long = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    private suspend fun runSteps(steps: List<List<String>>): Result = coroutineScope {
        val stdoutParts = mutableListOf<String>()
        val stderrParts = mutableListOf<String>()
        var lastExit = EXIT_OK

        for (step in steps) {
            val argv = step.map<String, String?> { it }.toTypedArray()
            val process = Stellar.newProcess(argv, null, null)
            liveProcesses.set(process)
            val outJob = async(Dispatchers.IO) { drain(process.inputStream) }
            val errJob = async(Dispatchers.IO) { drain(process.errorStream) }
            lastExit = process.waitFor()
            outJob.await().let { if (it.isNotEmpty()) stdoutParts.add(it) }
            errJob.await().let { if (it.isNotEmpty()) stderrParts.add(it) }
            if (lastExit != EXIT_OK) break
        }

        Result(
            exitCode = lastExit,
            stdout = stdoutParts.joinToString("\n"),
            stderr = stderrParts.joinToString("\n"),
        )
    }

    /** Called only when withTimeoutOrNull returned null (the job ran over its limit). */
    private fun killAndReport(clamped: Long, started: Long): Result {
        liveProcesses.getAndSet(null)?.let { p ->
            try {
                p.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "destroy on timeout failed", e)
            }
        }
        Log.w(TAG, "job timed out after ${System.currentTimeMillis() - started}ms (limit ${clamped}ms), killed")
        return Result(EXIT_TIMEOUT, "", "timed out after ${clamped}ms, process killed")
    }

    private fun drain(stream: InputStream): String {
        val buffer = ByteArray(8 * 1024)
        val out = StringBuilder()
        var total = 0
        while (true) {
            val n = try {
                stream.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (n < 0) break
            if (total < MAX_OUTPUT_BYTES) {
                val keep = minOf(n, MAX_OUTPUT_BYTES - total)
                out.append(String(buffer, 0, keep, Charsets.UTF_8))
                total += keep
            }
        }
        return out.toString()
    }
}
