import SwiftUI
import UniformTypeIdentifiers
import OpentomacShared

struct DashboardView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @State private var showPairing = false
    @State private var showPhotos = false
    @State private var showContacts = false
    @State private var showMessages = false
    @State private var showCalls = false

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("opentomac").font(.largeTitle.bold())
                Spacer()
                Button("Mirror phone") {
                    openWindow(id: "mirror")
                    if !model.mirrorActive { model.startMirror() }
                }
                Button("Webcam preview") {
                    openWindow(id: "camera")
                    if !model.cameraActive { model.startCamera() }
                }
                Button("Photos") { showPhotos = true }
                Button("Contacts") { showContacts = true }
                Button("Messages") { showMessages = true }
                Button("Calls") { showCalls = true }
                Button("Pair device") {
                    model.startHosting()
                    showPairing = true
                }
            }
            Text(model.connectionStatus).foregroundStyle(.secondary)

            if model.nowPlaying.hasSession {
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Now playing on phone")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(model.nowPlaying.appName)
                            .font(.callout.weight(.semibold))
                        Text(model.nowPlaying.artist.isEmpty
                            ? model.nowPlaying.title
                            : "\(model.nowPlaying.title) — \(model.nowPlaying.artist)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 12)
                    HStack(spacing: 4) {
                        Button { model.mediaControl("previous") } label: {
                            Image(systemName: "backward.fill")
                        }
                        .help("Previous")
                        Button { model.mediaControl("play_pause") } label: {
                            Image(systemName: model.nowPlaying.isPlaying ? "pause.fill" : "play.fill")
                        }
                        .help(model.nowPlaying.isPlaying ? "Pause" : "Play")
                        Button { model.mediaControl("next") } label: {
                            Image(systemName: "forward.fill")
                        }
                        .help("Next")
                        Button { model.mediaControl("volume_down") } label: {
                            Image(systemName: "speaker.wave.1")
                        }
                        .help("Volume down")
                        Button { model.mediaControl("volume_up") } label: {
                            Image(systemName: "speaker.wave.3")
                        }
                        .help("Volume up")
                    }
                    .buttonStyle(.borderless)
                    .controlSize(.small)
                }
                .padding(10)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
            }

            HStack(spacing: 8) {
                Image(systemName: model.notificationsAuthorized == false ? "bell.slash" : "bell")
                    .foregroundStyle(model.notificationsAuthorized == false ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
                Text(model.notificationsAuthorized == false
                    ? "Notifications are off for opentomac, so phone notifications cannot appear. Allow them in System Settings."
                    : "Phone notifications appear as Mac banners. Not seeing them? Check the alert style in System Settings and turn off Focus/Do Not Disturb.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Notification Settings") { model.openNotificationSettings() }
                    .controlSize(.small)
            }
            .padding(8)
            .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))

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

            if !model.transfers.isEmpty {
                Divider()
                HStack {
                    Text("Transfers").font(.headline)
                    Spacer()
                    Button("Show received") { model.revealReceived() }
                }
                ForEach(model.transfers, id: \.id) { transfer in
                    TransferRow(transfer: transfer) { model.cancelTransfer(transfer.id) }
                }
            }

            Spacer()
            HStack {
                Text("Drop files here to send")
                Spacer()
                Button("Send file…") { model.sendFile() }
            }
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
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            model.refreshNotificationPermission()
        }
        .sheet(isPresented: $showPairing) {
            PairingSheet(isPresented: $showPairing)
                .environmentObject(model)
        }
        .sheet(isPresented: $showPhotos) {
            PhotosView(isPresented: $showPhotos)
                .environmentObject(model)
        }
        .sheet(isPresented: $showContacts) {
            ContactsView(isPresented: $showContacts)
                .environmentObject(model)
        }
        .sheet(isPresented: $showMessages) {
            MessagesView(isPresented: $showMessages)
                .environmentObject(model)
        }
        .sheet(isPresented: $showCalls) {
            CallsView(isPresented: $showCalls)
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

private struct TransferRow: View {
    let transfer: MacTransfer
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("\(transfer.isReceive ? "Received" : "Sent"): \(transfer.name)")
                    .font(.callout)
                Spacer()
                Text(transfer.state.capitalized).font(.caption).foregroundStyle(.secondary)
            }
            if !["DONE", "FAILED", "CANCELLED"].contains(transfer.state) {
                HStack(spacing: 12) {
                    ProgressView(value: Double(transfer.percent) / 100.0)
                    Button("Cancel") { onCancel() }
                        .controlSize(.small)
                }
            }
        }
        .padding(.vertical, 4)
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
