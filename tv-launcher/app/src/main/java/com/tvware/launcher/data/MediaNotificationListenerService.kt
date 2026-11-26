package com.tvware.launcher.data

import android.service.notification.NotificationListenerService
import com.tvware.launcher.LauncherApp

class MediaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        (application as? LauncherApp)?.mediaMonitor?.refreshAccessAndSessions()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        (application as? LauncherApp)?.mediaMonitor?.refreshAccessAndSessions()
    }
}
