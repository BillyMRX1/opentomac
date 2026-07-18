import Foundation
import SwiftUI
import OpentomacShared

struct CallsView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool
    @State private var generation: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Recent phone calls").font(.title2.bold())
                Spacer()
                Button("Close") { isPresented = false }
                    .keyboardShortcut(.cancelAction)
            }

            if !model.callsGranted {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                    Text("Allow call history access on the phone")
                        .font(.callout)
                    Spacer()
                }
                .padding(10)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
            }

            if model.calls.isEmpty {
                ContentUnavailableView(
                    model.callsGranted ? "No recent calls" : "Call history unavailable",
                    systemImage: "phone",
                    description: Text(model.callsGranted ? "Recent calls from your phone will appear here." : "Grant call history access on the phone, then reopen this sheet.")
                )
            } else {
                List {
                    ForEach(Array(model.calls.enumerated()), id: \.offset) { _, entry in
                        HStack(spacing: 12) {
                            Image(systemName: icon(for: entry.type))
                                .foregroundStyle(iconColor(for: entry.type))
                                .frame(width: 24)

                            VStack(alignment: .leading, spacing: 3) {
                                Text(entry.contactName.isEmpty ? entry.number : entry.contactName)
                                    .font(.headline)
                                HStack(spacing: 6) {
                                    if !entry.contactName.isEmpty && !entry.number.isEmpty {
                                        Text(entry.number)
                                    }
                                    Text(entry.type.capitalized)
                                    if entry.durationSec > 0 {
                                        Text(duration(entry.durationSec))
                                    }
                                }
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            }

                            Spacer()

                            Text(relativeTime(entry.dateMs))
                                .font(.callout)
                                .foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 5)
                    }
                }
                .listStyle(.inset)
            }
        }
        .padding(20)
        .frame(minWidth: 560, minHeight: 460)
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
        case "missed": return .red
        case "rejected": return .orange
        default: return .secondary
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
