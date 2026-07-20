# opentomac handover

Last updated: 2026-07-21
Purpose: everything a fresh session needs to continue this project without re-reading the whole history.

READ FIRST: the "PENDING WORK (start here)" and "Operational gotchas" sections below. All of Phases A-E are built and mostly device-verified; the current work is UI polish. There is ONE approved-but-unbuilt task (the Mac sidebar) plus a couple of loose ends. `docs/FINDINGS.md` is historical now (its three items are all resolved).

## What this project is

opentomac is an open-source, local-first Android-to-Mac continuity app (clipboard, files, photos, notifications over local Wi-Fi; no account, no cloud relay). It is a clean-room build from public-surface requirements of LinkMyMac/LinkMyDroid. Key docs, all in `docs/`:

- `App Reverse Engineering Requirements (extracted).md`: the full requirement spec with IDs (PAIR/NET/CLIP/FILE/NOTIF/SEC etc.).
- `plans/2026-07-13-opentomac-mvp-design.md`: the validated design, including two recorded deviations (Noise-XX-style channel instead of mTLS; dynamic KMP framework for libsodium linkage).
- `plans/2026-07-13-opentomac-mvp-implementation.md`: the original 13-task build plan (all 13 done).
- `plans/2026-07-15-feature-roadmap.md`: the forward roadmap (Phases A-F). Phases A, B, C, E done; D removed; F not started. Read it for per-feature design and accepted limits.

## Architecture in one paragraph

A Kotlin Multiplatform `shared` module (targets: android, jvm for tests, macosArm64 as a dynamic framework named OpentomacShared) owns everything protocol-critical: kotlinx-serialization ProtoBuf messages with a two-stage envelope, 4 MiB length-prefixed framing, libsodium crypto (Ed25519 identity, Noise-XX-flavored handshake with domain-separated signatures, ChaCha20-Poly1305 sessions with mutex-serialized nonces), pairing/trust store, session manager (reconnect backoff, heartbeats that bypass the CONTROL rate limiter, revocation), ktor TCP transport, and the clipboard/transfer/notification/media engines. The Android app is Jetpack Compose with a foreground ConnectionService and a singleton `AppRuntime` orchestrator. The macOS app is SwiftUI over a Kotlin/Native `MacController` (in `shared/src/macosArm64Main/.../mac/`) that keeps all coroutine/Flow work in Kotlin and exposes plain callbacks to Swift; the Mac runs one persistent TCP listener on port 42420 serving both pairing and sessions.

## Verified working on real devices (user-tested, Samsung One UI phone R5GL60KRH3N + Apple Silicon Mac)

- QR pairing with 6-digit verification, trust persistence, reconnect. Pairing now uses a LIVE inline camera (embedded ZXing BarcodeView) that auto-scans in portrait, no button, no separate activity.
- Clipboard both directions (text/URL/images); phone-to-Mac fires on app foreground (Android OS rule) or manual button.
- File transfer both directions with progress + Cancel; received lists on both sides.
- Photos grid on Mac with click-to-preview and original import.
- Notification mirroring as native Mac banners + inline reply back to the phone (banner reply confirmed working).
- Screenshot offers (One UI has no screenshot-on-clipboard, so the phone announces new screenshots and the Mac shows a Send-to-Mac notification that pulls the original).
- URL handoff phone-to-Mac (share a link to the app, it opens in the Mac browser).
- Media remote (Mac now-playing card controls phone playback).
- Contacts search, SMS browse/reply (send guarded by an on-phone Send-SMS confirmation), recent-calls list.
- Screen mirroring both directions with the resizable/fullscreen viewer window, quality presets, and remote control (click/scroll/type via an accessibility service). Phone-initiated "Mirror to Mac" now auto-opens the Mac viewer (fixed 2026-07-21).
- Quick-settings tile, auto-connect, Android back-button navigation (fixed 2026-07-21).
- Still UNVERIFIED by the user: the fixed text-selection send action.

## Recent fixes worth knowing (regression traps)

- ClipboardSync records its dedupe hash only AFTER a successful send; `sendNow()` bypasses dedupe for manual sends; all sends serialized by a mutex (receiver drops out-of-order seq, so wire order must match seq order). The Android clipboard send lambda THROWS when not connected (AppRuntime wiring) so failures stay retryable; `safeSend` elsewhere still silently drops by design.
- Android images: bounded stream reads (never unbounded readBytes), downscale to JPEG <= 3 MiB / 2048 px, never fall back to sending a content:// URI as text, and `currentItem()` never throws (a bad provider must not kill the collect loop).
- Mac clipboard reads images only when the pasteboard carries PNG (screenshots, browsers); TIFF-only sources are skipped (Kotlin/Native binding for NSBitmapImageRep.representationUsingType would not resolve; do not chase it again without new info).
- okio + Kotlin/Native trap: `use {}` on okio Closeables needs `import okio.use` (JVM-only checks miss it).
- JUnit4 jvmTest trap: test methods must return Unit (`fun x(): Unit = runBlocking {}`).

