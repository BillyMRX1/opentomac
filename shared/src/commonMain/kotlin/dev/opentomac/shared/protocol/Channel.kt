@file:OptIn(ExperimentalSerializationApi::class)

package dev.opentomac.shared.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Logical multiplexing channels. CONTROL carries handshake/pairing/heartbeat,
 * EVENT carries clipboard/notification events, BULK carries file and media payloads,
 * and VIDEO carries the screen-mirroring stream. VIDEO is exempt from the CONTROL
 * rate limiter; only CONTROL envelopes are rate-limited by SessionManager.
 */
@Serializable
enum class ChannelId {
    @ProtoNumber(0)
    CONTROL,

    @ProtoNumber(1)
    EVENT,

    @ProtoNumber(2)
    BULK,

    @ProtoNumber(3)
    VIDEO,
}
