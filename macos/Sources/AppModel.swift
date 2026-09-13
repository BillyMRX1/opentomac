import Foundation
import AppKit
import OpentomacShared

struct NowPlayingState {
    let appName: String
    let title: String
    let artist: String
    let isPlaying: Bool
    let hasSession: Bool
}

enum MirrorQualityPreset: String, CaseIterable, Identifiable {
    case low
    case balanced
    case sharp

    var id: String { rawValue }

    var title: String {
        switch self {
        case .low: return "Low"
        case .balanced: return "Balanced"
        case .sharp: return "Sharp"
        }
    }

    var maxLongEdge: Int32 {
        switch self {
        case .low: return 854
        case .balanced: return 1280
        case .sharp: return 1920
        }
    }

    var bitrateBps: Int32 {
        switch self {
        case .low: return 2_000_000
        case .balanced: return 6_000_000
        case .sharp: return 10_000_000
        }
    }
}

/// Bridges the Kotlin `MacController` to SwiftUI. Controller callbacks are
/// marshaled onto the main queue before touching published state; video frames
/// go directly to the renderer's serial queue.
@MainActor
final class AppModel: ObservableObject {
    @Published var connectionStatus: String = "Starting"
    @Published var pairing: MacPairingState?
    @Published var devices: [TrustedDevice] = []
    @Published private(set) var peerCapabilities: Set<String> = []
    @Published var lastNotification: String?
    @Published var transfers: [MacTransfer] = []
    @Published var photos: [MacPhoto] = []
    @Published var contacts: [MacContact] = []
    @Published var contactsGranted = true
    @Published var smsThreads: [MacSmsThread] = []
    @Published var smsMessages: [MacSmsMessage] = []
    @Published var smsAddress = ""
    @Published var smsGranted = true
    @Published var calls: [MacCallEntry] = []
    @Published var callsGranted = true
    @Published var notificationsAuthorized: Bool?
    @Published var mirrorActive = false
    @Published var mirrorConfigured = false
    @Published private(set) var mirrorPresentationTick = 0
    @Published var mirrorStoppedReason: String?
    @Published var mirrorVideoSize: CGSize?
    @Published private(set) var mirrorQuality: MirrorQualityPreset
    @Published var nowPlaying = NowPlayingState(
        appName: "",
        title: "",
        artist: "",
        isPlaying: false,
        hasSession: false
    )
    let protocolVersion: Int32
    let videoRenderer: VideoRenderer

    private var controller: MacController!
    private let notifier = NotificationBridge()
    private var mirrorRestartTask: Task<Void, Never>?
    private var requestedSmsThreadId: String?
    private var smsGeneration = 0
    private var callGeneration = 0
    private static let mirrorQualityDefaultsKey = "mirrorQualityPreset"