## Build and verify (exact commands)

- Shared tests (~188, all green): `./gradlew :shared:jvmTest` (five TcpTransportTest cases need real localhost sockets, so Codex's sandbox can't run them; the coordinator can).
- Android APK: `./gradlew :android:assembleDebug` then `adb install -r android/build/outputs/apk/debug/android-debug.apk`
- macOS app: `./scripts/build-macos.sh` (links KMP framework, xcodegen, xcodebuild); app lands in `~/Library/Developer/Xcode/DerivedData/Opentomac-*/Build/Products/Debug/`
- Environment: Java 21, Gradle wrapper 8.13, Kotlin 2.1.20, AGP 8.9.1, ANDROID_HOME=/opt/homebrew/share/android-commandlinetools, Xcode + xcodegen installed. `macos/Opentomac.xcodeproj` is gitignored; regenerate with xcodegen (build-macos.sh does this). `rsvg-convert` (brew librsvg) is installed for SVG->PNG icon rasterization.

## Working style (user preference) and delegation

- Autopilot: implement, verify with real builds/tests, commit, report; do not ask permission mid-flow. Ask only for genuine scope changes/destructive actions.
- Implementation is delegated to Codex CLI (model gpt-5.6-sol) via `node ~/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs task --write --model gpt-5.6-sol --effort high [--fresh|--resume-last] "<prompt>"`, run in background. `adversarial-review --wait --scope working-tree "<focus>"` is a valued second-opinion gate that has caught real bugs. Codex's sandbox blocks localhost sockets, .git writes, Gradle, and SVG rasterization: the coordinator (Claude) always re-verifies with real Gradle/xcodebuild, generates icons, and makes all commits.
- CODEX USAGE LIMIT: as of 2026-07-21 Codex hit its usage cap and resets around 2026-07-25. If Codex returns "You've hit your usage limit", either wait for reset or implement directly (Claude has full write access).
- CODEX PROMPT GOTCHA: pass long prompts via a file, not inline. Backticks, `${...}`, and `{}` in an inline shell arg cause `zsh parse error`. Write the prompt to a scratchpad file, then `PROMPT="$(cat file)"; node ...companion.mjs task ... "$PROMPT"`.
- Commits: conventional messages, no AI attribution ever. Mac-to-phone clipboard is "perfect" per user: do not destabilize it.

## Operational gotchas (these have each bitten us; read before touching)

- RELAUNCH THE MAC APP WITH `open <app>`, NEVER by running the binary directly. Running `.../Opentomac.app/Contents/MacOS/Opentomac` from a terminal starts the app WITHOUT its TCP listener, so the phone gets "connection refused" on pair/connect. `open` starts it correctly. Confirm with `lsof -nP -iTCP:42420 | grep LISTEN`.
- ANDROID INSTALL SIGNATURE MISMATCH: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (a stale copy was installed under a secondary user / Samsung Secure Folder with a different key). Fix: `adb uninstall dev.opentomac.android` then `adb install ...apk`. The uninstall wipes the phone's trust store, so the user must RE-PAIR afterward. Reinstalls with the standard debug key over a standard-key install do NOT need this.
- MAC ICON CACHE: after changing the app icon, macOS keeps showing the old/blank one. Force it: `lsregister -f <app>` (path: `/System/Library/Frameworks/CoreServices.framework/Versions/A/Frameworks/LaunchServices.framework/Versions/A/Support/lsregister`) then `killall Dock`, then relaunch via `open`.
- The phone (R5GL60KRH3N) drops off adb frequently; installs often need to wait for it to reconnect (`adb devices`).

## UI / design system (redesigned 2026-07-18..21)

