import SwiftUI
import UniformTypeIdentifiers
import OpentomacShared

struct DashboardView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @State private var showPairing = false
    @State private var isDropTargeted = false

    var body: some View {
        ZStack {
            LiquidBackground()

            ScrollView {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.medium + 2) {
                    hero
                    notificationStatus
                    nowPlayingCard

                    if let note = model.lastNotification {
                        latestNotification(note)
                    }

                    moduleGrid
                    dropZone

                    Text("Protocol v\(model.protocolVersion)")
                        .font(DesignTokens.TypeStyle.meta)
                        .foregroundStyle(.tertiary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                .padding(DesignTokens.Spacing.xxLarge)
            }
        }
        .tint(DesignTokens.ColorToken.accent)
        .sheet(isPresented: $showPairing) {
            PairingSheet(isPresented: $showPairing)
                .environmentObject(model)
        }
    }

    private var hero: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: DesignTokens.Spacing.xxLarge) {
                brandLockup
                    .frame(minWidth: 250, alignment: .leading)
                toolGrid
            }
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.xLarge) {
                brandLockup
                toolGrid
            }
        }
        .padding(.bottom, DesignTokens.Spacing.small)
    }

    private var brandLockup: some View {
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.small) {
            HStack(spacing: DesignTokens.Spacing.medium) {
                BrandMark()
                Text("opentomac")
                    .font(DesignTokens.TypeStyle.display)
                    .tracking(-0.5)
            }
            HStack(spacing: DesignTokens.Spacing.small) {
                StatusDot(active: isConnected)
                Text(model.connectionStatus)
                    .font(DesignTokens.TypeStyle.body)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            .padding(.leading, 2)
        }
        .padding(.top, DesignTokens.Spacing.small)
    }

    private var toolGrid: some View {
        LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(minimum: 72), spacing: 9), count: 2),
            spacing: 9
        ) {
            toolButton("Mirror", systemImage: "iphone") {
                openWindow(id: "mirror")
                if !model.mirrorActive { model.startMirror() }
            }
            .disabled(!model.peerSupports(Capability.shared.SCREEN_MIRRORING))
            .help(model.peerSupports(Capability.shared.SCREEN_MIRRORING) ? "" : "Connected device needs an update for screen mirroring.")
            toolButton("Pair", systemImage: "qrcode") {
                model.startHosting()
                showPairing = true
            }
        }
    }

    private func toolButton(
        _ title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: DesignTokens.Spacing.small) {
                Image(systemName: systemImage)
                    .font(.system(size: 23, weight: .medium))
                    .symbolRenderingMode(.hierarchical)
                Text(title)
                    .font(DesignTokens.TypeStyle.metaEmphasized)
                    .lineLimit(1)
            }
            .foregroundStyle(.primary)
            .padding(.horizontal, DesignTokens.Spacing.small)
        }
        .buttonStyle(ToolTileButtonStyle())
    }

    private var notificationStatus: some View {
        PermissionBanner(
            systemImage: model.notificationsAuthorized == false ? "bell.slash.fill" : "bell.badge.fill",
            tint: model.notificationsAuthorized == false ? DesignTokens.ColorToken.warning : DesignTokens.ColorToken.accent,
            title: model.notificationsAuthorized == false
                ? "Notifications are off for opentomac"
                : "Phone notifications are ready",
            detail: model.notificationsAuthorized == false
                ? "Allow notifications in System Settings to receive phone banners on this Mac."
                : "Banners follow the Mac’s Focus and notification settings."
        ) {
            Button("Notification Settings") { model.openNotificationSettings() }
                .buttonStyle(.bordered)
                .controlSize(.small)
        }
    }

    private var nowPlayingCard: some View {
        Group {
            if model.nowPlaying.hasSession {
                HStack(spacing: DesignTokens.Spacing.standard) {
                    RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [Color.primary.opacity(0.82), DesignTokens.ColorToken.accent.opacity(0.72)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                        .overlay {
                            Image(systemName: "music.note")
                                .font(.title2.weight(.medium))
                                .foregroundStyle(.white.opacity(0.9))
                        }
                        .frame(width: 52, height: 52)

                    VStack(alignment: .leading, spacing: 2) {
                        Text("Now playing on phone · \(model.nowPlaying.appName)")
                            .font(DesignTokens.TypeStyle.meta)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        Text(model.nowPlaying.title)
                            .font(DesignTokens.TypeStyle.bodyEmphasized)
                            .lineLimit(1)
                        if !model.nowPlaying.artist.isEmpty {
                            Text(model.nowPlaying.artist)
                                .font(DesignTokens.TypeStyle.meta)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    Spacer(minLength: DesignTokens.Spacing.medium)
                    mediaControls
                }
                .padding(.horizontal, DesignTokens.Spacing.standard)
                .padding(.vertical, 14)
            } else {
                VStack(alignment: .leading, spacing: DesignTokens.Spacing.medium) {
                    Text("Now playing on phone")
                        .font(DesignTokens.TypeStyle.section)
                    HStack(spacing: DesignTokens.Spacing.medium) {
                        IconBadge(systemName: "music.note", tint: .secondary, size: 42)
                        Text("Media controls appear automatically while your connected phone is playing audio.")
                            .font(DesignTokens.TypeStyle.meta)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(DesignTokens.Spacing.standard)
            }
        }
        .glassCard()
    }

    private var mediaControls: some View {
        HStack(spacing: DesignTokens.Spacing.xSmall) {
            mediaButton("Previous", systemImage: "backward.fill") { model.mediaControl("previous") }
            mediaButton(
                model.nowPlaying.isPlaying ? "Pause" : "Play",
                systemImage: model.nowPlaying.isPlaying ? "pause.fill" : "play.fill"
            ) { model.mediaControl("play_pause") }
            mediaButton("Next", systemImage: "forward.fill") { model.mediaControl("next") }
            mediaButton("Volume down", systemImage: "speaker.wave.1.fill") { model.mediaControl("volume_down") }
            mediaButton("Volume up", systemImage: "speaker.wave.3.fill") { model.mediaControl("volume_up") }
        }
    }

    private func mediaButton(
        _ title: String,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .frame(width: 20, height: 20)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.circle)
        .controlSize(.small)
        .help(title)
        .accessibilityLabel(title)
        .disabled(!model.peerSupports(Capability.shared.MEDIA_CONTROL))
    }

    private func latestNotification(_ note: String) -> some View {
        HStack(spacing: DesignTokens.Spacing.medium) {
            IconBadge(systemName: "app.badge", tint: DesignTokens.ColorToken.secondaryTint, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text("Latest phone notification")
                    .font(DesignTokens.TypeStyle.bodyEmphasized)
                Text(note)
                    .font(DesignTokens.TypeStyle.meta)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            Spacer()
            Text("now")
                .font(DesignTokens.TypeStyle.meta)
                .foregroundStyle(.tertiary)
        }
        .padding(DesignTokens.Spacing.standard)
        .glassCard()
    }

    private var moduleGrid: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: DesignTokens.Spacing.medium + 2) {
                devicesCard.frame(minWidth: 340, maxWidth: .infinity)
                transfersCard.frame(minWidth: 340, maxWidth: .infinity)
            }
            VStack(spacing: DesignTokens.Spacing.medium + 2) {
                devicesCard
                transfersCard
            }
        }
    }

    private var devicesCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Paired devices")
                    .font(DesignTokens.TypeStyle.section)
                Spacer()
                Button("Pair another") {
                    model.startHosting()
                    showPairing = true
                }
                .buttonStyle(.borderless)
                .font(DesignTokens.TypeStyle.meta)
            }
            .padding(.bottom, DesignTokens.Spacing.medium)

            if model.devices.isEmpty {
                EmptyStateView(
                    title: "No devices yet",
                    description: "Pair once by scanning a code on your Android phone.",
                    systemImage: "link.badge.plus"
                )
            } else {
                ForEach(model.devices, id: \.deviceId) { device in
                    Divider()
                    HStack(spacing: DesignTokens.Spacing.medium) {
                        IconBadge(systemName: "iphone", size: 36)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(device.displayName)
                                .font(DesignTokens.TypeStyle.bodyEmphasized)
                            Text(device.platform)
                                .font(DesignTokens.TypeStyle.meta)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button("Forget…") { model.forget(device) }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                    }
                    .padding(.vertical, 11)
                }
            }
        }
        .padding(DesignTokens.Spacing.standard + 2)
        .glassCard()
    }

    private var transfersCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Transfers")
                    .font(DesignTokens.TypeStyle.section)
                Spacer()
                Button("Show received") { model.revealReceived() }
                    .buttonStyle(.borderless)
                    .font(DesignTokens.TypeStyle.meta)
            }
            .padding(.bottom, DesignTokens.Spacing.medium)

            if model.transfers.isEmpty {
                EmptyStateView(
                    title: "No transfers",
                    description: "Files you send or receive will appear here.",
                    systemImage: "arrow.down.doc",
                )
            } else {
                ForEach(model.transfers, id: \.id) { transfer in
                    Divider()
                    TransferRow(transfer: transfer) { model.cancelTransfer(transfer.id) }
                }
            }
        }
        .padding(DesignTokens.Spacing.standard + 2)
        .glassCard()
    }

    private var dropZone: some View {
        HStack(spacing: DesignTokens.Spacing.medium) {
            IconBadge(
                systemName: isDropTargeted ? "arrow.down.doc.fill" : "plus",
                tint: isDropTargeted ? DesignTokens.ColorToken.success : DesignTokens.ColorToken.accent,
                size: 38
            )
            VStack(alignment: .leading, spacing: 2) {
                Text("Drop files here to send")
                    .font(DesignTokens.TypeStyle.bodyEmphasized)
                Text(isConnected
                    ? "They travel directly to your phone over your local network."
                    : "Connect or pair a phone first.")
                    .font(DesignTokens.TypeStyle.meta)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button("Send file…") { model.sendFile() }
                .buttonStyle(.borderedProminent)
                .controlSize(.regular)
                .disabled(!model.peerSupports(Capability.shared.FILE_TRANSFER))
                .help(model.peerSupports(Capability.shared.FILE_TRANSFER) ? "" : "Connected device needs an update for file transfers.")
        }
        .padding(.horizontal, DesignTokens.Spacing.standard)
        .padding(.vertical, DesignTokens.Spacing.medium)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.large, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.large, style: .continuous)
                .strokeBorder(
                    isDropTargeted ? DesignTokens.ColorToken.accent : Color.secondary.opacity(0.4),
                    style: StrokeStyle(lineWidth: 1, dash: [6, 5])
                )
        }
        .scaleEffect(isDropTargeted ? 1.01 : 1)
        .animation(DesignTokens.Motion.standard, value: isDropTargeted)
        .onDrop(of: [.fileURL], isTargeted: $isDropTargeted) { providers in
            handleDrop(providers)
        }
    }

    private var isConnected: Bool {
        let status = model.connectionStatus.lowercased()
        return status.contains("connected")
            && !status.contains("not connected")
            && !status.contains("disconnected")
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
        HStack(alignment: .top, spacing: DesignTokens.Spacing.medium) {
            IconBadge(
                systemName: transfer.isReceive ? "arrow.down" : "arrow.up",
                tint: transfer.state == "FAILED" ? DesignTokens.ColorToken.danger : DesignTokens.ColorToken.accent,
                size: 34
            )
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.small) {
                HStack {
                    Text(transfer.name)
                        .font(DesignTokens.TypeStyle.bodyEmphasized)
                        .lineLimit(1)
                    Spacer()
                    Text("\(transfer.isReceive ? "Receiving" : "Sending") · \(transfer.state.capitalized)")
                        .font(DesignTokens.TypeStyle.meta)
                        .foregroundStyle(.secondary)
                }
                if !["DONE", "FAILED", "CANCELLED"].contains(transfer.state) {
                    HStack(spacing: DesignTokens.Spacing.medium) {
                        ProgressView(value: Double(transfer.percent) / 100.0)
                            .tint(DesignTokens.ColorToken.accent)
                        Text("\(transfer.percent)%")
                            .font(DesignTokens.TypeStyle.meta)
                            .foregroundStyle(.secondary)
                        Button("Cancel") { onCancel() }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                    }
                }
            }
        }
        .padding(.vertical, 11)
    }
}

