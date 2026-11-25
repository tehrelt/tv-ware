package com.tvware.launcher

import android.app.Application
import com.tvware.launcher.data.AppRepository
import com.tvware.launcher.data.IconCache
import com.tvware.launcher.data.LauncherPreferences
import com.tvware.launcher.data.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LauncherApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val iconCache: IconCache by lazy { IconCache(this) }
    val preferences: LauncherPreferences by lazy { LauncherPreferences(this) }
    val appRepository: AppRepository by lazy { AppRepository(this, iconCache, appScope) }
    val networkMonitor: NetworkMonitor by lazy { NetworkMonitor(this) }

    override fun onCreate() {
        super.onCreate()
        appRepository.start()
        networkMonitor.start()
    }
}
