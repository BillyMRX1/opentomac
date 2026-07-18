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

/// Bridges the Kotlin `MacController` to SwiftUI. All controller callbacks are
/// marshaled onto the main queue before touching published state.
@MainActor
final class AppModel: ObservableObject {
    @Published var connectionStatus: String = "Starting"
    @Published var pairing: MacPairingState?
    @Published var devices: [TrustedDevice] = []
    @Published var lastNotification: String?
    @Published var transfers: [MacTransfer] = []
    @Published var photos: [MacPhoto] = []
    @Published var contacts: [MacContact] = []
    @Published var contactsGranted = true
    @Published var notificationsAuthorized: Bool?
    @Published var nowPlaying = NowPlayingState(
        appName: "",
        title: "",
        artist: "",
        isPlaying: false,
        hasSession: false
    )
    let protocolVersion: Int32

    private var controller: MacController!
    private let notifier = NotificationBridge()

    init() {
        protocolVersion = ProtocolCodec.shared.PROTOCOL_VERSION
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
            }
        )
        notifier.start(
            onReply: { [weak self] key, actionIndex, text in
                self?.controller.replyToNotification(key: key, actionIndex: Int32(actionIndex), text: text)
            },
            onScreenshotSend: { [weak self] mediaId in
                // Reuses the photo-import path: the original arrives as a transfer.
                self?.controller.importPhoto(id: mediaId)
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

    func loadPhotos() { controller.loadPhotos() }

    func importPhoto(_ id: String) { controller.importPhoto(id: id) }

    func requestThumbnail(_ id: String, completion: @escaping (Data?) -> Void) {
        controller.requestThumbnail(id: id) { base64 in
            let data = base64.flatMap { Data(base64Encoded: $0) }
            Task { @MainActor in completion(data) }
        }
    }

    func searchContacts(_ query: String) {
        controller.searchContacts(query: query) { [weak self] items, granted in
            Task { @MainActor in
                self?.contacts = items
                self?.contactsGranted = granted.boolValue
            }
        }
    }

    func sendFile() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        if panel.runModal() == .OK {
            controller.sendFiles(paths: panel.urls.map(\.path))
        }
    }

    func mediaControl(_ command: String) { controller.mediaControl(command: command) }

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
    func sendFiles(_ paths: [String]) { controller.sendFiles(paths: paths) }
    func diagnostics() -> String { controller.diagnostics() }

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
