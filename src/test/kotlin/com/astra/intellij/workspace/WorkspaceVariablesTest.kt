package com.astra.intellij.workspace

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceVariablesTest {
    @Test
    fun `request variables follow Postman scope precedence`() {
        val workspace = WorkspaceService()
        val request = AstraRequest()
        val folder = RequestFolder(
            name = "Auth",
            requests = mutableListOf(request),
            variables = mutableMapOf("a" to "folder", "b" to "folder", "hidden" to "folder"),
            disabledVariables = mutableSetOf("hidden"),
        )
        val collection = RequestCollection(
            name = "Team",
            folders = mutableListOf(folder),
            variables = mutableMapOf("a" to "collection", "c" to "collection", "off" to "collection"),
            disabledVariables = mutableSetOf("off"),
        )
        workspace.data.collections.clear()
        workspace.data.collections.add(collection)
        workspace.data.globals.clear()
        workspace.data.globals["a"] = "global"
        workspace.data.globals["d"] = "global"
        workspace.data.environments.clear()
        val environment = AstraEnvironment(
            name = "dev",
            variables = mutableMapOf("a" to "env"),
        )
        workspace.data.environments.add(environment)
        workspace.data.activeEnvironmentId = environment.id

        assertEquals(
            mapOf("a" to "folder", "b" to "folder", "c" to "collection"),
            workspace.scopedVariables(request),
        )
        assertEquals(
            mapOf("a" to "env", "b" to "folder", "c" to "collection", "d" to "global"),
            workspace.variablesFor(request),
        )
    }
}
