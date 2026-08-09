package com.nscheatmanager.app

import android.app.Application
import com.nscheatmanager.app.data.db.AppDatabase
import com.nscheatmanager.app.data.files.CheatMirror
import com.nscheatmanager.app.data.files.CheatZipService
import com.nscheatmanager.app.data.files.FileEditorDraftStore
import com.nscheatmanager.app.data.preferences.AppPreferences
import com.nscheatmanager.app.domain.DeviceRepository
import com.nscheatmanager.app.domain.DeviceRepositorySessionPersistence
import com.nscheatmanager.app.domain.DeviceSession
import com.nscheatmanager.app.domain.ExecuteCheatGroup
import com.nscheatmanager.app.domain.MemoryUseCases
import com.nscheatmanager.app.domain.MirrorCheatLibrary
import com.nscheatmanager.app.domain.RecognizeCurrentGame
import com.nscheatmanager.app.domain.SyncCurrentGameFiles
import com.nscheatmanager.app.protocol.noexs.SocketNoexsClient
import com.nscheatmanager.app.protocol.sysbot.SocketSysBotbaseClient
import com.nscheatmanager.app.ui.game.DeviceRepositoryGameStore
import com.nscheatmanager.app.ui.game.DeviceSessionGateway
import com.nscheatmanager.app.ui.game.GameFileGateway
import com.nscheatmanager.app.ui.game.MirrorGameFileGateway
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import com.nscheatmanager.app.ui.settings.DeviceSettingsRepository
import com.nscheatmanager.app.ui.settings.LanguagePreferenceStore
import com.nscheatmanager.app.ui.settings.DeviceRepositoryAdapter
import com.nscheatmanager.app.ui.settings.AppPreferencesAdapter
import com.nscheatmanager.app.ui.game.GameDeviceStore
import com.nscheatmanager.app.ui.game.GameSessionGateway
import com.nscheatmanager.app.data.files.EditorDraftStore

interface MainActivityDependencies {
    val preferences: LanguagePreferenceStore
    val devices: DeviceSettingsRepository
    val gameDevices: GameDeviceStore
    val gameFiles: GameFileGateway
    val editorDrafts: EditorDraftStore
    fun createGameSession(scope: CoroutineScope): GameSessionGateway
}

class NSCheatManagerApplication : Application() {
    val dependencies: AppDependencies by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDependencies(this)
    }
}

class AppDependencies(application: Application) : MainActivityDependencies {
    val appPreferences = AppPreferences.create(application)
    private val database = AppDatabase.create(application)
    val deviceRepository = DeviceRepository(database, appPreferences)
    override val preferences: LanguagePreferenceStore = AppPreferencesAdapter(appPreferences)
    override val devices: DeviceSettingsRepository = DeviceRepositoryAdapter(deviceRepository)
    override val gameDevices = DeviceRepositoryGameStore(deviceRepository)
    private val mirror = CheatMirror(File(application.filesDir, "cheat-mirror"))
    private val zipService = CheatZipService(mirror, File(application.cacheDir, "zip-work").toPath())
    private val synchronization = SyncCurrentGameFiles(
        mirror,
        File(application.cacheDir, "ftp-staging").toPath(),
    )
    override val gameFiles: GameFileGateway = MirrorGameFileGateway(mirror, zipService, synchronization)
    override val editorDrafts = FileEditorDraftStore(File(application.cacheDir, "editor-drafts").toPath())

    override fun createGameSession(scope: CoroutineScope): DeviceSessionGateway {
        val persistence = DeviceRepositorySessionPersistence(deviceRepository)
        return DeviceSessionGateway(
            DeviceSession(
                scope = scope,
                cleanupDispatcher = Dispatchers.IO,
                sysBotbaseFactory = { profile ->
                    SocketSysBotbaseClient(profile.host, profile.sysBotPort, Dispatchers.IO)
                },
                noexsFactory = { profile ->
                    SocketNoexsClient(profile.host, profile.noexsPort, Dispatchers.IO)
                },
                recognizeCurrentGame = RecognizeCurrentGame(persistence, MirrorCheatLibrary(mirror)),
                executeCheatGroup = ExecuteCheatGroup(persistence = persistence),
                memoryUseCases = MemoryUseCases(),
            ),
        )
    }
}
