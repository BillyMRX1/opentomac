CLEAN-ROOM REVERSEENGINEERING REQUIREMENTS

A public-surface specification for an Android-to-Mac/iPad continuity app

Reference product: LinkMyMac / LinkMyDroid / LinkMyDrop

Public sources accessed 2026-07-12 | Baseline observed: version 1.54

PurposeDefine the user-visible behavior, system responsibilities, platform integrations, security controls, testable acceptance criteria, and delivery sequence needed to build a functionally similar product through independent implementation.

Clean-room boundaryThis document does not reproduce proprietary source code, private APIs, branding, icons, screenshots, copy, cryptographic material, or non-public protocols. It is based on public product pages, official store listings, release notes, and store screenshots.

Prepared as an engineering discovery artifact, not a legal opinion.

# 0. Document controls

Field

Value

Status

Draft baseline for discovery and estimation

Scope

Public-surface functional parity; Android, macOS, iPadOS, and optional iPhone-Android drop flow

Baseline

Public version 1.54

Research mode

Black-box / clean-room; no binary decompilation or traffic interception

Volatile facts

Pricing, store rankings, version dates, OS minimums and feature availability can change

Evidence labels

Confirmed = explicit source; Observed = official screenshot/listing; Inferred = proposed architecture to validate

# 1. Executive summary

The reference product is a local-first device-continuity system composed of an Android agent (LinkMyMac), a macOS/iPadOS companion (LinkMyDroid), and a separate nearby iPhone-Android transfer flow (LinkMyDrop). Its public value proposition is to make an Android device behave like a native companion to Mac and iPad without a required account or cloud content relay.

- Core experience: pair once by QR, reconnect automatically, then use clipboard, files, photos, notifications, messages, contacts, calls and media from the companion device.

- High-complexity capabilities: low-latency screen mirroring and control, virtual webcam, virtual microphone, reliable USB transport and platform-restricted SMS/call actions.

- Commercial model observed: one-time purchase on the Apple side; a regional Indonesian App Store page showed Rp 249 thousand. Treat price as volatile.

- Recommended MVP: secure pairing, local Wi-Fi transport, clipboard, file transfer, photos, notification mirroring and basic device management. Defer virtual devices and call/SMS parity until platform-policy validation.

- Indicative delivery: 2-3 engineers can target a narrow core MVP in roughly 4-6 months; broad cross-platform parity is more realistically a 4-6 engineer, 9-14 month program. These are planning estimates, not scraped facts.

# 2. Public scrape findings

The extracted public facts below are normalized from the official website and store listings. The companion JSON file contains the same material in machine-readable form.

Area

Public finding

Confidence

Product topology

Android agent; Mac/iPad companion; separate iPhone-Android nearby drop workflow

Confirmed

Connection

QR pairing; local Wi-Fi and USB; auto reconnect; saved devices; manual fallback appears in release history

Confirmed / observed

Privacy

No required account; direct device-to-device transport; no cloud content relay; stores declare no data collection

Confirmed

Current version

1.54 with clipboard history, USB transfer polish, mirroring input improvements, notification cleanup and reconnect fixes

Confirmed

Apple requirements

macOS 15.5+, iPadOS 17.0+, 6.5 MB, English plus five languages

Observed

Android traction

5K+ downloads on the observed Google Play listing

Observed

Android remote control

AccessibilityService disclosed solely for user-initiated touch/scroll/navigation

Confirmed

Background reliability

Users are instructed to set Android battery mode to Unrestricted

Confirmed

# 3. Product scope and actors

Actor / component

Responsibilities

Android user

Grants permissions, pairs devices, selects content, approves screen capture and remote-control access.

Mac user

Operates menu-bar/full-window companion, browses phone data, receives alerts, transfers files and controls mirror sessions.

iPad user

Uses a tablet-first subset: clipboard, files, photos, messages, notifications, contacts and peer-to-peer transfer.

iPhone/Android drop user

Creates or joins a nearby transfer, approves the peer, sends supported items, saves or exports received content.

Android agent

Collects permitted data, executes supported actions, streams media, exposes storage and maintains local sessions.

Apple companion

Discovers/pairs, presents native UI, routes user actions, stores trusted-device metadata and renders/receives media.

# 4. Feature parity matrix

Capability

Android

macOS

iPadOS

iPhone drop

Pairing / saved devices

Host agent

Primary companion

Companion

Invite/join

Clipboard

