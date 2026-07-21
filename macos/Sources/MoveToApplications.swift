import AppKit

/// Offers to copy opentomac into /Applications when launched from elsewhere (LetsMove-style).
enum MoveToApplications {
    private static let declinedKey = "declinedMoveToApplications"

    static func offerMoveToApplicationsIfNeeded() {
        let bundleURL = Bundle.main.bundleURL
        let path = bundleURL.path

        if path.hasPrefix("/Applications/") {
            return
        }
        if path.contains("/DerivedData/") || path.contains("/Xcode/") {
            return
        }
        if UserDefaults.standard.bool(forKey: declinedKey) {
            return
        }

        let alert = NSAlert()
        alert.alertStyle = .informational
        alert.messageText = "Move opentomac to the Applications folder?"
        alert.informativeText = "Keeping opentomac in Applications makes it easy to find and lets it stay running from a permanent location."
        alert.addButton(withTitle: "Move to Applications")
        alert.addButton(withTitle: "Not Now")

        let response = alert.runModal()
        guard response == .alertFirstButtonReturn else {
            UserDefaults.standard.set(true, forKey: declinedKey)
            return
        }

        let destinationURL = URL(fileURLWithPath: "/Applications/Opentomac.app")
        let fileManager = FileManager.default

        do {
            if fileManager.fileExists(atPath: destinationURL.path) {
                try fileManager.removeItem(at: destinationURL)
            }
            try fileManager.copyItem(at: bundleURL, to: destinationURL)
        } catch {
            showFailureAlert(error: error)
            return
        }

        let configuration = NSWorkspace.OpenConfiguration()
        NSWorkspace.shared.openApplication(at: destinationURL, configuration: configuration) { _, error in
            DispatchQueue.main.async {
                if let error {
                    showFailureAlert(error: error)
                    return
                }
                NSApp.terminate(nil)
            }
        }
    }

    private static func showFailureAlert(error: Error) {
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "Couldn't move opentomac to Applications"
        alert.informativeText = error.localizedDescription
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        MoveToApplications.offerMoveToApplicationsIfNeeded()
    }
}
