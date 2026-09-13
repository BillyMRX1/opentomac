# Changelog

All notable changes to opentomac are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.5.0](https://github.com/BillyMRX1/opentomac/compare/v0.4.0...v0.5.0) (2026-09-13)


### Features

* **android:** add permission setup center ([38b382c](https://github.com/BillyMRX1/opentomac/commit/38b382c2d562e49d5661177e10dfa92427636462))
* **android:** add permissions center ([90ce9ce](https://github.com/BillyMRX1/opentomac/commit/90ce9ce798b7d6649bda7616f71055ca12ec3ef5))
* **device:** add Ring Phone controls ([201c71a](https://github.com/BillyMRX1/opentomac/commit/201c71aab61d5d79898efef510fcbddcdc0db754))
* **device:** add Ring Phone controls ([c477faa](https://github.com/BillyMRX1/opentomac/commit/c477faa0b9977dd7b85cb191915727e2cbea1c67))
* **device:** show Android battery status on Mac ([a29bcae](https://github.com/BillyMRX1/opentomac/commit/a29bcaea5b651b60c5d8664cc674221eba1701f7))
* **device:** show Android battery status on Mac ([60b006e](https://github.com/BillyMRX1/opentomac/commit/60b006e4bd83486b213de79688a22fa012a67a87))
* **protocol:** negotiate peer capabilities ([92b4704](https://github.com/BillyMRX1/opentomac/commit/92b47046051478422aec99278272e28e60223e29))
* **protocol:** negotiate peer capabilities ([62989b3](https://github.com/BillyMRX1/opentomac/commit/62989b3cf3038edd0db0ae966e09e6c3d5ab6593))


### Bug Fixes

* **macOS:** import shared capabilities in mirror window ([89e7fb3](https://github.com/BillyMRX1/opentomac/commit/89e7fb3a3c5930c044cd6783634eab4c6224bd08))
* **macos:** restore windows from menu bar ([f6eb435](https://github.com/BillyMRX1/opentomac/commit/f6eb435d64597162f1e9b6de66c3b369d8b9cd9e))
* **macos:** restore windows from menu bar ([7972d01](https://github.com/BillyMRX1/opentomac/commit/7972d016521838855b165981a03976c036a2df82))
* **macos:** run as a menu bar agent ([76dca68](https://github.com/BillyMRX1/opentomac/commit/76dca687e42298a635c5a5d45b52c5b4d8e8f45b))
* **macos:** run as a menu bar agent ([bfb713b](https://github.com/BillyMRX1/opentomac/commit/bfb713b96925628e59127a8661b1f3666d28409d))

## [0.4.0](https://github.com/BillyMRX1/opentomac/compare/v0.3.0...v0.4.0) (2026-07-26)


### Features

* **android:** show app version and flag restricted-settings installs ([22a7732](https://github.com/BillyMRX1/opentomac/commit/22a77321c5603f8709a1c0dba85a4e6dd4953cee))
* **android:** show app version and flag restricted-settings installs ([424acfc](https://github.com/BillyMRX1/opentomac/commit/424acfc083b76738c068701b53ce8989b70de4e3))

## [0.3.0](https://github.com/BillyMRX1/opentomac/compare/v0.2.1...v0.3.0) (2026-07-23)


### Features

* add unsigned macOS install options (Homebrew cask, install script) ([336a6f1](https://github.com/BillyMRX1/opentomac/commit/336a6f113074ffd8892556fed764bdb3a1f76e8b))
* add unsigned macOS install options (Homebrew cask, install script) ([db90546](https://github.com/BillyMRX1/opentomac/commit/db905465ad9c3612739790e57fdfee99eb9c6669))


### Bug Fixes

* drop --no-quarantine from Homebrew docs (removed in Homebrew 6) ([9f7e552](https://github.com/BillyMRX1/opentomac/commit/9f7e55213de62bac8cdb6a42c25fe4220867ef0e))

## [0.2.1](https://github.com/BillyMRX1/opentomac/compare/v0.2.0...v0.2.1) (2026-07-21)


### Bug Fixes

* **macos:** report the real version in the app bundle ([23047be](https://github.com/BillyMRX1/opentomac/commit/23047bef8510fe6aa3f812d9d428bfa68a9d3eb9))
* **macos:** report the real version in the app bundle ([c1c7d8a](https://github.com/BillyMRX1/opentomac/commit/c1c7d8a6a667c4cb2edae45e211157b59de32212))

## [0.2.0](https://github.com/BillyMRX1/opentomac/compare/v0.1.0...v0.2.0) (2026-07-21)


### Features

* **ci:** land the release-please bot on main ([aebd50f](https://github.com/BillyMRX1/opentomac/commit/aebd50fa4b80cfabca7e5c4fcd30b30c3420b443))
* **ci:** release-please bot for automated versioning and releases ([26206ba](https://github.com/BillyMRX1/opentomac/commit/26206bae21b13eda59d49a18546c5c64eff8bc47))
* **ci:** release-please bot for automated versioning and releases ([df60aa0](https://github.com/BillyMRX1/opentomac/commit/df60aa0e12dd0c141939171f659c7aac230c615d))
* **macos:** DMG installer, move-to-Applications prompt, notarization-ready release ([e5eccb2](https://github.com/BillyMRX1/opentomac/commit/e5eccb28d6deb8d5329c4aa1dfa6c0c423c0f073))
* **macos:** DMG installer, move-to-Applications prompt, notarization-ready release ([907b0f2](https://github.com/BillyMRX1/opentomac/commit/907b0f24da32a7175c4b8e4f72c30d0afdeed738))

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
