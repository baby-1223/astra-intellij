package com.astra.intellij.ui

import com.astra.intellij.core.HttpFileParser
import com.astra.intellij.core.OpenApiService
import com.astra.intellij.core.SpringConfigParser
import com.astra.intellij.core.SpringControllerParser
import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.RequestCollection
import com.astra.intellij.model.RequestFolder
import com.astra.intellij.model.allRequests
import com.astra.intellij.workspace.WorkspaceService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File
import java.util.Locale
import java.util.UUID

/** Shared entry points for the IDE integration actions and the Astra toolbar. */
object AstraIntegration {
    private val specHint = Regex("\"?openapi\"?\\s*:|'?openapi'?\\s*:|(?m)^\\s*swagger\\s*:")

    fun isHttpFile(fileName: String): Boolean = HttpFileParser.supports(fileName)

    fun isSpecFile(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension == "json" || extension == "yaml" || extension == "yml"
    }

    fun looksLikeSpec(text: String): Boolean = specHint.containsMatchIn(text.take(8192))

    fun isJavaOrKotlin(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension == "java" || extension == "kt"
    }

    fun supportsFile(file: VirtualFile): Boolean {
        if (!file.isValid || file.isDirectory) return false
        if (isHttpFile(file.name)) return true
        if (!isSpecFile(file.name) || file.length > 4 * 1024 * 1024) return false
        return runCatching { looksLikeSpec(String(file.contentsToByteArray(), Charsets.UTF_8)) }
            .getOrDefault(false)
    }

    fun looksLikeSpringController(text: String): Boolean = SpringControllerParser.looksLikeController(text)

    fun importHttpFile(
        project: Project,
        workspace: WorkspaceService,
        text: String,
        fileName: String,
    ): String {
        val parsed = HttpFileParser.parse(text, fileName)
        if (parsed.requests.isEmpty()) {
            throw IllegalArgumentException("No requests found in this file")
        }
        val count = parsed.requests.size
        addCollection(workspace, parsed)
        workspace.notifyChanged(null)
        return "Imported $count request(s) into \"${parsed.name}\""
    }

    fun importSpec(project: Project, workspace: WorkspaceService, text: String): String {
        val imported = OpenApiService().parse(text)
        val count = imported.collection.allRequests().size
        if (count == 0) {
            throw IllegalArgumentException("No operations found in this file")
        }
        addCollection(workspace, imported.collection)
        imported.environment?.let { environment ->
            val stored = mergeEnvironment(workspace, environment)
            workspace.data.activeEnvironmentId = stored.id
        }
        workspace.notifyChanged(null)
        return "Imported $count operation(s) into \"${imported.collection.name}\""
    }

    fun importSpringConfig(project: Project, workspace: WorkspaceService, file: File): String {
        val text = file.readText()
        val properties = SpringConfigParser.parse(text, file.name)
        if (properties.isEmpty()) {
            throw IllegalArgumentException("No properties found in ${file.name}")
        }
        val environment = SpringConfigParser.environment(properties, "Spring ${file.name}")
        val stored = mergeEnvironment(workspace, environment)
        workspace.data.activeEnvironmentId = stored.id
        workspace.notifyChanged(null)
        return "Loaded ${stored.variables.size} variable(s) from ${file.name}"
    }

    /**
     * Turns a Spring controller into an Astra collection and brings the application's variables
     * along, so the generated `{{baseUrl}}` request is immediately runnable.
     */
    fun generateFromController(
        project: Project,
        workspace: WorkspaceService,
        text: String,
        fileName: String,
    ): String {
        val spec = SpringControllerParser.parse(text, fileName)
            ?: throw IllegalArgumentException("No Spring controller mappings found in this file")
        val collection = SpringControllerParser.toCollection(spec)
        addCollection(workspace, collection)
        val environment = springEnvironment(project)
            ?: AstraEnvironment(
                name = "Spring",
                variables = linkedMapOf("baseUrl" to "http://localhost:8080"),
            )
        val stored = mergeEnvironment(workspace, environment)
        workspace.data.activeEnvironmentId = stored.id
        workspace.notifyChanged(null)
        return "Generated ${collection.requests.size} request(s) from ${spec.className}"
    }

    /** Locates `application.yml` / `application.properties` in the usual project locations. */
    fun findSpringConfig(project: Project): File? {
        val base = project.basePath ?: return null
        val relative = listOf(
            "src/main/resources/application.yml",
            "src/main/resources/application.yaml",
            "src/main/resources/application.properties",
            "src/test/resources/application.yml",
            "src/test/resources/application.yaml",
            "application.yml",
            "application.yaml",
            "application.properties",
        )
        return relative.asSequence()
            .map { File(base, it) }
            .firstOrNull { it.isFile }
    }

    fun springEnvironment(project: Project): AstraEnvironment? {
        val file = findSpringConfig(project) ?: return null
        return runCatching {
            val properties = SpringConfigParser.parse(file.readText(), file.name)
            SpringConfigParser.environment(properties, "Spring ${file.name}")
        }.getOrNull()
    }

