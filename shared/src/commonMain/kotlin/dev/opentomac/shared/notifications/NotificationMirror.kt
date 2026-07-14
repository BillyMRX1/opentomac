package dev.opentomac.shared.notifications

import dev.opentomac.shared.protocol.FilterUpdate
import dev.opentomac.shared.protocol.Message
import dev.opentomac.shared.protocol.NotificationAction
import dev.opentomac.shared.protocol.NotificationDismissed
import dev.opentomac.shared.protocol.NotificationPosted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Notification events exposed by the platform-specific agent bridge. */
sealed interface NotificationEvent {
    data class Posted(val payload: NotificationPosted) : NotificationEvent

    data class Dismissed(val key: String) : NotificationEvent
}

interface NotificationSource {
    fun events(): Flow<NotificationEvent>

    suspend fun performAction(key: String, actionIndex: Int, remoteInputText: String?)
}

interface NotificationPresenter {
    suspend fun present(posted: NotificationPosted)

    suspend fun withdraw(key: String)
}

/**
 * Phone-side notification role. Filtering happens here so denied notification
 * content is never passed to the transport.
 */
class NotificationAgent(
    private val source: NotificationSource,
    private val send: suspend (Message) -> Unit,
    private val ownPackageId: String,
) {
    private val stateMutex = Mutex()
    private val packagesByKey = mutableMapOf<String, String>()
    private var policy = FilterPolicy()
    private var collectionJob: Job? = null

    /** Starts watching platform events; calling start while already running is a no-op. */
    fun start(scope: CoroutineScope) {
        if (collectionJob?.isActive == true) return
        collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            source.events().collect { event ->
                onEvent(event)
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    suspend fun onMessage(msg: Message) {
        when (msg) {
            is FilterUpdate -> stateMutex.withLock {
                policy = FilterPolicy.from(msg)
            }

            is NotificationAction -> source.performAction(
                key = msg.key,
                actionIndex = msg.actionIndex,
                remoteInputText = msg.remoteInputText,
            )

            else -> Unit
        }
    }

    private suspend fun onEvent(event: NotificationEvent) {
        when (event) {
            is NotificationEvent.Posted -> {
                val posted = event.payload
                val shouldSend = stateMutex.withLock {
                    packagesByKey[posted.key] = posted.packageId
                    posted.packageId != ownPackageId && policy.allows(posted.packageId)
                }
                if (shouldSend) send(posted)
            }

            is NotificationEvent.Dismissed -> {
                val shouldSend = stateMutex.withLock {
                    val packageId = packagesByKey.remove(event.key)
                    packageId != ownPackageId &&
                        (packageId == null || packageId !in policy.deniedPackages)
                }
                if (shouldSend) send(NotificationDismissed(event.key))
            }
        }
    }
}

/** Desktop-side notification role. Platform presentation remains host-owned. */
class NotificationCompanion(
    private val presenter: NotificationPresenter,
    private val send: suspend (Message) -> Unit,
) {
    suspend fun onMessage(msg: Message) {
        when (msg) {
            is NotificationPosted -> presenter.present(msg)
            is NotificationDismissed -> presenter.withdraw(msg.key)
            else -> Unit
        }
    }

    suspend fun sendAction(
        key: String,
        actionIndex: Int,
        remoteInputText: String? = null,
    ) {
        send(NotificationAction(key, actionIndex, remoteInputText))
    }

    suspend fun updateFilter(policy: FilterPolicy) {
        send(policy.toUpdate())
    }
}
