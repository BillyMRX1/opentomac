import AppKit

extension Notification.Name {
    static let opentomacOpenMainWindow = Notification.Name("dev.opentomac.openMainWindow")
}

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
    private var windowObservers: [NSObjectProtocol] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        windowObservers = [
            NotificationCenter.default.addObserver(
                forName: NSWindow.didBecomeKeyNotification,
                object: nil,
                queue: .main
            ) { [weak self] notification in
                guard
                    let self,
                    let window = notification.object as? NSWindow,
                    self.isNormalWindow(window)
                else { return }
                NSApp.setActivationPolicy(.regular)
            },
            NotificationCenter.default.addObserver(
                forName: NSWindow.willCloseNotification,
                object: nil,
                queue: .main
            ) { [weak self] notification in
                guard
                    let self,
                    let window = notification.object as? NSWindow,
                    self.isNormalWindow(window)
                else { return }
                DispatchQueue.main.async {
                    let hasVisibleWindow = NSApp.windows.contains {
                        $0 !== window && $0.isVisible && self.isNormalWindow($0)
                    }
                    NSApp.setActivationPolicy(hasVisibleWindow ? .regular : .accessory)
                }
            },
        ]
        NSApp.setActivationPolicy(.regular)
        MoveToApplications.offerMoveToApplicationsIfNeeded()
        DispatchQueue.main.async { [weak self] in
            self?.activateMainWindowIfPresent()
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    func applicationShouldHandleReopen(
        _ sender: NSApplication,
        hasVisibleWindows flag: Bool
    ) -> Bool {
        NSApp.setActivationPolicy(.regular)
        NotificationCenter.default.post(name: .opentomacOpenMainWindow, object: nil)
        DispatchQueue.main.async { [weak self] in
            self?.activateMainWindowIfPresent()
        }
        return true
    }

    deinit {
        windowObservers.forEach(NotificationCenter.default.removeObserver)
    }

    private func activateMainWindowIfPresent() {
        guard let window = NSApp.windows.first(where: { $0.title == "opentomac" }) else {
            return
        }
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    private func isNormalWindow(_ window: NSWindow) -> Bool {
        window.level == .normal && window.styleMask.contains(.titled)
    }
}
