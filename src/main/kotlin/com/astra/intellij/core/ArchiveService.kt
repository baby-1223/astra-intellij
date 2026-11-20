package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.RequestCollection
import com.google.gson.GsonBuilder

data class AstraArchive(
    val format: String? = null,
    val version: Int = 1,
    val collections: List<RequestCollection>? = null,
    val environments: List<AstraEnvironment>? = null,
    val activeEnvironmentId: String? = null,
)

class ArchiveService {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun export(
        collections: List<RequestCollection>,
        environments: List<AstraEnvironment>,
        activeEnvironmentId: String?,
    ): String = gson.toJson(
        AstraArchive(
            format = FORMAT,
            collections = collections,
            environments = environments,
            activeEnvironmentId = activeEnvironmentId,
        ),
    )

    fun import(content: String): AstraArchive {
        val archive = gson.fromJson(content, AstraArchive::class.java)
            ?: throw IllegalArgumentException("Empty Astra workspace file")
        if (archive.format != FORMAT) {
            throw IllegalArgumentException("Not an Astra workspace file (expected format \"$FORMAT\")")
        }
        return archive.copy(
            collections = archive.collections.orEmpty(),
            environments = archive.environments.orEmpty(),
        )
    }

    companion object {
        const val FORMAT = "astra.workspace"
    }
}
