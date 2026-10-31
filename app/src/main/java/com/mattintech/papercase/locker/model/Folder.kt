package com.mattintech.papercase.locker.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A folder in the locker. Folders nest through [parentId]; blank = top level. */
data class Folder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val parentId: String = "",
    /** Optional icon and colour ([DocumentIcons] keys); blank = the plain folder icon. */
    val iconKey: String = "",
    val iconColor: String = "",
    /** Optional photo shown instead of the icon (base64 JPEG from [FolderPhoto]); blank = none. */
    val photo: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("parentId", parentId)
        put("iconKey", iconKey)
        put("iconColor", iconColor)
        put("photo", photo)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject) = Folder(
            id = json.optString("id", UUID.randomUUID().toString()),
            name = json.optString("name", "Folder"),
            parentId = json.optString("parentId", ""),
            iconKey = json.optString("iconKey", ""),
            iconColor = json.optString("iconColor", ""),
            photo = json.optString("photo", ""),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
        )

        fun listToJson(folders: List<Folder>) = JSONArray().apply { folders.forEach { put(it.toJson()) } }

        fun listFromJson(array: JSONArray?): List<Folder> =
            if (array == null) emptyList() else (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::fromJson)
            }
    }
}

/** Helpers for working with the folder tree. Unknown or missing parents count as the top level. */
object FolderTree {

    /** The folder a document or folder really lives in: blank if [id] doesn't exist any more. */
    fun resolve(folders: List<Folder>, id: String): String =
        if (id.isNotEmpty() && folders.any { it.id == id }) id else ""

    fun children(folders: List<Folder>, parentId: String): List<Folder> =
        folders.filter { resolve(folders, it.parentId) == parentId && it.id != parentId }
            .sortedBy { it.name.lowercase() }

    /** Folders from the top level down to [id], e.g. Kids > Jane. */
    fun path(folders: List<Folder>, id: String): List<Folder> {
        val byId = folders.associateBy { it.id }
        val result = mutableListOf<Folder>()
        var current = byId[id]
        while (current != null && current !in result) {
            result.add(0, current)
            current = byId[current.parentId]
        }
        return result
    }

    /** [id] and every folder inside it, at any depth. */
    fun subtree(folders: List<Folder>, id: String): Set<String> {
        val result = mutableSetOf(id)
        var added = true
        while (added) {
            added = false
            folders.forEach { if (it.parentId in result && result.add(it.id)) added = true }
        }
        return result
    }

    /** Every folder with its depth, in tree order, for pickers. */
    fun flatten(folders: List<Folder>): List<Pair<Folder, Int>> {
        val result = mutableListOf<Pair<Folder, Int>>()
        fun walk(parent: String, depth: Int) {
            children(folders, parent).forEach { f ->
                if (result.none { it.first.id == f.id }) {
                    result.add(f to depth)
                    walk(f.id, depth + 1)
                }
            }
        }
        walk("", 0)
        return result
    }
}
