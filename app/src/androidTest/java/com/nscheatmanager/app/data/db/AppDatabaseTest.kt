package com.nscheatmanager.app.data.db

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nscheatmanager.app.data.preferences.AppPreferences
import com.nscheatmanager.app.domain.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var preferences: AppPreferences
    private lateinit var repository: DeviceRepository
    private lateinit var preferenceFile: File
    private lateinit var preferenceScope: CoroutineScope

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = AppDatabase.inMemory(context)
        preferenceFile = File.createTempFile("nscheat-preferences-", ".preferences_pb", context.cacheDir)
        preferenceFile.delete()
        preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = AppPreferences(
            PreferenceDataStoreFactory.create(
                scope = preferenceScope,
                produceFile = { preferenceFile },
            ),
        )
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

    private suspend fun assertFailsWithIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("Expected IllegalArgumentException")
    }
}
