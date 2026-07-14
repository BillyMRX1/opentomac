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

        MenuBarExtra("opentomac", systemImage: "link") {
            MenuBarView()
                .environmentObject(model)
        }
        .menuBarExtraStyle(.window)
    }
}
