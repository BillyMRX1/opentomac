import SwiftUI
import AppKit
import OpentomacShared

struct ContactsView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool
    @State private var query = ""
    @State private var hasSearched = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Phone contacts").font(.title2.bold())
                Spacer()
                Button("Close") { isPresented = false }
                    .keyboardShortcut(.cancelAction)
            }
            .onDisappear {
                // Contact details are PII; don't let results outlive the sheet.
                model.contacts = []
            }

            TextField("Search by name", text: $query)
                .textFieldStyle(.roundedBorder)
                .onSubmit(search)

            if !model.contactsGranted {
                HStack(spacing: 8) {
                    Image(systemName: "person.crop.circle.badge.exclamationmark")
                        .foregroundStyle(.orange)
                    Text("Allow contact access on the phone")
                        .font(.callout)
                    Spacer()
                }
                .padding(10)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
            }

            if model.contacts.isEmpty {
                Text(hasSearched ? "No contacts found." : "Enter a name and press Return to search your phone.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(Array(model.contacts.enumerated()), id: \.offset) { _, contact in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(contact.name)
                                .font(.headline)

                            ForEach(Array(contact.phones.enumerated()), id: \.offset) { _, phone in
                                ContactValueRow(icon: "phone", value: phone)
                            }
                            ForEach(Array(contact.emails.enumerated()), id: \.offset) { _, email in
                                ContactValueRow(icon: "envelope", value: email)
                            }
                        }
                        .padding(.vertical, 6)
                    }
                }
                .listStyle(.inset)
            }
        }
        .padding(20)
        .frame(minWidth: 520, minHeight: 420)
    }

    private func search() {
        hasSearched = true
        model.searchContacts(query)
    }
}

private struct ContactValueRow: View {
    let icon: String
    let value: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .frame(width: 16)
                .foregroundStyle(.secondary)
            Text(value)
                .textSelection(.enabled)
            Spacer()
            Button("Copy") {
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(value, forType: .string)
            }
            .controlSize(.mini)
        }
        .font(.callout)
    }
}
