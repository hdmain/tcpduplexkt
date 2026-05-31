# tcpduplexkt

**tcpduplexkt** is a Kotlin/JVM implementation of [tcpduplex](https://github.com/hdmain/tcpduplex): encrypted full-duplex messaging over TCP with X25519 ECDH, AES-256-GCM, length-prefixed records, and concurrent read/write loops. It is wire-compatible with the Go reference library and runs on the JVM and Android.

## Requirements

- JDK **11+**
- Android **minSdk 21+** (via standard JVM dependency + BouncyCastle X25519)

## Install

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral() // after publishing
    // or JitPack:
    // maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.hdmain:tcpduplex:0.1.0")
}
```

For a local checkout:

```kotlin
implementation(project(":lib"))
```

### Android

Add the same dependency in your app module's `build.gradle.kts`. No Android-specific artifact is required—the library uses `java.net.Socket` and standard JCE AES-GCM, with BouncyCastle for X25519 on all platforms.

```kotlin
dependencies {
    implementation("com.hdmain:tcpduplex:0.1.0")
}
```

## Features

- **Duplex `Conn`**: `send` / `receive`, bounded queues, max message size
- **Optional `onMessage`**: callback delivery path; `receive` is disabled when configured
- **`Config`**: dial/handshake timeouts, protocol version, queue depths, PSK + peer fingerprint hooks
- **`Server`**: `listen` + `serve` with per-connection handler
- Subpackages `com.hdmain.tcpduplex.protocol` and `com.hdmain.tcpduplex.crypto`

## Quick start

### Client

```kotlin
import com.hdmain.tcpduplex.TcpDuplex

val conn = TcpDuplex.dial("127.0.0.1:9090")
conn.use {
    it.send("hello".toByteArray())
    val msg = it.receive()
    println(msg.decodeToString())
}
```

### Server (manual accept)

```kotlin
import com.hdmain.tcpduplex.TcpDuplex
import java.net.ServerSocket

ServerSocket(9090).use { ss ->
    val raw = ss.accept()
    TcpDuplex.serveConn(raw).use { conn ->
        val msg = conn.receive()
        conn.send(msg)
    }
}
```

### Server helper (`listen` + `serve`)

```kotlin
import com.hdmain.tcpduplex.TcpDuplex
import java.util.concurrent.atomic.AtomicBoolean

val stop = AtomicBoolean(false)
val server = TcpDuplex.listen(9090)

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

Pass a non-nil `Config` to `TcpDuplex.dial` / `TcpDuplex.serveConn` / `TcpDuplex.listen`:

| Field | Role |
|--------|------|
| `dialTimeout` | TCP dial budget (default 30s) |
| `handshakeTimeout` | Full ECDH exchange budget (default 15s) |
| `protocolVersion` | Client-advertised wire revision (must be supported) |
| `maxMessageBytes` | Max decrypted payload (default 512 KiB) |
| `sendQueueDepth` / `receiveQueueDepth` | Backpressure for send/receive queues |
| `onMessage` | Optional inbound handler; when set, `receive` throws `ReceiveDisabledException` |
| `handshake.preSharedKey` | Mixed into key derivation (both peers must match) |
| `handshake.expectedPeerPubKeySha256` | Optional SHA-256 pin of peer X25519 public key |

Use `peerPublicKeyFingerprint(pubKey)` to compute the fingerprint from raw pubkey bytes.

## Wire format

Identical to the Go library:

1. **Handshake (plaintext)**: magic `TDX1`, `uint16` BE protocol version, 32-byte X25519 public key. Client sends first.
2. **Records**: `uint32` BE length, type byte (`MsgText`, `MsgPing`, `MsgPong`, `MsgClose`), then AES-GCM sealed blob (`nonce ‖ ciphertext ‖ tag`).

See the Go reference [`protocol` package](https://github.com/hdmain/tcpduplex/blob/main/protocol/protocol.go) for constants and details.

## Interoperability

The Kotlin library interoperates with Go peers on the same wire protocol. Use the [Go reference implementation](https://github.com/hdmain/tcpduplex) as the other end.

## Examples

| Path | Description |
|------|-------------|
| [`examples/simple`](examples/simple/) | Minimal listen/dial round-trip |

## Testing

```bash
./gradlew test
```

## Security notes

Same as the Go library: symmetric keys derive from ECDH (optionally mixed with PSK). Fingerprint pinning checks the peer's **ephemeral** X25519 key from the handshake. This is **not** TLS—use TLS/QUIC on hostile networks.

## License

MIT — see [LICENSE](LICENSE).

## Related

- Go reference: [github.com/hdmain/tcpduplex](https://github.com/hdmain/tcpduplex)
