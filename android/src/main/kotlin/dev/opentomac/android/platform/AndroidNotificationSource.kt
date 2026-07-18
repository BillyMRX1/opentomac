package dev.opentomac.android.platform

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.util.Log
import android.service.notification.StatusBarNotification
import dev.opentomac.shared.notifications.NotificationEvent
import dev.opentomac.shared.notifications.NotificationSource
import dev.opentomac.shared.protocol.NotifAction
import dev.opentomac.shared.protocol.NotificationPosted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AndroidNotificationSource : NotificationSource {
    override fun events(): Flow<NotificationEvent> = events.asSharedFlow()

    override suspend fun performAction(key: String, actionIndex: Int, remoteInputText: String?) {
        val listener = activeListener ?: return
        val notification = listener.activeNotifications
            ?.firstOrNull { it.key == key }
            ?.notification
            ?: return
        val action = notification.actions?.getOrNull(actionIndex) ?: return
        val fillInIntent = Intent()
        val remoteInputs = action.remoteInputs
        if (!remoteInputs.isNullOrEmpty() && remoteInputText != null) {
            val results = Bundle().apply {
                remoteInputs.forEach { putCharSequence(it.resultKey, remoteInputText) }
            }
            RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, results)
        }
        action.actionIntent.send(listener, 0, fillInIntent)
    }

    companion object Bridge {
        private const val LOG_TAG = "opentomac"
        private val events = MutableSharedFlow<NotificationEvent>(extraBufferCapacity = 64)

        @Volatile
        private var activeListener: NotificationListenerService? = null

        fun attach(listener: NotificationListenerService) {
            Log.w(LOG_TAG, "notification listener connected")
            activeListener = listener
        }

        fun detach(listener: NotificationListenerService) {
            if (activeListener === listener) {
                Log.w(LOG_TAG, "notification listener disconnected")
                activeListener = null
            }
        }

        fun posted(listener: NotificationListenerService, sbn: StatusBarNotification) {
            Log.w(LOG_TAG, "notification posted: ${sbn.packageName} key=${sbn.key}")
            val notification = sbn.notification
            val packageManager = listener.packageManager
            val appName = runCatching {
                val info = packageManager.getApplicationInfo(sbn.packageName, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrDefault(sbn.packageName)
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = (
                notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)
                )?.toString().orEmpty()
            val actions = notification.actions.orEmpty().mapIndexed { index, action ->
                NotifAction(
                    index = index,
                    title = action.title?.toString().orEmpty(),
                    isRemoteInput = !action.remoteInputs.isNullOrEmpty(),
                )
            }
            events.tryEmit(
                NotificationEvent.Posted(
                    NotificationPosted(
                        key = sbn.key,
                        packageId = sbn.packageName,
                        appName = appName,
                        title = title,
                        body = body,
                        postedAt = sbn.postTime,
                        iconPng = null,
                        actions = actions,
                    ),
                ),
            )
        }

        fun removed(sbn: StatusBarNotification) {
            events.tryEmit(NotificationEvent.Dismissed(sbn.key))
        }
    }
}
