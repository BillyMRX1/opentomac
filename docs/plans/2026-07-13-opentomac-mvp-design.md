# opentomac MVP design

Date: 2026-07-13
Status: Validated in brainstorm, ready for implementation planning
Requirements source: `docs/App Reverse Engineering Requirements.md` (summary), `docs/App Reverse Engineering Requirements (extracted).md` (full spec with requirement IDs), `docs/LinkMyMac Product Scrape.json` (normalized public scrape)

## What we are building

An open-source, local-first Android-to-Mac continuity app, functionally similar to LinkMyMac/LinkMyDroid, built clean-room from public-surface requirements only. No account, no cloud relay: devices pair once by QR and talk directly over the local network.

## Decisions made

| Decision | Choice | Rationale |
| --- | --- | --- |
| Scope | Spec's recommended MVP | QR pairing, Wi-Fi transport, clipboard (text/URL), file transfer, photos browser, notification mirroring, diagnostics. Defers USB, SMS/calls, mirroring, virtual camera/mic, and LinkMyDrop behind feasibility/policy spikes. |
| Distribution | Open source, direct install | Mac app notarized outside the App Store; Android via GitHub/F-Droid and optionally Play. Relaxes App Sandbox and store-review constraints. |
| Stack | Kotlin Multiplatform core | Protocol, crypto, pairing/trust, and transfer logic written once in `shared/`, tested once. |
| Mac UI | SwiftUI shell | KMP compiles to a native macOS framework; the app is Swift/SwiftUI for best access to menu bar extras, UserNotifications, NSPasteboard, Keychain, and Finder drag-and-drop. |
| Transport | mDNS discovery + mTLS 1.3 over TCP | `NsdManager` on Android, `Network.framework` on Mac. Length-prefixed protobuf messages with a channel ID. A second TCP connection carries bulk file transfer so it never starves control traffic (NET-002). |

## Repository layout

```
opentomac/
├── shared/          # KMP module (Kotlin) — targets Android + macOS framework
│   ├── protocol/    #   versioned protobuf messages, framing, channel mux
│   ├── crypto/      #   pairing handshake, key management interface, session encryption
│   ├── session/     #   trust store logic, reconnect state machine, heartbeats
│   └── features/    #   clipboard sync engine, transfer engine (chunking/hash/resume)
├── android/         # Jetpack Compose UI, foreground service, Keystore impl,
│                    #   NotificationListenerService, share target, QS tile
├── macos/           # SwiftUI app: menu bar extra + main window, Keychain impl,
│                    #   NSPasteboard watcher, UserNotifications, Finder drag/drop
└── docs/            # requirements + design docs
```

The `shared` module owns everything that must behave identically on both ends: wire protocol, pairing/trust rules, loop prevention, transfer state machine. Platform code stays thin and implements expect/interface boundaries for key storage, clipboard access, and notification presentation.

## Pairing and trust (PAIR-001..007, SEC-001..003, SEC-010)

- Each device generates a long-term Ed25519 keypair on first launch. Private keys live in Android Keystore / macOS Keychain, non-exportable where supported. Device ID is the public-key fingerprint.
- QR pairing (Mac displays, Android scans): the QR carries protocol version, the Mac's public key, address candidates, and a single-use 128-bit pairing token that expires in 2 minutes and is consumed on first use (PAIR-001, SEC-002).
- The handshake binds the token to both device public keys. Both screens then show the peer name and a 6-digit verification code derived from both public keys and the session transcript; the user confirms on both sides before trust persists (PAIR-002, defeats LAN MITM).
- Manual pairing is the same token plus address typed by hand (PAIR-003).
- Reconnection is pinned-key mTLS only: no tokens, no prompts. Revoking a device deletes its record and kills live sessions immediately; revoked credentials can never reconnect (PAIR-004, SEC-010).
- The reconnect state machine uses exponential backoff with a jitter cap and single-flight connection attempts so duplicate sessions never occur (PAIR-007).

## Clipboard engine (CLIP-001..006)

