package com.hdmain.tcpduplex

import com.hdmain.tcpduplex.internal.crypto.AesGcmSession
import com.hdmain.tcpduplex.internal.crypto.KeyDerivation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CryptoTest {

    @Test
    fun keyDerivationWithoutPsk() {
        val shared = byteArrayOf(9, 8, 7, 6)
        val key = KeyDerivation.deriveSessionKey(shared, null)
        assertEquals(32, key.size)
        assertArrayEquals(key, KeyDerivation.deriveSessionKey(shared, null))
    }

    @Test
    fun keyDerivationWithPsk() {
        val shared = byteArrayOf(1, 2, 3)
        val psk = "secret".toByteArray()
        val key = KeyDerivation.deriveSessionKey(shared, psk)
        assertNotEquals(KeyDerivation.deriveSessionKey(shared, null).contentHashCode(), key.contentHashCode())
        assertArrayEquals(key, KeyDerivation.deriveSessionKey(shared, psk))
    }

    @Test
    fun sealOpenRoundTrip() {
        val session = AesGcmSession.fromSharedSecret(
            sharedSecret = byteArrayOf(1, 2, 3, 4),
            preSharedKey = null,
        )
        val plaintext = "hello tcpduplex".toByteArray()
        val sealed = session.seal(plaintext)
        assertArrayEquals(plaintext, session.open(sealed))
    }

    @Test
    fun pskChangesSessionKey() {
        val shared = byteArrayOf(5, 6, 7, 8)
        val plain = byteArrayOf(42)
        val noPsk = AesGcmSession.fromSharedSecret(shared, null)
        val withPsk = AesGcmSession.fromSharedSecret(shared, "psk".toByteArray())
        assertFalse(noPsk.seal(plain).contentEquals(withPsk.seal(plain)))
    }
}
