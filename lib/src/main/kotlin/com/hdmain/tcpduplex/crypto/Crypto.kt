package com.hdmain.tcpduplex.crypto

import com.hdmain.tcpduplex.protocol.Protocol
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PeerFingerprintMismatchException :
    Exception("crypto: peer public key fingerprint mismatch")

class HandshakeAuthenticationException(message: String = "crypto: handshake authentication failed") :
    Exception(message)

data class HandshakeOpts(
    val preSharedKey: ByteArray? = null,
    val expectedPeerPubKeySha256: ByteArray? = null,
) {
    fun clone(): HandshakeOpts = HandshakeOpts(
        preSharedKey = preSharedKey?.copyOf(),
        expectedPeerPubKeySha256 = expectedPeerPubKeySha256?.copyOf(),
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeOpts) return false
        return preSharedKey.contentEquals(other.preSharedKey) &&
            expectedPeerPubKeySha256.contentEquals(other.expectedPeerPubKeySha256)
    }

    override fun hashCode(): Int {
        var result = preSharedKey?.contentHashCode() ?: 0
        result = 31 * result + (expectedPeerPubKeySha256?.contentHashCode() ?: 0)
        return result
    }
}

private fun fingerprintSha256(pubKey: ByteArray): ByteArray =
    java.security.MessageDigest.getInstance("SHA-256").digest(pubKey)

private fun verifyPeerFingerprint(remotePub: ByteArray, expected: ByteArray?) {
    if (expected == null) return
    val sum = fingerprintSha256(remotePub)
    if (!sum.contentEquals(expected)) {
        throw PeerFingerprintMismatchException()
    }
}

private fun generateX25519KeyPair(): Pair<X25519PrivateKeyParameters, ByteArray> {
    val gen = X25519KeyPairGenerator()
    gen.init(X25519KeyGenerationParameters(SecureRandom()))
    val kp = gen.generateKeyPair()
    val priv = kp.private as X25519PrivateKeyParameters
    val pub = (kp.public as X25519PublicKeyParameters).encoded
    return priv to pub
}

private fun x25519SharedSecret(privateKey: X25519PrivateKeyParameters, peerPub: ByteArray): ByteArray {
    val agreement = X25519Agreement()
    agreement.init(privateKey)
    val shared = ByteArray(agreement.agreementSize)
    agreement.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), shared, 0)
    return shared
}

fun clientHandshake(
    input: InputStream,
    output: OutputStream,
    negotiatedVersion: UShort,
    opts: HandshakeOpts?,
): Session {
    val (priv, pub) = generateX25519KeyPair()
    Protocol.writeHandshake(output, negotiatedVersion, pub)
    val (peerVer, peerPub) = Protocol.readHandshake(input)
    if (peerVer != negotiatedVersion) {
        throw HandshakeAuthenticationException(
            "crypto: handshake authentication failed: unexpected negotiated revision $peerVer vs $negotiatedVersion",
        )
    }
    if (opts?.expectedPeerPubKeySha256 != null) {
        verifyPeerFingerprint(peerPub, opts.expectedPeerPubKeySha256)
    }
    val shared = x25519SharedSecret(priv, peerPub)
    return Session.newSession(shared, opts?.preSharedKey)
}

fun serverHandshake(
    input: InputStream,
    output: OutputStream,
    opts: HandshakeOpts?,
): Pair<Session, UShort> {
    val (priv, pub) = generateX25519KeyPair()
    val (peerVer, peerPub) = Protocol.readHandshake(input)
    if (opts?.expectedPeerPubKeySha256 != null) {
        verifyPeerFingerprint(peerPub, opts.expectedPeerPubKeySha256)
    }
    val shared = x25519SharedSecret(priv, peerPub)
    Protocol.writeHandshake(output, peerVer, pub)
    val session = Session.newSession(shared, opts?.preSharedKey)
    return session to peerVer
}

class Session private constructor(private val key: SecretKeySpec) {
    private val nonceSize = 12
    private val tagSize = 16

    fun seal(plaintext: ByteArray): ByteArray {
        val maxSeal = Protocol.MAX_RECORD_PAYLOAD - 1
        if (nonceSize + tagSize + plaintext.size > maxSeal) {
            throw Protocol.BadFrameException()
        }
        val nonce = ByteArray(nonceSize)
        SecureRandom().nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < nonceSize + tagSize) {
            throw Protocol.BadFrameException()
        }
        val nonce = sealed.copyOfRange(0, nonceSize)
        val ciphertext = sealed.copyOfRange(nonceSize, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private fun deriveSessionKey(sharedSecret: ByteArray, psk: ByteArray?): ByteArray {
            if (psk == null || psk.isEmpty()) {
                return java.security.MessageDigest.getInstance("SHA-256").digest(sharedSecret)
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(sharedSecret)
            digest.update(byteArrayOf(0))
            val lb = ByteArray(4)
            java.nio.ByteBuffer.wrap(lb).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(psk.size)
            digest.update(lb)
            digest.update(psk)
            return digest.digest()
        }

        fun newSession(sharedSecret: ByteArray, psk: ByteArray?): Session {
            val keyBytes = deriveSessionKey(sharedSecret, psk)
            return Session(SecretKeySpec(keyBytes, "AES"))
        }
    }
}
