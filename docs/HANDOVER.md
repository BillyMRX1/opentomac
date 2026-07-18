# opentomac handover

Last updated: 2026-07-18
Purpose: everything a fresh session needs to continue this project without re-reading the whole history.

READ NEXT: `docs/FINDINGS.md` holds the latest on-device test results and three open items (photos-grid rate-limit bug with root cause identified, screenshot clipboard bug, notification mirroring verification). Start there; it defines the priority order for the next session.

## What this project is

opentomac is an open-source, local-first Android-to-Mac continuity app (clipboard, files, photos, notifications over local Wi-Fi; no account, no cloud relay). It is a clean-room build from public-surface requirements of LinkMyMac/LinkMyDroid. Key docs, all in `docs/`:

- `App Reverse Engineering Requirements (extracted).md`: the full requirement spec with IDs (PAIR/NET/CLIP/FILE/NOTIF/SEC etc.).
- `plans/2026-07-13-opentomac-mvp-design.md`: the validated design, including two recorded deviations (Noise-XX-style channel instead of mTLS; dynamic KMP framework for libsodium linkage).
- `plans/2026-07-13-opentomac-mvp-implementation.md`: the original 13-task build plan (all 13 done).
- `plans/2026-07-15-feature-roadmap.md`: the forward roadmap (Phases A-F). Phase A is complete.

## Architecture in one paragraph

A Kotlin Multiplatform `shared` module (targets: android, jvm for tests, macosArm64 as a dynamic framework named OpentomacShared) owns everything protocol-critical: kotlinx-serialization ProtoBuf messages with a two-stage envelope, 4 MiB length-prefixed framing, libsodium crypto (Ed25519 identity, Noise-XX-flavored handshake with domain-separated signatures, ChaCha20-Poly1305 sessions with mutex-serialized nonces), pairing/trust store, session manager (reconnect backoff, heartbeats that bypass the CONTROL rate limiter, revocation), ktor TCP transport, and the clipboard/transfer/notification/media engines. The Android app is Jetpack Compose with a foreground ConnectionService and a singleton `AppRuntime` orchestrator. The macOS app is SwiftUI over a Kotlin/Native `MacController` (in `shared/src/macosArm64Main/.../mac/`) that keeps all coroutine/Flow work in Kotlin and exposes plain callbacks to Swift; the Mac runs one persistent TCP listener on port 42420 serving both pairing and sessions.

## Verified working on real devices (user-tested)

- QR pairing with 6-digit verification, trust persistence, reconnect (phone taps Connect; Mac listens).
- Clipboard Mac to phone: automatic, text and images.
- Clipboard phone to Mac: automatic on app-open/foreground (Android forbids background clipboard reads; this is an OS rule, not a bug), manual via Send clipboard button; text and images; large images downscale to fit the 4 MiB frame.
- File transfer both directions with progress UI (Android picker + share sheet; Mac drop zone + Send file button; received lists both sides).
- Photos: Mac browses the phone photo grid with thumbnails.
- Notification mirroring: phone notifications appear as native Mac banners (needs notification access granted on phone via the dashboard button).

## Recent fixes worth knowing (regression traps)

- ClipboardSync records its dedupe hash only AFTER a successful send; `sendNow()` bypasses dedupe for manual sends; all sends serialized by a mutex (receiver drops out-of-order seq, so wire order must match seq order). The Android clipboard send lambda THROWS when not connected (AppRuntime wiring) so failures stay retryable; `safeSend` elsewhere still silently drops by design.
- Android images: bounded stream reads (never unbounded readBytes), downscale to JPEG <= 3 MiB / 2048 px, never fall back to sending a content:// URI as text, and `currentItem()` never throws (a bad provider must not kill the collect loop).
- Mac clipboard reads images only when the pasteboard carries PNG (screenshots, browsers); TIFF-only sources are skipped (Kotlin/Native binding for NSBitmapImageRep.representationUsingType would not resolve; do not chase it again without new info).
- okio + Kotlin/Native trap: `use {}` on okio Closeables needs `import okio.use` (JVM-only checks miss it).
- JUnit4 jvmTest trap: test methods must return Unit (`fun x(): Unit = runBlocking {}`).

