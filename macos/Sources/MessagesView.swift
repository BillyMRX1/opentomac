import Foundation
import SwiftUI
import OpentomacShared

struct MessagesView: View {
    @EnvironmentObject private var model: AppModel
    @State private var selectedThreadId: String?
    @State private var reply = ""
    @State private var isSending = false
    @State private var sendError: String?
    @State private var generation: Int?

    var body: some View {
        ZStack {
            LiquidBackground()

            VStack(alignment: .leading, spacing: DesignTokens.Spacing.standard) {
                SheetHeader(
                    title: "Phone messages",
                    subtitle: "Conversations are loaded from your phone on demand"
                ) {
                    EmptyView()
                }

                PermissionBanner(
                    systemImage: model.smsGranted ? "message.badge.fill" : "message.fill",
                    tint: model.smsGranted ? DesignTokens.ColorToken.accent : DesignTokens.ColorToken.warning,
                    title: model.smsGranted ? "SMS access is active" : "SMS access is required",
                    detail: model.smsGranted
                        ? "Replies may require confirmation on the phone before they send."
                        : "Allow SMS access on the phone to browse conversations."
                ) {
                    EmptyView()
                }

                HSplitView {
                    threadList
                        .frame(minWidth: 250, idealWidth: 290, maxWidth: 330)
                    conversation
                        .frame(minWidth: 430, maxWidth: .infinity, maxHeight: .infinity)
                }
                .glassCard(material: .regularMaterial)
            }
            .padding(DesignTokens.Spacing.xLarge)
        }
        .tint(DesignTokens.ColorToken.accent)
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
        VStack(alignment: .leading, spacing: DesignTokens.Spacing.small) {
            Text("Conversations")
                .font(DesignTokens.TypeStyle.heading)
                .padding(.horizontal, DesignTokens.Spacing.standard)
                .padding(.top, DesignTokens.Spacing.standard)

            if model.smsThreads.isEmpty {
                EmptyStateView(
                    title: model.smsGranted ? "No conversations" : "SMS access required",
                    description: model.smsGranted
                        ? "Your phone’s SMS conversations will appear here."
                        : "Grant access on the phone, then reopen this sheet.",
                    systemImage: "message"
                )
                .frame(maxHeight: .infinity)
            } else {
                List(model.smsThreads, id: \.threadId, selection: $selectedThreadId) { thread in
                    HStack(alignment: .top, spacing: DesignTokens.Spacing.small) {
                        ThreadAvatar(
                            name: thread.contactName.isEmpty ? thread.address : thread.contactName,
                            unread: thread.unread
                        )

                        VStack(alignment: .leading, spacing: 3) {
                            HStack(spacing: 6) {
                                Text(thread.contactName.isEmpty ? thread.address : thread.contactName)
                                    .font(DesignTokens.TypeStyle.body)
                                    .fontWeight(thread.unread ? .semibold : .regular)
                                    .lineLimit(1)
                                Spacer(minLength: 4)
                                Text(relativeTime(thread.dateMs))
                                    .font(DesignTokens.TypeStyle.meta)
                                    .foregroundStyle(.tertiary)
                            }
                            Text(thread.snippet.isEmpty ? "No message text" : thread.snippet)
                                .font(DesignTokens.TypeStyle.meta)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                    .padding(.vertical, DesignTokens.Spacing.xSmall)
                    .tag(thread.threadId)
                }
                .listStyle(.sidebar)
                .scrollContentBackground(.hidden)
            }
        }
        .background(.ultraThinMaterial)
    }

    @ViewBuilder
    private var conversation: some View {
        if selectedThreadId != nil {
            VStack(spacing: 0) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(selectedTitle)
                            .font(DesignTokens.TypeStyle.section)
                        if let address = selectedThread?.address, !address.isEmpty {
                            Text(address)
                                .font(DesignTokens.TypeStyle.meta)
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                        }
                    }
                    Spacer()
                    IconBadge(systemName: "message.fill", size: 36)
                }
                .padding(.horizontal, DesignTokens.Spacing.standard + 2)
                .padding(.vertical, DesignTokens.Spacing.medium)

                Divider()

                if model.smsMessages.isEmpty {
                    EmptyStateView(
                        title: model.smsGranted ? "No messages" : "SMS access required",
                        description: model.smsGranted
                            ? "There are no messages in this conversation."
                            : "Grant SMS access on the phone to read this conversation.",
                        systemImage: "bubble.left.and.bubble.right"
                    )
                    .frame(maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVStack(spacing: DesignTokens.Spacing.small + 2) {
                            ForEach(Array(model.smsMessages.enumerated()), id: \.offset) { _, message in
                                SmsMessageRow(message: message)
                            }
                        }
                        .padding(DesignTokens.Spacing.large)
                    }
                }

                Divider()

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: DesignTokens.Spacing.small) {
                        TextField("Reply", text: $reply)
                            .textFieldStyle(.plain)
                            .padding(.horizontal, DesignTokens.Spacing.medium)
                            .frame(height: 34)
                            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.small, style: .continuous))
                            .overlay {
                                RoundedRectangle(cornerRadius: DesignTokens.Radius.small, style: .continuous)
                                    .stroke(Color.primary.opacity(0.12), lineWidth: 1)
                            }
                            .onSubmit(sendReply)
                        Button("Send", action: sendReply)
                            .buttonStyle(.borderedProminent)
                            .keyboardShortcut(.defaultAction)
                            .disabled(reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || replyAddress.isEmpty || isSending)
                    }
                    if let sendError {
                        Text(sendError)
                            .font(DesignTokens.TypeStyle.meta)
                            .foregroundStyle(DesignTokens.ColorToken.danger)
                    } else {
                        Text("Reply through your phone")
                            .font(DesignTokens.TypeStyle.meta)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(DesignTokens.Spacing.medium)
                .background(.ultraThinMaterial)
            }
        } else {
            EmptyStateView(
                title: "Select a conversation",
                description: "Messages are loaded from your phone on demand.",
                systemImage: "message"
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
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

private struct ThreadAvatar: View {
    let name: String
    let unread: Bool

    var body: some View {
        Text(initials)
            .font(DesignTokens.TypeStyle.metaEmphasized)
            .foregroundStyle(.white)
            .frame(width: 38, height: 38)
            .background(
                DesignTokens.ColorToken.accent.opacity(unread ? 1 : 0.72),
                in: RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous)
            )
            .overlay(alignment: .topTrailing) {
                if unread {
                    Circle()
                        .fill(DesignTokens.ColorToken.accent)
                        .frame(width: 8, height: 8)
                        .overlay { Circle().stroke(.white, lineWidth: 1.5) }
                        .offset(x: 2, y: -2)
                }
            }
    }

    private var initials: String {
        let parts = name.split(separator: " ")
        let value = parts.prefix(2).compactMap(\.first).map(String.init).joined()
        return value.isEmpty ? "?" : value.uppercased()
    }
}

private struct SmsMessageRow: View {
    let message: MacSmsMessage

    var body: some View {
        HStack {
            if !message.incoming { Spacer(minLength: 100) }
            VStack(alignment: message.incoming ? .leading : .trailing, spacing: DesignTokens.Spacing.xSmall) {
                Text(message.body)
                    .font(DesignTokens.TypeStyle.body)
                    .textSelection(.enabled)
                    .padding(.horizontal, DesignTokens.Spacing.medium)
                    .padding(.vertical, 9)
                    .foregroundStyle(message.incoming ? Color.primary : Color.white)
                    .background(
                        message.incoming
                            ? AnyShapeStyle(.regularMaterial)
                            : AnyShapeStyle(DesignTokens.ColorToken.accent),
                        in: RoundedRectangle(cornerRadius: 15, style: .continuous)
                    )
                    .overlay {
                        if message.incoming {
                            RoundedRectangle(cornerRadius: 15, style: .continuous)
                                .stroke(Color.primary.opacity(0.08), lineWidth: 1)
                        }
                    }
                Text(
                    Date(timeIntervalSince1970: TimeInterval(message.dateMs) / 1_000)
                        .formatted(date: .omitted, time: .shortened)
                )
                .font(DesignTokens.TypeStyle.meta)
                .foregroundStyle(.tertiary)
            }
            if message.incoming { Spacer(minLength: 100) }
        }
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
