import SwiftUI
import AppKit
import OpentomacShared

extension MacPhoto: Identifiable {}

struct PhotosView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool
    @State private var preview: MacPhoto?

    private let columns = [GridItem(.adaptive(minimum: 132), spacing: DesignTokens.Spacing.medium)]

    var body: some View {
        ZStack {
            LiquidBackground()

            VStack(alignment: .leading, spacing: DesignTokens.Spacing.large) {
                SheetHeader(
                    title: "Phone photos",
                    subtitle: "Recent first · originals import separately"
                ) {
                    HStack(spacing: DesignTokens.Spacing.small) {
                        Button("Refresh", systemImage: "arrow.clockwise") { model.loadPhotos() }
                            .buttonStyle(.bordered)
                        Button("Close") { isPresented = false }
                            .buttonStyle(.bordered)
                            .keyboardShortcut(.cancelAction)
                    }
                }

                Group {
                    if model.photos.isEmpty {
                        EmptyStateView(
                            title: "No photos yet",
                            description: "Make sure the phone is connected and photo access is granted, then refresh.",
                            systemImage: "photo.on.rectangle.angled"
                        )
                        .frame(maxHeight: .infinity)
                    } else {
                        ScrollView {
                            LazyVGrid(columns: columns, spacing: DesignTokens.Spacing.medium) {
                                ForEach(model.photos, id: \.id) { photo in
                                    ThumbnailCell(photo: photo) { preview = photo }
                                }
                            }
                            .padding(DesignTokens.Spacing.medium)
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .glassCard(material: .regularMaterial)
            }
            .padding(DesignTokens.Spacing.xLarge)
        }
        .tint(DesignTokens.ColorToken.accent)
        .frame(minWidth: 680, minHeight: 500)
        .onAppear { model.loadPhotos() }
        .sheet(item: $preview) { photo in
            PhotoPreview(photo: photo) { preview = nil }
                .environmentObject(model)
        }
    }
}

private struct ThumbnailCell: View {
    @EnvironmentObject private var model: AppModel
    let photo: MacPhoto
    let onOpen: () -> Void
    @State private var image: NSImage?
    @State private var isHovered = false

    var body: some View {
        Button(action: onOpen) {
            RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous)
                .fill(.quaternary)
                .aspectRatio(1, contentMode: .fit)
                .overlay {
                    if let image {
                        Image(nsImage: image)
                            .resizable()
                            .scaledToFill()
                    } else {
                        ProgressView()
                    }
                }
                .overlay(alignment: .bottomLeading) {
                    if isHovered {
                        Text(photo.name)
                            .font(DesignTokens.TypeStyle.meta)
                            .foregroundStyle(.white)
                            .lineLimit(1)
                            .padding(.horizontal, DesignTokens.Spacing.small)
                            .padding(.vertical, 6)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(
                                LinearGradient(
                                    colors: [.clear, .black.opacity(0.65)],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )
                            .transition(.opacity)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous)
                        .stroke(Color.primary.opacity(0.1), lineWidth: 1)
                }
                .shadow(color: .black.opacity(isHovered ? 0.18 : 0.08), radius: isHovered ? 14 : 7, y: isHovered ? 8 : 4)
                .scaleEffect(isHovered ? 1.022 : 1)
                .animation(DesignTokens.Motion.standard, value: isHovered)
        }
        .buttonStyle(.plain)
        .onHover { isHovered = $0 }
        .accessibilityLabel("Open \(photo.name)")
        .contextMenu {
            Button("Import original") {
                model.importPhoto(photo.id)
            }
        }
        .onAppear {
            guard image == nil else { return }
            model.requestThumbnail(photo.id) { data in
                if let data, let nsImage = NSImage(data: data) {
                    image = nsImage
                }
            }
        }
    }
}

private struct PhotoPreview: View {
    @EnvironmentObject private var model: AppModel
    let photo: MacPhoto
    let onClose: () -> Void
    @State private var image: NSImage?
    @State private var imported = false

    var body: some View {
        ZStack {
            LiquidBackground()

            VStack(spacing: DesignTokens.Spacing.standard) {
                SheetHeader(
                    title: photo.name,
                    subtitle: "Preview quality · the original imports separately"
                ) {
                    Button("Close") { onClose() }
                        .buttonStyle(.bordered)
                        .keyboardShortcut(.cancelAction)
                }

                ZStack {
                    RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous)
                        .fill(.quaternary)
                    if let image {
                        Image(nsImage: image)
                            .resizable()
                            .scaledToFit()
                            .clipShape(RoundedRectangle(cornerRadius: DesignTokens.Radius.medium, style: .continuous))
                    } else {
                        ProgressView()
                            .controlSize(.large)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .glassCard(radius: DesignTokens.Radius.medium, material: .regularMaterial)

                HStack {
                    Text("The original will arrive in received files.")
                        .font(DesignTokens.TypeStyle.meta)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button(imported ? "Sent — check received files" : "Import to this Mac") {
                        model.importPhoto(photo.id)
                        imported = true
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(imported)
                    .keyboardShortcut(.defaultAction)
                }
            }
            .padding(DesignTokens.Spacing.xLarge)
        }
        .tint(DesignTokens.ColorToken.accent)
        .frame(minWidth: 620, minHeight: 520)
        .onAppear {
            model.requestThumbnail(photo.id) { data in
                if let data, let nsImage = NSImage(data: data) {
                    image = nsImage
                }
            }
        }
    }
}
