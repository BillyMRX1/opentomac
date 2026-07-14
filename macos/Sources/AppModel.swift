import Foundation
import OpentomacShared

/// Bridges the Kotlin `MacController` to SwiftUI. All controller callbacks are
/// marshaled onto the main queue before touching published state.
@MainActor
final class AppModel: ObservableObject {
    @Published var connectionStatus: String = "Starting"
    @Published var pairing: MacPairingState?
    @Published var devices: [TrustedDevice] = []
    @Published var lastNotification: String?
    let protocolVersion: Int32

    private var controller: MacController!

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
            onNotification: { [weak self] title, body, _ in
                Task { @MainActor in self?.lastNotification = "\(title) — \(body)" }
            }
        )
        controller.start()
    }

    func startHosting() { controller.startHosting() }
    func confirmPairing() { controller.confirmPairing() }
    func rejectPairing() { controller.rejectPairing() }
    func cancelPairing() { controller.cancelPairing() }
    func forget(_ device: TrustedDevice) { controller.forget(deviceId: device.deviceId) }
    func sendFiles(_ paths: [String]) { controller.sendFiles(paths: paths) }
    func diagnostics() -> String { controller.diagnostics() }
}
