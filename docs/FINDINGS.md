# Device test findings

Date: 2026-07-16, updated 2026-07-17
Devices: Samsung One UI Android phone (earlier notes wrongly said Xiaomi/MIUI) + Apple Silicon Mac, same Wi-Fi, paired and connected.
Read this together with `docs/HANDOVER.md` before continuing work.

## Test matrix (user-run)

| Test | Result |
| --- | --- |
| Clipboard Mac to Android, text | PASS |
| Clipboard Mac to Android, images | PASS |
| Clipboard Android to Mac, text | Works only after opening the app (expected, see F1) |
| Clipboard Android to Mac, images | Same as text; screenshots never sync (bug, see F2) |
| Photos grid on Mac | Only about 10 thumbnails load, rest spin forever (bug, see F3) |
| File transfer both directions | PASS ("flawless") |
| Notification mirroring | Not yet tested; user needs instructions (see F4) |

## F1. Android to Mac requires opening the app (EXPECTED BEHAVIOR)

Android 10+ forbids any backgrounded app from reading the clipboard. Sync fires on app foreground (ON_RESUME + 250 ms) and while the app is open; nothing can fire while the app is closed. The user labeled this "Failed" so it may be worth surfacing better in-app (a one-time explainer), but it is not fixable. Escape hatches: Send clipboard button, Quick Settings tile, share sheet, text-selection action.

## F2. Screenshots never reach the Mac (RESOLVED: OS behavior, not a bug)

Root cause established 2026-07-17 from device logs (this hunt also surfaced and fixed the real F5 bug below): on Samsung One UI, screenshots never touch the Android system clipboard. The "clipboard" that shows screenshots on the phone is Samsung Keyboard's private clipboard history, which third-party apps cannot read. Instrumented logs proved the primary clip keeps its previous content after a screenshot. Everything that does reach the real clipboard (text, URLs, gallery Copy image) syncs correctly, auto-on-open.

The one earlier read of a screenshot-looking clip that yielded no bytes (07-17 09:15, MediaStore URI, no exception) was never reproduced; images copied from Gallery read and sync fine (verified 825 KB jpeg).

Working screenshot flows on One UI: screenshot toolbar > Share > opentomac (arrives as file transfer; manifest accepts image ACTION_SEND), or Gallery > Copy then open opentomac (arrives on the Mac pasteboard). Feature idea (user interest pending): optional "auto-send new screenshots" via a MediaStore ContentObserver on the Screenshots bucket, off by default.

Diagnostics that made this findable stay in the debug build: `AndroidClipboard` logs a metadata-only clip summary and each image-path branch decision, and the AppRuntime clipboard send lambda logs attempt/delivery with connection state, all under the `opentomac` logcat tag.

## F5. Clipboard items from a restarted phone were silently dropped by the Mac (BUG, FIXED)

Found while chasing F2: `ClipboardSync`'s per-origin replay filter keeps the highest sequence seen, but a restarted phone app (reinstall, OS kill) resets its counter to 1, so the long-running Mac discarded every delivered item as a replay until the new counter outran the remembered watermark ("suddenly works after N sends" symptom). Fixed 2026-07-17: both sides clear the replay watermark via `ClipboardSync.onSessionEstablished()` when a session (re)connects; sequence numbers only order items within one peer lifetime.

Same round also fixed the app-open race: the ON_RESUME clipboard read fired before reconnection finished and its failed send was dropped with no retry. On transition to Connected the phone now re-reads the clipboard (foreground only) and re-drives it through normal dedupe (`ClipboardSync.trySend`). Both fixes verified on device: auto-sync on app open now works with no manual button.

## F3. Photos grid: only ~10 thumbnails load, the rest spin forever (FIXED, needs device retest)

Root cause: the Mac grid fired ~60 `ThumbnailRequest`s at once over CONTROL, and the Android session manager rate-limits inbound CONTROL to `rpcRateLimitPerMinute = 10` (token bucket, burst 10). Everything past the first burst was dropped (`droppedEnvelopes`), leaving `CompletableDeferred`s pending and cells spinning forever.

Fixed 2026-07-17 (option 1 from the original analysis): media traffic now travels on BULK (one-line `MacController` change; responses already used BULK on both sides). `MediaCompanionBrowser` additionally got a 15 s per-request timeout applied to every waiter, an 8-request in-flight thumbnail cap (semaphore), cancellation-safe pending cleanup under NonCancellable, and completion ownership serialized under the state mutex. A Codex adversarial review drove the hardening round; the "late response satisfies a retry" finding was consciously accepted because responses are keyed by content-identifying keys (mediaId / bucket+page), so late data is still valid data. Covered by 15 MediaBrowserTest cases. Awaiting user retest on device: the full grid should now load, and any failed cell should stop spinning within ~15 s.

## F4. How to test notification mirroring (user asked; untested)

1. Phone: dashboard button "Enable notification mirroring" opens the system notification-access screen; toggle opentomac ON.
2. Mac: first launch prompts for notification permission; if declined, enable in System Settings > Notifications > opentomac.
3. Both devices Connected.
4. Trigger any notification on the phone: send yourself a WhatsApp/Telegram message, or set a 10 second clock timer.
5. Expect a native Mac banner "AppName: title" with the body. For messaging apps exposing inline reply, the banner should offer Reply; replying should deliver the text back into the phone conversation (this reply path is wired but has never been verified end to end).
Known noise sources to ignore: opentomac's own foreground-service notification is deliberately never mirrored.

## Priority order for next session

1. DONE (2026-07-17): F3 photos rate-limit fix. Needs user device retest.
2. DONE (2026-07-17): F2 resolved as One UI behavior (workarounds documented); F5 replay-filter and reconnect-race bugs found and fixed, verified on device.
3. F4 verify notification mirroring end to end including reply.
4. Then continue the roadmap: photo import device test, optional auto-send-screenshots feature (user interest pending), Phase B.