## Build and verify (exact commands)

- Shared tests (139 green as of handover): `./gradlew :shared:jvmTest`
- Android APK: `./gradlew :android:assembleDebug` then `adb install -r android/build/outputs/apk/debug/android-debug.apk`
- macOS app: `./scripts/build-macos.sh` (links KMP framework, xcodegen, xcodebuild); app lands in `~/Library/Developer/Xcode/DerivedData/Opentomac-*/Build/Products/Debug/`
- Environment: Java 21, Gradle wrapper 8.13, Kotlin 2.1.20, AGP 8.9.1, ANDROID_HOME=/opt/homebrew/share/android-commandlinetools (sdk.dir in local.properties), Xcode + xcodegen installed. The generated `macos/Opentomac.xcodeproj` is gitignored; regenerate with xcodegen.

## Working style used so far (user preference)

- Autopilot: implement, verify with real builds/tests, commit, report; do not ask permission mid-flow.
- Implementation was largely delegated to Codex CLI (model gpt-5.6-sol) via `node ~/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs task --write --model gpt-5.6-sol --effort high [--fresh|--resume-last] "<prompt>"`, run in background. Codex sandbox blocks localhost sockets and .git writes: the coordinator (Claude) always re-verifies with real Gradle/xcodebuild and makes all commits. `adversarial-review --wait --scope working-tree "<focus>"` is useful as a second-opinion gate and caught real bugs.
- Commits: conventional messages, no AI attribution ever.
- Mac to phone clipboard works perfectly per user: do not destabilize it.

## Known limits and open items

- The test phone is a Samsung on One UI (earlier notes wrongly said Xiaomi/MIUI). One UI screenshots never reach the system clipboard (Samsung Keyboard keeps them in a private store); share-sheet or Gallery Copy are the working screenshot flows.
- Phone-side auto clipboard sync only fires when the app foregrounds (Android OS restriction; user accepted).
- Staged outbox files (cache/outbox on Android) have no deletion lifecycle in TransferEngine; repeated sends/imports accumulate cache until the OS evicts it. Known, shared by all send paths, accepted for now.
- Photo import is fire-and-forget: no fetch ack message; the arriving FileOffer is the feedback. Accepted MVP trade-off (adversarial review suggested a correlated response if this ever bites).
- macOS Mac clipboard TIFF-only image sources not synced (see above).
- Mac side stores keys in Application Support files, not Keychain (documented MVP simplification in MacKeyValueStore).
- Cross-device E2E has been user-tested manually; there is no automated two-app integration test.
- Notification reply routing (Mac banner inline reply to phone) is wired but not yet confirmed end-to-end by the user.

## Suggested next steps (from the roadmap, in order)

1. DONE 2026-07-17: photo import from the Mac grid (media_fetch_request over BULK into the transfer pipeline). Needs device test.
2. Phase B: URL handoff, media remote, contacts browse.
3. Phase C: screen mirroring + remote control (large; MediaProjection + AccessibilityService on Android, VideoToolbox viewer on Mac).
4. Phase D: virtual webcam/mic (prove the macOS system-extension distribution path first).
5. Phase E: SMS/calls (gate on Play policy review). Phase F: iPad + nearby drop.

## Task state

All 13 implementation-plan tasks completed. Roadmap Phases A and B completed. Phase B device-verified by the user on 2026-07-18 (screenshot flow reworked to offer-first and the Mac's redundant open-link button removed on their feedback). Phase C fully implemented 2026-07-18 (see the roadmap doc for design and accepted limits), pending user device test: C1 view-only mirroring, the viewer overhaul (real resizable/fullscreen window, aspect lock, rotation, quality presets), and C2 remote control (tap/swipe/nav/text injection via an accessibility service, gated on an active-mirror generation). Next roadmap items are Phase D (virtual webcam/mic, macOS system-extension path first) and beyond. Device-verified so far: clipboard all types with auto-sync, file transfer + cancel, photos grid + preview + import, notification mirroring + banner reply, screenshot offers, URL handoff phone-to-Mac, media remote, contacts search, quick-settings tile, auto-connect. Still unverified: the fixed text-selection action. Both apps rebuilt and verified after every change.
