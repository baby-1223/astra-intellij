package com.astra.intellij.ui

import com.astra.intellij.core.HttpFileParser
import com.astra.intellij.core.OpenApiService
import com.astra.intellij.core.SpringConfigParser
import com.astra.intellij.core.SpringControllerParser
import com.astra.intellij.core.SpringSource
import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.AstraRequest
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
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
        val sources = collectProjectSources(project, fileName)
        val specs = SpringControllerParser.parseAll(text, fileName, sources)
        if (specs.isEmpty()) throw IllegalArgumentException("No Spring controller mappings found in this file")

        var created = 0
        var updated = 0
        var requestCount = 0
        val pathVariables = LinkedHashMap<String, String>()
        val baseUrl = springEnvironment(project)?.variables?.get("baseUrl") ?: "http://localhost:8080"

        specs.forEach { spec ->
            val collection = SpringControllerParser.toCollection(spec)
            requestCount += collection.requests.size
            val result = upsertCollection(workspace, collection)
            if (result.second) updated++ else created++
            spec.endpoints.forEach { endpoint ->
                endpoint.pathVariables.forEach { (name, sample) -> pathVariables.putIfAbsent(name, sample) }
            }
        }

        val environment = springEnvironment(project) ?: AstraEnvironment(
            name = "Spring",
            variables = linkedMapOf("baseUrl" to baseUrl),
        )
        environment.variables.putIfAbsent("baseUrl", baseUrl)
        pathVariables.forEach { (name, value) -> environment.variables.putIfAbsent(name, value) }
        val stored = mergeEnvironment(workspace, environment)
        if (workspace.data.activeEnvironmentId.isNullOrBlank()) {
            workspace.data.activeEnvironmentId = stored.id
        }
        workspace.notifyChanged(null)

        val controllerText = if (specs.size == 1) "1 controller" else "${specs.size} controllers"
        val changeText = buildString {
            if (created > 0) append("created $created")
            if (created > 0 && updated > 0) append(", ")
            if (updated > 0) append("updated $updated")
        }
        return "Generated $requestCount request(s) from $controllerText ($changeText)"
    }

    /** Adds a new collection or merges generated requests into an existing collection with the same name. */
    fun upsertCollection(workspace: WorkspaceService, collection: RequestCollection): Pair<RequestCollection, Boolean> {
        val existing = workspace.data.collections.firstOrNull { it.name == collection.name }
        if (existing == null) {
            addCollection(workspace, collection)
            return collection to false
        }
        collection.requests.forEach { incoming ->
            val target = existing.requests.firstOrNull { it.name == incoming.name }
            if (target == null) {
                regenerateRequestIds(incoming)
                existing.requests += incoming
            } else {
                copyRequest(target, incoming)
            }
        }
        return existing to true
    }

    private fun copyRequest(target: AstraRequest, source: AstraRequest) {
        target.method = source.method
        target.url = source.url
        target.headers = source.headers
        target.params = source.params
        target.bodyMode = source.bodyMode
        target.body = source.body
        target.formFields = source.formFields
    }

    private fun regenerateRequestIds(request: AstraRequest) {
        request.id = UUID.randomUUID().toString()
    }

    /** Reads project sources so DTOs declared outside the controller file can be rendered. */
    fun collectProjectSources(project: Project, currentFileName: String): List<SpringSource> {
        val base = project.basePath ?: return emptyList()
        val result = mutableListOf<SpringSource>()
        var totalBytes = 0L
        var visited = 0
        val skipped = setOf("build", "target", ".git", ".idea", "node_modules", "out", "dist", ".gradle")
        runCatching {
            File(base).walkTopDown()
                .onEnter { directory -> directory.name !in skipped }
                .filter { file ->
                    file.isFile && (file.name.endsWith(".java") || file.name.endsWith(".kt")) &&
                        file.name != currentFileName
                }
                .take(4000)
                .forEach { file ->
                    val length = file.length()
                    if (length == 0L || length > 2 * 1024 * 1024 || totalBytes > 20 * 1024 * 1024) return@forEach
                    totalBytes += length
                    visited++
                    result += SpringSource(file.name, decodeText(file.readBytes()))
                }
        }
        if (visited == 0) return emptyList()
        return result
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

    /** Base config plus `application-{profile}` files selected by `spring.profiles.active`. */
    fun springConfigFiles(project: Project): List<File> {
        val base = findSpringConfig(project) ?: return emptyList()
        val properties = runCatching { SpringConfigParser.parse(base.readText(), base.name) }.getOrDefault(emptyMap())
        val active = properties["spring.profiles.active"]
            ?: System.getenv("SPRING_PROFILES_ACTIVE").orEmpty()
        val profiles = active.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val directory = base.parentFile ?: return listOf(base)
        val overlay = profiles.mapNotNull { profile ->
            listOf("application-$profile.yml", "application-$profile.yaml", "application-$profile.properties")
                .map { File(directory, it) }
                .firstOrNull { it.isFile }
        }
        return listOf(base) + overlay
    }

    fun springEnvironment(project: Project): AstraEnvironment? {
        val files = springConfigFiles(project)
        if (files.isEmpty()) return null
        return runCatching {
            val merged = LinkedHashMap<String, String>()
            files.forEach { file ->
                merged.putAll(SpringConfigParser.parse(file.readText(), file.name))
            }
            SpringConfigParser.environment(merged, "Spring ${files.first().name}")
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
            // Never overwrite values the user edited by hand.
            environment.variables.forEach { (key, value) -> existing.variables.putIfAbsent(key, value) }
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

    fun readVirtualFile(file: VirtualFile): String = decodeText(file.contentsToByteArray())

    /** Decodes UTF-8 with BOM or strict validation, falling back to GB18030 for legacy sources. */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("GB18030"))
        }
    }

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
                file?.name ?: "Controller",
            )
            AstraIntegration.openAstra(project)
            AstraIntegration.showInfo(project, message)
        } catch (error: Exception) {
            AstraIntegration.showError(project, error.message ?: "Unable to read this controller")
        }
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val text = AstraIntegration.readEditorText(event)
            ?: file?.let { runCatching { AstraIntegration.readVirtualFile(it) }.getOrNull() }
        val visible = text != null &&
            (file == null || AstraIntegration.isJavaOrKotlin(file.name)) &&
            AstraIntegration.looksLikeSpringController(text)
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