Read/send/history controls

Read/send/history UI

Read/send UI

Text item transfer

File transfer

Send/receive/share intent

Browse/drag-drop/send/receive

Send/receive

Send/receive/share extension

Photos/storage

Expose media and folders

Grid/browser and transfer

Grid/browser and transfer

Photo/file drop

Notifications

Notification source/actions

Native mirror/reply

Mirror/actions where supported

Not in drop scope

SMS/MMS

Data source and permitted actions

Thread visibility/reply subset

Thread visibility/reply subset

Not in drop scope

Mirroring/control

Capture and execute gestures

Decode/display/input

Not publicly confirmed

Not in drop scope

Calls/contacts

Source and permitted actions

Call surface/contact browser

Contact browser; call scope unclear

Profile details only

Webcam/mic

Capture and stream

Virtual system devices

Not publicly confirmed

Not in drop scope

Media remote/app launch

Expose sessions and launch apps

Control UI

Not publicly confirmed

Not in drop scope

# 5. User experience and state model

## 5.1 First-run onboarding

- Explain that the system is local-first and requires apps on both devices.

- Ask for permissions progressively by feature, not as an undifferentiated wall.

- Display battery-reliability guidance and deep-link to the Android app battery settings.

- On Mac/iPad, show a QR pairing code and a manual pairing option.

- On Android, scan the QR, verify device names and require explicit trust confirmation.

- Persist trusted device credentials in Android Keystore and Apple Keychain.

- Show a post-pairing checklist with connection path, enabled features and missing permissions.

## 5.2 Connection states

State

Required behavior

Unpaired

Show install/pair guidance; no protected data is exposed.

Pairing

Single-use token; visible peer identity; cancel and timeout.

Paired / offline

Saved-device card; last seen; connect action; revoke option.

Connecting

Show selected transport and a bounded timeout.

Connected Wi-Fi

Enable all supported local features; show network quality/latency when useful.

Connected USB

Prefer bulk transfer and latency-sensitive streams; provide fallback if cable path fails.

Permission degraded

Keep connection alive but disable affected features with remediation guidance.

Sleeping / background

Attempt controlled reconnect without notification spam or duplicate-host churn.

Version incompatible

Block unsafe protocol use and instruct both sides to update.

## 5.3 Navigation and visual structure

- Android dashboard: connection status, three or more high-frequency actions, and paired-device cards. Official store screenshots show a dark dashboard with quick actions and device status.

- macOS: native sidebar or menu-bar-only mode with feature destinations such as Clipboard, Files, Photos, Messages, Notifications, Mirror and Settings.

- Messages: thread list plus conversation detail. Photos: dense thumbnail grid. Files: folder browser with drag/drop. Mirror: dedicated low-latency viewing window.

- Do not copy the reference product name, logo, exact icons, marketing copy or visual assets. Build a distinct design system.

# 6. Functional requirements

## 6.1 Pairing, trust and device management

ID

Requirement

Priority

Acceptance / notes

PAIR-001

Generate a scannable QR containing a single-use pairing invitation, companion identity and protocol version.

Must

Invitation expires within 2 minutes and cannot be replayed.

PAIR-002

Require both devices to display/confirm the peer name and a short verification code before trust is persisted.

Must

A man-in-the-middle cannot silently replace a peer.

PAIR-003

Provide manual pairing for camera failure, difficult networks or accessibility needs.

Should

User can enter a short code plus address/session token.

PAIR-004

Persist multiple trusted devices and expose connect, rename, forget and block actions.

Must

Revoked devices cannot reconnect using old credentials.

PAIR-005

Support active-host selection when Android is paired to Mac and iPad.

Should

Only one host receives exclusive streams/actions unless explicitly designed otherwise.

PAIR-006

Negotiate protocol and feature versions before starting a session.

Must

Unsupported features are disabled with a clear reason.

PAIR-007

Reconnect after sleep, network changes and app restarts with exponential backoff.

Must

No duplicate sessions, connection storms or repeated user prompts.

## 6.2 Local transport

ID

Requirement

Priority

Acceptance / notes

NET-001

Discover paired peers on a local network without requiring a hosted account.

Must

Discovery works on normal LAN and degrades to manual address entry when multicast is blocked.

NET-002

Maintain an authenticated control channel and separate QoS paths for events, bulk files and real-time media.

Must

Large transfer does not starve notifications or input control.

NET-003

Support Wi-Fi and a no-developer-mode USB transport.

