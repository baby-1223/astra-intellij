package com.astra.intellij.model

/** Rules for drag & drop moves inside the collection tree. */
object TreeOperations {
    /** A request/folder can drop onto a collection or folder; a folder never into its own subtree. */
    fun canDrop(source: Any, target: Any): Boolean = when (target) {
        is RequestCollection -> source is AstraRequest || source is RequestFolder
        is RequestFolder -> when (source) {
            is AstraRequest -> true
            is RequestFolder -> source.allFolders().none { it === target }
            else -> false
        }
        else -> false
    }

    fun detach(workspace: WorkspaceData, value: Any) {
        workspace.collections.forEach { collection ->
            collection.requests.removeAll { it === value }
            collection.folders.removeAll { it === value }
            collection.allFolders().forEach { folder ->
                folder.requests.removeAll { it === value }
                folder.folders.removeAll { it === value }
            }
        }
    }

    fun attach(target: Any, value: Any) {
        when (value) {
            is AstraRequest -> when (target) {
                is RequestCollection -> target.requests.add(value)
                is RequestFolder -> target.requests.add(value)
            }
            is RequestFolder -> when (target) {
                is RequestCollection -> target.folders.add(value)
                is RequestFolder -> target.folders.add(value)
            }
        }
    }

    fun move(workspace: WorkspaceData, source: Any, target: Any): Boolean {
        if (!canDrop(source, target)) return false
        detach(workspace, source)
        attach(target, source)
        return true
    }
}
