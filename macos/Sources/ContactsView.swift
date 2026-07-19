import SwiftUI
import AppKit
import OpentomacShared

struct ContactsView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool
    @State private var query = ""
    @State private var hasSearched = false

    var body: some View {
        ZStack {
            LiquidBackground()

            VStack(alignment: .leading, spacing: DesignTokens.Spacing.standard) {
                SheetHeader(
                    title: "Phone contacts",
                    subtitle: "Searches are sent to your phone on demand"
                ) {
                    Button("Close") { isPresented = false }
                        .buttonStyle(.bordered)
                        .keyboardShortcut(.cancelAction)
                }

                HStack(spacing: DesignTokens.Spacing.small) {
                    HStack(spacing: DesignTokens.Spacing.small) {
                        Image(systemName: "magnifyingglass")
                            .foregroundStyle(.secondary)
                        TextField("Search by name, phone, or email", text: $query)
                            .textFieldStyle(.plain)
                            .onSubmit(search)
                    }
                    .padding(.horizontal, DesignTokens.Spacing.medium)
                    .frame(height: 36)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.small, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: DesignTokens.Radius.small, style: .continuous)
                            .stroke(Color.primary.opacity(0.12), lineWidth: 1)
                    }

                    Button("Search", action: search)
                        .buttonStyle(.borderedProminent)
                }

                if !model.contactsGranted {
                    PermissionBanner(
                        systemImage: "person.crop.circle.badge.exclamationmark",
                        tint: DesignTokens.ColorToken.warning,
                        title: "Contact access is required",
                        detail: "Allow contact access on the phone, then search again."
                    ) {
                        EmptyView()
                    }
                }

                Group {
                    if model.contacts.isEmpty {
                        EmptyStateView(
                            title: hasSearched ? "No contacts found" : "Search your phone contacts",
                            description: hasSearched
                                ? "Try a name, phone number, or email address."
                                : "Enter a name, phone number, or email address above.",
                            systemImage: hasSearched ? "person.crop.circle.badge.questionmark" : "person.text.rectangle"
                        )
                        .frame(maxHeight: .infinity)
                    } else {
                        ScrollView {
                            LazyVStack(spacing: 0) {
                                ForEach(Array(model.contacts.enumerated()), id: \.offset) { index, contact in
                                    if index > 0 { Divider() }
                                    ContactRow(contact: contact)
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
        .frame(minWidth: 620, minHeight: 500)
        .onDisappear {
            // Contact details are PII; don't let results outlive the sheet.
            model.contacts = []
        }
    }

    private func search() {
        hasSearched = true
        model.searchContacts(query)
    }
}

private struct ContactRow: View {
    let contact: MacContact

    var body: some View {
        HStack(alignment: .top, spacing: DesignTokens.Spacing.medium) {
            Text(initials)
                .font(DesignTokens.TypeStyle.bodyEmphasized)
                .foregroundStyle(.white)
                .frame(width: 42, height: 42)
                .background(
                    LinearGradient(
                        colors: [DesignTokens.ColorToken.accentHover, DesignTokens.ColorToken.accent.opacity(0.78)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    in: RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous)
                )

            VStack(alignment: .leading, spacing: DesignTokens.Spacing.small) {
                Text(contact.name)
                    .font(DesignTokens.TypeStyle.heading)

                ForEach(Array(contact.phones.enumerated()), id: \.offset) { _, phone in
                    ContactValueRow(icon: "phone", value: phone)
                }
                ForEach(Array(contact.emails.enumerated()), id: \.offset) { _, email in
                    ContactValueRow(icon: "envelope", value: email)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 14)
    }

    private var initials: String {
        let parts = contact.name.split(separator: " ")
        let value = parts.prefix(2).compactMap(\.first).map(String.init).joined()
        return value.isEmpty ? "?" : value.uppercased()
    }
}

private struct ContactValueRow: View {
    let icon: String
    let value: String

    var body: some View {
        HStack(spacing: DesignTokens.Spacing.small) {
            Image(systemName: icon)
                .frame(width: 16)
                .foregroundStyle(.secondary)
            Text(value)
                .font(DesignTokens.TypeStyle.body)
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
            Spacer()
            Button("Copy") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(value, forType: .string)
            }
            .buttonStyle(.bordered)
            .controlSize(.mini)
        }
    }
}