Must

USB operation does not depend on ADB. Exact implementation must pass App Sandbox and device-compatibility testing.

NET-004

Select the best available transport and permit manual override.

Should

UI identifies Wi-Fi/USB and can fall back without data corruption.

NET-005

Provide heartbeats, disconnect detection, retry limits and session resumption.

Must

Peer loss is detected quickly; transfers can resume where safe.

NET-006

Support hotspot and peer-to-peer network edge cases.

Should

Pair/reconnect tests cover Android hotspot and Apple peer-to-peer APIs.

## 6.3 Clipboard

ID

Requirement

Priority

Acceptance / notes

CLIP-001

Synchronize text and URLs bidirectionally between Android and Mac/iPad.

Must

New eligible item appears on the peer within 1 second on a healthy LAN.

CLIP-002

Support images and file references where both platforms permit.

Should

Unsupported types are rejected gracefully or converted with user-visible status.

CLIP-003

Maintain optional clipboard history with user-selectable limits of 10, 20 or 50 items.

Should

History is local, clearable and disabled by policy/profile if needed.

CLIP-004

Prevent feedback loops by content hash, origin ID and sequence number.

Must

A synchronized item does not bounce indefinitely.

CLIP-005

Provide manual Android actions: app button, text-selection action and Quick Settings tile.

Should

Works when background clipboard access is restricted by Android.

CLIP-006

Protect sensitive content with exclusion rules and explicit pause.

Must

Password-manager and one-time-code behavior is documented; users can pause sync quickly.

## 6.4 Files, photos and storage

ID

Requirement

Priority

Acceptance / notes

FILE-001

Send files in both directions over Wi-Fi or USB.

Must

Support photos, videos, documents, ZIP/PDF and arbitrary user-selected files.

FILE-002

Expose Android share-target integration and Mac drag/drop.

Must

A user can share from another Android app and drop from Finder.

FILE-003

Browse permitted Android folders and media with pagination and thumbnails.

Must

Downloads, photos, videos and user-granted folders are accessible without broad legacy storage access.

FILE-004

Provide batch selection, progress, throughput, ETA, cancel and retry.

Must

Cancellation leaves no falsely completed item; partial files are cleaned or resumable.

FILE-005

Handle duplicate names with Ask, Replace, Keep Both and Skip policies.

Must

No silent overwrite by default.

FILE-006

Verify transfer integrity per file and per chunk.

Must

Hash mismatch triggers retry/failure and never marks the file complete.

FILE-007

Store received items in platform-appropriate local destinations.

Must

Mac defaults to Downloads; Android uses selected/standard local storage; iPhone drop retains items until export/save.

FILE-008

Support resume for large files and interrupted batches.

Should

Resume does not retransmit completed chunks unless integrity check fails.

## 6.5 Notifications

ID

Requirement

Priority

Acceptance / notes

NOTIF-001

Mirror eligible Android notifications to native Mac/iPad presentation.

Must

App name, title, body, timestamp and icon/thumbnail are preserved when available.

NOTIF-002

Map Android notification actions, including reply actions, to companion controls.

Must

Only actions supplied by Android are shown; results are routed to the correct notification instance.

NOTIF-003

Support per-app allow/deny filters, quiet mode and temporary pause.

Must

Filtering is understandable and does not invert ON/OFF semantics.

NOTIF-004

Group related notifications while retaining correct action targeting.

Should

Multiple conversations from one app cannot receive replies intended for another.

NOTIF-005

Suppress internal status noise and reconnect reminders unless action is required.

Must

Background operation does not create repetitive user-visible notifications.

NOTIF-006

Offer Open on Phone/deep-open where Android provides a safe intent.

Could

Companion action opens the correct app/conversation on the phone.

## 6.6 Messages, contacts and calls

ID

Requirement

Priority

Acceptance / notes

MSG-001

Display SMS/MMS thread list and message history when Android permissions and store policy allow.

Should

Threads paginate and preserve sender, time, status and attachment metadata.

MSG-002

Support notification-based quick reply for messaging apps that expose a RemoteInput-like action.

Must

Do not advertise full compose parity for unsupported apps.

MSG-003

Search and browse Android contacts with local-only transfer.

Should

Contacts can be disabled independently and are not uploaded.

MSG-004

Show incoming call events with caller context and permitted controls.

Should

Answer/decline/mute only appear when the OS/device exposes a lawful supported action.

