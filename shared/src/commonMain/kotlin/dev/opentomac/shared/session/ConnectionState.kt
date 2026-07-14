package dev.opentomac.shared.session

import dev.opentomac.shared.pairing.TrustedDevice

/** Observable lifecycle of the single peer session owned by [SessionManager]. */
sealed interface ConnectionState {
    data object Unpaired : ConnectionState

    data object Idle : ConnectionState

    data class Connecting(
        val attempt: Int,
    ) : ConnectionState

    data class Connected(
        val peer: TrustedDevice,
        val transportLabel: String,
    ) : ConnectionState

    data class Degraded(
        val reason: String,
    ) : ConnectionState
}
