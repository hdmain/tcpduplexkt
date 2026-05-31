package com.hdmain.tcpduplex.internal.crypto

import com.hdmain.tcpduplex.TcpDuplexException
import com.hdmain.tcpduplex.internal.protocol.ProtocolConstants
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class AesGcmSession private constructor(
    private val key: SecretKeySpec,
    private val secureRandom: SecureRandom,
) {
    private val nonceSize = GCM_NONCE_BYTES
    private val tagSize = GCM_TAG_BITS / 8

    fun seal(plaintext: ByteArray): ByteArray {
        val maxSealed = ProtocolConstants.MAX_RECORD_PAYLOAD - 1
        if (nonceSize + tagSize + plaintext.size > maxSealed) {
            throw TcpDuplexException.ProtocolError("sealed payload exceeds max record size")
        }
        val nonce = ByteArray(nonceSize).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < nonceSize + tagSize) {
            throw TcpDuplexException.ProtocolError("sealed payload too short")
        }
        val nonce = sealed.copyOfRange(0, nonceSize)
        val ciphertext = sealed.copyOfRange(nonceSize, sealed.size)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw TcpDuplexException.ProtocolError("decryption failed", e)
        }
    }

    companion object {
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128

        fun fromSharedSecret(
            sharedSecret: ByteArray,
            preSharedKey: ByteArray?,
            secureRandom: SecureRandom = SecureRandom(),
        ): AesGcmSession {
            val keyBytes = KeyDerivation.deriveSessionKey(sharedSecret, preSharedKey)
            return AesGcmSession(SecretKeySpec(keyBytes, "AES"), secureRandom)
        }
    }
}
