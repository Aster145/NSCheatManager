package com.nscheatmanager.app.data.db

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nscheatmanager.app.data.preferences.AppPreferences
import com.nscheatmanager.app.domain.DeviceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var preferences: AppPreferences
    private lateinit var repository: DeviceRepository
    private lateinit var preferenceFile: File
    private lateinit var preferenceScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = AppDatabase.inMemory(context)
        preferenceFile = File.createTempFile("nscheat-preferences-", ".preferences_pb", context.cacheDir)
        preferenceFile.delete()
        preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = preferenceScope,
            produceFile = { preferenceFile },
        )
        preferences = AppPreferences(dataStore)
        repository = DeviceRepository(database, preferences)
    }

    @After
    fun tearDown() {
        preferenceScope.cancel()
        preferenceFile.delete()
        database.close()
    }

    @Test
    fun duplicateDeviceNameOrHostIsRejected() = runBlocking {
        repository.addDevice(name = "Living room", host = "192.168.1.21")

        assertFailsWithIllegalArgument {
            repository.addDevice(name = "Living room", host = "192.168.1.22")
        }
        assertFailsWithIllegalArgument {
            repository.addDevice(name = "Handheld", host = "192.168.1.21")
        }
    }

    @Test
    fun firstDeviceIsDefaultAndMakingAnotherDefaultClearsTheFirst() = runBlocking {
        val first = repository.addDevice(name = "Living room", host = "192.168.1.21")
        val second = repository.addDevice(name = "Handheld", host = "192.168.1.22")

        assertTrue(repository.observeDevices().first().single { it.id == first.id }.isDefault)
        repository.setDefaultDevice(second.id)

        val devices = repository.observeDevices().first()
        assertFalse(devices.single { it.id == first.id }.isDefault)
        assertTrue(devices.single { it.id == second.id }.isDefault)
        assertEquals(1, devices.count { it.isDefault })
    }

    @Test
    fun checkedGroupsRemainIsolatedAcrossDevicesAndGames() = runBlocking {
        val first = repository.addDevice(name = "Living room", host = "192.168.1.21")
        val second = repository.addDevice(name = "Handheld", host = "192.168.1.22")
        repository.setChecked(
            deviceId = first.id,
            titleId = "0100F2C0115B6000",
            buildId = "A4A8D3E7F29C81A2",
            groupName = "Infinite health",
            checked = true,
        )

        assertTrue(
            repository.observeCheckedGroupNames(
                first.id,
                "0100F2C0115B6000",
                "A4A8D3E7F29C81A2",
            ).first().contains("Infinite health"),
        )
        assertTrue(
            repository.observeCheckedGroupNames(
                second.id,
                "0100F2C0115B6000",
                "A4A8D3E7F29C81A2",
            ).first().isEmpty(),
        )
        assertTrue(
            repository.observeCheckedGroupNames(
                first.id,
                "0100F2C0115B6000",
                "0000000000000001",
            ).first().isEmpty(),
        )
    }

    @Test
    fun switchingSelectionRetainsPerDeviceSessionsAndDisconnectInvalidatesBases() = runBlocking {
        val first = repository.addDevice(name = "Living room", host = "192.168.1.21")
        val second = repository.addDevice(name = "Handheld", host = "192.168.1.22")
        repository.saveValidatedSession(
            deviceId = first.id,
            titleId = "0100F2C0115B6000",
            buildId = "A4A8D3E7F29C81A2",
            mainBase = "0000007100000000",
            heapBase = "0000007101000000",
        )
        repository.selectDevice(second.id)
        repository.selectDevice(first.id)

        val retained = repository.observeSession(first.id).first()
        assertEquals("0100F2C0115B6000", retained?.titleId)
        assertTrue(retained?.validated == true)
        assertEquals(first.id, preferences.selectedDeviceId.first())

        repository.markDeviceDisconnected(first.id)

        val invalidated = repository.observeSession(first.id).first()
        assertFalse(invalidated?.validated == true)
        assertEquals("0000007100000000", invalidated?.mainBase)
    }

    @Test
    fun languageIsLimitedToChineseOrEnglish() = runBlocking {
        assertEquals("zh-CN", preferences.languageTag.first())
        preferences.setLanguageTag("en")
        assertEquals("en", preferences.languageTag.first())

        assertFailsWithIllegalArgument { preferences.setLanguageTag("ja") }
        preferences.clearSelectedDevice()
        assertNull(preferences.selectedDeviceId.first())
    }

    @Test
    fun invalidPersistedLanguageFallsBackToChinese() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("language_tag")] = "ja" }

        assertEquals("zh-CN", preferences.languageTag.first())
    }

    @Test
    fun memoryPageIsHiddenByDefaultAndPreferencePersists() = runBlocking {
        assertFalse(preferences.showMemoryPage.first())
        preferences.setShowMemoryPage(true)
        assertTrue(preferences.showMemoryPage.first())
        preferences.setShowMemoryPage(false)
        assertFalse(preferences.showMemoryPage.first())
    }

    @Test
    fun automaticDmntDetachIsEnabledByDefaultAndPreferencePersists() = runBlocking {
        assertTrue(preferences.detachDmntBeforeConnect.first())
        preferences.setDetachDmntBeforeConnect(false)
        assertFalse(preferences.detachDmntBeforeConnect.first())
        preferences.setDetachDmntBeforeConnect(true)
        assertTrue(preferences.detachDmntBeforeConnect.first())
    }

    @Test
    fun validatedSessionIsTrustedOnlyByTheRepositoryThatRecognizedIt() = runBlocking {
        val databaseName = "restart-${UUID.randomUUID()}.db"
        var firstDatabase: AppDatabase? = null
        var reopenedDatabase: AppDatabase? = null
        try {
            firstDatabase = AppDatabase.create(context, databaseName)
            val firstRepository = DeviceRepository(firstDatabase, preferences)
            val device = firstRepository.addDevice(name = "Restart test", host = "192.168.1.31")
            firstRepository.saveValidatedSession(
                deviceId = device.id,
                titleId = "0100F2C0115B6000",
                buildId = "A4A8D3E7F29C81A2",
                mainBase = "0000007100000000",
                heapBase = "0000007101000000",
            )
            assertTrue(firstRepository.observeSession(device.id).first()?.validated == true)
            firstDatabase.close()
            firstDatabase = null

            reopenedDatabase = AppDatabase.create(context, databaseName)
            val reopenedRepository = DeviceRepository(reopenedDatabase, preferences)

            val reopened = reopenedRepository.observeSession(device.id).first()
            assertEquals("0000007100000000", reopened?.mainBase)
            assertFalse(reopened?.validated == true)
        } finally {
            firstDatabase?.close()
            reopenedDatabase?.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun danglingSelectedDeviceIsIgnoredAndCanBeReplaced() = runBlocking {
        val removed = repository.addDevice(name = "Transient", host = "192.168.1.41")
        val replacement = repository.addDevice(name = "Replacement", host = "192.168.1.42")
        repository.selectDevice(removed.id)

        // Simulate a process failure between Room deletion and the best-effort DataStore cleanup.
        database.deviceProfileDao().delete(removed.id)

        assertNull(repository.observeSelectedDeviceId().first())
        repository.selectDevice(replacement.id)
        assertEquals(replacement.id, repository.observeSelectedDeviceId().first())
    }

    @Test
    fun concurrentDefaultChangesAcrossRepositoriesLeaveExactlyOneDefault() = runBlocking {
        val first = repository.addDevice(name = "First", host = "192.168.1.51")
        val second = repository.addDevice(name = "Second", host = "192.168.1.52")
        val otherRepository = DeviceRepository(database, preferences)

        coroutineScope {
            launch(Dispatchers.IO) {
                repeat(25) { repository.setDefaultDevice(first.id) }
            }
            launch(Dispatchers.IO) {
                repeat(25) { otherRepository.setDefaultDevice(second.id) }
            }
        }

        assertEquals(1, repository.observeDevices().first().count { it.isDefault })
    }

    @Test
    fun disconnectInvalidatesRecognitionThatStartedBeforeIt() = runBlocking {
        val saveReachedPersistence = CompletableDeferred<Unit>()
        val allowSaveToContinue = CompletableDeferred<Unit>()
        val racingRepository = DeviceRepository(
            database = database,
            preferences = preferences,
            beforeSessionPersist = {
                saveReachedPersistence.complete(Unit)
                allowSaveToContinue.await()
            },
        )
        val device = racingRepository.addDevice(name = "Race", host = "192.168.1.61")

        val save = async(Dispatchers.IO) {
            racingRepository.saveValidatedSession(
                deviceId = device.id,
                titleId = "0100F2C0115B6000",
                buildId = "A4A8D3E7F29C81A2",
                mainBase = "0000007100000000",
                heapBase = "0000007101000000",
            )
        }
        saveReachedPersistence.await()
        racingRepository.markDeviceDisconnected(device.id)
        allowSaveToContinue.complete(Unit)
        save.await()

        val session = racingRepository.observeSession(device.id).first()
        assertEquals("A4A8D3E7F29C81A2", session?.buildId)
        assertFalse(session?.validated == true)
    }

    @Test
    fun startingRecognitionRevokesOldTrustEvenWhenNewSaveIsCancelled() = runBlocking {
        val hookCalls = AtomicInteger()
        val secondSaveBlocked = CompletableDeferred<Unit>()
        val neverReleaseSecondSave = CompletableDeferred<Unit>()
        val racingRepository = DeviceRepository(
            database = database,
            preferences = preferences,
            beforeSessionPersist = {
                if (hookCalls.incrementAndGet() == 2) {
                    secondSaveBlocked.complete(Unit)
                    neverReleaseSecondSave.await()
                }
            },
        )
        val device = racingRepository.addDevice(name = "Cancelled", host = "192.168.1.62")
        racingRepository.saveValidatedSession(
            deviceId = device.id,
            titleId = "0100F2C0115B6000",
            buildId = "A4A8D3E7F29C81A2",
            mainBase = "0000007100000000",
            heapBase = "0000007101000000",
        )
        assertTrue(racingRepository.observeSession(device.id).first()?.validated == true)

        val replacementSave = async(Dispatchers.IO) {
            racingRepository.saveValidatedSession(
                deviceId = device.id,
                titleId = "0100F2C0115B6000",
                buildId = "A4A8D3E7F29C81A2",
                mainBase = "0000007200000000",
                heapBase = "0000007201000000",
            )
        }
        secondSaveBlocked.await()

        assertFalse(racingRepository.observeSession(device.id).first()?.validated == true)
        replacementSave.cancelAndJoin()
        assertFalse(racingRepository.observeSession(device.id).first()?.validated == true)
    }

    @Test
    fun failedRecognitionDoesNotRestoreOldTrust() = runBlocking {
        val hookCalls = AtomicInteger()
        val failingRepository = DeviceRepository(
            database = database,
            preferences = preferences,
            beforeSessionPersist = {
                if (hookCalls.incrementAndGet() == 2) error("persistence failed")
            },
        )
        val device = failingRepository.addDevice(name = "Failure", host = "192.168.1.63")
        failingRepository.saveValidatedSession(
            deviceId = device.id,
            titleId = "0100F2C0115B6000",
            buildId = "A4A8D3E7F29C81A2",
            mainBase = "0000007100000000",
            heapBase = "0000007101000000",
        )

        try {
            failingRepository.saveValidatedSession(
                deviceId = device.id,
                titleId = "0100F2C0115B6000",
                buildId = "A4A8D3E7F29C81A2",
                mainBase = "0000007200000000",
                heapBase = "0000007201000000",
            )
            throw AssertionError("Expected failed persistence hook")
        } catch (_: IllegalStateException) {
            // Expected: the new recognition failed before it could publish trusted bases.
        }

        assertFalse(failingRepository.observeSession(device.id).first()?.validated == true)
        assertEquals("0000007100000000", failingRepository.observeSession(device.id).first()?.mainBase)
    }

    private suspend fun assertFailsWithIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
