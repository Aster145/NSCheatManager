package com.nscheatmanager.app

import android.app.Application
import com.nscheatmanager.app.data.db.AppDatabase
import com.nscheatmanager.app.data.preferences.AppPreferences
import com.nscheatmanager.app.domain.DeviceRepository

class NSCheatManagerApplication : Application() {
    val dependencies: AppDependencies by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppDependencies(this)
    }
}

class AppDependencies(application: Application) {
    val preferences = AppPreferences.create(application)
    private val database = AppDatabase.create(application)
    val devices = DeviceRepository(database, preferences)
}
