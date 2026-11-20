package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.AstraSettings
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

data class RunItemResult(
    val request: AstraRequest,
    val status: Int,
    val timeMs: Long,
    val tests: List<AssertionResult>,
    val error: String?,
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

    /** Runs requests sequentially on the calling thread; call from a background thread. */
    fun run(
        requests: List<AstraRequest>,
        baseVars: Map<String, String>,
        environment: AstraEnvironment?,
        settings: AstraSettings,
        onResult: (index: Int, result: RunItemResult) -> Unit,
    ) {
        cancelled = false
        requests.forEachIndexed { index, request ->
            if (cancelled) return
            val vars = PreRequestService.execute(request.preRequest, baseVars, environment)
            val call = try {
                http.send(request, vars, settings)
            } catch (e: Exception) {
                onResult(index, RunItemResult(request, 0, 0, emptyList(), e.message ?: "Invalid request"))
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
                )
            } catch (e: CancellationException) {
                RunItemResult(request, 0, 0, emptyList(), "Cancelled")
            } catch (e: Exception) {
                RunItemResult(request, 0, 0, emptyList(), e.cause?.message ?: e.message ?: "Request failed")
            }
            onResult(index, outcome)
        }
    }
}
