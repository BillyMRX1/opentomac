import Foundation
import SystemExtensions

/// Drives the signed-build installation flow. Unsigned/ad-hoc builds are
/// expected to fail here; the exact OS error is surfaced in the dashboard.
final class CameraExtensionInstaller: NSObject, OSSystemExtensionRequestDelegate {
    static let extensionIdentifier = "dev.opentomac.mac.CameraExtension"

    var onStatus: ((String) -> Void)?

    func install() {
        update("Requesting camera extension installation…")
        let request = OSSystemExtensionRequest.activationRequest(
            forExtensionWithIdentifier: Self.extensionIdentifier,
            queue: .main
        )
        request.delegate = self
        OSSystemExtensionManager.shared.submitRequest(request)
    }

    func request(
        _ request: OSSystemExtensionRequest,
        actionForReplacingExtension existing: OSSystemExtensionProperties,
        withExtension ext: OSSystemExtensionProperties
    ) -> OSSystemExtensionRequest.ReplacementAction {
        .replace
    }

    func requestNeedsUserApproval(_ request: OSSystemExtensionRequest) {
        update("Approval required in System Settings › Privacy & Security")
    }

    func request(_ request: OSSystemExtensionRequest, didFinishWithResult result: OSSystemExtensionRequest.Result) {
        switch result {
        case .completed:
            update("Camera extension installed")
        case .willCompleteAfterReboot:
            update("Camera extension will activate after restart")
        @unknown default:
            update("Camera extension request completed with an unknown result")
        }
    }

    func request(_ request: OSSystemExtensionRequest, didFailWithError error: Error) {
        update("Camera extension install failed: \(error.localizedDescription)")
    }

    private func update(_ status: String) {
        DispatchQueue.main.async { [weak self] in self?.onStatus?(status) }
    }
}
