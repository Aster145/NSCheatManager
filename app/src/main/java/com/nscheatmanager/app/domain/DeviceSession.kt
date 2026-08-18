package com.nscheatmanager.app.domain

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.vm.ExecutionReport
import com.nscheatmanager.app.cheats.vm.ExecutionStatus
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.protocol.ProtocolError
import com.nscheatmanager.app.protocol.noexs.Noexs
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.sysbot.SysBotbase
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class ConnectionState {
    Disconnected,
    Connecting,
    Recognizing,
    Ready,
    Error,
}

data class DeviceSessionState(
    val device: DeviceProfile? = null,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val game: GameIdentity? = null,
    val gameValidated: Boolean = false,
    val cheatFile: CheatFile? = null,
    val cheatRelativePath: String? = null,
    val checkedGroups: Set<String> = emptySet(),
    val executingGroups: Set<String> = emptySet(),
    val activeLocks: Map<ULong, LockedValue> = emptyMap(),
    val pendingLockCleanup: Set<ULong> = emptySet(),
    val error: Throwable? = null,
    val generation: Long = 0L,
    val lastExecutedAtEpochMillis: Map<String, Long> = emptyMap(),
) {
    val operationKey: GameOperationKey?
        get() = if (connection == ConnectionState.Ready && gameValidated) {
            val selected = device ?: return null
            val identity = game ?: return null
            GameOperationKey(selected.id, identity.titleId, identity.buildId, generation)
        } else {
            null
        }
}

data class GameOperationKey(
    val deviceId: String,
    val titleId: com.nscheatmanager.app.core.model.TitleId,
    val buildId: com.nscheatmanager.app.core.model.BuildId,
    val generation: Long,
)

class StaleGameOperationException : IllegalStateException("The validated game session changed")

class SessionNotReadyException(message: String = "A validated device session is required") :
    IllegalStateException(message)

class CheatGroupBusyException(val groupId: String) :
    IllegalStateException("Cheat group '$groupId' is already executing")

class SessionCloseTimeoutException(
    val timeoutMillis: Long,
    cause: Throwable,
) : IOException("Session cleanup did not finish within $timeoutMillis ms", cause)

fun interface SysBotbaseFactory {
    fun create(profile: DeviceProfile): SysBotbase
}

fun interface NoexsFactory {
    fun create(profile: DeviceProfile): Noexs
}

/**
 * Owns one live sys-botbase session. The supplied [scope] remains owned by the ViewModel/caller;
 * this class never creates a detached scope. Connection transitions are last-request-wins while
 * every protocol and persistence operation is serialized by [operationMutex].
 */