    init() {
        mirrorQuality = UserDefaults.standard.string(forKey: Self.mirrorQualityDefaultsKey)
            .flatMap(MirrorQualityPreset.init(rawValue:))
            ?? .balanced
        protocolVersion = ProtocolCodec.shared.PROTOCOL_VERSION
        videoRenderer = VideoRenderer()
        let renderer = videoRenderer
        controller = MacController(
            onState: { [weak self] status in
                Task { @MainActor in self?.connectionStatus = status }
            },
            onPairing: { [weak self] state in
                Task { @MainActor in self?.pairing = state }
            },
            onDevices: { [weak self] devices in
                Task { @MainActor in self?.devices = devices }
            },
            onNotification: { [weak self] title, body, key, replyIndex in
                Task { @MainActor in
                    self?.lastNotification = "\(title) — \(body)"
                    self?.notifier.present(title: title, body: body, key: key, replyIndex: Int(replyIndex))
                }
            },
            onTransfers: { [weak self] items in
                Task { @MainActor in self?.transfers = items }
            },
            onPhotos: { [weak self] items in
                Task { @MainActor in self?.photos = items }
            },
            onOpenUrl: { value in
                Task { @MainActor in
                    guard let url = Self.webURL(from: value) else { return }
                    NSWorkspace.shared.open(url)
                }
            },
            onNowPlaying: { [weak self] appName, title, artist, isPlaying, hasSession in
                // Kotlin Boolean crosses the bridge boxed as KotlinBoolean.
                Task { @MainActor in
                    self?.nowPlaying = NowPlayingState(
                        appName: appName,
                        title: title,
                        artist: artist,
                        isPlaying: isPlaying.boolValue,
                        hasSession: hasSession.boolValue
                    )
                }
            },
            onScreenshotTaken: { [weak self] mediaId, name in
                Task { @MainActor in
                    self?.notifier.presentScreenshot(name: name, mediaId: mediaId)
                }
            },
            onVideoConfig: { [weak self] width, height, sps, pps, frameRate in
                renderer.configure(
                    width: Int(width.int32Value),
                    height: Int(height.int32Value),
                    sps: sps,
                    pps: pps,
                    frameRate: Int(frameRate.int32Value)
                )
                Task { @MainActor in
                    guard let self else { return }
                    let isIncomingSession = !self.mirrorActive
                    self.mirrorVideoSize = CGSize(
                        width: Int(width.int32Value),
                        height: Int(height.int32Value)
                    )
                    self.mirrorConfigured = true
                    self.mirrorStoppedReason = nil
                    if isIncomingSession {
                        self.mirrorActive = true
                        self.mirrorPresentationTick += 1
                    }
                }
            },
            onVideoFrame: { data, ptsUs, keyframe in
                renderer.enqueue(
                    data: data,
                    ptsUs: ptsUs.int64Value,
                    keyframe: keyframe.boolValue
                )
            },
            onMirrorStopped: { [weak self] reason in
                renderer.reset()
                Task { @MainActor in
                    self?.mirrorRestartTask?.cancel()
                    self?.mirrorRestartTask = nil
                    self?.mirrorActive = false
                    self?.mirrorConfigured = false
                    self?.mirrorStoppedReason = reason
                }
            },
            onPeerCapabilities: { [weak self] capabilities in
                Task { @MainActor in self?.peerCapabilities = Set(capabilities) }
            }
        )
        notifier.start(
            onReply: { [weak self] key, actionIndex, text in
                guard self?.peerSupports(Capability.shared.NOTIFICATION_ACTIONS) ?? true else {
                    self?.showUnsupported("notification actions")
                    return
                }
                self?.controller.replyToNotification(key: key, actionIndex: Int32(actionIndex), text: text)
            },
            onScreenshotSend: { [weak self] mediaId in
                // Reuses the photo-import path: the original arrives as a transfer.
                self?.importPhoto(mediaId)
            }
        )
        controller.start()
        refreshNotificationPermission()
    }

    func refreshNotificationPermission() {
        notifier.authorized { ok in
            Task { @MainActor in self.notificationsAuthorized = ok }
        }
    }