- Mac: Liquid Glass. Central tokens in `macos/Sources/DesignTokens.swift` (accent #0071e3 Apple blue, SF Pro scale, glass card/background modifiers, radii/spacing/motion). Screens: DashboardView (glass tool grid + cards), PhotosView, ContactsView, MessagesView, CallsView, MirrorWindow, MenuBarView, PairingSheet. Dark + light.
- Android: Material 3 Expressive. Theme in `android/src/main/kotlin/dev/opentomac/android/ui/theme/` (Color/Theme/Type; dynamic color on 12+, accent-seeded fallback). Single dashboard + pairing screen (deliberately single-screen: the phone is a status/control surface; browse features live on the Mac).
- App icons: generated with `rsvg-convert` from `design-prototype/assets/opentomac-icon.svg` (blue glass two-device "bridge" mark). Mac: `macos/Assets.xcassets/AppIcon.appiconset` wired via `ASSETCATALOG_COMPILER_APPICON_NAME` in project.yml. Android: adaptive icon (`res/mipmap-*` foreground/background PNGs + `mipmap-anydpi-v26/ic_launcher*.xml`) + legacy density PNGs.
- `design-prototype/` is the opendesign HTML/CSS mockup source. It is GITIGNORED and must NEVER be committed or modified. Reference only. Confirm with `git check-ignore design-prototype` before any commit; every UI commit this session had 0 prototype files.

## Known limits and open items

- The test phone is a Samsung on One UI (earlier notes wrongly said Xiaomi/MIUI). One UI screenshots never reach the system clipboard (Samsung Keyboard keeps them in a private store); share-sheet or Gallery Copy are the working screenshot flows.
- Phone-side auto clipboard sync only fires when the app foregrounds (Android OS restriction; user accepted).
- Staged outbox files (cache/outbox on Android) have no deletion lifecycle in TransferEngine; repeated sends/imports accumulate cache until the OS evicts it. Known, shared by all send paths, accepted for now.
- Photo import is fire-and-forget: no fetch ack message; the arriving FileOffer is the feedback. Accepted MVP trade-off (adversarial review suggested a correlated response if this ever bites).
- macOS Mac clipboard TIFF-only image sources not synced (see above).
- Mac side stores keys in Application Support files, not Keychain (documented MVP simplification in MacKeyValueStore).
- Cross-device E2E has been user-tested manually; there is no automated two-app integration test.
- Notification reply routing (Mac banner inline reply to phone) is wired but not yet confirmed end-to-end by the user.

## PENDING WORK (start here)

1. MAC SIDEBAR NAVIGATION — approved by the user, NOT YET BUILT (Codex hit its usage limit before it could run). Convert the Mac app from "dashboard + modal sheets" to a `NavigationSplitView` with a left sidebar: Dashboard, Photos, Contacts, Messages, Calls as real detail pages (not `.sheet` modals). Mirror stays its own separate Window; Pairing can stay a sheet (transient). MUST preserve: all AppModel/MacController wiring, the Liquid Glass DesignTokens styling, the `model.mirrorPresentationTick` onChange that opens the mirror window (attach it to the split-view root or Dashboard page), and each browse view's behavior (Photos loadPhotos-on-appear, Contacts/Messages/Calls granted-state hints, PII-clear-on-disappear). Extract the inner content of PhotosView/ContactsView/MessagesView/CallsView (drop their Close button + isPresented binding) so it renders as a detail page. A ready-to-use Codex prompt is saved at `/private/tmp/.../scratchpad/sidebar-prompt.txt` from the 2026-07-21 session (or rewrite it). Verify with `scripts/build-macos.sh`. This is a VIEW-LAYER restructure only.
2. INSTALL the Android APK carrying the back-button + mirror fixes onto the phone once it is on USB (see the signature-mismatch gotcha).
3. Small polish backlog (user aware, not yet scheduled): single-instance guard for the Mac app (a stale instance holding port 42420 caused a "connection refused" pairing failure once), Mac Keychain for key storage (currently plain files), higher-resolution photo previews (preview reuses the 384px grid thumbnail), automated two-device integration test.
4. Phase F (only if the user asks; they said hold it): iPad companion, iPhone-to-Android nearby drop.

## Known limits and accepted trade-offs

- One UI screenshots never reach the system clipboard (Samsung Keyboard private store); the screenshot-offer feature works around this. Share-sheet / Gallery Copy are the other working screenshot flows.
- Phone-side auto clipboard sync only fires when the app foregrounds (Android OS restriction; accepted).
- Staged outbox files (Android cache/outbox) have no per-transfer deletion lifecycle; a 24h startup GC cleans orphans. Accepted.
- SMS send is guarded by an on-phone confirmation (recipient + body), multipart-aware, op-id idempotent, timeout surfaced as unknown (not resent). Contacts/SMS/calls results clear on the Mac when their sheet/page closes (PII).
- Mac stores keys in Application Support files, not Keychain (MVP simplification in MacKeyValueStore).
- Mac clipboard TIFF-only image sources not synced (Kotlin/Native NSBitmapImageRep binding wouldn't resolve; don't rechase without new info).
- Mirror MVP: fire-and-forget start/stop, flush-only decode-layer recovery.

## Task state (2026-07-21)

Roadmap Phases A, B, C, E are COMPLETE and mostly device-verified. Phase D (phone-as-webcam/mic) was DROPPED and fully REMOVED by the user (persistent CameraX/encoder corruption + the virtual camera needed a paid Apple Developer signed build). DO NOT reintroduce camera capture, its protocol messages, services, permissions, CameraX deps, or the Mac preview. Screen mirroring (Phase C) still owns the VIDEO channel and its `VideoConfig`/`VideoFrame` messages — those are mirror's, not camera's.

UI was fully redesigned (Mac Liquid Glass, Android Material 3 Expressive) and both apps got the new app icon. Android UI was made "honest" (fake QR viewfinder replaced by a real inline auto-scanning camera; inert top-right button removed). Two bugs fixed 2026-07-21: phone-initiated mirroring now auto-opens the Mac viewer (`onVideoConfig` no longer guards on `mirrorActive`; sets `mirrorPresentationTick`), and the Android system back button now navigates PAIR->DASHBOARD instead of closing the app (BackHandler). Latest commit: `8924754`.

The ONE outstanding build task is the Mac sidebar (item 1 above). Everything else is polish or Phase F.
