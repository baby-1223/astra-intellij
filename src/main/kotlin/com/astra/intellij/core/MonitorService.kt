package com.astra.intellij.core

import com.astra.intellij.model.MonitorConfig
import com.astra.intellij.model.allRequests
import com.astra.intellij.model.effectiveAuth
import com.astra.intellij.model.inheritedScripts
import com.astra.intellij.workspace.WorkspaceService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Local scheduler for collection monitors.
 *
 * A single daemon thread checks every 15 seconds which enabled monitors are due, then runs the
 * collection through [CollectionRunner] with the same inheritance rules as the interactive runner.
 * It only runs while the IDE is open, like the desktop app.
 */
class MonitorService(
    private val workspace: WorkspaceService,
    private val onChanged: () -> Unit,
) : Disposable {
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Astra monitors").apply { isDaemon = true }
        }
    private val running = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var disposed = false

    fun start() {
        if (disposed) return
        scheduler.scheduleWithFixedDelay({ runCatching { tick() } }, 3, 15, TimeUnit.SECONDS)
    }

    /** Runs a monitor immediately, from the management dialog. */
    fun runNow(monitorId: String) {
        if (disposed) return
        scheduler.execute { runCatching { runMonitor(monitorId) } }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        workspace.data.monitors.toList().forEach { monitor ->
            if (!monitor.enabled || monitor.collectionId.isBlank()) return@forEach
            val intervalMs = monitor.intervalMinutes.coerceAtLeast(1) * 60_000L
            if (monitor.lastRunAt == 0L || now - monitor.lastRunAt >= intervalMs) {
                runMonitor(monitor.id)
            }
        }
    }

    private fun runMonitor(monitorId: String) {
        if (!running.add(monitorId)) return
        try {
            val monitor = workspace.data.monitors.firstOrNull { it.id == monitorId } ?: return
            val collection = workspace.data.collections.firstOrNull { it.id == monitor.collectionId }
            if (collection == null) {
                record(monitorId, 0, 0, "Collection no longer exists")
                return
            }
            val requests = collection.allRequests().filterNot { it.isWebSocket }
            if (requests.isEmpty()) {
                record(monitorId, 0, 0, "Collection has no requests")
                return
            }
            val prepared = requests.map { original ->
                val working = original.copy(
                    params = original.params.map { it.copy() }.toMutableList(),
                    headers = original.headers.map { it.copy() }.toMutableList(),
                    formFields = original.formFields.map { it.copy() }.toMutableList(),
                    assertions = original.assertions.toMutableList(),
                    preRequest = original.preRequest.toMutableList(),
                    postResponse = original.postResponse.toMutableList(),
                    auth = original.auth.copy(),
                )
                collection.effectiveAuth(original)?.let { working.auth = it.copy() }
                val pre = collection.inheritedScripts(original, preRequest = true)
                if (pre.isNotEmpty()) working.preRequest = (pre + working.preRequest).toMutableList()
                val post = collection.inheritedScripts(original, preRequest = false)
                if (post.isNotEmpty()) working.postResponse = (post + working.postResponse).toMutableList()
                working
            }
            val scope = requests.associate { it.id to workspace.variablesFor(it) }
            val settings = workspace.data.settings
            val environment = workspace.activeEnvironment()
            var passed = 0
            var total = 0
            var firstError: String? = null
            CollectionRunner().run(
                requests = prepared,
                baseVars = emptyMap(),
                environment = environment,
                settings = settings,
                config = RunnerConfig(
                    iterations = monitor.iterations.coerceAtLeast(1),
                    continueOnFailure = true,
                ),
                scopeVariables = { request -> scope[request.id].orEmpty() },
            ) { _, _, result ->
                total += 1
                if (result.passed) passed += 1
                if (firstError == null) firstError = result.error
            }
            record(monitorId, passed, total, firstError.orEmpty())
        } finally {
            running.remove(monitorId)
        }
    }

    private fun record(monitorId: String, passed: Int, total: Int, error: String) {
        val target: MonitorConfig = workspace.data.monitors.firstOrNull { it.id == monitorId } ?: return
        target.lastRunAt = System.currentTimeMillis()
        target.lastPassed = passed
        target.lastTotal = total
        target.lastError = error
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) onChanged()
        }
    }

    override fun dispose() {
        disposed = true
        scheduler.shutdownNow()
        running.clear()
    }
}
