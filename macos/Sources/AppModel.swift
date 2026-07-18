import Foundation
import AppKit
import OpentomacShared

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
    @Published var notificationsAuthorized: Bool?
    @Published var urlNotice: String?
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
            }
        )
        notifier.start { [weak self] key, actionIndex, text in
            self?.controller.replyToNotification(key: key, actionIndex: Int32(actionIndex), text: text)
        }
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

    func sendFile() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        if panel.runModal() == .OK {
            controller.sendFiles(paths: panel.urls.map(\.path))
        }
    }

    func openCopiedLinkOnPhone() {
        guard connectionStatus.hasPrefix("Connected to") else {
            showURLNotice("Phone is not connected")
            return
        }
        guard
            let value = NSPasteboard.general.string(forType: .string),
            let url = Self.webURL(from: value)
        else {
            showURLNotice("Clipboard does not contain an http/https link")
            return
        }
        controller.openUrlOnPhone(url: url.absoluteString)
        showURLNotice("Link sent to phone")
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
    func sendFiles(_ paths: [String]) { controller.sendFiles(paths: paths) }
    func diagnostics() -> String { controller.diagnostics() }

    private func showURLNotice(_ message: String) {
        urlNotice = message
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
            if self?.urlNotice == message { self?.urlNotice = nil }
        }
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
