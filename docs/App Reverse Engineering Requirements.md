# Clean-room reverse engineering requirements

Reference product: LinkMyMac / LinkMyDroid / LinkMyDrop  

Public sources accessed: 2026-07-12; observed baseline: version 1.54.

## Boundary
Equivalent public behavior only. Do not copy code, branding, assets, copy, private APIs or non-public protocols.

## Product topology
- Android agent: pairing, data access, actions and media capture.
- macOS/iPadOS companion: native control hub.
- LinkMyDrop: separate iPhone-Android nearby transfer.

## Recommended MVP
Secure QR pairing, authenticated local Wi-Fi, saved devices, text/URL clipboard, bidirectional files, photos, notification mirroring, filters and diagnostics. Defer USB, SMS/calls, mirroring, virtual camera/mic and LinkMyDrop until feasibility/policy spikes pass.

## Core requirement groups
1. Pairing/trust and device revocation.
2. Wi-Fi and no-ADB USB transport.
3. Clipboard with loop prevention and optional history.
4. Chunked/resumable files, photos and storage browsing.
5. Notification mirroring and Android-exposed actions.
6. SMS/MMS, contacts and calls only where policy/platform permits.
7. MediaProjection screen stream and AccessibilityService remote control.
8. macOS virtual camera and microphone.
9. iPhone-Android local drop with QR/NFC/manual bootstrap.
10. Local-first privacy, strong cryptography, no required account or cloud relay.

## Files
The DOCX contains detailed IDs, priorities, acceptance criteria, architecture, data model, security controls, test catalogue, risks and phased estimates. The JSON contains the normalized public scrape.

## Sources

- S1: [LinkMyMac homepage](https://linkmymac.com/) - Product positioning, feature list, setup flow, platform coverage, privacy claims, version 1.54.

- S2: [Features Guide](https://linkmymac.com/features-guide) - Expanded feature descriptions and platform-specific scope.

- S3: [Support](https://linkmymac.com/support/) - Installation, pairing, permissions, battery setting, compatibility, destinations.

- S4: [FAQ](https://linkmymac.com/faq) - Permissions, reconnection, reply limitations, transfer destinations and troubleshooting.

- S5: [Privacy Policy](https://linkmymac.com/privacy-policy/) - Local transport, data categories, local storage, no advertising/resale intent.

- S6: [Terms](https://linkmymac.com/terms) - Authorized-device use, platform dependency, no uptime guarantee.

- S7: [LinkMyDrop guide](https://linkmymac.com/linkmydrop) - iPhone-Android QR/NFC/manual invites, approval, share sheet and receive storage.

- S8: [Google Play listing - LinkMyMac](https://play.google.com/store/apps/details?hl=en&id=com.kdg.beam_android) - Android listing, 5K+ downloads, feature claims, AccessibilityService disclosure, release notes.

- S9: [Apple App Store listing - LinkMyDroid](https://apps.apple.com/id/app/linkmydroid/id6755784154?platform=mac) - Price observed in Indonesia, OS requirements, version history, privacy declaration and app size.

- S10: [Android notifications on Mac guide](https://linkmymac.com/android-notifications-on-mac) - Notification mirroring and supported Android reply actions.

- S11: [Android to Mac file transfer guide](https://linkmymac.com/android-to-mac-file-transfer) - Transfer workflow, file types, duplicate handling and progress states.

- S12: [Android clipboard on Mac guide](https://linkmymac.com/android-clipboard-on-mac) - Clipboard use cases and local-pairing behavior.

- S13: [Android messages on Mac guide](https://linkmymac.com/android-messages-on-mac) - SMS/MMS visibility and bounded quick-reply behavior.

- S14: [Battery optimization guide](https://linkmymac.com/help/battery-optimization) - Unrestricted battery recommendation and background reliability.
