import SwiftUI
import OpentomacShared

enum AppSection: String, CaseIterable, Identifiable {
    case dashboard
    case photos
    case contacts
    case messages
    case calls

    var id: String { rawValue }

    var title: String {
        switch self {
        case .dashboard: return "Dashboard"
        case .photos: return "Photos"
        case .contacts: return "Contacts"
        case .messages: return "Messages"
        case .calls: return "Calls"
        }
    }

    var systemImage: String {
        switch self {
        case .dashboard: return "square.grid.2x2"
        case .photos: return "photo.on.rectangle"
        case .contacts: return "person.2"
        case .messages: return "message"
        case .calls: return "phone"
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.openWindow) private var openWindow
    @State private var selection: AppSection? = .dashboard

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
                Section {
                    HStack(spacing: DesignTokens.Spacing.small) {
                        BrandMark(size: 26)
                        Text("opentomac")
                            .font(DesignTokens.TypeStyle.heading)
                    }
                    .padding(.vertical, DesignTokens.Spacing.xSmall)
                }

                ForEach(AppSection.allCases) { section in
                    Label(section.title, systemImage: section.systemImage)
                        .tag(section)
                }
            }
            .listStyle(.sidebar)
            .navigationSplitViewColumnWidth(min: 190, ideal: 220)
        } detail: {
            detailView
        }
        .tint(DesignTokens.ColorToken.accent)
        .onChange(of: model.mirrorPresentationTick) {
            openWindow(id: "mirror")
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            model.refreshNotificationPermission()
        }
    }

    @ViewBuilder
    private var detailView: some View {
        switch selection {
        case .dashboard, .none:
            DashboardView()
        case .photos:
            PhotosView()
        case .contacts:
            ContactsView()
        case .messages:
            MessagesView()
        case .calls:
            CallsView()
        }
    }
}
