package com.astra.intellij.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TreeOperationsTest {
    private fun workspace(): Pair<WorkspaceData, RequestCollection> {
        val collection = RequestCollection(name = "c1")
        val workspace = WorkspaceData(collections = mutableListOf(collection))
        return workspace to collection
    }

    @Test
    fun `moves request into a folder of another collection`() {
        val (workspace, source) = workspace()
        val request = AstraRequest(name = "r")
        source.requests.add(request)
        val targetCollection = RequestCollection(name = "c2")
        val folder = RequestFolder(name = "f")
        targetCollection.folders.add(folder)
        workspace.collections.add(targetCollection)

        assertTrue(TreeOperations.move(workspace, request, folder))
        assertTrue(source.requests.isEmpty())
        assertEquals(listOf(request), folder.requests)
    }

    @Test
    fun `moves folder with children into another collection`() {
        val (workspace, source) = workspace()
        val folder = RequestFolder(name = "f")
        val child = RequestFolder(name = "child")
        folder.folders.add(child)
        source.folders.add(folder)
        val target = RequestCollection(name = "c2")
        workspace.collections.add(target)

        assertTrue(TreeOperations.move(workspace, folder, target))
        assertTrue(source.folders.isEmpty())
        assertEquals(listOf(folder), target.folders)
        assertEquals(listOf(child), folder.folders)
    }

    @Test
    fun `rejects dropping a folder into its own subtree`() {
        val (workspace, collection) = workspace()
        val parent = RequestFolder(name = "parent")
        val child = RequestFolder(name = "child")
        parent.folders.add(child)
        collection.folders.add(parent)

        assertFalse(TreeOperations.canDrop(parent, parent))
        assertFalse(TreeOperations.canDrop(parent, child))
        assertFalse(TreeOperations.move(workspace, parent, child))
        assertEquals(listOf(parent), collection.folders)
        assertEquals(listOf(child), parent.folders)
    }

    @Test
    fun `rejects dropping onto a request or a collection node itself`() {
        val request = AstraRequest(name = "r")
        val other = AstraRequest(name = "other")
        assertFalse(TreeOperations.canDrop(request, other))
        assertFalse(TreeOperations.canDrop(RequestCollection(), other))
        assertTrue(TreeOperations.canDrop(request, RequestFolder()))
    }

    @Test
    fun `moving nested folder detaches it from deep parents`() {
        val (workspace, collection) = workspace()
        val outer = RequestFolder(name = "outer")
        val inner = RequestFolder(name = "inner")
        val request = AstraRequest(name = "r")
        inner.requests.add(request)
        outer.folders.add(inner)
        collection.folders.add(outer)
        val target = RequestFolder(name = "target")
        collection.folders.add(target)

        assertTrue(TreeOperations.move(workspace, request, target))
        assertTrue(inner.requests.isEmpty())
        assertEquals(listOf(request), target.requests)
    }

    @Test
    fun `removes a deeply nested request`() {
        val (workspace, collection) = workspace()
        val outer = RequestFolder(name = "outer")
        val inner = RequestFolder(name = "inner")
        val request = AstraRequest(name = "r")
        val sibling = AstraRequest(name = "sibling")
        inner.requests.add(request)
        inner.requests.add(sibling)
        outer.folders.add(inner)
        collection.folders.add(outer)

        assertTrue(TreeOperations.remove(workspace, request))
        assertEquals(listOf(sibling), inner.requests)
        assertEquals(1, collection.folders.size)
    }

    @Test
    fun `removes a nested folder with its children`() {
        val (workspace, collection) = workspace()
        val outer = RequestFolder(name = "outer")
        val inner = RequestFolder(name = "inner")
        inner.requests.add(AstraRequest(name = "r"))
        outer.folders.add(inner)
        collection.folders.add(outer)

        assertTrue(TreeOperations.remove(workspace, inner))
        assertTrue(outer.folders.isEmpty())
        assertFalse(TreeOperations.remove(workspace, inner))
    }

    @Test
    fun `removes a whole collection`() {
        val (workspace, collection) = workspace()
        val other = RequestCollection(name = "other")
        workspace.collections.add(other)

        assertTrue(TreeOperations.remove(workspace, collection))
        assertEquals(listOf(other), workspace.collections)
    }

    @Test
    fun `legacy items without ids never delete each other`() {
        val gson = com.google.gson.Gson()
        val workspace = WorkspaceData()
        val first = gson.fromJson("""{"name":"a"}""", AstraRequest::class.java)
        val second = gson.fromJson("""{"name":"b"}""", AstraRequest::class.java)
        val collection = RequestCollection(name = "c")
        collection.requests.add(first)
        collection.requests.add(second)
        workspace.collections.add(collection)

        assertTrue(TreeOperations.remove(workspace, first))
        assertEquals(listOf(second), collection.requests)
    }
}
