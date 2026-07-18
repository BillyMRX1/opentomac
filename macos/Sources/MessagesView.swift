import Foundation
import SwiftUI
import OpentomacShared

struct MessagesView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool
    @State private var selectedThreadId: String?
    @State private var reply = ""
    @State private var isSending = false
    @State private var sendError: String?
    @State private var generation: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Phone messages").font(.title2.bold())
                Spacer()
                Button("Close") { isPresented = false }
                    .keyboardShortcut(.cancelAction)
            }

            if !model.smsGranted {
                HStack(spacing: 8) {
                    Image(systemName: "message.fill")
                        .foregroundStyle(.orange)
                    Text("Allow SMS access on the phone")
                        .font(.callout)
                    Spacer()
                }
                .padding(10)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
            }

            HSplitView {
                threadList
                    .frame(minWidth: 240, idealWidth: 280, maxWidth: 320)
                conversation
                    .frame(minWidth: 420, maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .padding(20)
        .frame(minWidth: 760, minHeight: 520)
        .onAppear {
            let token = model.beginSmsSession()
            generation = token
            model.loadSmsThreads(generation: token)
        }
        .onChange(of: selectedThreadId) { _, threadId in
            reply = ""
            sendError = nil
            model.smsMessages = []
            if let threadId, let generation {
                model.loadSmsThread(threadId, generation: generation)
            }
        }
        .onDisappear {
            // Message content is PII; don't let it outlive the sheet.
            if let generation { model.clearSms(generation: generation) }
            generation = nil
        }
    }

    private var threadList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Conversations")
                .font(.headline)
                .padding(.horizontal, 8)

            if model.smsThreads.isEmpty {
                Text(model.smsGranted ? "No SMS conversations found." : "SMS access is required to browse conversations.")
                    .foregroundStyle(.secondary)
                    .font(.callout)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding()
            } else {
                List(model.smsThreads, id: \.threadId, selection: $selectedThreadId) { thread in
                    HStack(alignment: .top, spacing: 8) {
                        Circle()
                            .fill(thread.unread ? Color.accentColor : Color.clear)
                            .frame(width: 7, height: 7)
                            .padding(.top, 6)

                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(thread.contactName.isEmpty ? thread.address : thread.contactName)
                                    .font(.callout.weight(thread.unread ? .semibold : .regular))
                                    .lineLimit(1)
                                Spacer(minLength: 4)
                                Text(relativeTime(thread.dateMs))
                                    .font(.caption2)
                                    .foregroundStyle(.tertiary)
                            }
                            Text(thread.snippet.isEmpty ? "No message text" : thread.snippet)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                    .padding(.vertical, 4)
                    .tag(thread.threadId)
                }
                .listStyle(.sidebar)
            }
        }
    }

    @ViewBuilder
    private var conversation: some View {
        if selectedThreadId != nil {
            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(selectedTitle)
                            .font(.headline)
                        if let address = selectedThread?.address, !address.isEmpty {
                            Text(address)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                        }
                    }
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

                Divider()

                if model.smsMessages.isEmpty {
                    Text(model.smsGranted ? "No messages in this conversation." : "SMS access is required to read this conversation.")
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVStack(spacing: 10) {
                            ForEach(Array(model.smsMessages.enumerated()), id: \.offset) { _, message in
                                SmsMessageRow(message: message)
                            }
                        }
                        .padding(16)
                    }
                }

                Divider()

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        TextField("Reply", text: $reply)
                            .textFieldStyle(.roundedBorder)
                            .onSubmit(sendReply)
                        Button("Send", action: sendReply)
                            .keyboardShortcut(.defaultAction)
                            .disabled(reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || replyAddress.isEmpty || isSending)
                    }
                    if let sendError {
                        Text(sendError)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
                .padding(12)
            }
        } else {
            ContentUnavailableView(
                "Select a conversation",
                systemImage: "message",
                description: Text("Messages are loaded from your phone on demand.")
            )
        }
    }

    private var selectedThread: MacSmsThread? {
        model.smsThreads.first { $0.threadId == selectedThreadId }
    }

    private var selectedTitle: String {
        guard let selectedThread else { return "Conversation" }
        return selectedThread.contactName.nonEmpty ?? selectedThread.address.nonEmpty ?? "Conversation"
    }

    private var replyAddress: String {
        selectedThread?.address.nonEmpty ?? model.smsAddress
    }

    private func sendReply() {
        let body = reply.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            !body.isEmpty,
            !replyAddress.isEmpty,
            !isSending,
            let selectedThreadId,
            let generation
        else { return }
        isSending = true
        sendError = nil
        model.sendSms(address: replyAddress, body: body) { sent, error in
            isSending = false
            if sent {
                reply = ""
                model.loadSmsThread(selectedThreadId, generation: generation)
                model.loadSmsThreads(generation: generation)
            } else {
                sendError = error.isEmpty ? "Could not send message." : error
            }
        }
    }

    private func relativeTime(_ millis: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(millis) / 1_000)
            .formatted(.relative(presentation: .numeric, unitsStyle: .abbreviated))
    }
}

private struct SmsMessageRow: View {
    let message: MacSmsMessage

    var body: some View {
        HStack {
            if !message.incoming { Spacer(minLength: 80) }
            VStack(alignment: message.incoming ? .leading : .trailing, spacing: 4) {
                Text(message.body)
                    .textSelection(.enabled)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 8)
                    .background(
                        message.incoming ? AnyShapeStyle(.quaternary) : AnyShapeStyle(Color.accentColor.opacity(0.18)),
                        in: RoundedRectangle(cornerRadius: 12)
                    )
                Text(
                    Date(timeIntervalSince1970: TimeInterval(message.dateMs) / 1_000)
                        .formatted(date: .omitted, time: .shortened)
                )
                .font(.caption2)
                .foregroundStyle(.tertiary)
            }
            if message.incoming { Spacer(minLength: 80) }
        }
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
