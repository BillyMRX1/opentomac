package dev.opentomac.android.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.opentomac.android.platform.AndroidNotificationSource

class OpentomacNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        AndroidNotificationSource.attach(this)
    }

    override fun onListenerDisconnected() {
        AndroidNotificationSource.detach(this)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { AndroidNotificationSource.posted(this, it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let(AndroidNotificationSource::removed)
    }

    override fun onDestroy() {
        AndroidNotificationSource.detach(this)
        super.onDestroy()
    }
}
