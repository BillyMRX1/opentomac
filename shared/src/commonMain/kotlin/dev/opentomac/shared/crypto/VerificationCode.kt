@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.opentomac.shared.crypto

import com.ionspin.kotlin.crypto.generichash.GenericHash

/**
 * Short authentication string shown on both devices during pairing so the user can
 * confirm no man-in-the-middle swapped the identity keys.
 */
object VerificationCode {

    /**
     * Derives a 6-decimal-digit code from both identity public keys and the handshake
     * transcript hash. Order-independent: the two keys are sorted lexicographically
     * (unsigned byte order) before hashing, so both devices display the same code
     * regardless of which was the initiator. Requires libsodium to be initialized,
     * which any completed handshake guarantees.
     */
    fun derive(pubA: ByteArray, pubB: ByteArray, transcriptHash: ByteArray): String {
        val (first, second) = if (lexicographicCompare(pubA, pubB) <= 0) pubA to pubB else pubB to pubA
        val digest = GenericHash.genericHash(
            message = (first + second + transcriptHash).toUByteArray(),
            requestedHashLength = 32,
        ).toByteArray()
        val value = ((digest[0].toUByte().toLong() shl 24) or
            (digest[1].toUByte().toLong() shl 16) or
            (digest[2].toUByte().toLong() shl 8) or
            digest[3].toUByte().toLong()) % 1_000_000L
        return value.toString().padStart(6, '0')
    }

    private fun lexicographicCompare(a: ByteArray, b: ByteArray): Int {
        val minLength = minOf(a.size, b.size)
        for (i in 0 until minLength) {
            val diff = a[i].toUByte().toInt() - b[i].toUByte().toInt()
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
