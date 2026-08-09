package com.nscheatmanager.app.domain

import com.nscheatmanager.app.cheats.parser.CheatFile
import com.nscheatmanager.app.cheats.parser.CheatGroup
import com.nscheatmanager.app.cheats.parser.EncodedInstruction
import com.nscheatmanager.app.cheats.vm.ExecutionStatus
import com.nscheatmanager.app.core.model.BuildId
import com.nscheatmanager.app.core.model.MemoryTarget
import com.nscheatmanager.app.core.model.TitleId
import com.nscheatmanager.app.core.model.ValueType
import com.nscheatmanager.app.protocol.ProtocolError
import com.nscheatmanager.app.protocol.noexs.Noexs
import com.nscheatmanager.app.protocol.sysbot.GameIdentity
import com.nscheatmanager.app.protocol.sysbot.SysBotbase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeviceSessionTest {
    @Test
    fun connectMovesThroughStatesRecognizesLoadsCheckedWithoutReplaying() = runTest {
        val connectRelease = CompletableDeferred<Unit>()
        val recognizeRelease = CompletableDeferred<Unit>()
        val client = FakeSysBotbase(
            identity = GAME_A,
            connectRelease = connectRelease,
            recognizeRelease = recognizeRelease,
        )
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.persistence.checked[Key(DEVICE_A.id, GAME_A)] = setOf("Infinite HP")
        fixture.library.documents[gameKey(GAME_A)] = DOCUMENT_A

        val job = fixture.session.connectAndRecognize(DEVICE_A)
        runCurrent()
        assertEquals(ConnectionState.Connecting, fixture.session.state.value.connection)
        assertFalse(fixture.session.state.value.gameValidated)

        connectRelease.complete(Unit)
        runCurrent()
        assertEquals(ConnectionState.Recognizing, fixture.session.state.value.connection)

        recognizeRelease.complete(Unit)
        job.join()
        val ready = fixture.session.state.value
        assertEquals(ConnectionState.Ready, ready.connection)
        assertEquals(GAME_A, ready.game)
        assertTrue(ready.gameValidated)
        assertSame(DOCUMENT_A.cheatFile, ready.cheatFile)
        assertEquals(setOf("Infinite HP"), ready.checkedGroups)
        assertEquals(listOf(DEVICE_A.id to GAME_A), fixture.persistence.saved)
        assertTrue(client.writes.isEmpty())
        assertTrue(client.freezes.isEmpty())
    }

    @Test
    fun switchClosesOldSocketFirstAndRetainsDisplayUntilNewRecognition() = runTest {
        val events = mutableListOf<String>()
        val first = FakeSysBotbase(GAME_A, events = events, label = "a")
        val secondRecognition = CompletableDeferred<Unit>()
        val second = FakeSysBotbase(
            GAME_B,
            recognizeRelease = secondRecognition,
            events = events,
            label = "b",
        )
        val fixture = fixture(
            clients = mutableMapOf(
                DEVICE_A.id to ArrayDeque(listOf(first)),
                DEVICE_B.id to ArrayDeque(listOf(second)),
            ),
        )
        fixture.persistence.checked[Key(DEVICE_A.id, GAME_A)] = setOf("A checked")
        fixture.session.connectAndRecognize(DEVICE_A).join()

        val switch = fixture.session.switchDevice(DEVICE_B)
        runCurrent()
        val transitional = fixture.session.state.value
        assertEquals(DEVICE_B, transitional.device)
        assertEquals(GAME_A, transitional.game)
        assertEquals(setOf("A checked"), transitional.checkedGroups)
        assertFalse(transitional.gameValidated)
        assertEquals(ConnectionState.Recognizing, transitional.connection)
        assertTrue(events.indexOf("a.disconnect") < events.indexOf("b.connect"))

        secondRecognition.complete(Unit)
        switch.join()
        assertEquals(GAME_B, fixture.session.state.value.game)
        assertTrue(fixture.session.state.value.gameValidated)
    }

    @Test
    fun switchingBackReloadsEachDevicesOwnCheckedDisplayWithoutExecutingEitherSet() = runTest {
        val firstA = FakeSysBotbase(GAME_A)
        val b = FakeSysBotbase(GAME_B)
        val secondA = FakeSysBotbase(GAME_A.copy(mainBase = 0x3000u))
        val fixture = fixture(
            clients = mutableMapOf(
                DEVICE_A.id to ArrayDeque(listOf(firstA, secondA)),
                DEVICE_B.id to ArrayDeque(listOf(b)),
            ),
        )
        fixture.persistence.checked[Key(DEVICE_A.id, GAME_A)] = setOf("A only")
        fixture.persistence.checked[Key(DEVICE_B.id, GAME_B)] = setOf("B only")
        fixture.persistence.checked[Key(DEVICE_A.id, secondA.identity)] = setOf("A only")

        fixture.session.connectAndRecognize(DEVICE_A).join()
        assertEquals(setOf("A only"), fixture.session.state.value.checkedGroups)
        fixture.session.switchDevice(DEVICE_B).join()
        assertEquals(setOf("B only"), fixture.session.state.value.checkedGroups)
        fixture.session.switchDevice(DEVICE_A).join()
        assertEquals(setOf("A only"), fixture.session.state.value.checkedGroups)
        assertEquals(0x3000uL, fixture.session.state.value.game?.mainBase)
        assertTrue(firstA.writes.isEmpty() && b.writes.isEmpty() && secondA.writes.isEmpty())
    }

    @Test
    fun supersededRecognitionCannotPersistOrResurrectItsCompletion() = runTest {
        val oldRecognitionStarted = CompletableDeferred<Unit>()
        val releaseOldRecognition = CompletableDeferred<Unit>()
        val old = FakeSysBotbase(
            GAME_A,
            recognizeStarted = oldRecognitionStarted,
            recognizeRelease = releaseOldRecognition,
            ignoreRecognitionCancellation = true,
        )
        val replacement = FakeSysBotbase(GAME_B)
        val fixture = fixture(
            clients = mutableMapOf(
                DEVICE_A.id to ArrayDeque(listOf(old)),
                DEVICE_B.id to ArrayDeque(listOf(replacement)),
            ),
        )

        val oldJob = fixture.session.connectAndRecognize(DEVICE_A)
        awaitGate(oldRecognitionStarted)
        val replacementJob = fixture.session.switchDevice(DEVICE_B)
        runCurrent()
        assertEquals(DEVICE_B, fixture.session.state.value.device)
        assertEquals(ConnectionState.Connecting, fixture.session.state.value.connection)

        releaseOldRecognition.complete(Unit)
        replacementJob.join()
        assertTrue(oldJob.isCancelled)
        assertEquals(listOf(DEVICE_B.id to GAME_B), fixture.persistence.saved)
        assertEquals(DEVICE_B, fixture.session.state.value.device)
        assertEquals(GAME_B, fixture.session.state.value.game)
        assertEquals(ConnectionState.Ready, fixture.session.state.value.connection)
    }

    @Test
    fun switchDuringNonCancellableSessionSaveRevokesOldRepositoryTrustEpoch() = runTest {
        val old = FakeSysBotbase(GAME_A)
        val replacement = FakeSysBotbase(GAME_B)
        val fixture = fixture(
            clients = mutableMapOf(
                DEVICE_A.id to ArrayDeque(listOf(old)),
                DEVICE_B.id to ArrayDeque(listOf(replacement)),
            ),
        )
        val saveStarted = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        fixture.persistence.saveStarted[DEVICE_A.id] = saveStarted
        fixture.persistence.saveRelease[DEVICE_A.id] = releaseSave

        val oldJob = fixture.session.connectAndRecognize(DEVICE_A)
        awaitGate(saveStarted)
        val replacementJob = fixture.session.switchDevice(DEVICE_B)
        runCurrent()
        assertEquals(DEVICE_B, fixture.session.state.value.device)
        assertFalse(fixture.session.state.value.gameValidated)

        releaseSave.complete(Unit)
        replacementJob.join()

        assertTrue(oldJob.isCancelled)
        assertEquals(setOf(DEVICE_B.id), fixture.persistence.trustedDevices)
        assertEquals(GAME_B, fixture.session.state.value.game)
        assertEquals(ConnectionState.Ready, fixture.session.state.value.connection)
    }

    @Test
    fun rapidSwitchAndDisconnectLeavesLastRequestedStateWithoutSocketResurrection() = runTest {
        val aConnect = CompletableDeferred<Unit>()
        val a = FakeSysBotbase(GAME_A, connectRelease = aConnect, ignoreConnectCancellation = true)
        val b = FakeSysBotbase(GAME_B)
        val fixture = fixture(
            clients = mutableMapOf(
                DEVICE_A.id to ArrayDeque(listOf(a)),
                DEVICE_B.id to ArrayDeque(listOf(b)),
            ),
        )

        val first = fixture.session.connectAndRecognize(DEVICE_A)
        runCurrent()
        val second = fixture.session.switchDevice(DEVICE_B)
        val disconnect = fixture.session.disconnect()
        aConnect.complete(Unit)
        disconnect.join()
        advanceUntilIdle()

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        val state = fixture.session.state.value
        assertEquals(ConnectionState.Disconnected, state.connection)
        assertFalse(state.gameValidated)
        assertEquals(DEVICE_B, state.device)
        assertTrue(a.disconnectCalls >= 1)
        assertEquals(0, b.connectCalls)
    }

    @Test
    fun disconnectCancelsInFlightMemoryOperationAndOldCompletionCannotRestoreReady() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val writeRelease = CompletableDeferred<Unit>()
        val client = FakeSysBotbase(
            GAME_A,
            writeStarted = writeStarted,
            writeRelease = writeRelease,
            ignoreWriteCancellation = true,
        )
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()

        val oldWrite = async {
            fixture.session.writeValue(MemoryTarget.Absolute(0x6000u), ValueType.UInt8, "9")
        }
        awaitGate(writeStarted)
        val disconnect = fixture.session.disconnect()
        assertEquals(ConnectionState.Disconnected, fixture.session.state.value.connection)
        assertFalse(fixture.session.state.value.gameValidated)

        writeRelease.complete(Unit)
        disconnect.join()
        assertTrue(oldWrite.isCancelled)
        assertEquals(ConnectionState.Disconnected, fixture.session.state.value.connection)
        assertFalse(fixture.session.state.value.gameValidated)
        assertEquals(1, client.disconnectCalls)
    }

    @Test
    fun cancellingCurrentConnectClosesItsSocketAndLeavesNoTrustedState() = runTest {
        val connectRelease = CompletableDeferred<Unit>()
        val client = FakeSysBotbase(GAME_A, connectRelease = connectRelease)
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))

        val connect = fixture.session.connectAndRecognize(DEVICE_A)
        runCurrent()
        connect.cancel()
        connect.join()

        assertTrue(connect.isCancelled)
        assertEquals(ConnectionState.Disconnected, fixture.session.state.value.connection)
        assertFalse(fixture.session.state.value.gameValidated)
        assertEquals(1, client.disconnectCalls)
        assertTrue(fixture.persistence.invalidated.isNotEmpty())
        assertTrue(fixture.persistence.invalidated.all { it == DEVICE_A.id })
    }

    @Test
    fun closeAndJoinCleansExactLocksAfterOwningScopeWasCancelled() = runTest {
        val ownerJob = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ownerScope = CoroutineScope(dispatcher + ownerJob)
        val client = FakeSysBotbase(GAME_A)
        val fixture = fixture(
            clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))),
            sessionScope = ownerScope,
            cleanupDispatcher = dispatcher,
        )
        fixture.session.connectAndRecognize(DEVICE_A).join()
        val lock = fixture.session.lockValue(
            MemoryTarget.MainRelative(0x33u),
            ValueType.UInt16,
            "4660",
        )

        ownerJob.cancel()
        withTimeout(TEST_GATE_TIMEOUT_MILLIS) {
            fixture.session.closeAndJoin()
        }

        assertEquals(listOf(lock.absoluteAddress), client.unfreezes)
        assertEquals(1, client.disconnectCalls)
        assertFalse(DEVICE_A.id in fixture.persistence.trustedDevices)
        assertEquals(ConnectionState.Disconnected, fixture.session.state.value.connection)
        assertFalse(fixture.session.state.value.gameValidated)
    }

    @Test
    fun explicitDisconnectUnfreezesOnlyLocksCreatedByThisAppThenInvalidatesBases() = runTest {
        val client = FakeSysBotbase(GAME_A)
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()
        val mainLock = fixture.session.lockValue(
            MemoryTarget.MainRelative(0x20u),
            ValueType.UInt32,
            "287454020",
        )
        val absoluteLock = fixture.session.lockValue(
            MemoryTarget.Absolute(0x7000u),
            ValueType.UInt16,
            "4660",
        )
        assertEquals(setOf(mainLock.absoluteAddress, absoluteLock.absoluteAddress), client.freezes.keys)

        val disconnect = fixture.session.disconnect()
        assertFalse(fixture.session.state.value.gameValidated)
        assertEquals(ConnectionState.Disconnected, fixture.session.state.value.connection)
        disconnect.join()

        assertEquals(
            listOf(mainLock.absoluteAddress, absoluteLock.absoluteAddress),
            client.unfreezes,
        )
        assertFalse(0xDEADuL in client.unfreezes)
        assertTrue(fixture.session.state.value.activeLocks.isEmpty())
        assertTrue(fixture.session.state.value.pendingLockCleanup.isEmpty())
        assertEquals(listOf(DEVICE_A.id), fixture.persistence.invalidated.takeLast(1))
    }

    @Test
    fun normalDisconnectContinuesAfterOneUnfreezeFailureAndKeepsOnlyFailedAddressPending() = runTest {
        val client = FakeSysBotbase(GAME_A)
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()
        val failed = fixture.session.lockValue(
            MemoryTarget.Absolute(0x7100u),
            ValueType.UInt8,
            "1",
        )
        val cleaned = fixture.session.lockValue(
            MemoryTarget.Absolute(0x7200u),
            ValueType.UInt8,
            "2",
        )
        client.unfreezeFailures[failed.absoluteAddress] = ProtocolError.Disconnected()

        fixture.session.disconnect().join()

        assertEquals(listOf(failed.absoluteAddress, cleaned.absoluteAddress), client.unfreezes)
        assertEquals(setOf(failed.absoluteAddress), fixture.session.state.value.pendingLockCleanup)
        assertTrue(fixture.session.state.value.activeLocks.isEmpty())
    }

    @Test
    fun abnormalLossMovesExactActiveLocksToPendingAndCompatibleReconnectCleansThem() = runTest {
        val failed = FakeSysBotbase(GAME_A)
        val recovered = FakeSysBotbase(GAME_A)
        val fixture = fixture(
            clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(failed, recovered))),
        )
        fixture.session.connectAndRecognize(DEVICE_A).join()
        val lock = fixture.session.lockValue(
            MemoryTarget.HeapRelative(0x30u),
            ValueType.UInt64,
            "72623859790382856",
        )
        failed.readFailure = ProtocolError.Disconnected()

        assertSuspendThrows<ProtocolError.Disconnected> {
            fixture.session.readValue(MemoryTarget.Absolute(0x9000u), ValueType.UInt8)
        }
        assertEquals(ConnectionState.Error, fixture.session.state.value.connection)
        assertEquals(setOf(lock.absoluteAddress), fixture.session.state.value.pendingLockCleanup)
        assertTrue(fixture.session.state.value.activeLocks.isEmpty())

        fixture.session.connectAndRecognize(DEVICE_A).join()
        assertEquals(listOf(lock.absoluteAddress), recovered.unfreezes)
        assertTrue(fixture.session.state.value.pendingLockCleanup.isEmpty())
        assertEquals(ConnectionState.Ready, fixture.session.state.value.connection)
    }

    @Test
    fun abnormalLossRevokesStateAndRepositoryTrustBeforeDisconnectCanFinish() = runTest {
        val disconnectStarted = CompletableDeferred<Unit>()
        val disconnectRelease = CompletableDeferred<Unit>()
        val client = FakeSysBotbase(
            GAME_A,
            disconnectStarted = disconnectStarted,
            disconnectRelease = disconnectRelease,
            ignoreDisconnectCancellation = true,
        )
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()
        fixture.session.lockValue(MemoryTarget.Absolute(0x7654u), ValueType.UInt8, "1")
        client.readFailure = ProtocolError.Disconnected()

        val failedRead = async {
            assertSuspendThrows<ProtocolError.Disconnected> {
                fixture.session.readValue(MemoryTarget.Absolute(0x9000u), ValueType.UInt8)
            }
        }
        awaitGate(disconnectStarted)

        val stateBeforeDisconnectFinished = fixture.session.state.value
        val trustedBeforeDisconnectFinished = DEVICE_A.id in fixture.persistence.trustedDevices
        val readCompletedBeforeDisconnectFinished = failedRead.isCompleted
        disconnectRelease.complete(Unit)
        withTimeout(TEST_GATE_TIMEOUT_MILLIS) { failedRead.await() }

        assertEquals(ConnectionState.Error, stateBeforeDisconnectFinished.connection)
        assertFalse(stateBeforeDisconnectFinished.gameValidated)
        assertEquals(setOf(0x7654uL), stateBeforeDisconnectFinished.pendingLockCleanup)
        assertFalse(trustedBeforeDisconnectFinished)
        assertFalse(readCompletedBeforeDisconnectFinished)
    }

    @Test
    fun disconnectDuringFreezeTracksAmbiguousAttemptForExactCleanup() = runTest {
        val failed = FakeSysBotbase(GAME_A)
        val recovered = FakeSysBotbase(GAME_A)
        val fixture = fixture(
            clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(failed, recovered))),
        )
        fixture.session.connectAndRecognize(DEVICE_A).join()
        failed.freezeFailure = ProtocolError.Disconnected()

        assertSuspendThrows<ProtocolError.Disconnected> {
            fixture.session.lockValue(
                MemoryTarget.MainRelative(0x99u),
                ValueType.UInt8,
                "7",
            )
        }
        val ambiguousAddress = GAME_A.mainBase + 0x99u
        assertEquals(setOf(ambiguousAddress), fixture.session.state.value.pendingLockCleanup)
        assertTrue(fixture.session.state.value.activeLocks.isEmpty())

        fixture.session.connectAndRecognize(DEVICE_A).join()
        assertEquals(listOf(ambiguousAddress), recovered.unfreezes)
        assertTrue(fixture.session.state.value.pendingLockCleanup.isEmpty())
    }

    @Test
    fun incompatibleReconnectNeverUnfreezesPendingAddressAgainstDifferentGame() = runTest {
        val failed = FakeSysBotbase(GAME_A)
        val differentGame = FakeSysBotbase(GAME_B)
        val fixture = fixture(
            clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(failed, differentGame))),
        )
        fixture.session.connectAndRecognize(DEVICE_A).join()
        val lock = fixture.session.lockValue(
            MemoryTarget.Absolute(0x7777u),
            ValueType.UInt8,
            "42",
        )
        failed.writeFailure = ProtocolError.Disconnected()
        try {
            fixture.session.writeValue(MemoryTarget.Absolute(0x8888u), ValueType.UInt8, "1")
        } catch (_: ProtocolError.Disconnected) {
            // Expected.
        }

        fixture.session.connectAndRecognize(DEVICE_A).join()
        assertTrue(differentGame.unfreezes.isEmpty())
        assertEquals(setOf(lock.absoluteAddress), fixture.session.state.value.pendingLockCleanup)
        assertEquals(GAME_B, fixture.session.state.value.game)
        assertTrue(fixture.session.state.value.gameValidated)
    }

    @Test
    fun typedMemoryAlwaysResolvesValidatedAbsoluteAddressAndUsesLittleEndian() = runTest {
        val client = FakeSysBotbase(GAME_A)
        client.readBytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()

        val read = fixture.session.readValue(
            MemoryTarget.MainRelative(0x44u),
            ValueType.UInt32,
        )
        assertEquals(GAME_A.mainBase + 0x44u, read.absoluteAddress)
        assertEquals("305419896", read.value)
        assertEquals(MemoryTarget.Absolute(GAME_A.mainBase + 0x44u), client.reads.single().first)
        fixture.session.writeValue(
            MemoryTarget.HeapRelative(0x88u),
            ValueType.UInt32,
            "305419896",
        )
        val write = client.writes.single()
        assertEquals(MemoryTarget.Absolute(GAME_A.heapBase + 0x88u), write.first)
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), write.second)

        assertSuspendThrows<IllegalArgumentException> {
            fixture.session.readValue(MemoryTarget.Absolute(1u), ValueType.Hex, 4097)
        }
        assertSuspendThrows<ArithmeticException> {
            fixture.session.readValue(MemoryTarget.MainRelative(ULong.MAX_VALUE), ValueType.UInt8)
        }
    }

    @Test
    fun lockSnapshotCannotBeMutatedAndUnlockUsesItsOriginalAbsoluteAddress() = runTest {
        val client = FakeSysBotbase(GAME_A)
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()
        val lock = fixture.session.lockValue(
            MemoryTarget.MainRelative(0x50u),
            ValueType.UInt16,
            "4660",
        )
        val leakedCopy = lock.bytes.copyToByteArray()
        leakedCopy[0] = 0
        assertArrayEquals(byteArrayOf(0x34, 0x12), lock.bytes.copyToByteArray())
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) {
            (fixture.session.state.value.activeLocks as MutableMap<ULong, LockedValue>).clear()
        }

        fixture.session.unlockValue(lock.absoluteAddress)
        assertEquals(listOf(GAME_A.mainBase + 0x50u), client.unfreezes)
        assertTrue(fixture.session.state.value.activeLocks.isEmpty())
    }

    @Test
    fun equivalentTargetCannotOverwriteThenAccidentallyUnfreezeExistingAbsoluteLock() = runTest {
        val client = FakeSysBotbase(GAME_A)
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()
        val original = fixture.session.lockValue(
            MemoryTarget.MainRelative(0x50u),
            ValueType.UInt16,
            "4660",
        )

        assertSuspendThrows<IllegalArgumentException> {
            fixture.session.lockValue(
                MemoryTarget.Absolute(original.absoluteAddress),
                ValueType.UInt16,
                "22136",
            )
        }

        assertEquals(1, client.freezeCalls.size)
        assertArrayEquals(byteArrayOf(0x34, 0x12), client.freezes.getValue(original.absoluteAddress))
        assertTrue(client.unfreezes.isEmpty())
        assertEquals(original, fixture.session.state.value.activeLocks[original.absoluteAddress])
    }

    @Test
    fun cancellationBeforeFreezeCommitSendsNeitherFreezeNorUnfreeze() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val writeRelease = CompletableDeferred<Unit>()
        val client = FakeSysBotbase(
            GAME_A,
            writeStarted = writeStarted,
            writeRelease = writeRelease,
            ignoreWriteCancellation = true,
        )
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()
        val blockingWrite = async {
            fixture.session.writeValue(MemoryTarget.Absolute(0x6000u), ValueType.UInt8, "1")
        }
        awaitGate(writeStarted)
        val cancelledLock = async {
            fixture.session.lockValue(MemoryTarget.Absolute(0x6100u), ValueType.UInt8, "2")
        }
        runCurrent()

        cancelledLock.cancel()
        writeRelease.complete(Unit)
        withTimeout(TEST_GATE_TIMEOUT_MILLIS) { blockingWrite.await() }
        cancelledLock.join()

        assertTrue(client.freezeCalls.isEmpty())
        assertTrue(client.unfreezes.isEmpty())
        assertTrue(fixture.session.state.value.activeLocks.isEmpty())
        assertTrue(fixture.session.state.value.pendingLockCleanup.isEmpty())
    }

    @Test
    fun cancellationAfterFreezeCommitFinishesAttemptThenCleansExactAddress() = runTest {
        val freezeEntered = CompletableDeferred<Unit>()
        val allowFreezeSend = CompletableDeferred<Unit>()
        val client = FakeSysBotbase(
            GAME_A,
            freezeEntered = freezeEntered,
            freezeBeforeSendRelease = allowFreezeSend,
        )
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()

        val cancelledLock = async {
            fixture.session.lockValue(MemoryTarget.Absolute(0x6200u), ValueType.UInt8, "3")
        }
        awaitGate(freezeEntered)
        cancelledLock.cancel()
        runCurrent()
        val completedBeforeCommittedSend = cancelledLock.isCompleted
        val cleanupBeforeCommittedSend = client.unfreezes.toList()

        allowFreezeSend.complete(Unit)
        cancelledLock.join()

        assertFalse(completedBeforeCommittedSend)
        assertTrue(cleanupBeforeCommittedSend.isEmpty())
        assertEquals(listOf(0x6200uL), client.freezeCalls.map { it.first })
        assertEquals(listOf(0x6200uL), client.unfreezes)
        assertTrue(fixture.session.state.value.activeLocks.isEmpty())
        assertTrue(fixture.session.state.value.pendingLockCleanup.isEmpty())
    }

    @Test
    fun failedCleanupAfterCommittedFreezeCancellationPublishesPendingAddress() = runTest {
        val freezeEntered = CompletableDeferred<Unit>()
        val allowFreezeSend = CompletableDeferred<Unit>()
        val client = FakeSysBotbase(
            GAME_A,
            freezeEntered = freezeEntered,
            freezeBeforeSendRelease = allowFreezeSend,
        )
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()
        client.unfreezeFailures[0x6300u] = ProtocolError.Disconnected()

        val cancelledLock = async {
            fixture.session.lockValue(MemoryTarget.Absolute(0x6300u), ValueType.UInt8, "4")
        }
        awaitGate(freezeEntered)
        cancelledLock.cancel()
        allowFreezeSend.complete(Unit)
        cancelledLock.join()

        assertEquals(listOf(0x6300uL), client.unfreezes)
        assertEquals(setOf(0x6300uL), fixture.session.state.value.pendingLockCleanup)
        assertTrue(fixture.session.state.value.activeLocks.isEmpty())
    }

    @Test
    fun recognizeAgainInvalidatesImmediatelyAndNeverExecutesCheckedGroups() = runTest {
        val secondRecognition = CompletableDeferred<Unit>()
        val client = FakeSysBotbase(GAME_A)
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.persistence.checked[Key(DEVICE_A.id, GAME_A)] = setOf("Persisted")
        fixture.session.connectAndRecognize(DEVICE_A).join()
        client.recognizeRelease = secondRecognition

        val refresh = fixture.session.recognizeAgain()
        runCurrent()
        assertFalse(fixture.session.state.value.gameValidated)
        assertEquals(ConnectionState.Recognizing, fixture.session.state.value.connection)
        assertTrue(fixture.persistence.invalidated.isNotEmpty())
        assertTrue(client.writes.isEmpty())

        secondRecognition.complete(Unit)
        refresh.join()
        assertEquals(ConnectionState.Ready, fixture.session.state.value.connection)
        assertEquals(setOf("Persisted"), fixture.session.state.value.checkedGroups)
        assertTrue(client.writes.isEmpty())
    }

    @Test
    fun executingAGroupRequiresValidatedIdentityAndPersistsCheckOnlyAfterSuccess() = runTest {
        val client = FakeSysBotbase(GAME_A)
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        assertSuspendThrows<SessionNotReadyException> {
            fixture.session.executeGroup(STATIC_GROUP)
        }
        fixture.session.connectAndRecognize(DEVICE_A).join()

        val report = fixture.session.executeGroup(STATIC_GROUP)
        assertEquals(ExecutionStatus.Complete, report.status)
        assertEquals(setOf("Write once"), fixture.session.state.value.checkedGroups)
        assertEquals(setOf("Write once"), fixture.persistence.checked[Key(DEVICE_A.id, GAME_A)])
        assertEquals(1, client.writes.size)

        fixture.session.uncheckGroup("Write once")
        assertTrue(fixture.session.state.value.checkedGroups.isEmpty())
        assertTrue(fixture.persistence.checked[Key(DEVICE_A.id, GAME_A)].orEmpty().isEmpty())
        assertEquals(1, client.writes.size)
    }

    @Test
    fun duplicateRapidGroupExecutionIsRejectedBeforeMutexAndWritesExactlyOnce() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val client = FakeSysBotbase(
            GAME_A,
            writeStarted = writeStarted,
            writeRelease = releaseWrite,
        )
        val fixture = fixture(clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(client))))
        fixture.session.connectAndRecognize(DEVICE_A).join()

        val firstExecution = async { fixture.session.executeGroup(STATIC_GROUP) }
        awaitGate(writeStarted)
        assertEquals(setOf(STATIC_GROUP.name), fixture.session.state.value.executingGroups)

        withTimeout(TEST_GATE_TIMEOUT_MILLIS) {
            assertSuspendThrows<CheatGroupBusyException> {
                fixture.session.executeGroup(STATIC_GROUP)
            }
        }
        assertEquals(1, client.writes.size)

        releaseWrite.complete(Unit)
        assertEquals(
            ExecutionStatus.Complete,
            withTimeout(TEST_GATE_TIMEOUT_MILLIS) { firstExecution.await() }.status,
        )
        assertEquals(1, client.writes.size)
        assertTrue(fixture.session.state.value.executingGroups.isEmpty())
    }

    @Test
    fun detachDmntUsesOnlySelectedProfilesNoexsFactoryAndDoesNotTouchSysBot() = runTest {
        val sys = FakeSysBotbase(GAME_A)
        val noexs = FakeNoexs()
        val fixture = fixture(
            clients = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(sys))),
            noexs = mutableMapOf(DEVICE_A.id to ArrayDeque(listOf(noexs))),
        )
        fixture.session.connectAndRecognize(DEVICE_A).join()
        val recognizesBefore = sys.recognizeCalls

        fixture.session.detachDmnt()

        assertEquals(1, noexs.detachCalls)
        assertEquals(DEVICE_A.noexsPort, fixture.noexsRequestedPorts.single())
        assertEquals(recognizesBefore, sys.recognizeCalls)
        assertEquals(ConnectionState.Ready, fixture.session.state.value.connection)
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        clients: MutableMap<String, ArrayDeque<FakeSysBotbase>>,
        noexs: MutableMap<String, ArrayDeque<FakeNoexs>> = mutableMapOf(),
        sessionScope: CoroutineScope = backgroundScope,
        cleanupDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Fixture {
        val persistence = FakeSessionPersistence()
        val library = FakeCheatLibrary()
        val noexsRequestedPorts = mutableListOf<Int>()
        val session = DeviceSession(
            scope = sessionScope,
            cleanupDispatcher = cleanupDispatcher,
            sysBotbaseFactory = SysBotbaseFactory { profile ->
                requireNotNull(clients[profile.id]?.removeFirstOrNull()) {
                    "No fake sys-botbase left for ${profile.id}"
                }
            },
            noexsFactory = NoexsFactory { profile ->
                noexsRequestedPorts += profile.noexsPort
                requireNotNull(noexs[profile.id]?.removeFirstOrNull()) {
                    "No fake Noexs left for ${profile.id}"
                }
            },
            recognizeCurrentGame = RecognizeCurrentGame(persistence, library),
            executeCheatGroup = ExecuteCheatGroup(persistence = persistence),
            memoryUseCases = MemoryUseCases(),
        )
        return Fixture(session, persistence, library, noexsRequestedPorts)
    }

    private suspend inline fun <reified T : Throwable> assertSuspendThrows(
        noinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.java.name}, got ${error::class.java.name}", error)
        }
        fail("Expected ${T::class.java.name}")
        error("unreachable")
    }

    private data class Fixture(
        val session: DeviceSession,
        val persistence: FakeSessionPersistence,
        val library: FakeCheatLibrary,
        val noexsRequestedPorts: List<Int>,
    )

    private data class Key(val deviceId: String, val game: GameIdentity)

    private class FakeSessionPersistence : SessionPersistence {
        val invalidated = mutableListOf<String>()
        val saved = mutableListOf<Pair<String, GameIdentity>>()
        val checked = mutableMapOf<Key, Set<String>>()
        val trustedDevices = mutableSetOf<String>()
        val saveStarted = mutableMapOf<String, CompletableDeferred<Unit>>()
        val saveRelease = mutableMapOf<String, CompletableDeferred<Unit>>()

        override suspend fun invalidate(deviceId: String) {
            invalidated += deviceId
            trustedDevices -= deviceId
        }

        override suspend fun saveValidated(deviceId: String, identity: GameIdentity) {
            saveStarted[deviceId]?.complete(Unit)
            saveRelease[deviceId]?.let { gate ->
                withContext(NonCancellable) { awaitGate(gate) }
            }
            saved += deviceId to identity
            trustedDevices += deviceId
        }

        override suspend fun checkedGroups(deviceId: String, identity: GameIdentity): Set<String> =
            checked[Key(deviceId, identity)].orEmpty()

        override suspend fun setChecked(
            deviceId: String,
            identity: GameIdentity,
            groupName: String,
            checked: Boolean,
        ) {
            val key = Key(deviceId, identity)
            val names = this.checked[key].orEmpty().toMutableSet()
            if (checked) names += groupName else names -= groupName
            this.checked[key] = names
        }
    }

    private class FakeCheatLibrary : CheatLibrary {
        val documents = mutableMapOf<GameKey, LoadedCheatDocument>()
        override suspend fun load(identity: GameIdentity): LoadedCheatDocument =
            documents[gameKey(identity)] ?: LoadedCheatDocument.missing(identity)
    }

    private class FakeNoexs : Noexs {
        var detachCalls = 0
        override suspend fun detachDmnt() {
            detachCalls++
        }
    }

    private class FakeSysBotbase(
        var identity: GameIdentity,
        private var connectRelease: CompletableDeferred<Unit> = completedGate(),
        var recognizeRelease: CompletableDeferred<Unit> = completedGate(),
        private val recognizeStarted: CompletableDeferred<Unit>? = null,
        private val ignoreRecognitionCancellation: Boolean = false,
        private val ignoreConnectCancellation: Boolean = false,
        private val writeStarted: CompletableDeferred<Unit>? = null,
        private val writeRelease: CompletableDeferred<Unit> = completedGate(),
        private val ignoreWriteCancellation: Boolean = false,
        private val disconnectStarted: CompletableDeferred<Unit>? = null,
        private val disconnectRelease: CompletableDeferred<Unit> = completedGate(),
        private val ignoreDisconnectCancellation: Boolean = false,
        private val freezeEntered: CompletableDeferred<Unit>? = null,
        private val freezeBeforeSendRelease: CompletableDeferred<Unit>? = null,
        private val events: MutableList<String> = mutableListOf(),
        private val label: String = "client",
    ) : SysBotbase {
        var connectCalls = 0
        var disconnectCalls = 0
        var recognizeCalls = 0
        var readBytes: ByteArray = byteArrayOf(0)
        var readFailure: ProtocolError? = null
        var writeFailure: ProtocolError? = null
        var freezeFailure: ProtocolError? = null
        val reads = mutableListOf<Pair<MemoryTarget, Int>>()
        val writes = mutableListOf<Pair<MemoryTarget, ByteArray>>()
        val freezes = linkedMapOf<ULong, ByteArray>()
        val freezeCalls = mutableListOf<Pair<ULong, ByteArray>>()
        val unfreezes = mutableListOf<ULong>()
        val unfreezeFailures = mutableMapOf<ULong, Throwable>()

        override suspend fun connect() {
            connectCalls++
            events += "$label.connect"
            if (ignoreConnectCancellation) {
                withContext(NonCancellable) { awaitGate(connectRelease) }
            } else {
                awaitGate(connectRelease)
            }
        }

        override suspend fun disconnect() {
            disconnectCalls++
            events += "$label.disconnect"
            disconnectStarted?.complete(Unit)
            if (ignoreDisconnectCancellation) {
                withContext(NonCancellable) { awaitGate(disconnectRelease) }
            } else {
                awaitGate(disconnectRelease)
            }
        }

        override suspend fun recognizeGame(): GameIdentity {
            recognizeCalls++
            events += "$label.recognize"
            recognizeStarted?.complete(Unit)
            if (ignoreRecognitionCancellation) {
                withContext(NonCancellable) { awaitGate(recognizeRelease) }
            } else {
                awaitGate(recognizeRelease)
            }
            return identity
        }

        override suspend fun read(target: MemoryTarget, size: Int): ByteArray {
            reads += target to size
            readFailure?.let { throw it }
            return readBytes.copyOf()
        }

        override suspend fun write(target: MemoryTarget, bytes: ByteArray) {
            writes += target to bytes.copyOf()
            writeStarted?.complete(Unit)
            if (ignoreWriteCancellation) {
                withContext(NonCancellable) { awaitGate(writeRelease) }
            } else {
                awaitGate(writeRelease)
            }
            writeFailure?.let { throw it }
        }

        override suspend fun freeze(absoluteAddress: ULong, bytes: ByteArray) {
            freezeEntered?.complete(Unit)
            freezeBeforeSendRelease?.let { gate -> awaitGate(gate) }
            freezeCalls += absoluteAddress to bytes.copyOf()
            freezes[absoluteAddress] = bytes.copyOf()
            freezeFailure?.let { throw it }
        }

        override suspend fun unfreeze(absoluteAddress: ULong) {
            unfreezes += absoluteAddress
            unfreezeFailures[absoluteAddress]?.let { throw it }
            freezes -= absoluteAddress
        }
    }

    private companion object {
        val DEVICE_A = DeviceProfile("a", "Alpha", "192.168.1.10", noexsPort = 7331)
        val DEVICE_B = DeviceProfile("b", "Beta", "192.168.1.11", noexsPort = 7441)
        val GAME_A = GameIdentity(
            TitleId.parse("0100F2C0115B6000"),
            BuildId.parse("A4A8D3E7F29C81A2"),
            mainBase = 0x1000u,
            heapBase = 0x8000u,
        )
        val GAME_B = GameIdentity(
            TitleId.parse("0100000000001000"),
            BuildId.parse("0011223344556677"),
            mainBase = 0x2000u,
            heapBase = 0x9000u,
        )
        val DOCUMENT_A = LoadedCheatDocument(
            identity = GAME_A,
            relativePath = "atmosphere/contents/${GAME_A.titleId.hex}/cheats/${GAME_A.buildId.hex}.txt",
            cheatFile = CheatFile(emptyList(), emptyList()),
        )
        val STATIC_GROUP = CheatGroup(
            name = "Write once",
            startLine = 1,
            instructions = listOf(
                EncodedInstruction(
                    words = listOf(0x04000000u, 0x00000020u, 0x12345678u),
                    sourceLine = 2,
                    sourceText = "04000000 00000020 12345678",
                ),
            ),
        )

        fun gameKey(identity: GameIdentity) = GameKey(identity.titleId, identity.buildId)

        fun completedGate() = CompletableDeferred(Unit)

        const val TEST_GATE_TIMEOUT_MILLIS = 5_000L

        suspend fun awaitGate(gate: CompletableDeferred<Unit>) {
            withTimeout(TEST_GATE_TIMEOUT_MILLIS) { gate.await() }
        }
    }
}
