import Foundation
import SwiftUI
import OpentomacShared

struct CallsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var generation: Int?

    var body: some View {
        ZStack {
            LiquidBackground()

            VStack(alignment: .leading, spacing: DesignTokens.Spacing.standard) {
                SheetHeader(
                    title: "Recent phone calls",
                    subtitle: "Loaded from your phone only while this window is open"
                ) {
                    Button("Refresh", systemImage: "arrow.clockwise") {
                        if let generation { model.loadCallLog(generation: generation) }
                    }
                    .buttonStyle(.bordered)
                    .disabled(!model.peerSupports(Capability.shared.CALLS))
                    .help(model.peerSupports(Capability.shared.CALLS) ? "" : "Connected device needs an update for call history.")
                }

                if !model.callsGranted {
                    PermissionBanner(
                        systemImage: "exclamationmark.triangle.fill",
                        tint: DesignTokens.ColorToken.warning,
                        title: "Call history access is required",
                        detail: "Allow call history access on the phone, then reopen this sheet."
                    ) {
                        EmptyView()
                    }
                }

                Group {
                    if model.calls.isEmpty {
                        EmptyStateView(
                            title: model.callsGranted ? "No recent calls" : "Call history unavailable",
                            description: model.callsGranted
                                ? "Recent calls from your phone will appear here."
                                : "Grant call history access on the phone, then reopen this sheet.",
                            systemImage: "phone"
                        )
                        .frame(maxHeight: .infinity)
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                ForEach(Array(model.calls.enumerated()), id: \.offset) { index, entry in
                                    if index > 0 { Divider() }
                                    callRow(entry)
                                }
                            }
                            .padding(.horizontal, DesignTokens.Spacing.standard)
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .glassCard(material: .regularMaterial)
            }
            .padding(DesignTokens.Spacing.xLarge)
        }
        .tint(DesignTokens.ColorToken.accent)
        .onAppear {
            let token = model.beginCallSession()
            generation = token
            model.loadCallLog(generation: token)
        }
        .onDisappear {
            // Call history is PII; don't let it outlive the sheet.
            if let generation { model.clearCallLog(generation: generation) }
            generation = nil
        }
    }

    private func callRow(_ entry: MacCallEntry) -> some View {
        HStack(spacing: DesignTokens.Spacing.medium) {
            IconBadge(
                systemName: icon(for: entry.type),
                tint: iconColor(for: entry.type),
                size: 42
            )

            VStack(alignment: .leading, spacing: 3) {
                Text(entry.contactName.isEmpty ? entry.number : entry.contactName)
                    .font(DesignTokens.TypeStyle.heading)
                HStack(spacing: 6) {
                    if !entry.contactName.isEmpty && !entry.number.isEmpty {
                        Text(entry.number)
                    }
                    Text(entry.type.capitalized)
                    if entry.durationSec > 0 {
                        Text(duration(entry.durationSec))
                    }
                }
                .font(DesignTokens.TypeStyle.meta)
                .foregroundStyle(.secondary)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 3) {
                Text(relativeTime(entry.dateMs))
                    .font(DesignTokens.TypeStyle.body)
                Text(
                    Date(timeIntervalSince1970: TimeInterval(entry.dateMs) / 1_000)
                        .formatted(date: .omitted, time: .shortened)
                )
                .font(DesignTokens.TypeStyle.meta)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 14)
    }

    private func icon(for type: String) -> String {
        switch type {
        case "incoming": return "arrow.down.left"
        case "outgoing": return "arrow.up.right"
        case "missed", "rejected": return "phone.down.fill"
        default: return "phone.fill"
        }
    }

    private func iconColor(for type: String) -> Color {
        switch type {
        case "missed": return DesignTokens.ColorToken.danger
        case "rejected": return DesignTokens.ColorToken.warning
        default: return DesignTokens.ColorToken.accent
        }
    }

    private func relativeTime(_ millis: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(millis) / 1_000)
            .formatted(.relative(presentation: .named, unitsStyle: .wide))
    }

    private func duration(_ seconds: Int32) -> String {
        let minutes = seconds / 60
        let remainder = seconds % 60
        return minutes > 0 ? "\(minutes)m \(remainder)s" : "\(remainder)s"
    }
}