MSG-005

Provide clear policy fallback when SMS/call permissions are unavailable.

Must

Core app remains usable without restricted permissions.

## 6.7 Screen mirroring and remote control

ID

Requirement

Priority

Acceptance / notes

MIR-001

Start screen capture only after Android system MediaProjection consent.

Must

Consent is explicit per Android rules; no bypass or hidden capture.

MIR-002

Encode and stream the screen with adaptive resolution, frame rate and bitrate.

Must

Target under 150 ms glass-to-glass latency on a healthy LAN for interactive use.

MIR-003

Decode with hardware acceleration on Mac and preserve rotation/aspect ratio.

Must

Orientation changes do not require a full re-pair.

MIR-004

Relay tap, long-press, drag, scroll, Back and Home actions through the consented Android AccessibilityService.

Must

Coordinates are normalized and accurate across scaling/rotation.

MIR-005

Provide smooth trackpad scrolling and pointer feedback.

Should

Input is rate-limited and coalesced to avoid queue buildup.

MIR-006

Expose a launchable-app list and app switching within mirror workflows.

Should

Only launchable, policy-permitted apps appear.

MIR-007

Stop capture/control immediately when either user ends the session or permission is revoked.

Must

No background capture persists after stop.

## 6.8 Virtual webcam and microphone

ID

Requirement

Priority

Acceptance / notes

CAM-001

Stream Android camera video to a macOS virtual camera device.

Should

The device appears in common apps after approved extension setup/restart as required.

CAM-002

Support front/back camera, flash where available, frame-rate selection and Camera Live controls.

Should

Control changes are reflected without reconnecting the entire device session.

CAM-003

Stream Android microphone audio to a macOS system input device.

Should

Input is selectable by conferencing/recording apps with stable timestamps and acceptable latency.

CAM-004

Provide setup diagnostics for missing extensions, permissions and incompatible conferencing apps.

Must

User receives specific remediation rather than a generic failure.

CAM-005

Prevent camera/microphone activation without visible user intent and indicators.

Must

Both devices show active-stream state and provide one-tap stop.

## 6.9 URL, media and LinkMyDrop

ID

Requirement

Priority

Acceptance / notes

AUX-001

Send a URL to the paired device and open it in the default browser after user action/policy.

Should

Unsafe schemes are blocked; normal http/https links work.

AUX-002

Expose current Android media-session metadata and playback controls.

Could

Play/pause/next/previous/volume reflect the active session when Android provides it.

DROP-001

Create/join a nearby iPhone-Android transfer via QR, manual invitation and NFC bootstrap where supported.

Should

NFC only bootstraps identity/session; content travels over an authenticated local channel.

DROP-002

Accept photos, videos, files, text and profile/vCard-like details.

Should

Receiver approves peer and item summary before transfer.

DROP-003

Integrate iOS share extension and Android share sheet.

Should

Selected content can be staged then continued in the main app.

DROP-004

Support discoverability controls and saved trusted peers.

Should

Nearby requests are disabled by default or time-bounded.

DROP-005

Store Android photos in an appropriate media location and retain iOS items until save/export.

Should

Storage behavior is explicit and recoverable.

# 7. Recommended clean-room architecture

ImportantThe architecture below is an independent implementation recommendation. The public sources confirm behavior, not the reference product's internal protocol or code structure.

## 7.1 Component view

Component

Recommended implementation

Android app

Kotlin + Jetpack Compose; foreground connection service; modular feature services; Android Keystore; WorkManager only for deferrable work.

macOS app

Swift/SwiftUI; menu bar extra plus main window; Network.framework; Keychain; UserNotifications; NSPasteboard; file and media browsers.

iPadOS app

Shared Swift package with iPad-specific navigation and restricted feature set; Network.framework/MultipeerConnectivity as validated.

iOS drop surface

SwiftUI app plus Share Extension, NFC session bootstrap and local file inbox/export flow.

Protocol layer

Versioned binary messages using Protocol Buffers or CBOR; separate control/event, bulk-transfer and real-time media channels.

Media

Android MediaProjection/CameraX/AudioRecord; H.264/HEVC or AV1 where practical; VideoToolbox decode; Opus or PCM audio.

Virtual devices

macOS Core Media I/O camera extension; virtual microphone through an Apple-supported audio device/extension approach validated for App Store distribution.

## 7.2 Transport options to validate

Path

Recommendation

Validation risk

Wi-Fi LAN

