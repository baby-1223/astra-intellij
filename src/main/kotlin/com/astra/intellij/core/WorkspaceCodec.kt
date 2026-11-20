package com.astra.intellij.core

import com.astra.intellij.model.AstraEnvironment
import com.astra.intellij.model.WorkspaceData
import com.google.gson.Gson
import com.google.gson.GsonBuilder

class WorkspaceCodec(
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create(),
) {
    fun serialize(data: WorkspaceData): String = gson.toJson(data)

    fun deserialize(content: String?): WorkspaceData {
        if (content.isNullOrBlank()) return defaultData()
        return runCatching {
            gson.fromJson(content, WorkspaceData::class.java) ?: defaultData()
        }.getOrElse { defaultData() }
    }

    fun deepCopy(data: WorkspaceData): WorkspaceData =
        gson.fromJson(gson.toJson(data), WorkspaceData::class.java)

    private fun defaultData(): WorkspaceData {
        val environment = AstraEnvironment(name = "Default Environment")
        return WorkspaceData(
            environments = mutableListOf(environment),
            activeEnvironmentId = environment.id,
        )
    }
}
