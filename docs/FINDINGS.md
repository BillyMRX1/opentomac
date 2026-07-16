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

## F2. Screenshots land on the phone clipboard but never reach the Mac (BUG, open)

Symptom: after taking a screenshot (which MIUI puts on the clipboard), opening opentomac does not sync it; presumably the manual button also fails.

Leading hypothesis: the screenshot clip carries a content:// URI from the system screenshot provider that opentomac lacks permission to read. `AndroidClipboard.readClipItem` wraps the read in runCatching, so a SecurityException silently yields null and nothing is sent (by design, to protect the collect loop). Secondary hypothesis: MIUI's screenshot "clipboard" is a MIUI-side overlay, not the real primary clip.

Next steps:
1. Add a debug path: log (SessionLog or logcat) the exact exception in readImageForSend/openStream instead of discarding it, then reproduce with `adb logcat | grep -i opentomac`.
2. Check what notice the Send clipboard button shows with a screenshot on the clipboard ("Clipboard is empty or unreadable" would confirm the read fails; "Could not send clipboard item" would point at the send).
3. If it is URI permission: try `ClipData.Item.getUri` read via `contentResolver.openTypedAssetFileDescriptor`, or check whether the clip needs `android.permission.READ_MEDIA_IMAGES` granted (it is requested but the user may not have granted it), or whether MIUI needs its own clipboard permission toggle for the app.

## F3. Photos grid: only ~10 thumbnails load, the rest spin forever (BUG, root cause identified)

The screenshot shows almost exactly 10 loaded thumbnails. The session manager rate-limits inbound CONTROL envelopes to `rpcRateLimitPerMinute = 10` (token bucket, burst 10) on the Android side. The Mac grid fires ~60 `ThumbnailRequest`s at once over CONTROL (`MacController` wires `MediaCompanionBrowser` sends to `safeSend(ChannelId.CONTROL, ...)`). The first ~10 requests consume the bucket; the rest are dropped by the phone (`droppedEnvelopes`), their `CompletableDeferred`s never complete, and the cells spin forever.

Fix options for next session (pick one, plus a UI guard):
1. Send media traffic (MediaListRequest/ThumbnailRequest) on the BULK channel instead of CONTROL; BULK is not rate-limited and the Android handler already routes BULK to `media.onMessage`. One-line change in `MacController` (and confirm Android replies stay on BULK, which they do). Simplest and consistent with "bulk data" semantics.
2. Alternatively exempt media messages from the CONTROL rate limiter (like Heartbeat/HeartbeatAck), or raise the limit.
Also worth adding: Mac-side concurrency cap (request thumbnails only for visible cells or ~8 in flight) and a timeout on `MediaCompanionBrowser` deferreds so cells fail visibly instead of spinning forever.

## F4. How to test notification mirroring (user asked; untested)

1. Phone: dashboard button "Enable notification mirroring" opens the system notification-access screen; toggle opentomac ON.
2. Mac: first launch prompts for notification permission; if declined, enable in System Settings > Notifications > opentomac.
3. Both devices Connected.
4. Trigger any notification on the phone: send yourself a WhatsApp/Telegram message, or set a 10 second clock timer.
5. Expect a native Mac banner "AppName: title" with the body. For messaging apps exposing inline reply, the banner should offer Reply; replying should deliver the text back into the phone conversation (this reply path is wired but has never been verified end to end).
Known noise sources to ignore: opentomac's own foreground-service notification is deliberately never mirrored.

## Priority order for next session

1. F3 photos rate-limit fix (root cause known, small change, big visible win).
2. F2 screenshot clipboard investigation (needs logging first).
3. F4 verify notification mirroring end to end including reply.
4. Then continue the roadmap (photo import, Phase B).
