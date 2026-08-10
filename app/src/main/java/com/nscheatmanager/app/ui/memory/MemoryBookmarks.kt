package com.nscheatmanager.app.ui.memory

import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.data.db.MemoryBookmarkDao
import com.nscheatmanager.app.data.db.MemoryBookmarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class MemoryBookmark(
    val name: String,
    val addressExpression: String,
    val valueType: ValueType,
    val hexLength: Int? = null,
    val note: String = "",
    val modifiedAtEpochMillis: Long,
)

interface MemoryBookmarkStore {
    fun observe(titleId: TitleId, buildId: BuildId): Flow<List<MemoryBookmark>>
    suspend fun save(titleId: TitleId, buildId: BuildId, bookmark: MemoryBookmark, originalName: String? = null)
    suspend fun delete(titleId: TitleId, buildId: BuildId, name: String)

    object Empty : MemoryBookmarkStore {
        override fun observe(titleId: TitleId, buildId: BuildId) = emptyFlow<List<MemoryBookmark>>()
        override suspend fun save(titleId: TitleId, buildId: BuildId, bookmark: MemoryBookmark, originalName: String?) = Unit
        override suspend fun delete(titleId: TitleId, buildId: BuildId, name: String) = Unit
    }
}

class RoomMemoryBookmarkStore(private val dao: MemoryBookmarkDao) : MemoryBookmarkStore {
    override fun observe(titleId: TitleId, buildId: BuildId) = dao.observe(titleId.hex, buildId.hex).map { rows ->
        rows.mapNotNull { row -> runCatching {
            MemoryBookmark(row.name, row.addressExpression, ValueType.valueOf(row.valueType), row.hexLength, row.note, row.modifiedAtEpochMillis)
        }.getOrNull() }
    }

    override suspend fun save(titleId: TitleId, buildId: BuildId, bookmark: MemoryBookmark, originalName: String?) {
        require(bookmark.name.isNotBlank() && bookmark.name.length <= 80)
        require(bookmark.addressExpression.length in 1..256)
        require(bookmark.note.length <= 1000)
        if (bookmark.valueType == ValueType.Hex) require(bookmark.hexLength in 1..4096)
        val existing = dao.find(titleId.hex, buildId.hex, bookmark.name)
        require(existing == null || existing.name.equals(originalName, ignoreCase = true)) { "duplicate bookmark name" }
        require(existing != null || originalName != null || dao.count(titleId.hex, buildId.hex) < 200) { "bookmark limit" }
        if (originalName != null && !originalName.equals(bookmark.name, ignoreCase = false)) dao.delete(titleId.hex, buildId.hex, originalName)
        dao.upsert(MemoryBookmarkEntity(titleId.hex, buildId.hex, bookmark.name, bookmark.addressExpression,
            bookmark.valueType.name, bookmark.hexLength, bookmark.note, bookmark.modifiedAtEpochMillis))
    }

    override suspend fun delete(titleId: TitleId, buildId: BuildId, name: String) { dao.delete(titleId.hex, buildId.hex, name) }
}

enum class BookmarkConflict { Overwrite, Skip }
data class BookmarkImportResult(val imported: Int, val skipped: Int)

object MemoryBookmarkJson {
    fun exportNative(titleId: TitleId, buildId: BuildId, bookmarks: List<MemoryBookmark>): String =
        JSONObject().put("schema", "NSCheatManager.memory-bookmarks").put("version", 1)
            .put("titleId", titleId.hex).put("buildId", buildId.hex)
            .put("bookmarks", JSONArray().also { array -> bookmarks.forEach { array.put(nativeObject(it)) } }).toString(2)

    fun exportNoexes(bookmarks: List<MemoryBookmark>): Pair<String, Int> {
        var skipped = 0
        val array = JSONArray()
        bookmarks.forEach { bookmark ->
            val noexesType = when (bookmark.valueType) {
                ValueType.Int8, ValueType.UInt8 -> "BYTE"
                ValueType.Int16, ValueType.UInt16 -> "SHORT"
                ValueType.Int32, ValueType.UInt32 -> "INT"
                ValueType.Int64, ValueType.UInt64 -> "LONG"
                ValueType.Hex -> when (bookmark.hexLength) { 1 -> "BYTE"; 2 -> "SHORT"; 4 -> "INT"; 8 -> "LONG"; else -> null }
                else -> null
            }
            if (noexesType == null) skipped++ else array.put(JSONObject().put("update", false).put("locked", false)
                .put("addr", bookmark.addressExpression).put("desc", bookmark.name).put("type", noexesType).put("value", 0))
        }
        return array.toString(2) to skipped
    }

    fun import(text: String, currentTitleId: TitleId, currentBuildId: BuildId, now: Long): List<MemoryBookmark> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) JSONArray(trimmed) else {
            val root = JSONObject(trimmed)
            require(root.getString("schema") == "NSCheatManager.memory-bookmarks" && root.getInt("version") == 1)
            require(TitleId.parse(root.getString("titleId")) == currentTitleId && BuildId.parse(root.getString("buildId")) == currentBuildId)
            root.getJSONArray("bookmarks")
        }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            if (item.has("addr")) {
                val type = when (item.getString("type")) { "BYTE" -> ValueType.UInt8; "SHORT" -> ValueType.UInt16; "INT" -> ValueType.UInt32; "LONG" -> ValueType.UInt64; else -> error("unsupported Noexes type") }
                MemoryBookmark(item.getString("desc"), item.getString("addr"), type, null, "", now + index)
            } else MemoryBookmark(item.getString("name"), item.getString("address"), ValueType.valueOf(item.getString("valueType")),
                item.optInt("hexLength").takeIf { item.has("hexLength") && !item.isNull("hexLength") }, item.optString("note"), now + index)
        }
    }

    private fun nativeObject(bookmark: MemoryBookmark) = JSONObject().put("name", bookmark.name)
        .put("address", bookmark.addressExpression).put("valueType", bookmark.valueType.name)
        .put("hexLength", bookmark.hexLength ?: JSONObject.NULL).put("note", bookmark.note)
}
