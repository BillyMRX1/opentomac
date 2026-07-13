# opentomac MVP Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Build the opentomac MVP: a KMP shared core (protocol, crypto, pairing, clipboard, file transfer, notifications engines) with full unit tests, an Android Compose app, and a macOS SwiftUI app, per `docs/plans/2026-07-13-opentomac-mvp-design.md`.

**Architecture:** Kotlin Multiplatform `shared` module (targets: `androidTarget`, `macosArm64`, `jvm` for tests) owns the wire protocol, encrypted session, trust store, and feature engines. The Android app is Jetpack Compose with a foreground connection service. The macOS app is SwiftUI (generated with XcodeGen) linking the KMP framework.

**Tech Stack:** Kotlin 2.1.x, Gradle wrapper, AGP 8.9.x, kotlinx-serialization (ProtoBuf format, no protoc), kotlinx-coroutines, ktor-network (TCP sockets), okio (file IO + SHA-256), `com.ionspin.kotlin:multiplatform-crypto-libsodium-bindings` (Ed25519/X25519/ChaCha20-Poly1305/BLAKE2b), zxing-android-embedded (QR scan), CoreImage QR generation, XcodeGen + xcodebuild.

**Environment facts (verified):** Java 21 Temurin; Gradle installed at `/opt/homebrew/bin/gradle` (use it once to generate the wrapper, then always `./gradlew`); `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` (platform 35, build-tools 35.0.0 installed — write this into `local.properties` as `sdk.dir`); Xcode 26.6; `xcodegen` on PATH. No Android device/emulator: Android app is verified by compilation (`assembleDebug`); shared logic is verified by JVM tests; macOS app is verified by `xcodebuild` + launching.

**Version note:** Start with Kotlin 2.1.20, AGP 8.9.1, coroutines 1.10.1, serialization 1.8.0, ktor 3.1.1, okio 3.10.2, libsodium-bindings 0.9.2. If dependency resolution or Gradle/AGP compatibility fails, resolve to the nearest compatible versions (check error messages; newer is fine) and record the final versions in the commit message.

**Verification gate for every task:** `./gradlew :shared:jvmTest` green (plus task-specific build commands). Commit after every task with a conventional message, no AI attribution.

**Crypto design note (deviation from design doc):** The design doc says "TLS 1.3 mTLS". Implementing pinned-raw-key mTLS across Kotlin/Native + Android is not tractable for MVP; instead we implement an equivalent authenticated channel in common code, modeled on Noise-XX, using libsodium primitives: ephemeral X25519 ECDH, identity Ed25519 signatures over the transcript, BLAKE2b key derivation, and per-frame ChaCha20-Poly1305 with counter nonces. This keeps all handshake logic testable in common Kotlin. Document this in the design doc as part of Task 3.

---

## Task 1: Repository + KMP scaffold

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `local.properties` (gitignored), `.gitignore`, `shared/build.gradle.kts`, `shared/src/commonMain/kotlin/dev/opentomac/shared/Placeholder.kt`, `shared/src/commonTest/kotlin/dev/opentomac/shared/PlaceholderTest.kt`

**Steps:**
1. `gradle wrapper --gradle-version 8.13` in repo root (add wrapper files to git; `.gitignore`: `.gradle/`, `build/`, `local.properties`, `*.xcodeproj`, `xcuserdata`, `.kotlin/`).
2. Version catalog with the versions above. Root build applies nothing; `shared` module: `kotlin("multiplatform")`, `kotlin("plugin.serialization")`, `com.android.library`. Targets: `androidTarget()`, `macosArm64()` (framework binary named `OpentomacShared`), `jvm()`. commonMain deps: coroutines-core, serialization-protobuf, okio, libsodium bindings, ktor-network. commonTest: kotlin-test, coroutines-test, okio-fakefilesystem.
3. Android library config: namespace `dev.opentomac.shared`, compileSdk 35, minSdk 26.
4. Write `local.properties` with `sdk.dir=/opt/homebrew/share/android-commandlinetools`.
5. Trivial test passes: `./gradlew :shared:jvmTest`. Also verify `./gradlew :shared:compileKotlinMacosArm64`.
6. Commit `chore: KMP project scaffold`.

