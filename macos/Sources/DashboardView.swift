import SwiftUI
import UniformTypeIdentifiers
import OpentomacShared

struct DashboardView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showPairing = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("opentomac").font(.largeTitle.bold())
                Spacer()
                Button("Pair device") {
                    model.startHosting()
                    showPairing = true
                }
            }
            Text(model.connectionStatus).foregroundStyle(.secondary)

            if let note = model.lastNotification {
                Text(note).font(.callout).padding(8)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
            }

            Divider()
            Text("Paired devices").font(.headline)
            if model.devices.isEmpty {
                Text("No devices yet. Click Pair device and scan the code on your Android phone.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.devices, id: \.deviceId) { device in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(device.displayName)
                            Text(device.platform).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Forget") { model.forget(device) }
                    }
                    .padding(.vertical, 4)
                }
            }

            Spacer()
            Text("Drop files here to send")
                .frame(maxWidth: .infinity)
                .padding()
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
                .onDrop(of: [.fileURL], isTargeted: nil) { providers in
                    handleDrop(providers)
                }

            Text("Protocol v\(model.protocolVersion)").font(.footnote).foregroundStyle(.tertiary)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .sheet(isPresented: $showPairing) {
            PairingSheet(isPresented: $showPairing)
                .environmentObject(model)
        }
    }

    private func handleDrop(_ providers: [NSItemProvider]) -> Bool {
        var paths: [String] = []
        let group = DispatchGroup()
        for provider in providers {
            group.enter()
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                if let url { paths.append(url.path) }
                group.leave()
            }
        }
        group.notify(queue: .main) {
            if !paths.isEmpty { model.sendFiles(paths) }
        }
        return true
    }
}

private struct PairingSheet: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool

    var body: some View {
        VStack(spacing: 16) {
            let state = model.pairing
            switch state?.phase {
            case "hosting":
                Text("Scan with your Android phone").font(.headline)
                if let code = state?.code, let qr = QRCode.image(from: code) {
                    qr.resizable().interpolation(.none).frame(width: 220, height: 220)
                }
                Button("Cancel") { model.cancelPairing(); isPresented = false }
            case "verify":
                Text("Confirm this code matches your phone").font(.headline)
                Text(state?.code ?? "").font(.system(size: 40, weight: .bold, design: .monospaced))
                HStack {
                    Button("Confirm") { model.confirmPairing() }.keyboardShortcut(.defaultAction)
                    Button("Reject") { model.rejectPairing(); isPresented = false }
                }
            case "done":
                Text("Paired successfully").font(.headline)
                Button("Done") { isPresented = false }.keyboardShortcut(.defaultAction)
            case "error":
                Text("Pairing failed").font(.headline)
                Text(state?.message ?? "").foregroundStyle(.secondary)
                Button("Close") { isPresented = false }
            default:
                ProgressView("Preparing…")
                Button("Cancel") { model.cancelPairing(); isPresented = false }
            }
        }
        .padding(28)
        .frame(width: 320)
    }
}
