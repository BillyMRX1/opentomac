import SwiftUI
import OpentomacShared

struct MenuBarView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.medium) {
            HStack(spacing: DesignTokens.Spacing.medium) {
                BrandMark(size: 42)
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.xSmall) {
                    Text("opentomac")
                        .font(DesignTokens.TypeStyle.section)
                    HStack(spacing: DesignTokens.Spacing.small) {
                        StatusDot(active: isConnected)
                        Text(model.connectionStatus)
                            .font(DesignTokens.TypeStyle.meta)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                }
            }

            Text(model.diagnostics())
                .font(.system(size: 11, weight: .regular, design: .monospaced))
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, DesignTokens.Spacing.small)
                .padding(.horizontal, DesignTokens.Spacing.medium)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous)
                        .stroke(Color.primary.opacity(0.09), lineWidth: 1)
                }

            if model.peerCapabilities.contains(Capability.shared.BATTERY) {
                HStack(spacing: DesignTokens.Spacing.small) {
                    Image(systemName: "battery.75")
                    Text(model.batteryState?.formatted ?? "Battery unavailable")
                        .font(DesignTokens.TypeStyle.meta)
                        .foregroundStyle(.secondary)
                }
            }

            Button {
                openMainWindow()
            } label: {
                Label("Open opentomac", systemImage: "macwindow")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

            Divider()

            Button {
                NSApplication.shared.terminate(nil)
            } label: {
                Label("Quit", systemImage: "power")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.secondary)
        }
        .padding(DesignTokens.Spacing.standard)
        .frame(width: 320)
        .tint(DesignTokens.ColorToken.accent)
        .onReceive(NotificationCenter.default.publisher(for: .opentomacOpenMainWindow)) { _ in
            openMainWindow()
        }
    }

    private func openMainWindow() {
        let app = NSApplication.shared
        app.setActivationPolicy(.regular)
        openWindow(id: "main")
        app.activate(ignoringOtherApps: true)
        DispatchQueue.main.async {
            guard let window = app.windows.first(where: { $0.title == "opentomac" }) else {
                return
            }
            window.makeKeyAndOrderFront(nil)
        }
    }

    private var isConnected: Bool {
        let status = model.connectionStatus.lowercased()
        return status.contains("connected")
            && !status.contains("not connected")
            && !status.contains("disconnected")
    }
}