- Items are `(contentHash, originDeviceId, sequenceNumber, type, payload)`.
- Loop prevention: applying a remote item records its hash; the local watcher ignores the next matching change, and origin ID plus sequence number break residual echo (CLIP-004).
- Mac watches `NSPasteboard.changeCount` by polling at roughly 0.5 s. Text and URL only in MVP.
- Android cannot read the clipboard in the background (Android 10+), so automatic send works only while foregrounded. Manual paths per CLIP-005: send button, text-selection action, Quick Settings tile. Receiving works anytime via the foreground service.
- Sensitive content: respect `ClipDescription.EXTRA_IS_SENSITIVE` and `org.nspasteboard.ConcealedType`, skip those items, and provide a quick pause toggle (CLIP-006).

## File transfer engine (FILE-001..008)

- A `TransferJob` runs over the dedicated bulk connection: 1 MiB chunks, SHA-256 running hash per file. The receiver writes to a `.part` temp file and renames only after the whole-file hash verifies, so nothing is ever falsely complete (FILE-006).
- Progress, throughput, cancel, and duplicate policy (Ask/Replace/Keep Both/Skip, default Ask) live in the shared engine (FILE-004, FILE-005).
- Resume restarts at the last verified chunk offset (FILE-008).
- Destinations: Mac receives into `~/Downloads`; Android into `Downloads/opentomac/` via MediaStore (FILE-007).
- Entry points: Android share-sheet target; Mac drag-and-drop onto the window or menu bar icon (FILE-002).

## Photos browser (FILE-003)

- Android exposes a paged `ListMedia(bucket, page)` API over the control channel, backed by MediaStore plus user-granted SAF folders. No broad legacy storage permission.
- Entries carry metadata and a lazily fetched downscaled JPEG thumbnail, cached Mac-side by content hash.
- The Mac grid supports multi-select; import creates a normal `TransferJob`, reusing all integrity/progress/cancel machinery.

## Notification mirroring (NOTIF-001..006)

- Source: Android `NotificationListenerService`. Each eligible notification ships key, package, app name, title, body, timestamp, rasterized icon, and actions with RemoteInput reply capability flagged.
- Mac presents native banners via `UNUserNotificationCenter`, carrying the Android notification key in userInfo so replies and action taps route to exactly the right notification instance (NOTIF-002, NOTIF-004). Misrouted replies across conversations in one app is the top test target.
- Per-app allow/deny filters are maintained on the Mac but evaluated on the Android side, so denied content never leaves the phone (NOTIF-003). Global pause and quiet mode included.
- The agent's own foreground-service notification and reconnect chatter are never mirrored; phone-side dismissal withdraws the matching Mac banner (NOTIF-005).
- Known platform limit, accepted for MVP: macOS collapses multiple custom actions into a dropdown; inline reply works.

## Diagnostics and security posture (SEC-005..008)

- No telemetry at all. Diagnostics are a local ring-buffer log with message/notification content redacted at the call site (SEC-007), viewable in-app and exportable only after user preview.
- Diagnostics screen: connection state, transport, negotiated protocol version, peer capabilities, connection test.
- Every error surfaces specific remediation (for example "multicast blocked: enter IP manually"), never a generic failure.
- Rate limiting on pairing attempts and incoming transfer requests in the shared session layer (SEC-008).

## Testing strategy

- The KMP core gets pure-Kotlin unit tests: protocol codec, pairing state machine (expired token, replayed token, wrong verification code), clipboard loop prevention, transfer engine (corrupt chunk, cancel mid-file, resume, duplicate names).
- An in-memory transport fake enables full two-peer session tests without sockets.
- Platform layers get thin integration tests plus a manual checklist derived from spec section 12 (sleep/wake, hotspot, permission revoked).

## Milestones (each ends usable)

1. **M1 Pair + trust**: QR pairing, verification code, saved devices, revoke, auto-reconnect.
2. **M2 Clipboard**: text/URL sync both ways with loop prevention.
3. **M3 Files**: bidirectional transfer, share sheet and drag-drop, duplicate policy, integrity.
4. **M4 Photos**: browse grid and import.
5. **M5 Notifications**: mirror, filters, reply routing.
6. **M6 Polish**: diagnostics screen, menu bar mode, onboarding checklist.

## Deferred (behind spikes, per spec section 13 phase 0)

USB no-ADB transport, screen mirroring and remote control, SMS/MMS and calls, virtual webcam/microphone, iPad companion, LinkMyDrop.

## Clean-room rules (spec section 15)

No decompilation, no copied names/logos/assets/copy, independently designed protocol and UI, and each requirement traced to a public fact, observed UI behavior, or engineering inference.
