# tcpduplexkt

Kotlin/JVM library for **encrypted full-duplex messaging over TCP** — wire-compatible with the Go reference [github.com/hdmain/tcpduplex](https://github.com/hdmain/tcpduplex).

## Features

- X25519 ECDH key agreement + AES-256-GCM record encryption
- Concurrent send/receive with bounded queues and graceful shutdown
- Optional inbound callback mode (`onMessage`) or blocking `receive()`
- Pre-shared key mixing and peer public-key fingerprint pinning
- Runs on **JVM 11+** and **Android** (minSdk 21+)

## Installation

### Gradle (Kotlin DSL)

**Maven Central** (when published):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.hdmain:tcpduplex:0.2.0")
}
```

**JitPack**:

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.hdmain:tcpduplexkt:0.2.0")
}
```

**Local development**:

```kotlin
dependencies {
    implementation(project(":lib"))
}
```

### Android

Add the dependency to your app module. BouncyCastle is bundled internally for X25519; AES-GCM uses the platform JCE.

## Quick start

### Client

```kotlin
import com.hdmain.tcpduplex.TcpDuplex

fun main() {
    TcpDuplex.connect("127.0.0.1", 9090).use { conn ->
        conn.send("hello".toByteArray())
        println(conn.receive().decodeToString())
    }
}
```

### Server (accept loop)

```kotlin
import com.hdmain.tcpduplex.TcpDuplex
import java.net.ServerSocket

fun main() {
    ServerSocket(9090).use { listener ->
        while (true) {
            TcpDuplex.accept(listener.accept()).use { conn ->
                val msg = conn.receive()
                conn.send(msg)
            }
        }
    }
}
```

### Server helper

```kotlin
import com.hdmain.tcpduplex.TcpDuplex
import java.util.concurrent.atomic.AtomicBoolean

val stop = AtomicBoolean(false)
val server = TcpDuplex.listen(port = 9090)

Thread {
    server.serve({ stop.get() }) { conn ->
        conn.use {
            val msg = it.receive()
            it.send(msg)
        }
    }
}.start()
```

## Configuration

Use [TcpDuplexConfig] for all connection and listener defaults:

```kotlin
import com.hdmain.tcpduplex.HandshakeAuth
import com.hdmain.tcpduplex.TcpDuplexConfig
import java.time.Duration

val config = TcpDuplexConfig.builder()
    .dialTimeout(Duration.ofSeconds(10))
    .handshakeTimeout(Duration.ofSeconds(5))
    .maxMessageBytes(64 * 1024)
    .preSharedKey("rotate-this-secret".toByteArray())
    .build()

val conn = TcpDuplex.connect("10.0.0.5", 9090, config)
```

| Option | Default | Description |
|--------|---------|-------------|
| `dialTimeout` | 30s | TCP connect budget |
| `handshakeTimeout` | 15s | ECDH handshake budget |
| `protocolVersion` | `1` | Wire revision (must match peer) |
| `maxMessageBytes` | 512 KiB | Max plaintext per message |
| `sendQueueDepth` | 256 | Outbound backpressure queue |
| `receiveQueueDepth` | 256 | Inbound queue when using `receive()` |
| `onMessage` | — | Async handler; disables `receive()` |
| `handshake.preSharedKey` | — | Mixed into session key derivation |
| `handshake.expectedPeerPublicKeySha256` | — | SHA-256 pin of peer X25519 key |

Compute a fingerprint with `peerPublicKeyFingerprint(publicKey)` or `peerPublicKeyFingerprintHex(publicKey)`.

## Error handling

All library failures throw [TcpDuplexException] subtypes:

```kotlin
import com.hdmain.tcpduplex.TcpDuplexException

try {
    conn.receive()
} catch (e: TcpDuplexException.ConnectionClosed) {
    // peer closed or local shutdown
} catch (e: TcpDuplexException.TimedOut) {
    // deadline exceeded
} catch (e: TcpDuplexException) {
    // other typed errors
}

// Or classify programmatically:
if (TcpDuplexException.isCausedBy(error, TcpDuplexException.PeerFingerprintMismatch::class.java)) {
    // ...
}
```

## Public API

| Type | Role |
|------|------|
| [TcpDuplex] | Factory: `connect`, `accept`, `listen` |
| [TcpDuplexConnection] | Encrypted session: `send`, `receive`, `close`, `shutdown` |
| [TcpDuplexServer] | Listener: `serve`, `close` |
| [TcpDuplexConfig] | Immutable configuration + `Builder` |
| [HandshakeAuth] | PSK and fingerprint settings |
| [TcpDuplexException] | Sealed error hierarchy |

Implementation details (`internal.*`) are not part of the stable API and may change in minor releases.

## Wire format

1. **Handshake (plaintext):** magic `TDX1`, big-endian `uint16` version, 32-byte X25519 public key (client sends first).
2. **Records:** big-endian `uint32` length, 1-byte type, AES-GCM blob (`nonce ‖ ciphertext ‖ tag`).

See the Go [protocol package](https://github.com/hdmain/tcpduplex/blob/main/protocol/protocol.go) for normative details.

## Publishing (maintainers)

```bash
./gradlew :lib:publishToMavenLocal
```

Signed release to Maven Central (requires `SIGNING_KEY` and `SIGNING_PASSWORD` in `~/.gradle/gradle.properties`):

```bash
./gradlew :lib:publish
```

## Development

```bash
./gradlew test
./gradlew :lib:build
```

## Versioning

This project follows [Semantic Versioning](https://semver.org/). See [CHANGELOG.md](CHANGELOG.md).

## Security

Symmetric keys derive from ephemeral ECDH (optionally mixed with a PSK). Fingerprint pinning validates the peer's **ephemeral** X25519 key from the handshake — not a long-term certificate. This is **not TLS**; prefer TLS/QUIC on untrusted networks.

## License

MIT — see [LICENSE](LICENSE).

[TcpDuplex]: lib/src/main/kotlin/com/hdmain/tcpduplex/TcpDuplex.kt
[TcpDuplexConnection]: lib/src/main/kotlin/com/hdmain/tcpduplex/TcpDuplexConnection.kt
[TcpDuplexConfig]: lib/src/main/kotlin/com/hdmain/tcpduplex/TcpDuplexConfig.kt
[TcpDuplexException]: lib/src/main/kotlin/com/hdmain/tcpduplex/TcpDuplexException.kt
