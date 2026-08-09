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

class NSCheatManagerApplication : Application() {
    val dependencies: AppDependencies by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDependencies(this)
    }
}

class AppDependencies(application: Application) {
    val preferences = AppPreferences.create(application)
    private val database = AppDatabase.create(application)
    val devices = DeviceRepository(database, preferences)
    val gameDevices = DeviceRepositoryGameStore(devices)
    private val mirror = CheatMirror(File(application.filesDir, "cheat-mirror"))
    private val zipService = CheatZipService(mirror, File(application.cacheDir, "zip-work").toPath())
    private val synchronization = SyncCurrentGameFiles(
        mirror,
        File(application.cacheDir, "ftp-staging").toPath(),
    )
    val gameFiles: GameFileGateway = MirrorGameFileGateway(mirror, zipService, synchronization)
    val editorDrafts = FileEditorDraftStore(File(application.cacheDir, "editor-drafts").toPath())

    fun createGameSession(scope: CoroutineScope): DeviceSessionGateway {
        val persistence = DeviceRepositorySessionPersistence(devices)
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
