package dev.opentomac.shared.crypto

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerificationCodeTest {

    private val pubA = ByteArray(32) { it.toByte() }
    private val pubB = ByteArray(32) { (255 - it).toByte() }
    private val transcriptHash = ByteArray(32) { (it * 3).toByte() }

    @Test
    fun codeIsSixDecimalDigits() = runTest {
        ensureLibsodiumInitialized()
        val code = VerificationCode.derive(pubA, pubB, transcriptHash)
        assertEquals(6, code.length)
        assertTrue(code.all { it in '0'..'9' }, "code should be decimal digits: $code")
    }

    @Test
    fun codeIsOrderIndependent() = runTest {
        ensureLibsodiumInitialized()
        assertEquals(
            VerificationCode.derive(pubA, pubB, transcriptHash),
            VerificationCode.derive(pubB, pubA, transcriptHash),
        )
    }

    @Test
    fun codeIsDeterministic() = runTest {
        ensureLibsodiumInitialized()
        assertEquals(
            VerificationCode.derive(pubA, pubB, transcriptHash),
            VerificationCode.derive(pubA, pubB, transcriptHash),
        )
    }
}