Bonjour/mDNS discovery plus mutually authenticated TLS 1.3 over TCP or QUIC.

Multicast may be blocked; require manual fallback.

Peer-to-peer Apple

Use Network.framework peer-to-peer or MultipeerConnectivity only where cross-platform interop is proven.

"Wi-Fi Direct" wording does not reveal exact mechanism.

USB

Prototype Android Open Accessory or another no-ADB user-space protocol compatible with macOS App Sandbox USB entitlements.

Highest implementation and hardware-compatibility risk.

NFC

Carry only a short-lived invitation or URL-like bootstrap.

Cross-platform NFC payload limits and UX vary by device.

## 7.3 Protocol services

- Session service: authentication, feature negotiation, heartbeat, active-host arbitration and revocation.

- Event service: notifications, call state, clipboard announcements, media state and device status.

- RPC service: user actions such as reply, launch app, open URL, answer/decline where supported and permission checks.

- File service: browse, metadata, thumbnail, chunk transfer, hash, resume, cancel and duplicate policy.

- Video service: screen/camera stream configuration, key frames, rotation and congestion feedback.

- Audio service: microphone stream configuration, clock synchronization, jitter buffer and mute state.

- Diagnostics service: local logs, version/capability dump, exportable support bundle with user review.

# 8. Data model

Entity

Key fields

TrustedDevice

deviceId, displayName, platform, publicKey, pairedAt, lastSeen, capabilities, revokedAt

Session

sessionId, peerId, transport, startedAt, protocolVersion, activeHost, state, latency

ClipboardItem

itemId, originDevice, type, preview, contentHash, createdAt, expiresAt, sensitiveFlag

TransferJob

jobId, direction, items, totalBytes, completedBytes, transport, state, duplicatePolicy, checksum

NotificationRecord

notificationKey, packageId, appName, title, body, groupKey, actions, postedAt, dismissedAt

MessageThread

threadId, participants, lastMessage, unreadCount, updatedAt

ContactSummary

contactId, displayName, phones, avatarToken; transferred only on demand

MediaState

packageId, title, artist, album, artworkToken, position, duration, playbackState

DropInvite

inviteId, initiatorPublicKey, expiresAt, advertisedItems, approvalState, nonce

# 9. Permissions and platform policy

Capability

Android / Apple integration

Policy requirement

Notifications

NotificationListenerService; macOS/iPad notifications

Explicit user grant; per-app filtering; minimize retention.

Remote control

AccessibilityService

Use only for visible user-initiated control; prominent disclosure; no automation beyond scope.

Screen capture

MediaProjection

System confirmation; active indicator; immediate stop.

SMS/MMS

SMS provider / restricted permissions

Validate Google Play policy eligibility before committing to parity.

Calls

Telecom/notification actions/device-specific APIs

Expose only platform-supported actions; no universal claim.

Files/photos

SAF, MediaStore, Photos/Files pickers

Least privilege; scoped access; user-selected folders.

Camera/mic

CameraX/AudioRecord; macOS extensions

Visible active state, revoke support, OS permission compliance.

Local network

Android network; Apple Local Network privacy

Purpose string and explicit local-network prompt.

USB

Android USB + macOS sandbox entitlement/API

No ADB/developer mode; App Store sandbox review.

NFC/share extensions

NFC invite bootstrap, iOS/Android share extensions

No background data transfer without user awareness.

# 10. Security and privacy requirements

ID

Requirement

Priority

Acceptance / notes

SEC-001

Use mutually authenticated encrypted sessions for every transport.

Must

No application data is sent before trust verification.

SEC-002

Use a single-use, expiring pairing secret and bind it to both device public keys.

Must

Captured QR data cannot pair a second time.

SEC-003

Store long-term private keys in Android Keystore and Apple Keychain/Secure Enclave where available.

Must

Keys are non-exportable where platform supports it.

SEC-004

Encrypt all content in transit and integrity-protect each message/chunk.

Must

Tampering is detected and session terminated or item retried.

SEC-005

Collect no analytics or crash data by default unless separately disclosed and consented.

Must

Product can operate fully without telemetry.

SEC-006

Retain only local state necessary for feature operation and expose clear/delete controls.

Must

Clipboard history, notifications, transfer history and received drop items are independently clearable.

SEC-007

Redact message/notification content from logs and support bundles.

Must

User can preview exported diagnostics.

SEC-008

