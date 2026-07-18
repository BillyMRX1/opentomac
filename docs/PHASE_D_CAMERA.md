# Phase D: phone camera and microphone

## What works without Apple signing

The verifiable path does not use Core Media I/O:

1. In the Mac app, click **Webcam preview**.
2. The Mac sends `camera_request` on the EVENT channel.
3. Android uses CameraX `Preview` to supply the camera surface to a direct
   MediaCodec H.264 encoder (1280×720, 30 fps, about 4 Mbps, two-second keyframes).
4. If requested, AudioRecord captures 44.1 kHz mono PCM and a second MediaCodec
   produces AAC-LC. Every `audio_frame` contains one AAC access unit with a
   seven-byte ADTS header.
5. Camera config, H.264 access units, and AAC access units use the rate-limit-exempt
   VIDEO channel. The Mac decodes H.264 with the same VideoToolbox-backed renderer
   as screen mirroring and displays it in the **Webcam preview** window.

Camera and screen mirroring are separate services and controllers. The phone
refuses either request while the other capture mode is active. Disconnect,
shutdown, either peer's stop request, and the Android notification action all
tear capture down.

Android's while-in-use permission rules prevent a camera/microphone foreground
service from being started silently while the app is in the background. An
inbound Mac request therefore posts a notification; tapping it foregrounds the
app and starts capture. CAMERA and RECORD_AUDIO are normal runtime permissions.

The Mac preview intentionally does not play the microphone stream, which would
create an immediate acoustic feedback path. The AAC frames are received and are
reserved for the signed extension bridge.

## CMIO extension scaffold and current gate

`CameraExtension` is an optional Xcode target that builds a
`CameraExtension.systemextension`. It publishes one 1280×720 BGRA source stream
and currently generates a moving test pattern. The source contains the explicit
bridge milestone: the main app must write decoded phone frames to an App Group
shared-memory ring (or an authenticated XPC service), and the extension must read
that ring. An extension is a separate process, so the in-app renderer cannot be
shared directly.

The extension target is intentionally not a dependency of the default
`Opentomac` scheme. This preserves `scripts/build-macos.sh` for the unsigned
debug app. It can be compile-checked without signing after running XcodeGen:

```sh
cd macos
xcodegen generate
xcodebuild \
  -project Opentomac.xcodeproj \
  -scheme CameraExtension \
  -configuration Debug \
  -destination 'platform=macOS,arch=arm64' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

The app's **Install camera extension** button submits an
`OSSystemExtensionRequest` and displays success, approval-required, reboot, or
the exact failure returned by macOS. Failure from the normal unsigned/ad-hoc
build is expected and is not treated as success.

## Exact signed distribution steps (Zoom, Meet, and other camera clients)

These steps require a paid Apple Developer account and cannot be completed by
the repository's unsigned build:

1. In the Apple Developer portal, create explicit App IDs for
   `dev.opentomac.mac` and `dev.opentomac.mac.CameraExtension`. Enable the System
   Extension capability/entitlement for the containing app and create the
   corresponding Developer ID provisioning profiles. Create and provision an
   App Group if using the documented shared-memory bridge.
2. Set `DEVELOPMENT_TEAM` to the Team ID in `macos/project.yml` (or an xcconfig),
   select the appropriate **Developer ID Application** signing identity, retain
   `com.apple.developer.system-extension.install` on the app, and add any
   provisioned App Group entitlement to both targets.
3. Add `CameraExtension` as a target dependency of `Opentomac` and embed
   `CameraExtension.systemextension` in the app's
   `Contents/Library/SystemExtensions` directory. The default unsigned scheme
   omits this on purpose.
4. Complete the TODO bridge in `CameraExtensionProvider.swift`; the current
   signed extension displays only its test pattern, not phone frames.
5. Archive with hardened runtime, sign the app and extension with the same team,
   submit the archive to Apple's notary service, wait for acceptance, and staple
   the notarization ticket to the distributed app.
6. Launch that notarized app and click **Install camera extension**. Approve the
   system extension when macOS prompts, or open **System Settings → Privacy &
   Security** and approve it there. Restart if macOS reports that activation will
   complete after reboot.
7. Quit and reopen Zoom, the browser used for Google Meet, or any other camera
   client so it refreshes its CMIO device list. Select **opentomac Phone Camera**
   as the camera. A virtual microphone stream/bridge must also be implemented in
   the extension before the phone microphone can be selected as an input device.

SIP blocks an unsigned/unnotarized CMIO system extension. Disabling SIP is not a
supported installation strategy.
