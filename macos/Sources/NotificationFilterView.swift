import SwiftUI
import OpentomacShared

/// Compact sheet for controlling which phone notifications appear on this Mac.
/// Launched from the notification-readiness banner in DashboardView.
///
/// Shows:
///  - A Pause / Resume toggle (globally halts all mirrored notifications)
///  - Per-app allow / deny toggles for every app observed through notification mirroring
///  - A disconnected or unsupported placeholder when appropriate
struct NotificationFilterView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool

    var body: some View {
        ZStack {
            LiquidBackground()

            VStack(spacing: 0) {
                // Title bar
                HStack {
                    Text("Notification Filters")
                        .font(DesignTokens.TypeStyle.section)
                    Spacer()
                    Button { isPresented = false } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
                .padding(DesignTokens.Spacing.standard)

                Divider()

                ScrollView {
                    VStack(alignment: .leading, spacing: DesignTokens.Spacing.standard) {
                        content
                    }
                    .padding(DesignTokens.Spacing.standard)
                }
            }
            .frame(width: 380)
            .frame(minHeight: 300)
        }
        .tint(DesignTokens.ColorToken.accent)
    }

    @ViewBuilder
    private var content: some View {
        let isConnected = model.activePeerDeviceId != nil
        let supported = model.notificationFilteringSupported

        if !isConnected {
            EmptyStateView(
                title: "Not connected",
                description: "Connect to your Android phone to manage notification filters.",
                systemImage: "iphone.slash"
            )
        } else if !supported {
            EmptyStateView(
                title: "Not supported",
                description: "The connected device needs an app update to support notification filtering.",
                systemImage: "exclamationmark.triangle"
            )
        } else {
            pauseToggle
            appList
        }
    }

    private var pauseToggle: some View {
        HStack(spacing: DesignTokens.Spacing.medium) {
            IconBadge(
                systemName: model.notificationsPaused ? "bell.slash.fill" : "bell.badge.fill",
                tint: model.notificationsPaused ? DesignTokens.ColorToken.warning : DesignTokens.ColorToken.accent,
                size: 36
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(model.notificationsPaused ? "Notifications paused" : "Notifications active")
                    .font(DesignTokens.TypeStyle.bodyEmphasized)
                Text(model.notificationsPaused
                    ? "All phone notifications are suppressed on this Mac."
                    : "Phone notifications appear as Mac banners."
                )
                .font(DesignTokens.TypeStyle.meta)
                .foregroundStyle(.secondary)
            }
            Spacer()
            Text(model.notificationsPaused ? "Off" : "On")
                .font(DesignTokens.TypeStyle.meta)
                .foregroundStyle(model.notificationsPaused ? DesignTokens.ColorToken.warning : .secondary)
            Toggle("Mirror phone notifications", isOn: Binding(
                get: { !model.notificationsPaused },
                set: { active in
                    model.applyFilterPolicy(
                        paused: !active,
                        deniedPackageIds: model.deniedPackageIds
                    )
                }
            ))
            .labelsHidden()
        }
        .padding(DesignTokens.Spacing.standard)
        .glassCard()
    }

    @ViewBuilder
    private var appList: some View {
        if model.observedApps.isEmpty {
            HStack(spacing: DesignTokens.Spacing.medium) {
                Image(systemName: "app.badge.clock")
                    .font(.title2)
                    .foregroundStyle(.secondary)
                Text("No apps seen yet. Notifications from your phone will appear here as they arrive.")
                    .font(DesignTokens.TypeStyle.meta)
                    .foregroundStyle(.secondary)
            }
            .padding(DesignTokens.Spacing.standard)
            .glassCard()
        } else {
            VStack(alignment: .leading, spacing: 0) {
                Text("Per-app settings")
                    .font(DesignTokens.TypeStyle.heading)
                    .padding(.bottom, DesignTokens.Spacing.small)

                Text("Apps listed below have sent at least one notification.")
                    .font(DesignTokens.TypeStyle.meta)
                    .foregroundStyle(.secondary)
                    .padding(.bottom, DesignTokens.Spacing.medium)

                ForEach(model.observedApps) { app in
                    appRow(app)
                    if app.id != model.observedApps.last?.id {
                        Divider()
                    }
                }
            }
            .padding(DesignTokens.Spacing.standard)
            .glassCard()
        }
    }

    private func appRow(_ app: ObservedApp) -> some View {
        let denied = model.deniedPackageIds.contains(app.packageId)
        return HStack(spacing: DesignTokens.Spacing.medium) {
            VStack(alignment: .leading, spacing: 2) {
                Text(app.appName)
                    .font(DesignTokens.TypeStyle.bodyEmphasized)
                    .lineLimit(1)
                Text(app.packageId)
                    .font(DesignTokens.TypeStyle.meta)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Text(denied ? "Blocked" : "Allowed")
                .font(DesignTokens.TypeStyle.meta)
                .foregroundStyle(denied ? DesignTokens.ColorToken.warning : .secondary)
            Toggle("Allow", isOn: Binding(
                get: { !denied },
                set: { allow in
                    var updated = model.deniedPackageIds
                    if allow {
                        updated.remove(app.packageId)
                    } else {
                        updated.insert(app.packageId)
                    }
                    model.applyFilterPolicy(
                        paused: model.notificationsPaused,
                        deniedPackageIds: updated
                    )
                }
            ))
            .labelsHidden()
            .disabled(model.notificationsPaused)
            .opacity(model.notificationsPaused ? 0.4 : 1)
            .help(
                model.notificationsPaused
                    ? "Resume notifications to change per-app settings."
                    : (denied ? "Allow \(app.appName)" : "Block \(app.appName)")
            )
        }
        .padding(.vertical, 10)
    }
}
