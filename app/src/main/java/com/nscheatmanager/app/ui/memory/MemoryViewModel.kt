package com.nscheatmanager.app.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nscheatmanager.app.core.binary.LittleEndianCodec
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.core.model.checkedAdd
import com.nscheatmanager.app.domain.GameOperationKey
import com.nscheatmanager.app.domain.LockedValue
import com.nscheatmanager.app.domain.MemoryReadResult
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import java.util.Collections
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

enum class AddressMode { Absolute, Main, Heap }
enum class MemoryError { SessionRequired, InvalidAddress, InvalidLength, InvalidValue, OperationFailed, SessionChanged }

data class MemorySessionSnapshot(
    val operationKey: GameOperationKey?,
    val identity: GameIdentity?,
    val activeLocks: Map<ULong, LockedValue>,
    val pendingCleanup: Set<ULong>,
)

interface MemorySessionGateway {
    val changes: Flow<Unit> get() = emptyFlow()
    fun currentSnapshot(): MemorySessionSnapshot
    suspend fun read(expected: GameOperationKey, target: MemoryTarget, type: ValueType, count: Int?): MemoryReadResult
    suspend fun write(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray)
    suspend fun lock(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray): LockedValue
    suspend fun unlock(expected: GameOperationKey, address: ULong)
}

data class MemoryResultUi(val address: ULong, val raw: String, val value: String, val type: ValueType, val atMillis: Long)
data class MemoryTargetDisplay(val mode: AddressMode, val inputHex: String, val resolvedAbsolute: ULong)
data class WriteConfirmation(
    val id: Long, val key: GameOperationKey, val target: MemoryTarget, val display: MemoryTargetDisplay,
    val type: ValueType, val inputValue: String, val bytes: com.nscheatmanager.app.domain.ImmutableBytes,
)
data class MemoryUiState(
    val mode: AddressMode = AddressMode.Absolute,
    val address: String = "",
    val type: ValueType = ValueType.Int32,
    val value: String = "",
    val length: String = "4",
    val busy: Boolean = false,
    val result: MemoryResultUi? = null,
    val confirmation: WriteConfirmation? = null,
    val locked: LockedValue? = null,
    val pendingCleanup: Set<ULong> = emptySet(),
    val error: MemoryError? = null,
    val ready: Boolean = false,
    val bookmarks: List<MemoryBookmark> = emptyList(),
    val pendingBookmarkImport: List<MemoryBookmark>? = null,
) { val parametersLocked get() = locked != null }

