import SwiftUI

struct MenuBarView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("opentomac").font(.headline)
            Text(model.connectionStatus).font(.callout).foregroundStyle(.secondary)
            Divider()
            Text(model.diagnostics())
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.secondary)
            Divider()
            Button("Open opentomac") { openWindow(id: "main") }
            Button("Quit") { NSApplication.shared.terminate(nil) }
        }
        .padding(16)
        .frame(width: 280)
    }
}