## Task 2: Protocol messages and framing

**Files:** `shared/src/commonMain/kotlin/dev/opentomac/shared/protocol/Messages.kt`, `Framing.kt`, `Channel.kt`; tests in `shared/src/commonTest/kotlin/dev/opentomac/shared/protocol/`

**Spec:**
- `@Serializable` message hierarchy (kotlinx ProtoBuf): sealed `Message` with envelope `Envelope(version: Int, channel: ChannelId, seq: Long, payload: Message)`. Channels: `CONTROL`, `EVENT`, `BULK`.
- Messages for the whole MVP (add now, engines use later): `Hello(protocolVersion, deviceId, deviceName, platform, capabilities)`, `PairInit(token, publicKey)`, `PairAccept(publicKey, signature)`, `PairConfirm(signature)`, `Heartbeat(sentAtMs)`, `HeartbeatAck`, `ClipboardItemMsg(itemId, originDeviceId, seq, type, contentHash, payloadBytes, sensitive)`, `FileOffer(jobId, files: List<FileMeta>)`, `FileOfferReply(jobId, accepted, perFilePolicy)`, `FileChunk(jobId, fileIndex, offset, bytes)`, `FileChunkAck(jobId, fileIndex, verifiedThrough)`, `FileDone(jobId, fileIndex, sha256)`, `FileCancel(jobId, reason)`, `MediaListRequest(bucket, page, pageSize)`, `MediaListResponse(items: List<MediaItem>, hasMore)`, `ThumbnailRequest(mediaId)`, `ThumbnailResponse(mediaId, jpegBytes)`, `NotificationPosted(key, packageId, appName, title, body, postedAt, iconPng, actions: List<NotifAction>)`, `NotificationDismissed(key)`, `NotificationAction(key, actionIndex, remoteInputText)`, `FilterUpdate(deniedPackages, paused)`, `RevokeDevice(deviceId)`.
- Framing: length-prefixed (4-byte big-endian length, max 4 MiB → reject oversized per SEC-009) frames over a `ByteReadChannel`/`ByteWriteChannel`-agnostic interface `FrameTransport { suspend fun send(bytes: ByteArray); suspend fun receive(): ByteArray; fun close() }`.
- Tests: round-trip encode/decode every message type; oversized frame rejected; truncated frame fails cleanly; unknown-version envelope surfaces `ProtocolException`.

TDD: write failing round-trip tests first, then implement. Commit `feat(protocol): message codec and framing`.

## Task 3: Crypto core — identity, handshake, session encryption

**Files:** `shared/src/commonMain/kotlin/dev/opentomac/shared/crypto/Identity.kt`, `Handshake.kt`, `SecureSession.kt`, `VerificationCode.kt`; tests mirror the package.

**Spec:**
- `Identity`: Ed25519 keypair via libsodium (`Signature.keypair()`); `deviceId` = hex of BLAKE2b-16 of public key. Initialize libsodium once (`LibsodiumInitializer.initialize()`).
- Handshake (Noise-XX-flavored, both sides end mutually authenticated):
  1. Initiator → ephemeral X25519 pub `eA`.
  2. Responder → `eB` + Ed25519 signature over transcript `(eA‖eB‖pairingToken?)` + identity pub.
  3. Initiator verifies (during pairing: any key, then user confirms; after pairing: must match pinned trusted key), replies with its own signature + identity pub; responder verifies likewise.
  4. Shared secret = X25519(e_priv, e_peer_pub); session keys = BLAKE2b-KDF(secret, transcriptHash) split into tx/rx keys per direction.
- `SecureSession`: wraps a `FrameTransport`; encrypts each frame with ChaCha20-Poly1305-IETF, nonce = 4-byte direction salt + 8-byte counter; decrypt failure = throw + close (SEC-004).
- `VerificationCode.derive(pubA, pubB, transcriptHash): String` → 6 digits, order-independent (sort keys first).
- Tests (in-memory transport pair): full handshake succeeds and both sides derive equal session keys + equal verification codes; tampered signature fails; tampered ciphertext throws; nonce/counter reuse impossible across direction; a replayed handshake transcript with a stale token fails once Task 4's token store is wired.

