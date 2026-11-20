package com.astra.intellij.workspace

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.WorkspaceData
import com.google.gson.Gson
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.project.Project
import java.util.UUID

@Service(Service.Level.PROJECT)
@State(name = "AstraWorkspace", storages = [Storage("astra.xml")])
class WorkspaceService : PersistentStateComponent<WorkspaceService.State> {
    data class State(var payload: String = "")

    private val gson = Gson()
    var data = WorkspaceData()
        private set

    init {
        ensureEnvironment()
    }

    override fun getState(): State = State(gson.toJson(data))

    override fun loadState(state: State) {
        data = if (state.payload.isBlank()) {
            WorkspaceData()
        } else {
            runCatching { gson.fromJson(state.payload, WorkspaceData::class.java) }
                .getOrNull() ?: WorkspaceData()
        }
        ensureEnvironment()
    }

    fun activeVariables(): Map<String, String> {
        val environmentVars = data.environments
            .firstOrNull { it.id == data.activeEnvironmentId }?.variables ?: emptyMap()
        return data.globals + environmentVars
    }

    fun activeEnvironment(): AstraEnvironment =
        data.environments.firstOrNull { it.id == data.activeEnvironmentId } ?: data.environments.first()

    private fun ensureEnvironment() {
        if (data.environments.isEmpty()) {
            data.environments.add(AstraEnvironment(UUID.randomUUID().toString(), "Default Environment"))
        }
        if (data.environments.none { it.id == data.activeEnvironmentId }) {
            data.activeEnvironmentId = data.environments.first().id
        }
    }

    companion object {
        fun get(project: Project): WorkspaceService = project.getService(WorkspaceService::class.java)
    }
}