class DeviceSession(
    private val scope: CoroutineScope,
    private val cleanupDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val closeTimeoutMillis: Long = DEFAULT_CLOSE_TIMEOUT_MILLIS,
    private val freezeTimeoutMillis: Long = DEFAULT_FREEZE_TIMEOUT_MILLIS,
    private val sysBotbaseFactory: SysBotbaseFactory,
    private val noexsFactory: NoexsFactory,
    private val recognizeCurrentGame: RecognizeCurrentGame,
    private val executeCheatGroup: ExecuteCheatGroup,
    private val memoryUseCases: MemoryUseCases,
) {
    init {
        require(closeTimeoutMillis > 0) { "Close timeout must be positive" }
        require(freezeTimeoutMillis > 0) { "Freeze timeout must be positive" }
    }

    private val operationMutex = Mutex()
    private val controlLock = Any()
    private val mutableState = MutableStateFlow(DeviceSessionState())

    val state: StateFlow<DeviceSessionState> = mutableState.asStateFlow()

    private var generation = 0L
    private var transitionJob: Job? = null
    private var activeOperationJob: Job? = null
    private var closed = false
    private var liveConnection: LiveConnection? = null
    private val activeLocksByDevice = mutableMapOf<String, LinkedHashMap<ULong, ActiveLock>>()
    private val pendingByDevice =
        mutableMapOf<String, LinkedHashMap<GameKey, LinkedHashSet<ULong>>>()
    private val executionClaims = linkedSetOf<ExecutionClaim>()

    fun connectAndRecognize(device: DeviceProfile): Job = reconnect(device)

    fun switchDevice(device: DeviceProfile): Job = reconnect(device)

    fun recognizeAgain(): Job {
        val device = synchronized(controlLock) {
            mutableState.value.device ?: throw SessionNotReadyException("Select a device first")
        }
        return startTransition(
            initialState = { current ->
                current.copy(
                    connection = ConnectionState.Recognizing,
                    gameValidated = false,
                    error = null,
                )
            },
        ) { token -> refreshTransition(device, token) }
    }

    fun disconnect(): Job = startTransition(
        initialState = { current ->
            current.copy(
                connection = ConnectionState.Disconnected,
                gameValidated = false,
                checkedGroups = emptySet(),
                executingGroups = emptySet(),
                lastExecutedAtEpochMillis = emptyMap(),
                error = null,
            )
        },
    ) { token ->
        val cleanupError = withContext(NonCancellable) { closeLiveNormally() }
        updateIfCurrent(token) { current ->
            val selectedId = current.device?.id
            current.copy(
                connection = ConnectionState.Disconnected,
                gameValidated = false,
                activeLocks = lockSnapshot(selectedId),
                pendingLockCleanup = pendingSnapshot(selectedId),
                error = cleanupError,
            )
        }
    }

    /**
     * Terminal lifecycle cleanup which does not depend on [scope] still being active.
     * The caller awaits the bounded cleanup directly; no detached cleanup job is created.
     */
    suspend fun closeAndJoin() {
        val token = synchronized(controlLock) {
            if (!closed) {
                closed = true
                generation += 1
                transitionJob?.cancel(SupersededSessionOperation())
                activeOperationJob?.cancel(SupersededSessionOperation())
                mutableState.value = mutableState.value.copy(
                    connection = ConnectionState.Disconnected,
                    gameValidated = false,
                    checkedGroups = emptySet(),
                    executingGroups = emptySet(),
                    lastExecutedAtEpochMillis = emptyMap(),
                    error = null,
                )
            }
            generation
        }
        try {
            withContext(cleanupDispatcher + NonCancellable) {
                withTimeout(closeTimeoutMillis) {
                    operationMutex.withLock {
                        val cleanupError = closeLiveNormally()
                        updateIfCurrentIgnoringCancellation(token) { current ->
                            current.copy(
                                connection = ConnectionState.Disconnected,
                                gameValidated = false,
                                activeLocks = lockSnapshot(current.device?.id),
                                pendingLockCleanup = pendingSnapshot(current.device?.id),
                                error = cleanupError,
                            )
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            val failure = SessionCloseTimeoutException(closeTimeoutMillis, timeout)
            updateIfCurrentIgnoringCancellation(token) { current ->
                current.copy(
                    connection = ConnectionState.Error,
                    gameValidated = false,
                    activeLocks = lockSnapshot(current.device?.id),
                    pendingLockCleanup = pendingSnapshot(current.device?.id),
                    error = failure,
                )
            }
            throw failure
        }
    }

    fun currentOperationKey(): GameOperationKey? = synchronized(controlLock) {
        mutableState.value.operationKey
    }

    fun requireCurrentOperationKey(expected: GameOperationKey) {
        synchronized(controlLock) {
            if (mutableState.value.operationKey != expected || generation != expected.generation) {
                throw StaleGameOperationException()
            }
        }
    }

    suspend fun executeGroup(expected: GameOperationKey, group: CheatGroup): ExecutionReport {
        val claim = claimExecution(expected, group.id)
        try {
            return readyOperation(expected) { token, live, identity ->
                val report = executeCheatGroup.execute(
                    device = live.device,
                    identity = identity,
                    group = group,
                    client = live.client,
                    checkpoint = { checkpoint(token) },
                )
                report.error?.let { error ->
                    if (isConnectionLoss(error)) markAbnormalLoss(token, error)
                }
                if (report.status == ExecutionStatus.Complete) {
                    checkpoint(token)
                    updateIfCurrent(token) { current ->
                        current.copy(
                            checkedGroups = immutableSet(current.checkedGroups + group.id),
                            lastExecutedAtEpochMillis = immutableMap(
                                current.lastExecutedAtEpochMillis + (group.id to System.currentTimeMillis()),
                            ),
                        )
                    }
                }
                report
            }
        } finally {
            releaseExecution(claim)
        }
    }

    suspend fun executeGroup(group: CheatGroup): ExecutionReport =
        executeGroup(currentOperationKey() ?: throw SessionNotReadyException(), group)

    /** Clearing a checkbox is persistence/UI-only and intentionally sends no memory command. */
    suspend fun uncheckGroup(expected: GameOperationKey, groupId: String) = readyOperation(expected) { token, _, _ ->
        checkpoint(token)
        updateIfCurrent(token) { current ->
            current.copy(
                checkedGroups = immutableSet(current.checkedGroups - groupId),
                lastExecutedAtEpochMillis = immutableMap(current.lastExecutedAtEpochMillis - groupId),
            )
        }
    }

    suspend fun uncheckGroup(groupId: String) =
        uncheckGroup(currentOperationKey() ?: throw SessionNotReadyException(), groupId)

    suspend fun readValue(
        target: MemoryTarget,
        type: ValueType,
        hexByteCount: Int? = null,
    ): MemoryReadResult = readyOperation { token, live, identity ->
        try {
            memoryUseCases.readValue(live.client, identity, target, type, hexByteCount).also {
                checkpoint(token)
            }
        } catch (error: Throwable) {
            if (isConnectionLoss(error)) markAbnormalLoss(token, error)
            throw error
        }
    }

    /** Screen capture/control need a live sys-botbase socket but no foreground-game identity. */
    suspend fun captureScreenshot(): ByteArray = connectedOperation { live -> live.client.captureScreenshot() }

    suspend fun setScreenEnabled(enabled: Boolean): Unit = connectedOperation { live ->
        live.client.setScreenEnabled(enabled)
    }

    suspend fun readValue(expected: GameOperationKey, target: MemoryTarget, type: ValueType, hexByteCount: Int? = null): MemoryReadResult =
        readyOperation(expected) { token, live, identity ->
            try { memoryUseCases.readValue(live.client, identity, target, type, hexByteCount).also { checkpoint(token) } }
            catch (error: Throwable) { if (isConnectionLoss(error)) markAbnormalLoss(token, error); throw error }
        }

    suspend fun writePrepared(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray) {
        val ownedBytes = bytes.copyOf()
        readyOperation(expected) { token, live, identity ->
            try {
                val prepared = memoryUseCases.prepareWrite(identity, target, type, ownedBytes)
                checkpoint(token)
                live.client.write(MemoryTarget.Absolute(prepared.absoluteAddress), prepared.bytes.copyToByteArray())
                checkpoint(token)
            } catch (error: Throwable) { if (isConnectionLoss(error)) markAbnormalLoss(token, error); throw error }
        }
    }

    suspend fun lockPrepared(expected: GameOperationKey, target: MemoryTarget, type: ValueType, bytes: ByteArray): LockedValue {
        val ownedBytes = bytes.copyOf()
        return readyOperation(expected) { token, live, identity ->
            val prepared = memoryUseCases.prepareLock(identity, target, type, ownedBytes)
            commitPreparedLock(token, live, identity, prepared)
        }
    }

    suspend fun unlockValue(expected: GameOperationKey, absoluteAddress: ULong) {
        readyOperation(expected) { token, live, _ -> unlockPrepared(token, live, absoluteAddress) }
    }

    suspend fun writeValue(
        target: MemoryTarget,
        type: ValueType,
        value: String,
    ): MemoryWriteResult = readyOperation { token, live, identity ->
        try {
            memoryUseCases.writeValue(live.client, identity, target, type, value).also {
                checkpoint(token)
            }
        } catch (error: Throwable) {
            if (isConnectionLoss(error)) markAbnormalLoss(token, error)
            throw error
        }
    }

    suspend fun lockValue(
        target: MemoryTarget,
        type: ValueType,
        value: String,
    ): LockedValue = readyOperation { token, live, identity ->
        val prepared = memoryUseCases.prepareLock(identity, target, type, value)
        commitPreparedLock(token, live, identity, prepared)
    }

    private suspend fun commitPreparedLock(token: Long, live: LiveConnection, identity: GameIdentity, prepared: LockedValue): LockedValue {
        synchronized(controlLock) {
            require(prepared.absoluteAddress !in activeLocksByDevice[live.device.id].orEmpty()) {
                "This absolute address is already locked"
            }
        }
        val callerJob = requireNotNull(currentCoroutineContext()[Job])
        checkpoint(token)
        var freezeCommitted = false
        val lock = try {
            withContext(NonCancellable) {
                if (!callerJob.isActive || !isCurrentGeneration(token)) {
                    throw FreezeCancelledBeforeCommit()
                }
                freezeCommitted = true
                try {
                    withTimeout(freezeTimeoutMillis) {
                        memoryUseCases.freezePrepared(live.client, prepared)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    throw ProtocolError.Timeout("freeze", timeout)
                }
            }
            prepared
        } catch (error: Throwable) {
            if (error is FreezeCancelledBeforeCommit) {
                throw error
            } else if (error is CancellationException && freezeCommitted) {
                // Cancellation after the command write may still have created the remote lock.
                cleanupUntrackedLock(live, identity, prepared)
            } else if (isConnectionLoss(error)) {
                // A disconnect after sending freeze is ambiguous; conservatively reconcile it.
                synchronized(controlLock) {
                    addPendingLocked(live.device.id, gameKey(identity), prepared.absoluteAddress)
                }
                markAbnormalLoss(token, error)
            }
            throw error
        }
        if (!callerJob.isActive || !isCurrentGeneration(token)) {
            cleanupUntrackedLock(live, identity, lock)
            callerJob.ensureActive()
            checkpoint(token)
        }
        try {
            synchronized(controlLock) {
                checkGenerationLocked(token)
                val locks = activeLocksByDevice.getOrPut(live.device.id) { linkedMapOf() }
                require(lock.absoluteAddress !in locks) { "This absolute address is already locked" }
                locks[lock.absoluteAddress] = ActiveLock(gameKey(identity), lock)
                mutableState.value = mutableState.value.copy(
                    activeLocks = lockSnapshotLocked(live.device.id),
                )
            }
        } catch (cancelled: CancellationException) {
            cleanupUntrackedLock(live, identity, lock)
            throw cancelled
        } catch (error: Throwable) {
            cleanupUntrackedLock(live, identity, lock)
            throw error
        }
        return lock
    }

    suspend fun unlockValue(absoluteAddress: ULong) = readyOperation { token, live, _ -> unlockPrepared(token, live, absoluteAddress) }

    private suspend fun unlockPrepared(token: Long, live: LiveConnection, absoluteAddress: ULong) {
        val lock = synchronized(controlLock) {
            activeLocksByDevice[live.device.id]?.get(absoluteAddress)?.lock
                ?: throw IllegalArgumentException("No app-created lock exists at this address")
        }
        try {
            memoryUseCases.unlockValue(live.client, lock)
            synchronized(controlLock) {
                activeLocksByDevice[live.device.id]?.remove(absoluteAddress)
                if (generation == token && mutableState.value.device?.id == live.device.id) {
                    mutableState.value = mutableState.value.copy(
                        activeLocks = lockSnapshotLocked(live.device.id),
                    )
                }
            }
        } catch (error: Throwable) {
            if (isConnectionLoss(error)) markAbnormalLoss(token, error)
            throw error
        }
    }

    /** Noexs is independent: a detach failure never changes sys-botbase readiness. */
    suspend fun detachDmnt() {
        val (token, device) = synchronized(controlLock) {
            generation to (mutableState.value.device
                ?: throw SessionNotReadyException("Select a device first"))
        }
        operationMutex.withLock {
            registerActiveOperation()
            try {
                checkpoint(token)
                noexsFactory.create(device).detachDmnt()
                checkpoint(token)
            } finally {
                unregisterActiveOperation()
            }
        }
    }

    private fun reconnect(device: DeviceProfile): Job = startTransition(
        initialState = { current ->
            current.copy(
                device = device,
                connection = ConnectionState.Connecting,
                // Checkbox state is deliberately session-only: reconnect always starts fresh.
                gameValidated = false,
                activeLocks = if (current.device?.id == device.id) current.activeLocks else emptyMap(),
                pendingLockCleanup = pendingSnapshot(device.id),
                checkedGroups = emptySet(),
                executingGroups = emptySet(),
                lastExecutedAtEpochMillis = emptyMap(),
                error = null,
            )
        },
    ) { token -> connectTransition(device, token) }

    private fun startTransition(
        initialState: (DeviceSessionState) -> DeviceSessionState,
        action: suspend (Long) -> Unit,
    ): Job {
        val token: Long
        val job: Job
        synchronized(controlLock) {
            check(!closed) { "Device session is closed" }
            generation += 1
            token = generation
            transitionJob?.cancel(SupersededSessionOperation())
            activeOperationJob?.cancel(SupersededSessionOperation())
                mutableState.value = initialState(mutableState.value).copy(generation = token)
            job = scope.launch(start = CoroutineStart.LAZY) {
                operationMutex.withLock {
                    registerActiveOperation()
                    try {
                        checkpoint(token)
                        action(token)
                    } finally {
                        unregisterActiveOperation()
                    }
                }
            }
            transitionJob = job
        }
        job.invokeOnCompletion {
            synchronized(controlLock) {
                if (transitionJob === job) transitionJob = null
            }
        }
        job.start()
        return job
    }

    private suspend fun connectTransition(device: DeviceProfile, token: Long) {
        try {
            withContext(NonCancellable) { closeLiveNormally() }
            checkpoint(token)
            persistenceInvalidate(device.id)
            checkpoint(token)

            val client = sysBotbaseFactory.create(device)
            liveConnection = LiveConnection(device, client)
            client.connect()
            checkpoint(token)
            updateIfCurrent(token) { current ->
                current.copy(connection = ConnectionState.Recognizing, error = null)
            }

            val recognized = recognizeCurrentGame.execute(
                device = device,
                client = client,
                checkpoint = { checkpoint(token) },
            )
            reconcilePending(device, recognized.identity, client, token)
            checkpoint(token)
            publishReady(token, device, recognized)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { closeLiveNormally() }
            updateIfCurrentIgnoringCancellation(token) { current ->
                current.copy(
                    connection = ConnectionState.Disconnected,
                    gameValidated = false,
                    activeLocks = lockSnapshot(current.device?.id),
                    pendingLockCleanup = pendingSnapshot(current.device?.id),
                )
            }
            throw cancelled
        } catch (error: Throwable) {
            markAbnormalLoss(token, error)
        }
    }

    private suspend fun refreshTransition(device: DeviceProfile, token: Long) {
        try {
            val live = liveConnection?.takeIf { it.device.id == device.id }
                ?: throw SessionNotReadyException("The selected device is not connected")
            persistenceInvalidate(device.id)
            checkpoint(token)
            val cleanupFailure = releaseActiveLocks(live)
            if (cleanupFailure != null && isConnectionLoss(cleanupFailure)) throw cleanupFailure
            checkpoint(token)
            val recognized = recognizeCurrentGame.execute(
                device = device,
                client = live.client,
                checkpoint = { checkpoint(token) },
            )
            reconcilePending(device, recognized.identity, live.client, token)
            checkpoint(token)
            publishReady(token, device, recognized)
        } catch (cancelled: CancellationException) {
            updateIfCurrentIgnoringCancellation(token) { current ->
                current.copy(connection = ConnectionState.Error, gameValidated = false)
            }
            throw cancelled
        } catch (error: Throwable) {
            if (isConnectionLoss(error)) {
                markAbnormalLoss(token, error)
            } else {
                updateIfCurrent(token) { current ->
                    current.copy(connection = ConnectionState.Error, gameValidated = false, error = error)
                }
            }
        }
    }

    private suspend fun publishReady(
        token: Long,
        device: DeviceProfile,
        recognized: RecognizedCurrentGame,
    ) {
        updateIfCurrent(token) { current ->
            current.copy(
                device = device,
                connection = ConnectionState.Ready,
                game = recognized.identity,
                gameValidated = true,
                cheatFile = recognized.document.cheatFile,
                cheatRelativePath = recognized.document.relativePath,
                checkedGroups = emptySet(),
                lastExecutedAtEpochMillis = emptyMap(),
                executingGroups = executionSnapshot(device.id, recognized.identity),
                activeLocks = lockSnapshot(device.id),
                pendingLockCleanup = pendingSnapshot(device.id),
                error = null,
            )
        }
    }

    private suspend fun reconcilePending(
        device: DeviceProfile,
        identity: GameIdentity,
        client: SysBotbase,
        token: Long,
    ) {
        val key = gameKey(identity)
        val addresses = synchronized(controlLock) {
            pendingByDevice[device.id]?.get(key)?.toList().orEmpty()
        }
        for (address in addresses) {
            checkpoint(token)
            client.unfreeze(address)
            synchronized(controlLock) {
                pendingByDevice[device.id]?.get(key)?.remove(address)
                prunePendingLocked(device.id, key)
            }
        }
    }

    private suspend fun cleanupUntrackedLock(
        live: LiveConnection,
        identity: GameIdentity,
        lock: LockedValue,
    ) = withContext(cleanupDispatcher + NonCancellable) {
        try {
            withTimeout(closeTimeoutMillis) {
                live.client.unfreeze(lock.absoluteAddress)
            }
        } catch (_: Throwable) {
            synchronized(controlLock) {
                addPendingLocked(live.device.id, gameKey(identity), lock.absoluteAddress)
                if (mutableState.value.device?.id == live.device.id) {
                    mutableState.value = mutableState.value.copy(
                        pendingLockCleanup = pendingSnapshotLocked(live.device.id),
                    )
                }
            }
        }
    }

    /** Returns the first cleanup error but always attempts every app-created lock and closes. */
    private suspend fun closeLiveNormally(): Throwable? {
        val live = liveConnection ?: return null
        var firstFailure: Throwable? = null
        try {
            // Revoke process-local trust before any potentially slow network cleanup.
            persistenceInvalidate(live.device.id)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            firstFailure = error
        }
        val releaseFailure = releaseActiveLocks(live)
        if (releaseFailure != null) {
            if (firstFailure == null) firstFailure = releaseFailure else firstFailure.addSuppressed(releaseFailure)
        }
        try {
            live.client.disconnect()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
        }
        if (liveConnection === live) liveConnection = null
        return firstFailure
    }

    private suspend fun releaseActiveLocks(live: LiveConnection): Throwable? {
        val locks = synchronized(controlLock) {
            activeLocksByDevice[live.device.id]?.values?.toList().orEmpty()
        }
        var firstFailure: Throwable? = null
        for (active in locks) {
            try {
                live.client.unfreeze(active.lock.absoluteAddress)
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    synchronized(controlLock) {
                        addPendingLocked(live.device.id, active.game, active.lock.absoluteAddress)
                    }
                    throw error
                }
                if (firstFailure == null) firstFailure = error else firstFailure.addSuppressed(error)
                synchronized(controlLock) {
                    addPendingLocked(live.device.id, active.game, active.lock.absoluteAddress)
                }
            } finally {
                synchronized(controlLock) {
                    activeLocksByDevice[live.device.id]?.remove(active.lock.absoluteAddress)
                }
            }
        }
        synchronized(controlLock) {
            if (mutableState.value.device?.id == live.device.id) {
                mutableState.value = mutableState.value.copy(
                    activeLocks = lockSnapshotLocked(live.device.id),
                    pendingLockCleanup = pendingSnapshotLocked(live.device.id),
                )
            }
        }
        return firstFailure
    }

    private suspend fun markAbnormalLoss(token: Long, error: Throwable) {
        val live = liveConnection
        if (live != null) {
            synchronized(controlLock) {
                activeLocksByDevice.remove(live.device.id)?.values?.forEach { active ->
                    addPendingLocked(live.device.id, active.game, active.lock.absoluteAddress)
                }
            }
        }
        // Revoke UI trust before either persistence or socket cleanup can suspend.
        updateIfCurrentIgnoringCancellation(token) { current ->
            current.copy(
                connection = ConnectionState.Error,
                gameValidated = false,
                activeLocks = lockSnapshot(current.device?.id),
                pendingLockCleanup = pendingSnapshot(current.device?.id),
                error = error,
            )
        }
        if (live != null) {
            withContext(NonCancellable) {
                try {
                    persistenceInvalidate(live.device.id)
                } catch (persistError: Throwable) {
                    error.addSuppressed(persistError)
                }
                try {
                    live.client.disconnect()
                } catch (closeError: Throwable) {
                    error.addSuppressed(closeError)
                }
            }
            if (liveConnection === live) liveConnection = null
        }
    }

    private suspend fun <T> readyOperation(
        expected: GameOperationKey? = null,
        action: suspend (token: Long, live: LiveConnection, identity: GameIdentity) -> T,
    ): T {
        val token = synchronized(controlLock) {
            val current = mutableState.value
            if (current.connection != ConnectionState.Ready || !current.gameValidated || current.game == null) {
                throw SessionNotReadyException()
            }
            if (expected != null && current.operationKey != expected) throw StaleGameOperationException()
            generation
        }
        return operationMutex.withLock {
            registerActiveOperation()
            try {
                checkpoint(token)
                val current = mutableState.value
                if (expected != null && current.operationKey != expected) throw StaleGameOperationException()
                val identity = current.game
                    ?.takeIf { current.gameValidated && current.connection == ConnectionState.Ready }
                    ?: throw SessionNotReadyException()
                val live = liveConnection
                    ?.takeIf { it.device.id == current.device?.id }
                    ?: throw SessionNotReadyException("The selected device socket is not connected")
                action(token, live, identity)
            } finally {
                unregisterActiveOperation()
            }
        }
    }

    private suspend fun <T> connectedOperation(action: suspend (LiveConnection) -> T): T = operationMutex.withLock {
        registerActiveOperation()
        try {
            val current = mutableState.value
            if (current.connection != ConnectionState.Ready) throw SessionNotReadyException("Connect to the device first")
            val live = liveConnection?.takeIf { it.device.id == current.device?.id }
                ?: throw SessionNotReadyException("The selected device socket is not connected")
            action(live)
        } finally {
            unregisterActiveOperation()
        }
    }

    private fun claimExecution(expected: GameOperationKey, groupId: String): ExecutionClaim = synchronized(controlLock) {
        val current = mutableState.value
        val device = current.device
        val identity = current.game
        if (
            device == null || identity == null || !current.gameValidated ||
            current.connection != ConnectionState.Ready
        ) {
            throw SessionNotReadyException()
        }
        if (current.operationKey != expected || generation != expected.generation) {
            throw StaleGameOperationException()
        }
        val claim = ExecutionClaim(device.id, gameKey(identity), groupId)
        if (!executionClaims.add(claim)) throw CheatGroupBusyException(groupId)
        mutableState.value = current.copy(
            executingGroups = executionSnapshotLocked(device.id, identity),
        )
        claim
    }

    private fun releaseExecution(claim: ExecutionClaim) {
        synchronized(controlLock) {
            executionClaims.remove(claim)
            val current = mutableState.value
            val device = current.device
            val identity = current.game
            if (device != null && identity != null) {
                mutableState.value = current.copy(
                    executingGroups = executionSnapshotLocked(device.id, identity),
                )
            }
        }
    }

    private suspend fun checkpoint(token: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(controlLock) { checkGenerationLocked(token) }
    }

    private fun checkGenerationLocked(token: Long) {
        if (generation != token) throw SupersededSessionOperation()
    }

    private fun isCurrentGeneration(token: Long): Boolean =
        synchronized(controlLock) { generation == token }

    private suspend fun updateIfCurrent(
        token: Long,
        transform: (DeviceSessionState) -> DeviceSessionState,
    ) {
        currentCoroutineContext().ensureActive()
        synchronized(controlLock) {
            checkGenerationLocked(token)
            mutableState.value = transform(mutableState.value)
        }
    }

    private fun updateIfCurrentIgnoringCancellation(
        token: Long,
        transform: (DeviceSessionState) -> DeviceSessionState,
    ) {
        synchronized(controlLock) {
            if (generation == token) mutableState.value = transform(mutableState.value)
        }
    }

    private suspend fun registerActiveOperation() {
        val job = requireNotNull(currentCoroutineContext()[Job])
        synchronized(controlLock) { activeOperationJob = job }
    }

    private suspend fun unregisterActiveOperation() {
        val job = currentCoroutineContext()[Job]
        synchronized(controlLock) {
            if (activeOperationJob === job) activeOperationJob = null
        }
    }

    private suspend fun persistenceInvalidate(deviceId: String) {
        recognizeCurrentGame.invalidate(deviceId)
    }

    private fun lockSnapshot(deviceId: String?): Map<ULong, LockedValue> = synchronized(controlLock) {
        lockSnapshotLocked(deviceId)
    }

    private fun lockSnapshotLocked(deviceId: String?): Map<ULong, LockedValue> {
        if (deviceId == null) return emptyMap()
        val copy = LinkedHashMap<ULong, LockedValue>()
        activeLocksByDevice[deviceId]?.forEach { (address, active) -> copy[address] = active.lock }
        return Collections.unmodifiableMap(copy)
    }

    private fun pendingSnapshot(deviceId: String?): Set<ULong> = synchronized(controlLock) {
        pendingSnapshotLocked(deviceId)
    }

    private fun pendingSnapshotLocked(deviceId: String?): Set<ULong> {
        if (deviceId == null) return emptySet()
        val copy = linkedSetOf<ULong>()
        pendingByDevice[deviceId]?.values?.forEach(copy::addAll)
        return Collections.unmodifiableSet(copy)
    }

    private fun addPendingLocked(deviceId: String, game: GameKey, address: ULong) {
        pendingByDevice.getOrPut(deviceId) { linkedMapOf() }
            .getOrPut(game) { linkedSetOf() }
            .add(address)
    }

    private fun prunePendingLocked(deviceId: String, game: GameKey) {
        val games = pendingByDevice[deviceId] ?: return
        if (games[game].isNullOrEmpty()) games.remove(game)
        if (games.isEmpty()) pendingByDevice.remove(deviceId)
    }

    private fun immutableSet(values: Set<String>): Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(values))

    private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(values))

    private fun executionSnapshot(deviceId: String, identity: GameIdentity): Set<String> =
        synchronized(controlLock) { executionSnapshotLocked(deviceId, identity) }

    private fun executionSnapshotLocked(deviceId: String, identity: GameIdentity): Set<String> =
        immutableSet(
            executionClaims.asSequence()
                .filter { it.deviceId == deviceId && it.game == gameKey(identity) }
                .map { it.groupId }
                .toCollection(linkedSetOf()),
        )

    private fun isConnectionLoss(error: Throwable): Boolean = when (error) {
        is ProtocolError.Connection,
        is ProtocolError.Disconnected,
        is ProtocolError.Timeout,
        -> true
        else -> error is IOException && error !is ProtocolError.MalformedResponse
    }

    private data class LiveConnection(val device: DeviceProfile, val client: SysBotbase)
    private data class ActiveLock(val game: GameKey, val lock: LockedValue)
    private data class ExecutionClaim(val deviceId: String, val game: GameKey, val groupId: String)
    private class SupersededSessionOperation : CancellationException("Session operation was superseded")
    private class FreezeCancelledBeforeCommit : CancellationException("Freeze was cancelled before commit")

    private companion object {
        const val DEFAULT_CLOSE_TIMEOUT_MILLIS = 5_000L
        const val DEFAULT_FREEZE_TIMEOUT_MILLIS = 5_000L
    }
}

private fun gameKey(identity: GameIdentity) = GameKey(identity.titleId, identity.buildId)
