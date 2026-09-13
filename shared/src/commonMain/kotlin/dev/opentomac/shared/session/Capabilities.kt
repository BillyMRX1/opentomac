package dev.opentomac.shared.session

/** Stable feature identifiers advertised in [dev.opentomac.shared.protocol.Hello]. */
object Capability {
    const val CLIPBOARD = "clipboard.sync"
    const val FILE_TRANSFER = "file.transfer"
    const val NOTIFICATION_MIRRORING = "notification.mirror"
    const val NOTIFICATION_ACTIONS = "notification.actions"
    const val MEDIA_CONTROL = "media.control"
    const val CONTACTS = "contacts.browse"
    const val MESSAGING = "messaging.sms"
    const val CALLS = "calls.history"
    const val PHOTO_BROWSING = "photo.browse"
    const val SCREEN_MIRRORING = "screen.mirror"
    const val REMOTE_INPUT = "remote.input"
    const val OPEN_URL = "url.open"
    const val BATTERY = "device.battery"
    const val RING = "device.ring"

    val all = setOf(
        CLIPBOARD,
        FILE_TRANSFER,
        NOTIFICATION_MIRRORING,
        NOTIFICATION_ACTIONS,
        MEDIA_CONTROL,
        CONTACTS,
        MESSAGING,
        CALLS,
        PHOTO_BROWSING,
        SCREEN_MIRRORING,
        REMOTE_INPUT,
        OPEN_URL,
        BATTERY,
        RING,
    )
}

/** Empty or absent peer capabilities mean the peer predates capability negotiation. */
fun peerSupports(peerCapabilities: Set<String>?, capability: String): Boolean =
    peerCapabilities.isNullOrEmpty() || capability in peerCapabilities