private struct PairingSheet: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool

    var body: some View {
        ZStack {
            LiquidBackground()
            pairingContent
                .padding(DesignTokens.Spacing.xxLarge)
                .frame(width: 430)
                .frame(minHeight: 500)
                .glassCard(material: .regularMaterial)
                .padding(DesignTokens.Spacing.xxLarge)
        }
        .tint(DesignTokens.ColorToken.accent)
    }

    @ViewBuilder
    private var pairingContent: some View {
        let state = model.pairing
        switch state?.phase {
        case "hosting":
            VStack(spacing: DesignTokens.Spacing.standard) {
                pairingHeader(
                    title: "Scan with your Android phone",
                    detail: "Open opentomac on the phone, choose Pair, then point the camera here."
                )
                if let code = state?.code, let qr = QRCode.image(from: code) {
                    qr.resizable()
                        .interpolation(.none)
                        .frame(width: 216, height: 216)
                        .padding(14)
                        .background(.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .shadow(color: .black.opacity(0.12), radius: 22, y: 10)
                }
                Text("Local network only")
                    .font(DesignTokens.TypeStyle.meta)
                    .foregroundStyle(.secondary)
                Button("Cancel") { model.cancelPairing(); isPresented = false }
                    .buttonStyle(.bordered)
                    .keyboardShortcut(.cancelAction)
            }
        case "verify":
            VStack(spacing: DesignTokens.Spacing.large) {
                pairingHeader(
                    title: "Confirm the code",
                    detail: "Make sure the same digits appear on your phone."
                )
                Text(state?.code ?? "")
                    .font(DesignTokens.TypeStyle.verification)
                    .tracking(4)
                    .textSelection(.enabled)
                HStack {
                    Button("Reject") { model.rejectPairing(); isPresented = false }
                        .buttonStyle(.bordered)
                    Button("Confirm") { model.confirmPairing() }
                        .buttonStyle(.borderedProminent)
                        .keyboardShortcut(.defaultAction)
                }
            }
        case "done":
            VStack(spacing: DesignTokens.Spacing.standard) {
                pairingStatus(systemImage: "checkmark", tint: DesignTokens.ColorToken.success)
                pairingHeader(
                    title: "Paired successfully",
                    detail: "Your phone can reconnect on this local network without scanning again."
                )
                Button("Done") { isPresented = false }
                    .buttonStyle(.borderedProminent)
                    .keyboardShortcut(.defaultAction)
            }
        case "error":
            VStack(spacing: DesignTokens.Spacing.standard) {
                pairingStatus(systemImage: "xmark", tint: DesignTokens.ColorToken.danger)
                pairingHeader(
                    title: "Pairing failed",
                    detail: state?.message ?? "No trust record was saved on either device."
                )
                Button("Close") { isPresented = false }
                    .buttonStyle(.borderedProminent)
            }
        default:
            VStack(spacing: DesignTokens.Spacing.standard) {
                ProgressView()
                    .controlSize(.large)
                pairingHeader(title: "Preparing…", detail: "Starting a secure local pairing session.")
                Button("Cancel") { model.cancelPairing(); isPresented = false }
                    .buttonStyle(.bordered)
                    .keyboardShortcut(.cancelAction)
            }
        }
    }

    private func pairingHeader(title: String, detail: String) -> some View {
        VStack(spacing: DesignTokens.Spacing.small) {
            Text(title)
                .font(DesignTokens.TypeStyle.title)
                .multilineTextAlignment(.center)
            Text(detail)
                .font(DesignTokens.TypeStyle.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private func pairingStatus(systemImage: String, tint: Color) -> some View {
        RoundedRectangle(cornerRadius: 22, style: .continuous)
            .fill(tint.opacity(0.12))
            .overlay {
                Image(systemName: systemImage)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(tint)
            }
            .frame(width: 72, height: 72)
    }
}
