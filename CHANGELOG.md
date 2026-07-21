# Changelog

All notable changes to opentomac are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-07-21

First release. Everything below was built between 2026-07-13 and 2026-07-21 and verified on a real device pair (Samsung One UI phone, Apple Silicon Mac).

### Added

- Wire protocol: kotlinx-serialization ProtoBuf messages with a two-stage envelope and 4 MiB length-prefixed framing, shared across platforms in a Kotlin Multiplatform module.
- Cryptography: Ed25519 device identities, a Noise-XX-style authenticated handshake with domain-separated transcript signatures, BLAKE2b key derivation, and per-frame ChaCha20-Poly1305 session encryption (libsodium).
- Pairing: QR code on the Mac, live inline auto-scanning camera on the phone, 6-digit verification code confirmed on both sides, single-use pairing tokens, persistent trust store, and device revocation.
- Session manager: automatic reconnect with bounded exponential backoff, heartbeats, per-channel rate limiting, and a single persistent Mac TCP listener serving both pairing and sessions.
- Clipboard sync in both directions for text, URLs, and images, with restart-safe replay handling and dedupe that never drops a manual send.
- File transfer in both directions: chunked, verified, cancelable, with progress and received-file lists on both apps, plus a drag-and-drop send zone on the Mac.
- Photos: browse the phone's photos in a grid on the Mac, click to preview, and import originals.
- Notification mirroring: phone notifications as native Mac banners with inline reply back to the phone; ongoing and group-summary notifications are filtered out.
- Screenshot offers: the phone announces new screenshots and the Mac asks before pulling the original.
- URL handoff: share a link to the phone app and it opens in the Mac browser.
- Media remote: a Mac now-playing card that controls phone playback and volume.
- Contacts search, SMS conversation browsing with reply (guarded by an on-phone send confirmation), and the recent-calls list, all from the Mac, with results cleared when their page closes.
- Screen mirroring with remote control: view the phone screen in a resizable Mac window with quality presets, and click, scroll, and type into the phone via an accessibility service. Phone-initiated mirroring opens the Mac viewer automatically.
- Android quick-settings tile, share-sheet target, text-selection send action, and auto-connect on app start.
- macOS app: Liquid Glass design system, sidebar navigation (Dashboard, Photos, Contacts, Messages, Calls), a separate mirror window, a menu-bar extra, and the app icon.
- Android app: Material 3 Expressive design with dynamic color, single-screen dashboard, and an adaptive app icon.
- Wi-Fi awareness: the phone stops reconnect attempts entirely while off Wi-Fi, reconnects immediately when Wi-Fi returns, and keeps the persistent notification to a single calm status line.

### Removed

- The phone-as-webcam feature (Phase D) was built and then fully removed: the macOS virtual camera requires a paid signed build, and the capture path was not reliable enough to keep.

### Security notes

- The session channel is an authenticated encrypted channel in common Kotlin modeled on Noise XX, chosen over mutual TLS for testability across Kotlin/Native and Android. See `docs/plans/2026-07-13-opentomac-mvp-design.md`.
- Known MVP simplification: the Mac stores keys in Application Support files rather than the Keychain.

[0.1.0]: https://github.com/BillyMRX1/opentomac/releases/tag/v0.1.0
