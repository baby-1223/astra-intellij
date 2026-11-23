package com.astra.intellij.workspace

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.RequestFolder
import com.astra.intellij.model.WorkspaceData
import com.astra.intellij.model.containsRequest
import com.astra.intellij.model.folderPathTo
import com.astra.intellij.model.normalize
import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.messages.Topic
import java.io.File
import java.util.UUID

/**
 * Application-level workspace storage shared by every IDE window.
 *
 * Data lives in the IDE configuration directory instead of a single project, so opening the
 * same workspace in another window shows the same collections and environments. Data that was
 * previously written to a project's .idea/astra.xml is merged in once, on first access.
 */
@Service(Service.Level.APP)
@State(name = "AstraWorkspace", storages = [Storage("astra.xml")])
class WorkspaceService : PersistentStateComponent<WorkspaceService.State> {
    data class State(
        var payload: String = "",
        var migratedProjects: MutableList<String> = mutableListOf(),
    )

    fun interface Listener {
        fun workspaceChanged(source: Any?)
    }

    private val gson = Gson()
    private val migrated = mutableSetOf<String>()
    var data = WorkspaceData()
        private set

    init {
        ensureEnvironment()
    }

    override fun getState(): State = State(gson.toJson(data), migrated.toMutableList())

    override fun loadState(state: State) {
        data = if (state.payload.isBlank()) {
            WorkspaceData()
        } else {
            runCatching { gson.fromJson(state.payload, WorkspaceData::class.java) }
                .getOrNull() ?: WorkspaceData()
        }
        data.normalize()
        migrated.clear()
        migrated.addAll(state.migratedProjects.orEmpty())
        ensureEnvironment()
    }

    /** Restores the first-run state: no collections, history or settings, one empty environment. */
    fun reset() {
        data = WorkspaceData()
        ensureEnvironment()
        notifyChanged(this)
    }

    fun activeVariables(): Map<String, String> {
        val environmentVars = data.environments
            .firstOrNull { it.id == data.activeEnvironmentId }?.variables ?: emptyMap()
        return data.globals + environmentVars
    }

    fun activeEnvironment(): AstraEnvironment =
        data.environments.firstOrNull { it.id == data.activeEnvironmentId } ?: data.environments.first()

    /** Variables declared on the collection and its folder chain for [request]. */
    fun scopedVariables(request: AstraRequest?): Map<String, String> {
        if (request == null) return emptyMap()
        val collection = data.collections.firstOrNull { it.containsRequest(request) } ?: return emptyMap()
        val merged = LinkedHashMap<String, String>()
        collection.variables.forEach { (key, value) ->
            if (key !in collection.disabledVariables) merged[key] = value
        }
        collection.folderPathTo(request)?.forEach { folder ->
            folder.variables.forEach { (key, value) ->
                if (key !in folder.disabledVariables) merged[key] = value
            }
        }
        return merged
    }

    /** Postman precedence: globals < collection < folder < environment. */
    fun variablesFor(request: AstraRequest?): Map<String, String> {
        val merged = LinkedHashMap<String, String>(data.globals)
        merged.putAll(scopedVariables(request))
        merged.putAll(activeEnvironment().variables)
        return merged
    }

    /** Tells other windows that the shared workspace changed. */
    fun notifyChanged(source: Any?) {
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).workspaceChanged(source)
    }

    private fun ensureEnvironment() {
        if (data.environments.isEmpty()) {
            data.environments.add(AstraEnvironment(UUID.randomUUID().toString(), "Default Environment"))
        }
        if (data.environments.none { it.id == data.activeEnvironmentId }) {
            data.activeEnvironmentId = data.environments.first().id
        }
    }

    /**
     * Imports the old project-level workspace once per project. The legacy file is produced by
     * the project-scoped component that earlier plugin versions used.
     */
    private fun migrateLegacy(project: Project) {
        val basePath = project.basePath ?: return
        val key = File(basePath).absolutePath
        if (!migrated.add(key)) return
        val legacyFile = File(File(basePath, ".idea"), "astra.xml")
        if (!legacyFile.isFile) return
        val payload = runCatching { findPayload(JDOMUtil.load(legacyFile)) }.getOrNull()
        if (payload.isNullOrBlank()) return
        val legacy = runCatching { gson.fromJson(payload, WorkspaceData::class.java) }.getOrNull() ?: return
        merge(legacy)
    }

    /** The legacy file may be a bare <component> or wrapped in <project>; search both shapes. */
    private fun findPayload(element: org.jdom.Element): String? {
        if (element.name == "option" && element.getAttributeValue("name") == "payload") {
            return element.getAttributeValue("value")
        }
        element.getChildren().forEach { child ->
            findPayload(child as org.jdom.Element)?.let { return it }
        }
        return null
    }

    private fun merge(legacy: WorkspaceData) {
        legacy.normalize()
        var changed = false
        legacy.collections.forEach { collection ->
            if (data.collections.any { it.id == collection.id }) {
                collection.id = UUID.randomUUID().toString()
                collection.requests.forEach { it.id = UUID.randomUUID().toString() }
                collection.folders.forEach { resetIds(it) }
            }
            data.collections.add(collection)
            changed = true
        }
        legacy.environments.forEach { environment ->
            val existing = data.environments.firstOrNull { it.name == environment.name }
            if (existing == null) {
                environment.id = UUID.randomUUID().toString()
                data.environments.add(environment)
                changed = true
            } else {
                environment.variables.forEach { (key, value) -> existing.variables.putIfAbsent(key, value) }
            }
        }
        legacy.globals.forEach { (key, value) -> data.globals.putIfAbsent(key, value) }
        ensureEnvironment()
        if (changed) notifyChanged(null)
    }

    private fun resetIds(folder: RequestFolder) {
        folder.id = UUID.randomUUID().toString()
        folder.requests.forEach { it.id = UUID.randomUUID().toString() }
        folder.folders.forEach { resetIds(it) }
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("Astra workspace changed", Listener::class.java)

        fun get(project: Project): WorkspaceService {
            val service = ApplicationManager.getApplication().getService(WorkspaceService::class.java)
            service.migrateLegacy(project)
            return service
        }
    }
}