    func openNotificationSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension") {
            NSWorkspace.shared.open(url)
        }
    }

    func loadPhotos() {
        guard peerSupports(Capability.shared.PHOTO_BROWSING) else {
            showUnsupported("photo browsing")
            return
        }
        controller.loadPhotos()
    }

    func importPhoto(_ id: String) {
        guard peerSupports(Capability.shared.PHOTO_BROWSING) else {
            showUnsupported("photo browsing")
            return
        }
        controller.importPhoto(id: id)
    }

    func requestThumbnail(_ id: String, completion: @escaping (Data?) -> Void) {
        guard peerSupports(Capability.shared.PHOTO_BROWSING) else {
            showUnsupported("photo browsing")
            completion(nil)
            return
        }
        controller.requestThumbnail(id: id) { base64 in
            let data = base64.flatMap { Data(base64Encoded: $0) }
            Task { @MainActor in completion(data) }
        }
    }

    func searchContacts(_ query: String) {
        guard peerSupports(Capability.shared.CONTACTS) else {
            showUnsupported("contacts")
            return
        }
        controller.searchContacts(query: query) { [weak self] items, granted in
            Task { @MainActor in
                self?.contacts = items
                self?.contactsGranted = granted.boolValue
            }
        }
    }

    func beginSmsSession() -> Int {
        smsGeneration &+= 1
        requestedSmsThreadId = nil
        smsThreads = []
        smsMessages = []
        smsAddress = ""
        smsGranted = true
        return smsGeneration
    }

    func loadSmsThreads(generation: Int) {
        guard generation == smsGeneration else { return }
        guard peerSupports(Capability.shared.MESSAGING) else {
            showUnsupported("messaging")
            return
        }
        controller.loadSmsThreads { [weak self] items, granted in
            Task { @MainActor in
                guard let self, self.smsGeneration == generation else { return }
                self.smsThreads = items
                self.smsGranted = granted.boolValue
            }
        }
    }

    func loadSmsThread(_ threadId: String, generation: Int) {
        guard generation == smsGeneration else { return }
        guard peerSupports(Capability.shared.MESSAGING) else {
            showUnsupported("messaging")
            return
        }
        requestedSmsThreadId = threadId
        controller.loadSmsThread(threadId: threadId) { [weak self] address, items, granted in
            Task { @MainActor in
                guard
                    let self,
                    self.smsGeneration == generation,
                    self.requestedSmsThreadId == threadId
                else { return }
                self.smsAddress = address
                self.smsMessages = items
                self.smsGranted = granted.boolValue
            }
        }
    }

    func sendSms(
        address: String,
        body: String,
        onResult: @escaping (Bool, String) -> Void
    ) {
        guard peerSupports(Capability.shared.MESSAGING) else {
            onResult(false, "Connected device needs an update for messaging.")
            return
        }
        controller.sendSms(address: address, body: body) { sent, error in
            Task { @MainActor in onResult(sent.boolValue, error) }
        }
    }

    func clearSms(generation: Int) {
        guard generation == smsGeneration else { return }
        smsGeneration &+= 1
        requestedSmsThreadId = nil
        smsThreads = []
        smsMessages = []
        smsAddress = ""
        smsGranted = true
    }

    func beginCallSession() -> Int {
        callGeneration &+= 1
        calls = []
        callsGranted = true
        return callGeneration
    }

    func loadCallLog(generation: Int) {
        guard generation == callGeneration else { return }
        guard peerSupports(Capability.shared.CALLS) else {
            showUnsupported("call history")
            return
        }
        controller.loadCallLog { [weak self] items, granted in
            Task { @MainActor in
                guard let self, self.callGeneration == generation else { return }
                self.calls = items
                self.callsGranted = granted.boolValue
            }
        }
    }

    func clearCallLog(generation: Int) {
        guard generation == callGeneration else { return }
        callGeneration &+= 1
        calls = []
        callsGranted = true
    }

    func sendFile() {
        guard peerSupports(Capability.shared.FILE_TRANSFER) else {
            showUnsupported("file transfers")
            return
        }
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        if panel.runModal() == .OK {
            controller.sendFiles(paths: panel.urls.map(\.path))
        }
    }

    func mediaControl(_ command: String) {
        guard peerSupports(Capability.shared.MEDIA_CONTROL) else {
            showUnsupported("media controls")
            return
        }
        controller.mediaControl(command: command)
    }

    func startMirror() {
        guard peerSupports(Capability.shared.SCREEN_MIRRORING) else {
            showUnsupported("screen mirroring")
            return
        }
        mirrorRestartTask?.cancel()
        mirrorRestartTask = nil
        videoRenderer.reset()
        mirrorActive = true
        mirrorConfigured = false
        mirrorStoppedReason = nil
        mirrorPresentationTick += 1
        controller.requestMirror(
            maxLongEdge: mirrorQuality.maxLongEdge,
            bitrateBps: mirrorQuality.bitrateBps
        )
    }

    func stopMirror() {
        mirrorRestartTask?.cancel()
        mirrorRestartTask = nil
        guard mirrorActive || mirrorConfigured else { return }
        controller.stopMirror()
        videoRenderer.reset()
        mirrorActive = false
        mirrorConfigured = false
    }

    func sendMirrorTap(x: CGFloat, y: CGFloat) {
        guard peerSupports(Capability.shared.REMOTE_INPUT) else {
            showUnsupported("remote input")
            return
        }
        controller.sendInputTap(x: Float(x), y: Float(y))
    }

    func sendMirrorSwipe(
        x1: CGFloat,
        y1: CGFloat,
        x2: CGFloat,
        y2: CGFloat,
        durationMs: Int
    ) {
        guard peerSupports(Capability.shared.REMOTE_INPUT) else {
            showUnsupported("remote input")
            return
        }
        controller.sendInputSwipe(
            x1: Float(x1),
            y1: Float(y1),
            x2: Float(x2),
            y2: Float(y2),
            durationMs: Int32(clamping: durationMs)
        )
    }

    func sendMirrorKey(_ action: String) {
        guard peerSupports(Capability.shared.REMOTE_INPUT) else {
            showUnsupported("remote input")
            return
        }
        controller.sendInputKey(action: action)
    }

    func sendMirrorText(_ text: String, deleteCount: Int = 0) {
        guard peerSupports(Capability.shared.REMOTE_INPUT) else {
            showUnsupported("remote input")
            return
        }
        controller.sendInputText(text: text, deleteCount: Int32(clamping: deleteCount))
    }

    func setMirrorQuality(_ quality: MirrorQualityPreset) {
        guard quality != mirrorQuality else { return }
        mirrorQuality = quality
        UserDefaults.standard.set(quality.rawValue, forKey: Self.mirrorQualityDefaultsKey)
        guard mirrorActive else { return }

        mirrorRestartTask?.cancel()
        controller.stopMirror()
        videoRenderer.reset()
        mirrorActive = false
        mirrorConfigured = false
        mirrorStoppedReason = nil
        mirrorRestartTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled, let self else { return }
            self.mirrorRestartTask = nil
            self.startMirror()
        }
    }

    func cancelTransfer(_ id: String) { controller.cancelTransfer(jobId: id) }

    func revealReceived() {
        let path = controller.receiveDirectoryPath()
        NSWorkspace.shared.selectFile(nil, inFileViewerRootedAtPath: path)
    }

    func startHosting() { controller.startHosting() }
    func confirmPairing() { controller.confirmPairing() }
    func rejectPairing() { controller.rejectPairing() }
    func cancelPairing() { controller.cancelPairing() }
    func forget(_ device: TrustedDevice) { controller.forget(deviceId: device.deviceId) }
    func sendFiles(_ paths: [String]) {
        guard peerSupports(Capability.shared.FILE_TRANSFER) else {
            showUnsupported("file transfers")
            return
        }
        controller.sendFiles(paths: paths)
    }
    func diagnostics() -> String { controller.diagnostics() }

    func peerSupports(_ capability: String) -> Bool {
        peerCapabilities.isEmpty || peerCapabilities.contains(capability)
    }

    func showUnsupported(_ feature: String) {
        lastNotification = "Connected device needs an update for \(feature)."
    }

    private static func webURL(from value: String) -> URL? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            !trimmed.isEmpty,
            !trimmed.contains(where: \.isWhitespace),
            let url = URL(string: trimmed),
            let scheme = url.scheme?.lowercased(),
            scheme == "http" || scheme == "https",
            url.host?.isEmpty == false
        else { return nil }
        return url
    }
}
