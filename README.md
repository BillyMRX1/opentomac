# opentomac

opentomac is an open-source, local-first continuity app that links an Android phone with a Mac. Pair once by QR, then share clipboard, files, photos, and notifications directly between the two devices over your local network. There is no account and no cloud relay: the devices talk to each other and to nobody else.

This is a clean-room project built from public-surface requirements only. It does not reproduce any proprietary code, assets, names, or protocols from the products that inspired it.

## Status

MVP in progress. The shared core is complete and tested; the Android and macOS apps build and launch.

| Area | State |
| --- | --- |
| Wire protocol, framing | Done, unit tested |
| Cryptography (identity, handshake, session encryption) | Done, unit tested |
| Pairing, trust store, single-use tokens | Done, unit tested |
| Session manager (reconnect, heartbeats, rate limiting) | Done, unit tested |
| TCP transport | Done, integration tested over real sockets |
| Clipboard sync | Done, unit tested |
| File transfer (chunked, verified, resumable) | Done, unit tested |
| Notification and media engines | Done, unit tested |
| Android app | Builds and launches |
| macOS app | Builds and launches |

135 automated tests cover the shared core (`./gradlew :shared:jvmTest`).

Deferred behind future feasibility and policy work: USB transport, screen mirroring and remote control, SMS and calls, the iPad companion, and the iPhone-to-Android drop flow. The virtual camera and microphone feature was dropped. On macOS, photo browsing is engine-complete in the shared module but not yet surfaced in the UI.

## How it works

- Each device holds a long-term Ed25519 identity. Pairing shows a QR code on the Mac; the phone scans it, both sides run an authenticated handshake, and both display a 6-digit verification code the user confirms. Only then is the peer trusted.
- After pairing, reconnection uses the pinned peer key. All session traffic is encrypted and authenticated per frame.
- A Kotlin Multiplatform `shared` module owns the protocol, cryptography, and feature engines so both platforms behave identically. The Android UI is Jetpack Compose; the macOS UI is SwiftUI. Each app implements the platform pieces (key storage, clipboard, notifications) behind interfaces the shared module defines.

## Security note

The design brief described the session channel as "TLS 1.3 with mutual authentication." Pinned raw-key mutual TLS is not tractable across Kotlin/Native and Android for the MVP, so opentomac instead implements an equivalent authenticated channel in common Kotlin, modeled on the Noise XX pattern: ephemeral X25519 key agreement, Ed25519 identity signatures over a domain-separated transcript, BLAKE2b key derivation, and per-frame ChaCha20-Poly1305 with counter nonces. This keeps the whole handshake in testable common code. See `docs/plans/2026-07-13-opentomac-mvp-design.md`.

## Build

Requirements: JDK 21, the Android SDK (platform 35, build-tools 35), Xcode 14+ with XcodeGen, and a recent Gradle (the wrapper pins 8.13). Point `local.properties` at your Android SDK with `sdk.dir=...`.

Shared core tests:

```
./gradlew :shared:jvmTest
```

Android debug APK:

```
./gradlew :android:assembleDebug
```

macOS app (links the KMP framework, generates the Xcode project, and builds):

```
./scripts/build-macos.sh
```

### Install

Releases ship a DMG for macOS and an APK for Android, see the Releases page. Unsigned macOS releases need the Privacy & Security "Open Anyway" approval until notarized releases land.

## Clean-room boundary

opentomac is developed from public product pages and store listings only. It contains no decompiled code, no copied names or icons, and no proprietary protocols. Its user interface and wire protocol are independently designed.

## License

Apache License 2.0. See `LICENSE`.
