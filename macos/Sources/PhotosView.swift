import SwiftUI
import AppKit
import OpentomacShared

struct PhotosView: View {
    @EnvironmentObject private var model: AppModel
    @Binding var isPresented: Bool

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
                            ThumbnailCell(photo: photo)
                        }
                    }
                }
            }
        }
        .padding(20)
        .frame(minWidth: 560, minHeight: 420)
        .onAppear { model.loadPhotos() }
    }
}

private struct ThumbnailCell: View {
    @EnvironmentObject private var model: AppModel
    let photo: MacPhoto
    @State private var image: NSImage?
    @State private var isHovering = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            RoundedRectangle(cornerRadius: 8).fill(.quaternary)
            if let image {
                Image(nsImage: image)
                    .resizable()
                    .scaledToFill()
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            } else {
                ProgressView()
            }
            if isHovering {
                Button {
                    model.importPhoto(photo.id)
                } label: {
                    Image(systemName: "square.and.arrow.down")
                        .frame(width: 24, height: 24)
                }
                .buttonStyle(.borderless)
                .background(
                    Color(nsColor: .windowBackgroundColor).opacity(0.9),
                    in: RoundedRectangle(cornerRadius: 6)
                )
                .padding(6)
                .accessibilityLabel("Import original \(photo.name)")
            }
        }
        .frame(height: 110)
        .clipped()
        .contentShape(RoundedRectangle(cornerRadius: 8))
        .onHover { isHovering = $0 }
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
