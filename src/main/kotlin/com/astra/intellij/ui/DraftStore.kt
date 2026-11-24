package com.astra.intellij.ui

import com.astra.intellij.core.SecretStore
import com.astra.intellij.core.hydrateRequestSecrets
import com.astra.intellij.core.redactRequestSecrets
import com.astra.intellij.model.AstraRequest
import com.google.gson.Gson
import com.intellij.openapi.application.PathManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persists unsaved request tabs so a crash or IDE restart does not lose work in progress.
 * Only the first tool window in a JVM restores the drafts, avoiding duplicates across windows.
 */
object RequestDrafts {
    private val gson = Gson()
    private val claimed = AtomicBoolean(false)

    private fun file(): File = PathManager.getConfigDir().resolve("astra").resolve("drafts.json").toFile()

    fun loadOnce(): List<AstraRequest> {
        if (!claimed.compareAndSet(false, true)) return emptyList()
        return runCatching {
            val target = file()
            if (!target.isFile) return emptyList()
            val parsed = gson.fromJson(target.readText(), Array<AstraRequest>::class.java)?.toList().orEmpty()
            parsed.forEach { hydrateRequestSecrets(it) { ref -> SecretStore.read(ref) } }
            parsed
        }.getOrDefault(emptyList())
    }

    fun save(requests: List<AstraRequest>) {
        val target = file()
        if (requests.isEmpty() && !target.exists()) return
        runCatching {
            target.parentFile?.mkdirs()
            val copies = requests.map {
                gson.fromJson(gson.toJson(it), AstraRequest::class.java).also { copy ->
                    redactRequestSecrets(copy) { ref, value -> SecretStore.write(ref, value) }
                }
            }
            val temp = File(target.parentFile, "drafts.json.tmp")
            temp.writeText(gson.toJson(copies))
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    fun clear() {
        runCatching { file().delete() }
    }
}