Commit `feat(crypto): identity, authenticated handshake, session encryption`.

## Task 4: Trust store and pairing state machine

**Files:** `shared/src/commonMain/kotlin/dev/opentomac/shared/pairing/PairingManager.kt`, `TrustStore.kt`, `PairingToken.kt`; tests mirror.

**Spec:**
- `TrustStore` interface: `save/get/list/remove(TrustedDevice)`, `TrustedDevice(deviceId, displayName, platform, publicKey, pairedAt, lastSeen)`. Common in-memory impl for tests; platform persistence arrives in Tasks 10/13 (`KeyValueStore` expect/actual: Android EncryptedSharedPreferences, macOS Keychain — define the `KeyValueStore` interface now in common).
- `PairingToken`: 16 random bytes; `expiresAt = now + 120s`; single-use (consumed on first handshake attempt, success or failure). Inject a `Clock` interface for tests.
- `PairingManager` drives both roles: host (generates token + `PairingPayload(version, publicKey, addresses, token)` serialized for the QR) and joiner (consumes payload). After handshake: both sides expose `verificationCode` and suspend on `confirm()/reject()`; only after both confirm is the peer written to `TrustStore` (PAIR-002).
- Revocation: `revoke(deviceId)` removes from store and emits an event the session layer uses to kill live sessions (SEC-010).
- Tests (spec §12 pairing catalogue): expired token rejected; token cannot be used twice; reject on either side leaves no trust record; revoked device's completed handshake is refused at pinning check; concurrent second pairing attempt while one is active is refused.

Commit `feat(pairing): trust store, single-use tokens, pairing state machine`.

## Task 5: Session manager — connect, heartbeat, reconnect

**Files:** `shared/src/commonMain/kotlin/dev/opentomac/shared/session/SessionManager.kt`, `ConnectionState.kt`; tests mirror.

**Spec:**
- `ConnectionState` sealed: `Unpaired, Idle, Connecting, Connected(peer, transport), Degraded(reason)` exposed as `StateFlow`.
- `SessionManager(trustStore, transportFactory, clock, scope)`: single-flight connect (a second `connect()` while connecting joins the same attempt); heartbeat every 10 s, peer declared lost after 2 missed acks (NET-005); reconnect with exponential backoff 1 s→2→4→…cap 60 s with ±20 % jitter (PAIR-007); rate-limits inbound pairing/RPC per SEC-008 (token bucket, 10/min default).
- Message dispatch: registered per-channel handlers (`suspend (Message) -> Unit`); unknown message types logged and ignored (forward compat).
- Tests use `runTest` virtual time + fake transport: backoff schedule asserted; no duplicate sessions under racing connects; heartbeat loss triggers reconnect; revoke event closes session immediately.

Commit `feat(session): session manager with reconnect and heartbeats`.

## Task 6: TCP transport (ktor-network)

**Files:** `shared/src/commonMain/kotlin/dev/opentomac/shared/transport/TcpTransport.kt` (+ server acceptor); integration test in `shared/src/jvmTest/.../TcpTransportTest.kt`

**Spec:** `TcpFrameTransport` implements `FrameTransport` over ktor `aSocket(...).tcp()`; `TcpServer(port=0)` exposes the bound port and accepted transports as a Flow. jvmTest: client/server over localhost run the full Task-3 handshake and exchange 1000 frames including a 2 MiB frame; abrupt socket close surfaces as transport error, not hang.

Commit `feat(transport): TCP frame transport`.

## Task 7: Clipboard sync engine

**Files:** `shared/src/commonMain/kotlin/dev/opentomac/shared/clipboard/ClipboardSync.kt`, `ClipboardHistory.kt`; tests mirror.

