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

struct BatteryState {
    let percentage: Int
    let charging: Bool

    var formatted: String {
        "\(percentage)% · \(charging ? "Charging" : "On battery")"
    }
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

/// An Android app observed from a mirrored notification.
struct ObservedApp: Identifiable, Equatable {
    let packageId: String
    let appName: String
    var id: String { packageId }
}

/// Bridges the Kotlin `MacController` to SwiftUI. Controller callbacks are
/// marshaled onto the main queue before touching published state; video frames
/// go directly to the renderer's serial queue.
@MainActor
final class AppModel: ObservableObject {
    @Published var connectionStatus: String = "Starting"
    @Published var clipboardNotice: String?
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
    @Published private(set) var batteryState: BatteryState?
    @Published private(set) var phoneRinging = false
    @Published var ringError: String?

    // ---- Notification filter state ----
    /// The active peer's device ID (non-nil while connected).
    @Published private(set) var activePeerDeviceId: String?
    /// Apps observed from mirrored notifications for the active peer.
    @Published private(set) var observedApps: [ObservedApp] = []
    /// Whether notification mirroring is paused for the active peer.
    @Published private(set) var notificationsPaused: Bool = false
    /// Package IDs currently on the deny-list for the active peer.
    @Published private(set) var deniedPackageIds: Set<String> = []

    let protocolVersion: Int32
    let videoRenderer: VideoRenderer

    private var controller: MacController!
    private let notifier = NotificationBridge()
    private var mirrorRestartTask: Task<Void, Never>?
    private var requestedSmsThreadId: String?
    private var smsGeneration = 0
    private var callGeneration = 0
    private static let mirrorQualityDefaultsKey = "mirrorQualityPreset"

    /// UserDefaults key prefix for per-device filter policy.
    private static let filterPausedKeyPrefix = "notifFilter.paused."
    private static let filterDeniedKeyPrefix = "notifFilter.denied."
    /// UserDefaults key prefix for observed apps (stored as JSON array of {packageId, appName}).
    private static let observedAppsKeyPrefix = "notifFilter.observedApps."

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
            onClipboardNotice: { [weak self] notice in
                Task { @MainActor in self?.clipboardNotice = notice }
            },
            onPairing: { [weak self] state in
                Task { @MainActor in self?.pairing = state }
            },
            onDevices: { [weak self] devices in
                Task { @MainActor in self?.devices = devices }
            },
            onNotification: { [weak self] title, body, key, replyIndex, dismissible, packageId, appName in
                Task { @MainActor in
                    guard let self else { return }
                    self.lastNotification = "\(title) — \(body)"
                    // Record observed app (no duplicates).
                    let app = ObservedApp(packageId: packageId, appName: appName)
                    if let peerId = self.activePeerDeviceId, !packageId.isEmpty,
                       !self.observedApps.contains(where: { $0.packageId == packageId }) {
                        self.observedApps.append(app)
                        self.persistObservedApps(deviceId: peerId)
                    }
                    self.notifier.present(
                        title: title, body: body, key: key,
                        replyIndex: Int(replyIndex),
                        dismissible: dismissible.boolValue,
                        packageId: packageId,
                        appName: appName
                    )
                }
            },
            onNotificationWithdraw: { [weak self] key in
                Task { @MainActor in self?.notifier.withdraw(key: key) }
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
            },
            onBattery: { [weak self] state in
                Task { @MainActor in
                    self?.batteryState = state.map { BatteryState(percentage: Int($0.percentage), charging: $0.charging) }
                }
            },
            onRingStatus: { [weak self] ringing, error in
                Task { @MainActor in
                    self?.phoneRinging = ringing.boolValue
                    self?.ringError = error.isEmpty ? nil : error
                }
            },
            onConnectedPeer: { [weak self] deviceId in
                Task { @MainActor in
                    guard let self else { return }
                    self.activePeerDeviceId = deviceId
                    guard let deviceId else { return }
                    self.loadPersistedFilterState(deviceId: deviceId)
                    self.pushNotificationFilter()
                }
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
            },
            onDismiss: { [weak self] key in
                guard let self else { return }
                guard self.peerSupports(Capability.shared.NOTIFICATION_DISMISS) else { return }
                self.controller.dismissNotification(key: key)
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

    func toggleRingPhone() {
        guard isConnected else {
            ringError = "Connect to a phone before ringing it."
            return
        }
        guard peerSupports(Capability.shared.RING) else {
            showUnsupported("ring phone")
            return
        }
        ringError = nil
        controller.ringPhone(start: !phoneRinging)
    }

    private var isConnected: Bool {
        let value = connectionStatus.lowercased()
        return value.contains("connected") && !value.contains("not connected") && !value.contains("disconnected")
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

    // MARK: – Notification filter

    /// Returns whether notification filtering controls should be available.
    var notificationFilteringSupported: Bool {
        peerSupports(Capability.shared.NOTIFICATION_MIRRORING)
    }

    /// Applies a new filter policy: persists it, pushes it to the phone via
    /// `MacController`, and withdraws any already-delivered notifications
    /// that are now suppressed.
    func applyFilterPolicy(paused: Bool, deniedPackageIds: Set<String>) {
        guard let deviceId = activePeerDeviceId else { return }
        let previouslyDenied = self.deniedPackageIds
        let wasPaused = self.notificationsPaused

        self.notificationsPaused = paused
        self.deniedPackageIds = deniedPackageIds

        // Persist.
        UserDefaults.standard.set(paused, forKey: Self.filterPausedKeyPrefix + deviceId)
        UserDefaults.standard.set(Array(deniedPackageIds), forKey: Self.filterDeniedKeyPrefix + deviceId)

        pushNotificationFilter()

        // Withdraw delivered/pending notifications that are now suppressed.
        if paused && !wasPaused {
            notifier.withdrawAll()
        } else if !paused {
            let newlyDenied = deniedPackageIds.subtracting(previouslyDenied)
            for pkg in newlyDenied {
                notifier.withdrawByPackage(packageId: pkg)
            }
        }
    }

    /// Loads persisted filter policy and observed apps for a freshly-connected device.
    private func loadPersistedFilterState(deviceId: String) {
        notificationsPaused = UserDefaults.standard.bool(forKey: Self.filterPausedKeyPrefix + deviceId)
        let savedDenied = UserDefaults.standard.stringArray(forKey: Self.filterDeniedKeyPrefix + deviceId) ?? []
        deniedPackageIds = Set(savedDenied)
        observedApps = loadObservedApps(deviceId: deviceId)
    }

    private func pushNotificationFilter() {
        controller.updateNotificationFilter(
            paused: notificationsPaused,
            deniedPackages: Array(deniedPackageIds)
        )
    }

    private func persistObservedApps(deviceId: String) {
        let raw = observedApps.map { ["p": $0.packageId, "n": $0.appName] }
        UserDefaults.standard.set(raw, forKey: Self.observedAppsKeyPrefix + deviceId)
    }

    private func loadObservedApps(deviceId: String) -> [ObservedApp] {
        guard let raw = UserDefaults.standard.array(forKey: Self.observedAppsKeyPrefix + deviceId)
                as? [[String: String]] else { return [] }
        return raw.compactMap { dict -> ObservedApp? in
            guard let pkg = dict["p"], let name = dict["n"] else { return nil }
            return ObservedApp(packageId: pkg, appName: name)
        }
    }
}
