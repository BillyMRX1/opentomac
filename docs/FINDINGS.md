# Device test findings

Date: 2026-07-16
Devices: Xiaomi/MIUI Android phone + Apple Silicon Mac, same Wi-Fi, paired and connected.
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

## F2. Screenshots land on the phone clipboard but never reach the Mac (BUG, diagnostics shipped)

Symptom: after taking a screenshot (which MIUI puts on the clipboard), opening opentomac does not sync it; presumably the manual button also fails.

Leading hypothesis: the screenshot clip carries a content:// URI from the system screenshot provider that opentomac lacks permission to read. `AndroidClipboard.readClipItem` wraps the read in runCatching, so a SecurityException silently yields null and nothing is sent (by design, to protect the collect loop). Secondary hypothesis: MIUI's screenshot "clipboard" is a MIUI-side overlay, not the real primary clip.

Diagnostics shipped 2026-07-17: every swallow point in `AndroidClipboard` (clip read, image read, stream open, MIME resolve, text coerce) now logs a warning with URI and cause under the `opentomac` tag. To reproduce and capture:
1. Install the new APK, connect the phone over adb, run `adb logcat -s opentomac`.
2. Take a screenshot, open opentomac, also try the Send clipboard button.
3. The logged exception pinpoints the failure (SecurityException would confirm the URI-permission hypothesis; no log at all points at the MIUI overlay hypothesis).
4. If it is URI permission: try `ClipData.Item.getUri` read via `contentResolver.openTypedAssetFileDescriptor`, or check whether the clip needs `android.permission.READ_MEDIA_IMAGES` granted (it is requested but the user may not have granted it), or whether MIUI needs its own clipboard permission toggle for the app.

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
2. F2 screenshot clipboard: diagnostics shipped; needs user repro with `adb logcat -s opentomac`, then the actual fix.
3. F4 verify notification mirroring end to end including reply.
4. Then continue the roadmap (photo import, Phase B).