**Spec:** Platform side implements `LocalClipboard { fun changes(): Flow<ClipItem>; suspend fun apply(ClipItem) }`. Engine: on local change → hash content (BLAKE2b), skip if hash matches last applied remote item (CLIP-004), skip if `sensitive` flag or paused (CLIP-006), send `ClipboardItemMsg` with monotonic seq. On remote item → record hash, `apply`. Optional history ring buffer 10/20/50 (CLIP-003), clearable. Tests: two engines wired back-to-back never loop (item bounces exactly once); sensitive skipped; pause works; history caps and clears; out-of-order seq ignored.

Commit `feat(clipboard): sync engine with loop prevention and history`.

## Task 8: File transfer engine

**Files:** `shared/src/commonMain/kotlin/dev/opentomac/shared/transfer/TransferEngine.kt`, `TransferJob.kt`, `DuplicatePolicy.kt`; tests mirror (use okio `FakeFileSystem`).

**Spec:**
- Sender: `FileOffer` with metadata (name, size, mime); await `FileOfferReply`; stream 1 MiB `FileChunk`s from okio source; finish with `FileDone(sha256)` (hash computed streaming via okio `HashingSource`).
- Receiver: duplicate handling before accept — policies Ask (surface via callback), Replace, KeepBoth (` (2)` suffix), Skip (FILE-005); write to `<name>.part`, verify SHA-256 on `FileDone`, atomic rename; mismatch → delete `.part`, report failure (FILE-006).
- Progress `StateFlow` (bytes, throughput, ETA); `cancel()` propagates `FileCancel` and deletes `.part` within one chunk boundary (FILE-004); resume: receiver reports `verifiedThrough` offset, sender seeks (FILE-008).
- Tests: end-to-end over in-memory transport with FakeFileSystem: single file, 1000-file batch metadata, corrupt chunk → hash mismatch → no falsely-complete file, cancel mid-transfer cleans `.part`, resume after transport drop retransmits nothing already verified, every duplicate policy.

Commit `feat(transfer): chunked verified file transfer with resume`.

## Task 9: Notification + media engines (common halves)

**Files:** `shared/src/commonMain/kotlin/dev/opentomac/shared/notifications/NotificationMirror.kt`, `FilterPolicy.kt`; `shared/src/commonMain/kotlin/dev/opentomac/shared/media/MediaBrowser.kt`; tests mirror.

**Spec:** `FilterPolicy` (deny-list + global pause; evaluated agent-side so denied content never leaves the phone — NOTIF-003) with serialization for `FilterUpdate`. `NotificationMirror` routes `NotificationPosted/Dismissed/Action` between a platform `NotificationSource` (Android) and `NotificationPresenter` (macOS), keyed strictly by notification key (NOTIF-002/004); suppresses own-package keys (NOTIF-005). `MediaBrowser`: paging request/response state, thumbnail request de-dup + LRU cache keyed by content hash. Tests: filter semantics don't invert; action routed to exact key; own notifications suppressed; paging and cache behavior.

Commit `feat(notifications,media): mirroring and browsing engines`.

## Task 10: Android app scaffold + platform services

**Files:** `android/` module (`build.gradle.kts`, manifest, `MainActivity.kt`, Compose UI packages), `androidMain` actuals in `shared`.

**Spec:**
- App `dev.opentomac.android`, minSdk 26, target/compile 35, Compose BOM, Material3, `zxing-android-embedded` for QR scan.
- `androidMain` actuals/impls: `KeyValueStore` via EncryptedSharedPreferences; `LocalClipboard` via `ClipboardManager` (auto-send only when foregrounded — document CLIP-005 manual paths); mDNS advertise/discover via `NsdManager` (service type `_opentomac._tcp`).
- Foreground `ConnectionService` (type `connectedDevice`) hosting `SessionManager`; POST_NOTIFICATIONS permission flow.
- UI screens: Dashboard (connection state card, quick actions, paired devices), Pair (scan QR → verification code confirm), Send-clipboard button, file receive notification. Share-sheet target (`ACTION_SEND`/`SEND_MULTIPLE`) staging files into a `TransferJob`. `NotificationListenerService` gated behind its system permission screen; MediaStore-backed `MediaSource` for the browser; Quick Settings tile for clipboard send.
- **Verify:** `./gradlew :android:assembleDebug` compiles; shared engines already unit-tested. No emulator run required.

