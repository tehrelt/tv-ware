package com.tvware.launcher.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppRepository(
    private val context: Context,
    private val iconCache: IconCache,
    private val scope: CoroutineScope,
) {
    private val pm: PackageManager = context.packageManager
    private val selfPackage: String = context.packageName

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private var receiverRegistered = false

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart
            if (!pkg.isNullOrBlank()) {
                iconCache.evictPackage(pkg)
            }
            refresh()
        }
    }

    fun start() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            context.registerReceiver(packageReceiver, filter)
            receiverRegistered = true
        }
        refresh()
    }

    fun stop() {
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(packageReceiver) }
            receiverRegistered = false
        }
    }

    fun refresh() {
        scope.launch {
            val loaded = queryInstalledApps()
            _apps.value = loaded
        }
    }

    fun launchApp(app: AppInfo): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(if (app.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            component = app.componentName
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            val fallback = pm.getLeanbackLaunchIntentForPackage(app.packageName)
                ?: pm.getLaunchIntentForPackage(app.packageName)
                ?: return false
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching {
                context.startActivity(fallback)
                true
            }.getOrDefault(false)
        }
    }

    fun launchHapp(): Boolean {
        for (pkg in HAPP_PACKAGES) {
            if (launchPackage(pkg)) return true
        }
        return false
    }

    fun launchPackage(packageName: String): Boolean {
        val intent = pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private suspend fun queryInstalledApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val standardIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val byPackage = LinkedHashMap<String, AppInfo>()

        queryActivities(leanbackIntent).forEach { info ->
            val pkg = info.activityInfo.packageName
            if (pkg != selfPackage && !byPackage.containsKey(pkg)) {
                byPackage[pkg] = toAppInfo(info, isLeanback = true)
            }
        }

        queryActivities(standardIntent).forEach { info ->
            val pkg = info.activityInfo.packageName
            if (pkg != selfPackage && !byPackage.containsKey(pkg)) {
                byPackage[pkg] = toAppInfo(info, isLeanback = false)
            }
        }

        byPackage.values.sortedBy { it.label.lowercase() }
    }

    private fun queryActivities(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

    private fun toAppInfo(info: ResolveInfo, isLeanback: Boolean): AppInfo {
        val activityInfo = info.activityInfo
        val pkg = activityInfo.packageName
        val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
        val versionCode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L)).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0).longVersionCode
            }
        }.getOrDefault(0L)

        return AppInfo(
            packageName = pkg,
            label = label,
            componentName = ComponentName(pkg, activityInfo.name),
            versionCode = versionCode,
            isLeanback = isLeanback,
        )
    }

    private companion object {
        val HAPP_PACKAGES = listOf("su.happ.proxyutility", "com.happproxy")
    }
}
