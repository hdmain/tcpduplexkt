package com.hdmain.tcpduplex

import java.security.MessageDigest

/** Returns SHA-256(raw X25519 public key bytes) for [HandshakeAuth.expectedPeerPublicKeySha256]. */
fun peerPublicKeyFingerprint(publicKey: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(publicKey)

/** Returns a lowercase hex SHA-256 fingerprint suitable for logging and config files. */
fun peerPublicKeyFingerprintHex(publicKey: ByteArray): String =
    peerPublicKeyFingerprint(publicKey).joinToString("") { "%02x".format(it) }
