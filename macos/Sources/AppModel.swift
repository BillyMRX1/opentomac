import Foundation
import OpentomacShared

/// Holds app-wide state. The scaffold proves the KMP framework links and is callable;
/// the session, pairing, and feature wiring arrive with the macOS feature work.
@MainActor
final class AppModel: ObservableObject {
    @Published var status: String = "Ready to pair"
    let protocolVersion: Int32

    init() {
        protocolVersion = ProtocolCodec.shared.PROTOCOL_VERSION
    }
}
