import SwiftUI
import OpentomacShared

@main
struct OpentomacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel()

    var body: some Scene {
        Window("opentomac", id: "main") {
            RootView()
                .environmentObject(model)
                .frame(minWidth: 1000, minHeight: 640)
        }
        .windowResizability(.contentSize)

        Window("Phone screen", id: "mirror") {
            MirrorWindow()
                .environmentObject(model)
        }
        .defaultSize(width: 360, height: 640)

        MenuBarExtra("opentomac", systemImage: "link") {
            MenuBarView()
                .environmentObject(model)
        }
        .menuBarExtraStyle(.window)
    }
}
