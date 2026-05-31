package com.hdmain.tcpduplex.internal.crypto

import com.hdmain.tcpduplex.HandshakeAuth
import com.hdmain.tcpduplex.TcpDuplexException
import com.hdmain.tcpduplex.peerPublicKeyFingerprint
import com.hdmain.tcpduplex.internal.protocol.WireCodec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

internal object HandshakeEngine {

    data class HandshakeOptions(
        val preSharedKey: ByteArray? = null,
        val expectedPeerPublicKeySha256: ByteArray? = null,
    )

    fun optionsFrom(auth: HandshakeAuth): HandshakeOptions? {
        val psk = auth.preSharedKey
        val fingerprint = auth.expectedPeerPublicKeySha256
        if ((psk == null || psk.isEmpty()) && fingerprint == null) return null
        return HandshakeOptions(
            preSharedKey = psk?.copyOf(),
            expectedPeerPublicKeySha256 = fingerprint?.copyOf(),
        )
    }

    fun performClientHandshake(
        input: InputStream,
        output: OutputStream,
        negotiatedVersion: UShort,
        options: HandshakeOptions?,
        secureRandom: SecureRandom = SecureRandom(),
    ): AesGcmSession {
        val (privateKey, publicKey) = generateKeyPair(secureRandom)
        WireCodec.writeHandshake(output, negotiatedVersion, publicKey)
        val (peerVersion, peerPublicKey) = WireCodec.readHandshake(input)
        if (peerVersion != negotiatedVersion) {
            throw TcpDuplexException.HandshakeFailed(
                IllegalStateException("unexpected negotiated version $peerVersion vs $negotiatedVersion"),
            )
        }
        verifyFingerprint(peerPublicKey, options?.expectedPeerPublicKeySha256)
        val shared = agree(privateKey, peerPublicKey)
        return AesGcmSession.fromSharedSecret(shared, options?.preSharedKey, secureRandom)
    }

    fun performServerHandshake(
        input: InputStream,
        output: OutputStream,
        options: HandshakeOptions?,
        secureRandom: SecureRandom = SecureRandom(),
    ): AesGcmSession {
        val (privateKey, publicKey) = generateKeyPair(secureRandom)
        val (peerVersion, peerPublicKey) = WireCodec.readHandshake(input)
        verifyFingerprint(peerPublicKey, options?.expectedPeerPublicKeySha256)
        val shared = agree(privateKey, peerPublicKey)
        WireCodec.writeHandshake(output, peerVersion, publicKey)
        return AesGcmSession.fromSharedSecret(shared, options?.preSharedKey, secureRandom)
    }

    private fun verifyFingerprint(peerPublicKey: ByteArray, expected: ByteArray?) {
        if (expected == null) return
        if (!peerPublicKeyFingerprint(peerPublicKey).contentEquals(expected)) {
            throw TcpDuplexException.PeerFingerprintMismatch()
        }
    }

    private fun generateKeyPair(secureRandom: SecureRandom): Pair<X25519PrivateKeyParameters, ByteArray> {
        val generator = X25519KeyPairGenerator()
        generator.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = generator.generateKeyPair()
        val privateKey = keyPair.private as X25519PrivateKeyParameters
        val publicKey = (keyPair.public as X25519PublicKeyParameters).encoded
        return privateKey to publicKey
    }

    private fun agree(privateKey: X25519PrivateKeyParameters, peerPublicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)
        return shared
    }
}
