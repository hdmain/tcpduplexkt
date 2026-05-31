package com.hdmain.tcpduplex

import java.security.MessageDigest

/** Returns SHA-256(raw X25519 public key bytes) for use with [HandshakeAuth.expectedPeerPubKeySha256]. */
fun peerPublicKeyFingerprint(pubKey: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(pubKey)