Rate-limit pairing, action RPCs and incoming transfer requests.

Must

Brute-force and notification/action floods are bounded.

SEC-009

Perform threat modeling for malicious LAN peers, compromised paired device, replay, downgrade and oversized payloads.

Must

Security test plan covers each threat before release.

SEC-010

Provide device revoke and emergency disconnect controls on both sides.

Must

Revocation invalidates sessions and future reconnects immediately.

# 11. Non-functional requirements

Category

Target

Pairing

Median under 60 seconds from both apps open to connected, matching the public positioning.

Reconnect

Under 5 seconds on a healthy known LAN; bounded retries after sleep/network changes.

Clipboard

Under 1 second median for small text on LAN; no loops or duplicate history items.

Notifications

Under 2 seconds median from Android post to companion display.

Mirroring

Under 150 ms target interactive latency on LAN; adaptive 30/60 fps; graceful degradation.

File transfer

Stream without full-memory buffering; multi-gigabyte files; integrity verification; cancellation within 2 seconds.

Reliability

No duplicate active host, no reconnect storm, no orphaned partial file presented as complete.

Battery

Foreground-service behavior is clear; idle mode reduces wakeups; user guidance for aggressive OEM power management.

Accessibility

Keyboard navigation, VoiceOver/TalkBack labels, scalable text, non-color state indicators and manual pairing alternative.

Localization

Externalized strings; launch with at least English plus selected priority languages; reconcile website/store language mismatch.

Supportability

Local diagnostic dashboard, version/capability display, connection test and user-reviewed support bundle.

# 12. Acceptance test catalogue

Test group

Representative acceptance scenarios

Pairing/security

Expired QR, replayed QR, wrong verification code, forgotten device, protocol downgrade, concurrent pairing attempts.

Networks

Normal LAN, multicast blocked, Android hotspot, network switch, sleep/wake, VPN, firewall, high loss/latency.

USB

Cable attach/detach, locked phone, no developer mode, repeated reconnect, large transfer, App Sandbox distribution build.

Clipboard

Text/URL/image/file, sensitive item, 10/20/50 history, loop prevention, Android background restriction.

Files/photos

1 byte to multi-GB, batch of 1,000 items, duplicate names, cancel, resume, full disk, permission revoked.

Notifications

Grouped messages, multiple conversations same app, action/reply routing, app filter, quiet mode, dismissal.

Messages/calls

Permission denied, unsupported OEM, multi-SIM, MMS attachment metadata, call action unavailable.

Mirroring/control

Portrait/landscape, notched devices, scaling, long press, scroll, rotation, consent revoke, screen lock.

Webcam/mic

Zoom/Teams/Meet/FaceTime-like clients, restart-required extension, camera switch, flash, mute, clock drift.

Drop

QR/manual/NFC bootstrap, unknown peer approval, share extension, interrupted transfer, iOS export, Android media indexing.

# 13. Delivery plan and estimates

Phase

Scope

Indicative effort

0. Discovery spikes

Policy review; QR trust prototype; LAN protocol; USB feasibility; virtual camera/mic distribution proof.

4-6 weeks

1. Core MVP

Android + macOS pairing, Wi-Fi, device management, clipboard text/URL, basic file transfer, local diagnostics.

10-14 weeks

2. Daily continuity

Photos/storage browser, notifications/actions, clipboard history, transfer resume, menu bar mode.

8-12 weeks

3. Advanced control

Screen mirroring, AccessibilityService control, app launch, media remote, URL handoff.

10-16 weeks

4. Restricted features

SMS/MMS, call surface/actions, contacts, policy hardening.

6-10 weeks plus review risk

5. Media devices

Virtual webcam and microphone, setup diagnostics, client compatibility.

10-16 weeks

6. iPad + LinkMyDrop

iPad subset; iPhone/Android nearby drop, share extensions, NFC bootstrap.

12-20 weeks

Planning assumptionBroad parity requires parallel platform specialists: Android, macOS/iOS, networking/media, and QA/security. A narrow core product can be delivered sooner by omitting SMS/call actions, USB, virtual devices and iPhone-Android drop.

# 14. Risks and open questions

Risk / unknown

Required resolution

Exact USB protocol is not public

Prototype at least two no-ADB options and validate on Samsung, Pixel, OnePlus and Xiaomi plus Apple Silicon Macs.

"Wi-Fi Direct" cross-platform meaning is ambiguous

