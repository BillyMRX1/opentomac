import SwiftUI
import AppKit
import AVFoundation

/** In-app proof path for the phone camera; no system extension is required. */
struct CameraWindow: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ZStack {
            CameraVideoSurface(renderer: model.cameraRenderer)

            if !model.cameraConfigured {
                VStack(spacing: 12) {
                    if model.cameraActive {
                        ProgressView("Waiting for phone camera…")
                    } else {
                        Text(model.cameraStoppedReason.map { "Camera ended: \($0)" } ?? "Phone camera is stopped")
                        Button("Start front camera") { model.startCamera() }
                    }
                }
                .padding(20)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .background(Color.black)
        .toolbar {
            ToolbarItemGroup {
                Button("Front") { model.startCamera(facing: "front", withAudio: true) }
                Button("Back") { model.startCamera(facing: "back", withAudio: true) }
                Button("Stop") { model.stopCamera() }
                    .disabled(!model.cameraActive && !model.cameraConfigured)
            }
        }
        .onDisappear { model.stopCamera() }
    }
}

private struct CameraVideoSurface: NSViewRepresentable {
    let renderer: VideoRenderer

    func makeNSView(context: Context) -> CameraDisplayView {
        CameraDisplayView(renderer: renderer)
    }

    func updateNSView(_ nsView: CameraDisplayView, context: Context) {}

    static func dismantleNSView(_ nsView: CameraDisplayView, coordinator: ()) {
        nsView.detachRenderer()
    }
}

private final class CameraDisplayView: NSView {
    private let renderer: VideoRenderer
    private let displayLayer = AVSampleBufferDisplayLayer()

    init(renderer: VideoRenderer) {
        self.renderer = renderer
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        displayLayer.videoGravity = .resizeAspect
        layer?.addSublayer(displayLayer)
        renderer.attach(displayLayer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layout() {
        super.layout()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        displayLayer.frame = bounds
        CATransaction.commit()
    }

    func detachRenderer() {
        renderer.detach(displayLayer)
    }
}