Split into two commits if large: scaffold+pairing, then features. Commit(s) `feat(android): ...`.

## Task 11: KMP framework + macOS app scaffold (XcodeGen)

**Files:** `macos/project.yml`, `macos/Sources/OpentomacApp.swift`, `macos/Sources/**`, `shared` framework config, `macos/Makefile` (or `scripts/build-macos.sh`).

**Spec:**
- `./gradlew :shared:linkDebugFrameworkMacosArm64` produces `OpentomacShared.framework`; XcodeGen project references it (FRAMEWORK_SEARCH_PATHS to shared build dir + embed).
- `project.yml`: app target `Opentomac`, deployment macOS 14+, no sandbox (direct-distribution per design), entitlements empty; Info.plist with `NSLocalNetworkUsageDescription` and `NSBonjourServices: _opentomac._tcp`.
- SwiftUI: `MenuBarExtra` + main `WindowGroup`; app boots the KMP `SessionManager` via the framework.
- Build script: gradle link → xcodegen → `xcodebuild -project Opentomac.xcodeproj -scheme Opentomac -configuration Debug build`.
- **Verify:** clean build succeeds; app launches (`open` the built .app) and menu bar item appears.

Commit `feat(macos): SwiftUI app scaffold linking KMP framework`.

## Task 12: macOS features

**Files:** `macos/Sources/Pairing/**`, `Clipboard/**`, `Files/**`, `Photos/**`, `Notifications/**`, `Diagnostics/**`

**Spec:**
- Pairing window: host mode — generate `PairingPayload` from KMP, render QR via CoreImage `CIQRCodeGenerator`, show 6-digit code confirm dialog. Keychain-backed `KeyValueStore` actual (generic password items).
- Bonjour advertise/browse via `NWListener`/`NWBrowser` bridged into the KMP transport factory (or advertise from Swift and pass endpoints down — pick simpler).
- Clipboard: poll `NSPasteboard.general.changeCount` every 0.5 s; respect `org.nspasteboard.ConcealedType`; apply remote items.
- Files: drop target on window + menu bar icon → `TransferJob`; receive into `~/Downloads`; progress UI with cancel; duplicate-policy dialog.
- Photos: `LazyVGrid` thumbnail grid with paging + multi-select → import via transfer engine.
- Notifications: `UNUserNotificationCenter` banners with reply text field (`UNTextInputNotificationAction`), userInfo carries notification key; reply routes `NotificationAction` back. Per-app filter list UI pushing `FilterUpdate`.
- Diagnostics screen: state, transport, protocol version, peer capabilities, connection test button, redacted exportable log (SEC-007).
- **Verify:** `xcodebuild` clean build; launch app; pairing window shows a QR; unit-test Swift-side pure logic only where cheap.

Commit(s) `feat(macos): pairing UI, clipboard, files, photos, notifications, diagnostics`.

## Task 13: Onboarding, docs, final verification

**Files:** `README.md`, onboarding checklist UI on both apps, `docs/plans/...design.md` update (crypto note), final sweep.

**Spec:** README: what it is, clean-room statement, build instructions for both apps (exact commands from Tasks 10-11), feature matrix, license (MIT or Apache-2.0 — pick Apache-2.0 for patent grant). Post-pairing checklist screen (connection path, enabled features, missing permissions — spec §5.1). Full verification: `./gradlew :shared:jvmTest :shared:compileKotlinMacosArm64 :android:assembleDebug` green and macOS `xcodebuild` green. Update design doc's transport section to record the Noise-style channel decision.

Commit `docs: README, onboarding, design doc update`.

---

## Honest verification limits (report these to the user at the end)

- Shared core: fully unit/integration tested on JVM including real TCP on localhost.
- Android app: verified by compilation only (no device/emulator in this environment).
- macOS app: verified by build + launch; cross-device end-to-end pairing needs a real Android phone.
