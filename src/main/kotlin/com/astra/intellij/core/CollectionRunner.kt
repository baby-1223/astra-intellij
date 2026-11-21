package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AstraSettings
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

data class RunnerConfig(
    val iterations: Int = 1,
    val delayMs: Long = 0L,
    val continueOnFailure: Boolean = true,
    val dataRows: List<Map<String, String>> = emptyList(),
)

data class RunItemResult(
    val request: AstraRequest,
    val status: Int,
    val timeMs: Long,
    val tests: List<AssertionResult>,
    val error: String?,
    val bodySize: Int = 0,
) {
    val passed: Boolean
        get() = error == null && tests.all { it.passed }
}

class CollectionRunner(
    private val http: HttpService = HttpService(),
) {
    @Volatile
    private var cancelled = false

    @Volatile
    private var currentCall: HttpCall? = null

    fun cancel() {
        cancelled = true
        currentCall?.cancel()
    }

    /** Runs requests sequentially once on the calling thread; call from a background thread. */
    fun run(
        requests: List<AstraRequest>,
        baseVars: Map<String, String>,
        environment: AstraEnvironment?,
        settings: AstraSettings,
        onResult: (index: Int, result: RunItemResult) -> Unit,
    ) {
        run(requests, baseVars, environment, settings, RunnerConfig()) { _, index, result ->
            onResult(index, result)
        }
    }

    /** Runs iterations sequentially on the calling thread; call from a background thread. */
    fun run(
        requests: List<AstraRequest>,
        baseVars: Map<String, String>,
        environment: AstraEnvironment?,
        settings: AstraSettings,
        config: RunnerConfig,
        onResult: (iteration: Int, index: Int, result: RunItemResult) -> Unit,
    ) {
        cancelled = false
        val dataRows = config.dataRows.ifEmpty {
            List(config.iterations.coerceAtLeast(1)) { emptyMap() }
        }
        val totalPlanned = dataRows.size * requests.size
        var completed = 0
        dataRows.forEachIndexed { iteration, dataRow ->
            requests.forEachIndexed { index, request ->
                if (cancelled) return
                val vars = (baseVars + dataRow).toMutableMap()
                val resolvedVars = PreRequestService.execute(request.preRequest, vars, environment)
                val call = try {
                    http.send(request, resolvedVars, settings)
                } catch (e: Exception) {
                    val outcome = RunItemResult(request, 0, 0, emptyList(), e.message ?: "Invalid request")
                    onResult(iteration, index, outcome)
                    completed++
                    if (!config.continueOnFailure && !outcome.passed) return
                    if (shouldDelay(config, completed, totalPlanned)) waitDelay(config.delayMs)
                    return@forEachIndexed
                }
                currentCall = call
                val outcome = try {
                    val result = call.future.get(settings.timeoutSeconds.toLong() + 10, TimeUnit.SECONDS)
                    RunItemResult(
                        request = request,
                        status = result.status,
                        timeMs = result.elapsedMs,
                        tests = TestService.evaluate(request.assertions, result),
                        error = null,
                        bodySize = result.body.size,
                    )
                } catch (e: CancellationException) {
                    RunItemResult(request, 0, 0, emptyList(), "Cancelled")
                } catch (e: Exception) {
                    RunItemResult(request, 0, 0, emptyList(), e.cause?.message ?: e.message ?: "Request failed")
                }
                onResult(iteration, index, outcome)
                completed++
                if (!config.continueOnFailure && !outcome.passed) return
                if (shouldDelay(config, completed, totalPlanned)) waitDelay(config.delayMs)
            }
        }
    }

    private fun shouldDelay(config: RunnerConfig, completed: Int, totalPlanned: Int): Boolean =
        config.delayMs > 0 && completed < totalPlanned && !cancelled

    /** Sleeps in small slices so cancel() is responsive even during a long inter-request delay. */
    private fun waitDelay(delayMs: Long) {
        if (delayMs <= 0L) return
        val deadlineNs = System.nanoTime() + delayMs * 1_000_000L
        while (!cancelled) {
            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0L) return
            runCatching { Thread.sleep(minOf(remainingMs, 50L)) }
        }
    }
}