Test Network.framework peer-to-peer, MultipeerConnectivity and hotspot/manual flows.

SMS permissions are store-restricted

Obtain policy/legal review and define a no-SMS fallback before schedule commitment.

Call answer/decline varies by Android/OEM

Create a capability matrix and display actions only when runtime-supported.

Automatic Android clipboard access is restricted

Use foreground/explicit actions and test on target Android versions/OEMs.

Virtual mic App Store path may be difficult

Complete an early signed/distributed proof of concept before full implementation.

No cloud account means no remote rendezvous

Define product as nearby/local only and make network troubleshooting first-class.

Website and store language lists differ

Choose a canonical launch language set and test every platform consistently.

Reference UI details are only partially visible

Conduct authorized hands-on black-box usability testing with purchased apps, documenting behavior without copying assets or internals.

# 15. Clean-room implementation rules

- Separate the public-behavior research record from the implementation repository.

- Do not decompile, disassemble, bypass protections, extract private keys, imitate store identity or access non-public services.

- Do not copy names, logos, icons, screenshots, text, layouts pixel-for-pixel or proprietary assets.

- Use platform documentation and independently designed protocols/components.

- Document each requirement source as public fact, observed UI behavior or engineering inference.

- Have counsel review trademark, copyright, store policy, SMS/call permissions and any interoperability testing plan.

# 16. Source inventory

S1 - LinkMyMac homepage: https://linkmymac.com/. Product positioning, feature list, setup flow, platform coverage, privacy claims, version 1.54. Accessed 2026-07-12.

S2 - Features Guide: https://linkmymac.com/features-guide. Expanded feature descriptions and platform-specific scope. Accessed 2026-07-12.

S3 - Support: https://linkmymac.com/support/. Installation, pairing, permissions, battery setting, compatibility, destinations. Accessed 2026-07-12.

S4 - FAQ: https://linkmymac.com/faq. Permissions, reconnection, reply limitations, transfer destinations and troubleshooting. Accessed 2026-07-12.

S5 - Privacy Policy: https://linkmymac.com/privacy-policy/. Local transport, data categories, local storage, no advertising/resale intent. Accessed 2026-07-12.

S6 - Terms: https://linkmymac.com/terms. Authorized-device use, platform dependency, no uptime guarantee. Accessed 2026-07-12.

S7 - LinkMyDrop guide: https://linkmymac.com/linkmydrop. iPhone-Android QR/NFC/manual invites, approval, share sheet and receive storage. Accessed 2026-07-12.

S8 - Google Play listing - LinkMyMac: https://play.google.com/store/apps/details?hl=en&amp;id=com.kdg.beam_android. Android listing, 5K+ downloads, feature claims, AccessibilityService disclosure, release notes. Accessed 2026-07-12.

S9 - Apple App Store listing - LinkMyDroid: https://apps.apple.com/id/app/linkmydroid/id6755784154?platform=mac. Price observed in Indonesia, OS requirements, version history, privacy declaration and app size. Accessed 2026-07-12.

S10 - Android notifications on Mac guide: https://linkmymac.com/android-notifications-on-mac. Notification mirroring and supported Android reply actions. Accessed 2026-07-12.

S11 - Android to Mac file transfer guide: https://linkmymac.com/android-to-mac-file-transfer. Transfer workflow, file types, duplicate handling and progress states. Accessed 2026-07-12.

S12 - Android clipboard on Mac guide: https://linkmymac.com/android-clipboard-on-mac. Clipboard use cases and local-pairing behavior. Accessed 2026-07-12.

S13 - Android messages on Mac guide: https://linkmymac.com/android-messages-on-mac. SMS/MMS visibility and bounded quick-reply behavior. Accessed 2026-07-12.

S14 - Battery optimization guide: https://linkmymac.com/help/battery-optimization. Unrestricted battery recommendation and background reliability. Accessed 2026-07-12.

# 17. Definition of done for a parity release

- All Must requirements pass on the supported OS/device matrix.

- Security review and threat-model actions are closed or explicitly accepted.

- Store-policy review is complete for accessibility, SMS, call, local-network, USB and virtual-device capabilities.

- Pairing, reconnect and transfer reliability meet targets across normal LAN, hotspot, sleep/wake and USB cases.

- No cloud account or content relay is required for supported local workflows.

- Privacy controls, permission education, revoke, clear-history and diagnostic redaction are complete.

- Product branding and UI are independently created and not confusingly similar to the reference product.