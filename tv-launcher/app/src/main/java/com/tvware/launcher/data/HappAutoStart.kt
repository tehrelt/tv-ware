package com.tvware.launcher.data

internal const val HAPP_CONNECT_SILENT_URI = "happ://connect_without_ui"
internal const val HAPP_CONNECT_UI_URI = "happ://connect"

internal fun selectHappConnectUri(
    canConnectWithoutUi: Boolean,
    canConnectWithUi: Boolean,
): String? = when {
    canConnectWithoutUi -> HAPP_CONNECT_SILENT_URI
    canConnectWithUi -> HAPP_CONNECT_UI_URI
    else -> null
}

internal fun shouldAutoStartHapp(
    enabled: Boolean,
    alreadyAttempted: Boolean,
    isVpnActive: Boolean,
    isHappInstalled: Boolean,
): Boolean = enabled && !alreadyAttempted && !isVpnActive && isHappInstalled
