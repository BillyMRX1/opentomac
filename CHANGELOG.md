# Changelog

All notable changes to opentomac are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [0.3.0](https://github.com/BillyMRX1/opentomac/compare/v0.2.0...v0.3.0) (2026-07-21)


### Features

* **android:** Compose app with pairing, clipboard, files, notifications ([ae75c43](https://github.com/BillyMRX1/opentomac/commit/ae75c437b9ee70fcd18b5cc2203178a19ec5b55e))
* **camera:** phone as Mac webcam and mic (Phase D) ([d93ed0b](https://github.com/BillyMRX1/opentomac/commit/d93ed0ba3d63b05e8629929352730c05a21c303a))
* **ci:** land the release-please bot on main ([aebd50f](https://github.com/BillyMRX1/opentomac/commit/aebd50fa4b80cfabca7e5c4fcd30b30c3420b443))
* **ci:** release-please bot for automated versioning and releases ([26206ba](https://github.com/BillyMRX1/opentomac/commit/26206bae21b13eda59d49a18546c5c64eff8bc47))
* **ci:** release-please bot for automated versioning and releases ([df60aa0](https://github.com/BillyMRX1/opentomac/commit/df60aa0e12dd0c141939171f659c7aac230c615d))
* **clipboard:** image support and auto-sync on app foreground ([5050a76](https://github.com/BillyMRX1/opentomac/commit/5050a7662ee1f4e81461f8a9dde347bf26d17847))
* **clipboard:** sync engine with loop prevention and history ([5c700c8](https://github.com/BillyMRX1/opentomac/commit/5c700c8894ffdb743b51965edb6cc241c0848bf9))
* **contacts:** search phone contacts from the Mac ([feaf327](https://github.com/BillyMRX1/opentomac/commit/feaf327ac1f633f029a27e12aa3b1077b0717896))
* **crypto:** identity, authenticated handshake, session encryption ([0781ab9](https://github.com/BillyMRX1/opentomac/commit/0781ab9112c710348e8bb34175e6e48386d6ebe4))
* **files:** send picker and transfers list on both apps ([e0619e3](https://github.com/BillyMRX1/opentomac/commit/e0619e336ae517e980fb7bfc3b5c042711bcfbd4))
* **icon:** add the opentomac app icon to the macOS app ([7c4ddda](https://github.com/BillyMRX1/opentomac/commit/7c4dddad2b2005f4f0188a0c88b8a719c0ba01ce))
* **macos:** DMG installer, move-to-Applications prompt, notarization-ready release ([e5eccb2](https://github.com/BillyMRX1/opentomac/commit/e5eccb28d6deb8d5329c4aa1dfa6c0c423c0f073))
* **macos:** DMG installer, move-to-Applications prompt, notarization-ready release ([907b0f2](https://github.com/BillyMRX1/opentomac/commit/907b0f24da32a7175c4b8e4f72c30d0afdeed738))
* **macos:** pairing UI, clipboard, file send, notifications, diagnostics ([29719c5](https://github.com/BillyMRX1/opentomac/commit/29719c5f909ea06aae7178cc871d16a676b2e0be))
* **macos:** SwiftUI app scaffold linking KMP framework ([213d596](https://github.com/BillyMRX1/opentomac/commit/213d596c67368cb1bf536c226227174677f834f9))
* **mac:** sidebar navigation with NavigationSplitView ([9d32a09](https://github.com/BillyMRX1/opentomac/commit/9d32a09edb009164b1d0cd15033b97724683a534))
* **media:** control phone media playback from the Mac ([b840e2a](https://github.com/BillyMRX1/opentomac/commit/b840e2aefab558fb4329dbb3c8d1a5435f716ca8))
* **messaging:** SMS browse/reply and call log from the Mac (Phase E) ([91b6d60](https://github.com/BillyMRX1/opentomac/commit/91b6d6043c5f52af0901a083a37c0487bf0956d5))
* **mirror:** control the phone from the Mac window (C2) ([a15177f](https://github.com/BillyMRX1/opentomac/commit/a15177f81e89d3532c336e136c94cb81c24f408d))
* **mirror:** Mac viewer window for the phone screen (C1 part 2) ([bd07fe0](https://github.com/BillyMRX1/opentomac/commit/bd07fe027edc88bef72ac9eeff1b21f24e462e5b))
* **mirror:** phone-side screen mirroring capture and stream (C1 part 1) ([f1065bc](https://github.com/BillyMRX1/opentomac/commit/f1065bc34af6efc82d2f057b1bd3f1702fd0c65f))
* **mirror:** real viewer window, rotation, quality presets ([624f569](https://github.com/BillyMRX1/opentomac/commit/624f56961a061f34171f2ca11321cc079c9dca93))
* **notifications,media:** mirroring and browsing engines ([5d094e1](https://github.com/BillyMRX1/opentomac/commit/5d094e19a1d802042e824326c5a60c6af44b74fa))
* **notifications,photos:** native Mac banners with reply, notification-access prompt, macOS photos grid ([42bb776](https://github.com/BillyMRX1/opentomac/commit/42bb7760268355a4a33f35b964f02c813c99f205))
* **pairing:** live inline QR scanner that auto-scans in portrait ([a9426b3](https://github.com/BillyMRX1/opentomac/commit/a9426b366d5bc9ed39b5d98c0ed77158d2bbfe82))
* **pairing:** trust store, single-use tokens, pairing state machine ([d044cef](https://github.com/BillyMRX1/opentomac/commit/d044ceff993c5e85122acc1c9ea2d56f6a61144e))
* **photos:** click-to-preview with import inside the preview ([57a1a69](https://github.com/BillyMRX1/opentomac/commit/57a1a691089da29c55c0677e5c12f84b971e7659))
* **photos:** import originals from the Mac photo grid ([bc942a5](https://github.com/BillyMRX1/opentomac/commit/bc942a5ae10f72ace5d6ff67cc6aa029ad428fae))
* **protocol:** message codec and framing ([5de96e2](https://github.com/BillyMRX1/opentomac/commit/5de96e270b5ee60c2e3eae03d697c7b9ef8751cf))
* **screenshots,urls:** auto-send screenshots and URL handoff both ways ([1a9e841](https://github.com/BillyMRX1/opentomac/commit/1a9e84126304b59ee33681f2100b958b60c143da))
* **screenshots:** ask on the Mac before fetching, drop redundant URL button ([33efdcf](https://github.com/BillyMRX1/opentomac/commit/33efdcf78882ddc9827934e1b637802bb6c2e4d6))
* **session:** session manager with reconnect and heartbeats ([1918884](https://github.com/BillyMRX1/opentomac/commit/1918884dfc3a7791590bd0b508866041deeec48a))
* **transfer:** chunked verified file transfer with resume ([f4f1cc0](https://github.com/BillyMRX1/opentomac/commit/f4f1cc00f1095c042bbb5e1deaafc89ac88d3e4d))
* **transfers:** cancel button for in-progress transfers on both apps ([42ea737](https://github.com/BillyMRX1/opentomac/commit/42ea737c9e9dd689b104abdc105caf61aa3bcf11))
* **transport:** TCP frame transport ([2b20f71](https://github.com/BillyMRX1/opentomac/commit/2b20f714d5e862d3ff54dbc8cb97d8f005fe558e))
* **ui:** Liquid Glass redesign of the macOS app ([56694b2](https://github.com/BillyMRX1/opentomac/commit/56694b23a6f6372775d16fce158581c314828a47))
* **ui:** Material 3 Expressive redesign of the Android app ([51f0a47](https://github.com/BillyMRX1/opentomac/commit/51f0a471ad7dfea3b259175297082e324e7400b0))


### Bug Fixes

* **android,macos:** working tile and text-selection, auto-connect, notification diagnostics ([6b3934c](https://github.com/BillyMRX1/opentomac/commit/6b3934c875a753796f741584fcc2613b4f07af98))
* **android:** gate reconnect on Wi-Fi and calm the connection notification ([16625d5](https://github.com/BillyMRX1/opentomac/commit/16625d5a66dcd53815dd68d9f94c463ee82a556a))
* **camera:** match encoder to CameraX's actual resolution (fixes green corruption) ([eb51646](https://github.com/BillyMRX1/opentomac/commit/eb51646f1bf3614b610213c8a5c7a155f76269b5))
* **clipboard:** reliable manual send, image downscaling, ordered concurrent sends ([1626985](https://github.com/BillyMRX1/opentomac/commit/1626985a8c838d1632c6d710d2fca887f9fd4e22))
* **clipboard:** stop dropping items from a restarted peer, re-sync on reconnect ([e18f67f](https://github.com/BillyMRX1/opentomac/commit/e18f67fbaa37e44e8b09ff9cc910ec5b76f95cc9))
* **clipboard:** sync phone clipboard on app resume with focus, dedupe resends ([983e97b](https://github.com/BillyMRX1/opentomac/commit/983e97bee6ae8f455116279a0dc2ab2f363c7ef0))
* **crypto:** serialize session nonces, domain-separate handshake signatures ([0f117e0](https://github.com/BillyMRX1/opentomac/commit/0f117e03b7aa48fae97d5a999d96ce7a84a4ffff))
* **macos:** persistent session listener so paired phone can connect ([638fcc3](https://github.com/BillyMRX1/opentomac/commit/638fcc3936ad6701d7c437780b9b8a7471e0c6b8))
* **media:** stop photos grid stalling after ten thumbnails ([a4246f7](https://github.com/BillyMRX1/opentomac/commit/a4246f7e807548fa7568e93c291c29a716549dc6))
* **mirror,nav:** auto-show phone-initiated mirroring on Mac; Android back navigates ([8924754](https://github.com/BillyMRX1/opentomac/commit/89247548a3de6ca595eb9f272430e5e27675a739))
* **notifications:** skip ongoing and group-summary notifications ([18d00c0](https://github.com/BillyMRX1/opentomac/commit/18d00c02d1f81445e687d9f240a145ca9e59469d))
* **photos:** clip landscape thumbnails to their grid cell ([4bcba93](https://github.com/BillyMRX1/opentomac/commit/4bcba93865ceb266501d72e6832e5685fd99349a))
* run Android pairing off main thread, add macOS pairing cancel ([a086f73](https://github.com/BillyMRX1/opentomac/commit/a086f736486e1a9ec3cd5d7d664155c617d7a1d0))
* **ui,icon:** make the Android UI honest and add the app icon ([89caa64](https://github.com/BillyMRX1/opentomac/commit/89caa6435a339ada710fb8deaebcee06a3671337))

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