    fun addCollection(workspace: WorkspaceService, collection: RequestCollection): RequestCollection {
        regenerateIds(collection)
        collection.name = uniqueName(
            collection.name.ifBlank { "Imported" },
            workspace.data.collections.map { it.name },
        )
        workspace.data.collections.add(collection)
        return collection
    }

    fun mergeEnvironment(workspace: WorkspaceService, environment: AstraEnvironment): AstraEnvironment {
        val existing = workspace.data.environments.firstOrNull { it.name == environment.name }
        if (existing != null) {
            environment.variables.forEach { (key, value) -> existing.variables[key] = value }
            return existing
        }
        environment.id = UUID.randomUUID().toString()
        workspace.data.environments.add(environment)
        return environment
    }

    fun openAstra(project: Project) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Astra") ?: return
        toolWindow.show()
        toolWindow.activate(null)
    }

    fun readVirtualFile(file: VirtualFile): String = String(file.contentsToByteArray(), Charsets.UTF_8)

    fun readEditorText(event: AnActionEvent): String? =
        event.getData(CommonDataKeys.EDITOR)?.document?.text

    fun showError(project: Project, message: String) =
        Messages.showErrorDialog(project, message, "Astra")

    fun showInfo(project: Project, message: String) =
        Messages.showInfoMessage(project, message, "Astra")

    private fun uniqueName(base: String, existing: List<String>): String {
        if (existing.none { it == base }) return base
        var index = 2
        while (existing.any { it == "$base ($index)" }) index++
        return "$base ($index)"
    }

    private fun regenerateIds(collection: RequestCollection) {
        collection.id = UUID.randomUUID().toString()
        collection.requests.forEach { it.id = UUID.randomUUID().toString() }
        collection.folders.forEach { regenerateFolder(it) }
    }

    private fun regenerateFolder(folder: RequestFolder) {
        folder.id = UUID.randomUUID().toString()
        folder.requests.forEach { it.id = UUID.randomUUID().toString() }
        folder.folders.forEach { regenerateFolder(it) }
    }
}

/** Right-click action for `.http`, `.rest` and OpenAPI documents, in the editor or Project view. */
class ImportApiFileAction : AnAction(
    "Import into Astra",
    "Import this .http / .rest / OpenAPI file as an Astra collection",
    null,
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        try {
            val workspace = WorkspaceService.get(project)
            val text = AstraIntegration.readVirtualFile(file)
            val message = if (AstraIntegration.isHttpFile(file.name)) {
                AstraIntegration.importHttpFile(project, workspace, text, file.name)
            } else {
                AstraIntegration.importSpec(project, workspace, text)
            }
            AstraIntegration.openAstra(project)
            AstraIntegration.showInfo(project, message)
        } catch (error: Exception) {
            AstraIntegration.showError(project, error.message ?: "Unable to import this file")
        }
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = file != null && AstraIntegration.supportsFile(file)
    }
}

/** Right-click action on a Spring controller that creates one Astra request per mapping. */
class GenerateSpringRequestsAction : AnAction(
    "Generate Astra Requests",
    "Create Astra requests for every mapping in this Spring controller",
    null,
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val text = AstraIntegration.readEditorText(event)
            ?: file?.let { AstraIntegration.readVirtualFile(it) }
            ?: return
        try {
            val workspace = WorkspaceService.get(project)
            val message = AstraIntegration.generateFromController(
                project,
                workspace,
                text,
                file?.nameWithoutExtension ?: "Controller",
            )
            AstraIntegration.openAstra(project)
            AstraIntegration.showInfo(project, message)
        } catch (error: Exception) {
            AstraIntegration.showError(project, error.message ?: "Unable to read this controller")
        }
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val visible = file != null && AstraIntegration.isJavaOrKotlin(file.name) && runCatching {
            val text = AstraIntegration.readEditorText(event) ?: AstraIntegration.readVirtualFile(file)
            AstraIntegration.looksLikeSpringController(text)
        }.getOrDefault(false)
        event.presentation.isEnabledAndVisible = visible
    }
}

/** Loads variables from `application.yml` / `application.properties` into an environment. */
class LoadSpringConfigAction : AnAction(
    "Load Variables into Astra",
    "Create an Astra environment from this Spring configuration file",
    null,
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)?.let { File(it.path) }
            ?: AstraIntegration.findSpringConfig(project)
            ?: return
        try {
            val workspace = WorkspaceService.get(project)
            val message = AstraIntegration.importSpringConfig(project, workspace, file)
            AstraIntegration.openAstra(project)
            AstraIntegration.showInfo(project, message)
        } catch (error: Exception) {
            AstraIntegration.showError(project, error.message ?: "Unable to read this configuration file")
        }
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val name = file?.name?.lowercase(Locale.ROOT).orEmpty()
        event.presentation.isEnabledAndVisible = file != null &&
            (name == "application.yml" || name == "application.yaml" || name.endsWith(".properties"))
    }
}
