import SwiftUI
import AppKit
import OpentomacShared

extension MacPhoto: Identifiable {}

struct PhotosView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool
    @State private var preview: MacPhoto?

    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 8)]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Phone photos").font(.title2.bold())
                Spacer()
                Button("Refresh") { model.loadPhotos() }
                Button("Close") { isPresented = false }
            }
            if model.photos.isEmpty {
                Text("No photos yet. Make sure the phone is connected and photo access is granted, then Refresh.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVGrid(columns: columns, spacing: 8) {
                        ForEach(model.photos, id: \.id) { photo in
                            ThumbnailCell(photo: photo) { preview = photo }
                        }
                    }
                }
            }
        }
        .padding(20)
        .frame(minWidth: 560, minHeight: 420)
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

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8).fill(.quaternary)
            if let image {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                ProgressView()
            }
        }
        .frame(height: 110)
        .clipped()
        .contentShape(RoundedRectangle(cornerRadius: 8))
        .onTapGesture { onOpen() }
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
        VStack(spacing: 12) {
            HStack {
                Text(photo.name)
                    .font(.headline)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                Button("Close") { onClose() }
                    .keyboardShortcut(.cancelAction)
            }
            ZStack {
                RoundedRectangle(cornerRadius: 8).fill(.quaternary)
                if let image {
                    Image(nsImage: image)
                        .resizable()
                        .scaledToFit()
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    ProgressView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            HStack {
                Text("Preview quality; the import fetches the original.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button(imported ? "Sent — check received files" : "Import to this Mac") {
                    model.importPhoto(photo.id)
                    imported = true
                }
                .disabled(imported)
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(minWidth: 540, minHeight: 480)
        .onAppear {
            model.requestThumbnail(photo.id) { data in
                if let data, let nsImage = NSImage(data: data) {
                    image = nsImage
                }
            }
        }
    }
}
