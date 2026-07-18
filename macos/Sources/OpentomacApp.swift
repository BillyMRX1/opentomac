import SwiftUI
import OpentomacShared

@main
struct OpentomacApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        Window("opentomac", id: "main") {
            DashboardView()
                .environmentObject(model)
                .frame(minWidth: 480, minHeight: 360)
        }
        .windowResizability(.contentSize)

        Window("Phone screen", id: "mirror") {
            MirrorWindow()
                .environmentObject(model)
        }
        .defaultSize(width: 360, height: 640)

        Window("Webcam preview", id: "camera") {
            CameraWindow()
                .environmentObject(model)
        }
        .defaultSize(width: 960, height: 540)

        MenuBarExtra("opentomac", systemImage: "link") {
            MenuBarView()
                .environmentObject(model)
        }
        .menuBarExtraStyle(.window)
    }
}
