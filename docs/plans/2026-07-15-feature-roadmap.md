# opentomac feature roadmap

Date: 2026-07-15
Status: Planning
Context: The shared core (protocol, crypto, pairing, session, TCP, clipboard, file transfer, notification and media engines) is complete and tested. Clipboard is fully wired and working across both apps. This roadmap covers everything else: finishing the features that are built but not surfaced, then the features deliberately deferred from the MVP.

## Where each feature stands today

| Feature | Engine | Android UI | macOS UI | Gap to usable |
| --- | --- | --- | --- | --- |
| Clipboard (text/URL/image) | Done | Done | Done | None, shipping |
| File transfer | Done | Send via OS share sheet only; no button; no received list | Send via drop zone; no received list | Add in-app send + received view on both |
| Notification mirroring | Done | Reads notifications (needs permission granted) | Shows as transient text | Permission onboarding + a real notifications panel with reply |
| Photos browser | Done | Exposes media as agent | No UI | Build the Mac photos grid and import |
| Screen mirroring + control | Not built | Not built | Not built | Full feature |
| Virtual webcam + microphone | Not built | Not built | Not built | Full feature |
| SMS/MMS, calls, contacts | Not built | Not built | Not built | Full feature, policy-gated |
| Media remote, URL handoff | Not built | Not built | Not built | Full feature |
| iPad companion, LinkMyDrop | Not built | Not built | Not built | Full feature |

## Phase A: finish the MVP features that are already built

Highest value for lowest effort. These make the work already in the codebase actually usable.

### A1. File transfer UI (both apps)
- Android: add a "Send file" action on the dashboard using the system document picker (`ACTION_OPEN_DOCUMENT`, multi-select), routing selected URIs through the existing `AppRuntime.enqueueSharedUris`. Add a received-files section that lists items landing in the receive directory, with a tap-to-open action.
- macOS: keep the drop zone; add a received-files list bound to the transfer engine's completed jobs, with reveal-in-Finder.
- Both: surface transfer progress (the engine already exposes a `StateFlow<TransferProgress>`), so the user sees percentage, throughput, and cancel.
- Effort: about 1 to 2 days. No engine changes.

### A2. Notification mirroring, made real
- Android: add a first-run step that deep-links the user to grant notification access (the `NotificationListenerService` is dead without it), plus a per-app filter screen writing `FilterUpdate`.
- macOS: replace the transient status text with real `UNUserNotificationCenter` banners, carrying the notification key so an inline reply routes back to the phone (the engine already supports this).
- Effort: about 2 to 3 days. No engine changes.

### A3. Photos browser on macOS
- macOS: build a `LazyVGrid` thumbnail browser driven by the shared `MediaCompanionBrowser` (paging and thumbnail cache already exist), with multi-select and an Import action that hands selection to the transfer engine.
- Android: confirm the `MediaSource` returns thumbnails for the modern photo-permission model.
- Effort: about 2 to 3 days. No engine changes.

Exit criteria for Phase A: a user can send and receive files from either device with visible progress, see phone notifications on the Mac and reply to them, and browse and import phone photos on the Mac.

## Phase B: lightweight continuity additions — COMPLETE (2026-07-18)

All implemented, tested (unit + build), committed; pending user device test.

### B0 (added). Auto-send screenshots — DONE
- One UI never puts screenshots on the system clipboard, so a MediaStore observer watches the Screenshots bucket while connected and pushes new ones through the transfer pipeline. Dashboard switch, off by default; history is never re-sent.

### B1. URL handoff (AUX-001) — DONE
- open_url message. Phone: share an http(s) link to opentomac and it opens on the Mac. Mac: "Open copied link on phone" dashboard button; Android opens directly when foregrounded, else via a tappable notification (background activity-start restriction). Both receivers validate the scheme.

### B2. Media remote (AUX-002, F11) — DONE
- media_now_playing / media_control on EVENT (CONTROL rate limit would drop volume presses). MediaSessionManager bridge reuses the notification-listener component; Mac dashboard now-playing card with transport and volume controls.

### B3. Contacts browse (MSG-003, F09) — DONE
- contacts_search_request/response on BULK; shared agent/companion with single-flight + timeout; Android ContactsContract source behind READ_CONTACTS with LIKE-escaped, length-bounded queries; Mac Contacts sheet with copyable values that clear on close.

Also added in this phase: transfer cancel buttons on both apps (wired the existing TransferEngine.cancel).

## Phase C: screen mirroring and remote control (MIR-001..007, F06/F07)

The first marquee feature and a large one.
- Android: `MediaProjection` capture (explicit system consent), hardware H.264/HEVC encode, and an `AccessibilityService` for injecting taps, scrolls, Back and Home (prominent-disclosure policy required).
- macOS: hardware decode via VideoToolbox, a low-latency viewer window, and pointer and keyboard input relayed as normalized coordinates.
- New shared services: a video channel with keyframe and congestion handling, and an input RPC channel.
- Targets: under 150 ms interactive latency on a healthy LAN, adaptive resolution and frame rate.
- Effort: about 3 to 5 weeks. Risks: latency tuning, per-OEM `AccessibilityService` behavior, Play Store accessibility-use disclosure.

## Phase D: virtual webcam and microphone (CAM-001..005, F12/F13)

Use the phone as a Mac camera and mic.
- Android: `CameraX` and `AudioRecord` capture and stream.
- macOS: a Core Media I/O camera extension and a virtual audio input device, both of which are separate signed system extensions with their own distribution and approval path.
- Effort: about 3 to 5 weeks. Risk: the macOS system-extension distribution and notarization path is the hard part; prove a signed, installable extension early before building the rest.

## Phase E: messaging and calls (MSG-001..005, F05/F08), policy-gated

- SMS/MMS thread visibility and bounded quick-reply, incoming-call surface with answer/decline where the OS permits.
- Requires Google Play policy review for the restricted SMS and call-log permissions before any schedule commitment, and a capability matrix so actions appear only when the specific device supports them.
- Effort: about 2 to 3 weeks of build plus review risk. Do the policy review first; if it fails, this phase does not ship and the app degrades gracefully without it.

## Phase F: second surfaces (F14/F15)

- iPad companion: a tablet-first subset (clipboard, files, photos, notifications) reusing the shared module behind an iPad SwiftUI shell.
- LinkMyDrop-style iPhone-to-Android nearby transfer: QR/NFC/manual bootstrap, incoming approval, share extension.
- Effort: about 3 to 5 weeks combined. Lower priority than the phone-to-Mac core.

## Suggested order

1. Phase A (finish what exists) — do this next, it is the best value.
2. Phase B (cheap wins) — URL handoff and media remote are crowd-pleasers.
3. Phase C (mirroring) — the headline feature, once the basics are solid.
4. Phases D, E, F — each is a project in its own right; sequence by what matters most to you and gate E on policy review.

## Clean-room reminder

Every phase stays within the clean-room boundary: independently designed protocol and UI, no copied assets or names, platform APIs and public documentation only.
