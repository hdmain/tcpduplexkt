# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-05-31

### Added

- Stable public API: `TcpDuplex`, `TcpDuplexConnection`, `TcpDuplexServer`, `TcpDuplexConfig`, `HandshakeAuth`
- Sealed `TcpDuplexException` hierarchy with `TcpDuplexException.isCausedBy()` helper
- Immutable configuration with `TcpDuplexConfig.Builder`
- Internal package layout (`internal.protocol`, `internal.crypto`, `internal`)
- Unit tests for wire codec, crypto/key derivation, config resolution, and integration flows
- Maven Publish configuration (sources, javadoc jars, POM metadata)
- Optional PGP signing via `SIGNING_KEY` / `SIGNING_PASSWORD` Gradle properties

### Changed

- **Breaking:** Renamed entry points — `TcpDuplex.connect()` / `accept()` / `listen()` replace `dial()` / `serveConn()`
- **Breaking:** `Config` → immutable `TcpDuplexConfig`; mutable `class Config` removed
- **Breaking:** `Conn` → `TcpDuplexConnection` interface; implementation is internal
- **Breaking:** Exception types consolidated under `TcpDuplexException`
- **Breaking:** Timeout parameters use `java.time.Duration` instead of raw milliseconds
- **Breaking:** `peerPublicKeyFingerprint` parameter renamed in `HandshakeAuth` (`expectedPeerPublicKeySha256`)
- Graceful `close()` now waits for writer flush and close record before closing the socket
- Protocol and crypto packages are internal (not part of the public API surface)

### Deprecated

- `TcpDuplex.dial()` → use `TcpDuplex.connect()`
- `TcpDuplex.serveConn()` → use `TcpDuplex.accept()`

## [0.1.0] - 2026-05-31

### Added

- Initial Kotlin port wire-compatible with [github.com/hdmain/tcpduplex](https://github.com/hdmain/tcpduplex)
- X25519 ECDH + AES-256-GCM encrypted duplex messaging over TCP
- JVM and Android support via BouncyCastle X25519

[0.2.0]: https://github.com/hdmain/tcpduplexkt/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/hdmain/tcpduplexkt/releases/tag/v0.1.0