class MemoryViewModel(
    private val gateway: MemorySessionGateway,
    private val bookmarkStore: MemoryBookmarkStore = MemoryBookmarkStore.Empty,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = scope ?: viewModelScope
    private val mutableState = MutableStateFlow(MemoryUiState(ready = gateway.currentSnapshot().operationKey != null))
    val uiState = mutableState.asStateFlow()
    private var nextId = 1L
    private var trustedKey = gateway.currentSnapshot().operationKey
    private var nextClaimId = 1L
    private var activeClaim: OperationClaim? = null
    private var activeJob: kotlinx.coroutines.Job? = null
    private var bookmarkJob: kotlinx.coroutines.Job? = null
    private val claimed = Collections.synchronizedSet(mutableSetOf<Long>())
    init { refreshSession(); workScope.launch { gateway.changes.collect { refreshSession() } } }

    fun selectMode(v: AddressMode) = change { copy(mode = v) }
    fun selectType(v: ValueType) = change { copy(type = v, length = v.byteSize?.toString() ?: length) }
    fun updateAddress(v: String) = change { copy(address = v) }
    fun updateValue(v: String) = change { copy(value = v) }
    fun updateLength(v: String) = change { copy(length = v) }

    fun refreshSession() {
        val snapshot = gateway.currentSnapshot()
        val lock = mutableState.value.locked
        val changed = trustedKey != snapshot.operationKey
        val hadTrust = trustedKey != null || lock != null || mutableState.value.busy || mutableState.value.result != null || mutableState.value.confirmation != null
        trustedKey = snapshot.operationKey
        if (changed) {
            activeClaim = null
            activeJob?.cancel()
            activeJob = null
            bookmarkJob?.cancel()
            bookmarkJob = snapshot.operationKey?.let { key -> workScope.launch {
                bookmarkStore.observe(key.titleId, key.buildId).collect { rows -> mutableState.update { it.copy(bookmarks = rows) } }
            } }
        }
        mutableState.update { state ->
            state.copy(
                locked = lock?.takeIf { snapshot.activeLocks[it.absoluteAddress] == it }
                    ?: snapshot.activeLocks.values.firstOrNull(),
                pendingCleanup = snapshot.pendingCleanup.toSet(),
                result = if (changed) null else state.result,
                confirmation = if (changed) null else state.confirmation,
                error = if (changed && hadTrust) MemoryError.SessionChanged else state.error,
                busy = if (changed) false else state.busy,
                ready = snapshot.operationKey != null,
                bookmarks = if (snapshot.operationKey == null) emptyList() else state.bookmarks,
            )
        }
    }

    fun applyBookmark(bookmark: MemoryBookmark) = change {
        copy(address = bookmark.addressExpression, type = bookmark.valueType,
            length = bookmark.hexLength?.toString() ?: bookmark.valueType.byteSize?.toString() ?: length)
    }

    fun saveBookmark(name: String, note: String, originalName: String? = null) {
        val key = gateway.currentSnapshot().operationKey ?: run { fail(MemoryError.SessionRequired); return }
        val state = mutableState.value
        val expression = runCatching { AddressExpressionParser(state.address).parse() }.getOrElse { fail(MemoryError.InvalidAddress); return }
        if (expression.toString().isEmpty()) return
        val hexLength = if (state.type == ValueType.Hex) state.length.toIntOrNull() else null
        workScope.launch {
            runCatching { bookmarkStore.save(key.titleId, key.buildId,
                MemoryBookmark(name.trim(), state.address.trim(), state.type, hexLength, note.trim(), clockMillis()), originalName) }
                .onFailure { fail(MemoryError.OperationFailed) }
        }
    }

    fun deleteBookmark(name: String) {
        val key = gateway.currentSnapshot().operationKey ?: return
        workScope.launch { runCatching { bookmarkStore.delete(key.titleId, key.buildId, name) }.onFailure { fail(MemoryError.OperationFailed) } }
    }

    fun exportBookmarks(): String? {
        gateway.currentSnapshot().operationKey ?: run { fail(MemoryError.SessionRequired); return null }
        return MemoryBookmarkJson.exportNoexes(mutableState.value.bookmarks).first
    }

    fun beginBookmarkImport(json: String) {
        val key = gateway.currentSnapshot().operationKey ?: run { fail(MemoryError.SessionRequired); return }
        val parsed = runCatching { MemoryBookmarkJson.import(json, key.titleId, key.buildId, clockMillis()) }
            .getOrElse { fail(MemoryError.OperationFailed); return }
        mutableState.update { it.copy(pendingBookmarkImport = parsed) }
    }

    fun confirmBookmarkImport(overwrite: Boolean) {
        val key = gateway.currentSnapshot().operationKey ?: return
        val incoming = mutableState.value.pendingBookmarkImport ?: return
        mutableState.update { it.copy(pendingBookmarkImport = null) }
        workScope.launch {
            incoming.forEach { bookmark ->
                val existing = mutableState.value.bookmarks.firstOrNull { it.name.equals(bookmark.name, true) }
                if (existing == null || overwrite) runCatching {
                    bookmarkStore.save(key.titleId, key.buildId, bookmark, existing?.name)
                }.onFailure { fail(MemoryError.OperationFailed) }
            }
        }
    }

    fun dismissBookmarkImport() = mutableState.update { it.copy(pendingBookmarkImport = null) }

    fun read() {
        val request = parseRequest(read = true) ?: return
        val claim = beginClaim(request.key)
        mutableState.update { it.copy(busy = true, error = null) }
        activeJob = workScope.launch {
            val address = runCatching { resolveExpression(request).also {
                val width = request.count ?: request.type.byteSize ?: 1
                checkedAdd(it, (width - 1).toULong())
            } }.getOrElse {
                if (it is CancellationException) throw it
                publish(claim) { state -> state.copy(busy = false, error = MemoryError.InvalidAddress) }
                return@launch
            }
            runCatching {
                gateway.read(request.key, MemoryTarget.Absolute(address), request.type, request.count)
            }
                .onSuccess { result ->
                    publish(claim) { it.copy(busy = false, result = MemoryResultUi(result.absoluteAddress, result.bytes.copyToByteArray().toHex(), result.value, result.type, clockMillis())) }
                }.onFailure { if (it is CancellationException) throw it; publish(claim) { s -> s.copy(busy = false, error = MemoryError.OperationFailed) } }
        }
    }

    fun requestWrite() {
        val request = parseRequest(read = false) ?: return
        val bytes = runCatching { LittleEndianCodec.encode(request.type, mutableState.value.value) }
            .getOrElse { fail(MemoryError.InvalidValue); return }
        if (bytes.isEmpty() || bytes.size > 4096) { fail(MemoryError.InvalidValue); return }
        val claim = beginClaim(request.key)
        mutableState.update { it.copy(busy = true, error = null) }
        activeJob = workScope.launch {
            runCatching { resolveExpression(request).also { checkedAdd(it, (bytes.size - 1).toULong()) } }
                .onSuccess { resolved -> publish(claim) { state ->
                    val target = MemoryTarget.Absolute(resolved)
                    val display = MemoryTargetDisplay(AddressMode.Absolute, normalizedInput(), resolved)
                    state.copy(
                        busy = false,
                        confirmation = WriteConfirmation(nextId++, request.key, target, display, request.type, state.value, com.nscheatmanager.app.domain.ImmutableBytes.copyOf(bytes)),
                    )
                } }
                .onFailure { if (it is CancellationException) throw it; publish(claim) { state -> state.copy(busy = false, error = MemoryError.InvalidAddress) } }
        }
    }

    fun dismissWrite(id: Long) = mutableState.update { if (it.confirmation?.id == id) it.copy(confirmation = null) else it }

    fun confirmWrite(id: Long) {
        val confirmation = mutableState.value.confirmation?.takeIf { it.id == id } ?: return
        if (!claimed.add(id)) return
        val claim = beginClaim(confirmation.key)
        mutableState.update { it.copy(confirmation = null, busy = true) }
        activeJob = workScope.launch {
            if (gateway.currentSnapshot().operationKey != confirmation.key) { fail(MemoryError.SessionChanged); return@launch }
            runCatching { gateway.write(confirmation.key, confirmation.target, confirmation.type, confirmation.bytes.copyToByteArray()) }
                .onSuccess { publish(claim) { it.copy(busy = false) } }
                .onFailure { if (it is CancellationException) throw it; publish(claim) { s -> s.copy(busy = false, error = MemoryError.OperationFailed) } }
        }
    }

    fun toggleLock(enabled: Boolean) {
        if (enabled) lock() else unlock()
    }

    private fun lock() {
        if (mutableState.value.locked != null) return
        val request = parseRequest(read = false) ?: return
        val bytes = runCatching { LittleEndianCodec.encode(request.type, mutableState.value.value) }
            .getOrElse { fail(MemoryError.InvalidValue); return }
        if (bytes.isEmpty() || bytes.size > 4096) { fail(MemoryError.InvalidValue); return }
        val claim = beginClaim(request.key)
        mutableState.update { it.copy(busy = true) }
        activeJob = workScope.launch {
            if (gateway.currentSnapshot().operationKey != request.key) { fail(MemoryError.SessionChanged); return@launch }
            runCatching {
                val address = resolveExpression(request)
                checkedAdd(address, (bytes.size - 1).toULong())
                gateway.lock(request.key, MemoryTarget.Absolute(address), request.type, bytes.copyOf())
            }
                .onSuccess { locked -> refreshSession(); publish(claim) { it.copy(busy = false, locked = gateway.currentSnapshot().activeLocks[locked.absoluteAddress]) } }
                .onFailure { if (it is CancellationException) throw it; refreshSession(); publish(claim) { s -> s.copy(busy = false, error = MemoryError.OperationFailed) } }
        }
    }

    private fun unlock() {
        val lock = mutableState.value.locked ?: return
        val key = gateway.currentSnapshot().operationKey ?: run { fail(MemoryError.SessionRequired); return }
        val claim = beginClaim(key)
        mutableState.update { it.copy(busy = true) }
        activeJob = workScope.launch {
            runCatching { gateway.unlock(key, lock.absoluteAddress) }
                .onSuccess { refreshSession(); publish(claim) { it.copy(busy = false, locked = gateway.currentSnapshot().activeLocks[lock.absoluteAddress]) } }
                .onFailure { if (it is CancellationException) throw it; refreshSession(); publish(claim) { s -> s.copy(busy = false, error = MemoryError.OperationFailed) } }
        }
    }

    private fun parseRequest(read: Boolean): Request? {
        val state = mutableState.value
        val snapshot = gateway.currentSnapshot()
        val key = snapshot.operationKey ?: run { fail(MemoryError.SessionRequired); return null }
        val identity = snapshot.identity ?: run { fail(MemoryError.SessionRequired); return null }
        val expressionText = runCatching { legacyCompatibleExpression(state) }
            .getOrElse { fail(MemoryError.InvalidAddress); return null }
        val expression = runCatching { AddressExpressionParser(expressionText).parse() }
            .getOrElse { fail(MemoryError.InvalidAddress); return null }
        val count = if (state.type == ValueType.Hex && read) state.length.toIntOrNull() else null
        if (state.type == ValueType.Hex && read && (count == null || count !in 1..4096)) { fail(MemoryError.InvalidLength); return null }
        return Request(key, expressionText, expression, identity, state.type, count)
    }

    private fun change(block: MemoryUiState.() -> MemoryUiState) {
        if (!mutableState.value.parametersLocked && !mutableState.value.busy) mutableState.update { it.block().copy(error = null) }
    }
    private fun fail(error: MemoryError) = mutableState.update { it.copy(busy = false, error = error) }
    private fun beginClaim(key: GameOperationKey) = OperationClaim(nextClaimId++, key).also { activeJob?.cancel(); activeJob = null; activeClaim = it }
    private fun publish(claim: OperationClaim, update: (MemoryUiState) -> MemoryUiState) {
        if (activeClaim == claim && gateway.currentSnapshot().operationKey == claim.key) {
            activeClaim = null; activeJob = null; mutableState.update(update)
        }
    }
    private fun normalizedInput() = mutableState.value.address.trim().uppercase()
    private fun legacyCompatibleExpression(state: MemoryUiState): String {
        val raw = state.address.trim()
        require(raw.isNotEmpty())
        val explicitlyQualified = raw.contains('[', ignoreCase = true) ||
            raw.contains("main", ignoreCase = true) || raw.contains("heap", ignoreCase = true)
        return if (explicitlyQualified || state.mode == AddressMode.Absolute) raw else when (state.mode) {
            AddressMode.Main -> "main+$raw"
            AddressMode.Heap -> "heap+$raw"
            AddressMode.Absolute -> raw
        }
    }
    private suspend fun resolveExpression(request: Request): ULong = request.expression.resolve(request.identity) { address, size ->
        val result = gateway.read(request.key, MemoryTarget.Absolute(address), ValueType.UInt64, null)
        val bytes = result.bytes.copyToByteArray()
        require(bytes.size == size)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).long.toULong()
    }.also { require(it != 0uL) }
    private fun resolve(target: MemoryTarget, identity: GameIdentity, width: Int): ULong = when (target) {
        is MemoryTarget.Absolute -> target.address
        is MemoryTarget.MainRelative -> checkedAdd(identity.mainBase, target.offset)
        is MemoryTarget.HeapRelative -> checkedAdd(identity.heapBase, target.offset)
    }.also { require(it != 0uL); checkedAdd(it, (width - 1).toULong()) }
    private data class OperationClaim(val id: Long, val key: GameOperationKey)
    private data class Request(
        val key: GameOperationKey,
        val expressionText: String,
        val expression: AddressExpression,
        val identity: GameIdentity,
        val type: ValueType,
        val count: Int?,
    )

    class Factory(private val gateway: MemorySessionGateway, private val bookmarks: MemoryBookmarkStore = MemoryBookmarkStore.Empty) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MemoryViewModel(gateway, bookmarks) as T
    }
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
