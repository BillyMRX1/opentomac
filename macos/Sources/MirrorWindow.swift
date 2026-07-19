import SwiftUI
import AppKit
import AVFoundation
import CoreMedia
import CoreFoundation

/// Owns the H.264 decoder input path. Kotlin callbacks enter on a background
/// thread; all format creation, Annex-B conversion, and display-layer access is
/// serialized here without involving the main queue.
final class VideoRenderer: ObservableObject {
    private let queue = DispatchQueue(label: "dev.opentomac.video-renderer", qos: .userInteractive)
    private weak var displayLayer: AVSampleBufferDisplayLayer?
    private var formatDescription: CMVideoFormatDescription?
    private var waitingForKeyframe = true

    func attach(_ layer: AVSampleBufferDisplayLayer) {
        queue.async { [weak self, weak layer] in
            guard let self else { return }
            self.displayLayer = layer
            layer?.flushAndRemoveImage()
        }
    }

    func detach(_ layer: AVSampleBufferDisplayLayer) {
        queue.async { [weak self, weak layer] in
            guard let self, self.displayLayer === layer else { return }
            self.displayLayer = nil
        }
    }

    func reset() {
        queue.async { [weak self] in
            self?.formatDescription = nil
            self?.waitingForKeyframe = true
            self?.displayLayer?.flushAndRemoveImage()
        }
    }

    func configure(width: Int, height: Int, sps: Data, pps: Data, frameRate: Int) {
        queue.async { [weak self] in
            guard let self else { return }
            self.displayLayer?.flushAndRemoveImage()
            self.formatDescription = Self.makeFormatDescription(sps: sps, pps: pps)
            self.waitingForKeyframe = true

            if self.formatDescription == nil {
                print("opentomac mirror: rejected invalid H.264 config")
            } else {
                print("opentomac mirror: configured \(width)x\(height) at \(frameRate) fps")
            }
        }
    }

    func enqueue(data: Data, ptsUs: Int64, keyframe: Bool) {
        queue.async { [weak self] in
            guard
                let self,
                let layer = self.displayLayer,
                let formatDescription = self.formatDescription
            else { return }

            if layer.status == .failed {
                print("opentomac mirror: display layer failed; waiting for a keyframe")
                layer.flush()
                self.waitingForKeyframe = true
            }

            if self.waitingForKeyframe {
                guard keyframe else { return }
            }

            guard let blockBuffer = Self.makeAVCCBlockBuffer(from: data) else { return }
            var timing = CMSampleTimingInfo(
                duration: .invalid,
                presentationTimeStamp: .invalid,
                decodeTimeStamp: .invalid
            )
            var sampleSize = CMBlockBufferGetDataLength(blockBuffer)
            var sampleBuffer: CMSampleBuffer?
            let status = CMSampleBufferCreateReady(
                allocator: kCFAllocatorDefault,
                dataBuffer: blockBuffer,
                formatDescription: formatDescription,
                sampleCount: 1,
                sampleTimingEntryCount: 1,
                sampleTimingArray: &timing,
                sampleSizeEntryCount: 1,
                sampleSizeArray: &sampleSize,
                sampleBufferOut: &sampleBuffer
            )
            guard status == noErr, let sampleBuffer else { return }

            if let attachments = CMSampleBufferGetSampleAttachmentsArray(
                sampleBuffer,
                createIfNecessary: true
            ) {
                let attachment = unsafeBitCast(
                    CFArrayGetValueAtIndex(attachments, 0),
                    to: CFMutableDictionary.self
                )
                CFDictionarySetValue(
                    attachment,
                    Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                    Unmanaged.passUnretained(kCFBooleanTrue).toOpaque()
                )
            }

            // Display-immediately mode intentionally ignores the source PTS for MVP.
            _ = ptsUs
            self.waitingForKeyframe = false
            layer.enqueue(sampleBuffer)
        }
    }

    private static func makeFormatDescription(sps: Data, pps: Data) -> CMVideoFormatDescription? {
        guard !sps.isEmpty, !pps.isEmpty else { return nil }
        var description: CMVideoFormatDescription?
        let status = sps.withUnsafeBytes { spsRawBuffer in
            pps.withUnsafeBytes { ppsRawBuffer in
                let spsBuffer = spsRawBuffer.bindMemory(to: UInt8.self)
                let ppsBuffer = ppsRawBuffer.bindMemory(to: UInt8.self)
                guard let spsBytes = spsBuffer.baseAddress, let ppsBytes = ppsBuffer.baseAddress else {
                    return kCMFormatDescriptionError_InvalidParameter
                }
                let spsPrefixLength = annexBPrefixLength(bytes: spsBytes, length: spsBuffer.count)
                let ppsPrefixLength = annexBPrefixLength(bytes: ppsBytes, length: ppsBuffer.count)
                guard spsBuffer.count > spsPrefixLength, ppsBuffer.count > ppsPrefixLength else {
                    return kCMFormatDescriptionError_InvalidParameter
                }

                let pointers = [
                    spsBytes.advanced(by: spsPrefixLength),
                    ppsBytes.advanced(by: ppsPrefixLength),
                ]
                let sizes = [
                    spsBuffer.count - spsPrefixLength,
                    ppsBuffer.count - ppsPrefixLength,
                ]
                return pointers.withUnsafeBufferPointer { pointerBuffer in
                    sizes.withUnsafeBufferPointer { sizeBuffer in
                        CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: 2,
                            parameterSetPointers: pointerBuffer.baseAddress!,
                            parameterSetSizes: sizeBuffer.baseAddress!,
                            nalUnitHeaderLength: 4,
                            formatDescriptionOut: &description
                        )
                    }
                }
            }
        }
        return status == noErr ? description : nil
    }

    private static func annexBPrefixLength(bytes: UnsafePointer<UInt8>, length: Int) -> Int {
        if length >= 4 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 1 {
            return 4
        }
        if length >= 3 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 1 {
            return 3
        }
        return 0
    }

    /// Converts directly from the bridged Data into one CoreMedia-owned AVCC
    /// allocation. This avoids an intermediate Swift Data copy on every frame.
    private static func makeAVCCBlockBuffer(from data: Data) -> CMBlockBuffer? {
        data.withUnsafeBytes { rawBuffer in
            let sourceBuffer = rawBuffer.bindMemory(to: UInt8.self)
            guard let source = sourceBuffer.baseAddress else { return nil }
            let nalUnits = annexBNALUnits(bytes: source, length: sourceBuffer.count)
            guard !nalUnits.isEmpty else { return nil }

            let outputLength = nalUnits.reduce(0) { $0 + 4 + $1.length }
            guard outputLength > 0,
                  let memory = CFAllocatorAllocate(kCFAllocatorDefault, outputLength, 0) else {
                return nil
            }

            let destination = memory.assumingMemoryBound(to: UInt8.self)
            var writeOffset = 0
            for nalUnit in nalUnits {
                guard nalUnit.length <= Int(UInt32.max) else {
                    CFAllocatorDeallocate(kCFAllocatorDefault, memory)
                    return nil
                }
                let length = UInt32(nalUnit.length)
                destination[writeOffset] = UInt8((length >> 24) & 0xff)
                destination[writeOffset + 1] = UInt8((length >> 16) & 0xff)
                destination[writeOffset + 2] = UInt8((length >> 8) & 0xff)
                destination[writeOffset + 3] = UInt8(length & 0xff)
                memcpy(
                    destination.advanced(by: writeOffset + 4),
                    source.advanced(by: nalUnit.offset),
                    nalUnit.length
                )
                writeOffset += 4 + nalUnit.length
            }

            var blockBuffer: CMBlockBuffer?
            let status = CMBlockBufferCreateWithMemoryBlock(
                allocator: kCFAllocatorDefault,
                memoryBlock: memory,
                blockLength: outputLength,
                blockAllocator: kCFAllocatorDefault,
                customBlockSource: nil,
                offsetToData: 0,
                dataLength: outputLength,
                flags: 0,
                blockBufferOut: &blockBuffer
            )
            if status != kCMBlockBufferNoErr {
                CFAllocatorDeallocate(kCFAllocatorDefault, memory)
                return nil
            }
            return blockBuffer
        }
    }

    private static func annexBNALUnits(
        bytes: UnsafePointer<UInt8>,
        length: Int
    ) -> [(offset: Int, length: Int)] {
        guard length >= 4 else { return [] }
        var starts: [(offset: Int, prefixLength: Int)] = []
        var index = 0

        while index + 3 <= length {
            if index + 4 <= length,
               bytes[index] == 0,
               bytes[index + 1] == 0,
               bytes[index + 2] == 0,
               bytes[index + 3] == 1 {
                starts.append((index, 4))
                index += 4
            } else if bytes[index] == 0,
                      bytes[index + 1] == 0,
                      bytes[index + 2] == 1 {
                starts.append((index, 3))
                index += 3
            } else {
                index += 1
            }
        }

        return starts.enumerated().compactMap { position, start in
            let payloadOffset = start.offset + start.prefixLength
            let payloadEnd = position + 1 < starts.count ? starts[position + 1].offset : length
            guard payloadEnd > payloadOffset else { return nil }
            return (payloadOffset, payloadEnd - payloadOffset)
        }
    }
}

struct MirrorWindow: View {
    @EnvironmentObject private var model: AppModel
    @State private var showsControlHint = !UserDefaults.standard.bool(
        forKey: "mirrorControlHintDismissed"
    )

    var body: some View {
        VStack(spacing: 0) {
            mirrorToolbar

            ZStack {
                MirrorVideoSurface(
                    renderer: model.videoRenderer,
                    videoSize: model.mirrorVideoSize,
                    onTap: model.sendMirrorTap,
                    onSwipe: model.sendMirrorSwipe,
                    onText: model.sendMirrorText,
                    onInteraction: dismissControlHint
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                if let reason = model.mirrorStoppedReason {
                    VStack(spacing: DesignTokens.Spacing.medium) {
                        IconBadge(
                            systemName: "rectangle.slash",
                            tint: DesignTokens.ColorToken.danger,
                            size: 48
                        )
                        VStack(spacing: DesignTokens.Spacing.xSmall) {
                            Text("Mirroring ended")
                                .font(DesignTokens.TypeStyle.section)
                            Text(reason)
                                .font(DesignTokens.TypeStyle.meta)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        Button("Retry") { model.startMirror() }
                            .buttonStyle(.borderedProminent)
                            .keyboardShortcut(.defaultAction)
                    }
                    .padding(DesignTokens.Spacing.large)
                    .frame(minWidth: 240)
                    .glassCard(material: .regularMaterial)
                } else if !model.mirrorConfigured {
                    VStack(spacing: DesignTokens.Spacing.small) {
                        ProgressView()
                            .controlSize(.large)
                            .tint(.white)
                        Text("Waiting for the phone…")
                            .font(DesignTokens.TypeStyle.bodyEmphasized)
                        Text("The live screen will stay aspect-locked.")
                            .font(DesignTokens.TypeStyle.meta)
                            .foregroundStyle(.secondary)
                    }
                    .padding(DesignTokens.Spacing.large)
                    .foregroundStyle(.white)
                    .frame(minWidth: 240)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.large, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: DesignTokens.Radius.large, style: .continuous)
                            .stroke(Color.white.opacity(0.14), lineWidth: 1)
                    }
                }

                if showsControlHint && model.mirrorConfigured {
                    VStack {
                        Spacer()
                        Label(
                            "Click, scroll, and type to control the phone",
                            systemImage: "hand.tap"
                        )
                        .font(DesignTokens.TypeStyle.meta)
                        .foregroundStyle(.white.opacity(0.9))
                        .padding(.horizontal, DesignTokens.Spacing.medium)
                        .padding(.vertical, DesignTokens.Spacing.small)
                        .background(.ultraThinMaterial, in: Capsule())
                        .overlay { Capsule().stroke(Color.white.opacity(0.14), lineWidth: 1) }
                        .padding(DesignTokens.Spacing.medium)
                    }
                    .allowsHitTesting(false)
                }
            }
            .background(
                RadialGradient(
                    colors: [DesignTokens.ColorToken.accent.opacity(0.17), .black],
                    center: .center,
                    startRadius: 0,
                    endRadius: 460
                )
            )
        }
        .tint(DesignTokens.ColorToken.accent)
        .frame(minWidth: 320, minHeight: 240)
        .background(
            MirrorWindowAccessor(videoSize: model.mirrorVideoSize) {
                model.stopMirror()
            }
        )
    }

    private var mirrorToolbar: some View {
        HStack(spacing: DesignTokens.Spacing.small) {
            HStack(spacing: DesignTokens.Spacing.xSmall) {
                mirrorControlButton("Back", systemImage: "chevron.backward", action: "back")
                mirrorControlButton("Home", systemImage: "house", action: "home")
                mirrorControlButton("Recents", systemImage: "square.on.square", action: "recents")
            }

            Spacer(minLength: DesignTokens.Spacing.small)

            Picker(
                "Quality",
                selection: Binding(
                    get: { model.mirrorQuality },
                    set: model.setMirrorQuality
                )
            ) {
                ForEach(MirrorQualityPreset.allCases) { preset in
                    Text(preset.title).tag(preset)
                }
            }
            .labelsHidden()
            .pickerStyle(.segmented)
            .frame(width: 174)
            .help("Mirroring quality")
        }
        .padding(.horizontal, DesignTokens.Spacing.medium)
        .padding(.vertical, 7)
        .background(.regularMaterial)
        .overlay(alignment: .bottom) { Divider() }
    }

    private func mirrorControlButton(
        _ title: String,
        systemImage: String,
        action: String
    ) -> some View {
        Button {
            model.sendMirrorKey(action)
            dismissControlHint()
        } label: {
            Image(systemName: systemImage)
                .frame(width: 18, height: 18)
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.circle)
        .controlSize(.small)
        .help(title)
        .accessibilityLabel(title)
        .disabled(!model.mirrorConfigured)
    }

    private func dismissControlHint() {
        guard showsControlHint else { return }
        showsControlHint = false
        UserDefaults.standard.set(true, forKey: "mirrorControlHintDismissed")
    }
}

private struct MirrorWindowAccessor: NSViewRepresentable {
    let videoSize: CGSize?
    let onClose: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onClose: onClose)
    }

    func makeNSView(context: Context) -> WindowProbeView {
        let view = WindowProbeView()
        view.onWindowChanged = { [weak coordinator = context.coordinator] window in
            coordinator?.attach(to: window)
        }
        return view
    }

    func updateNSView(_ nsView: WindowProbeView, context: Context) {
        context.coordinator.onClose = onClose
        context.coordinator.videoSize = videoSize
        context.coordinator.attach(to: nsView.window)
    }

    final class Coordinator {
        var onClose: () -> Void
        var videoSize: CGSize? {
            didSet { applyVideoSize() }
        }

        private weak var window: NSWindow?
        private var closeObserver: NSObjectProtocol?
        private var exitFullscreenObserver: NSObjectProtocol?
        private var appliedVideoSize: CGSize?

        init(onClose: @escaping () -> Void) {
            self.onClose = onClose
        }

        deinit {
            if let closeObserver { NotificationCenter.default.removeObserver(closeObserver) }
            if let exitFullscreenObserver {
                NotificationCenter.default.removeObserver(exitFullscreenObserver)
            }
        }

        func attach(to newWindow: NSWindow?) {
            guard let newWindow, window !== newWindow else {
                applyVideoSize()
                return
            }
            if let closeObserver { NotificationCenter.default.removeObserver(closeObserver) }
            if let exitFullscreenObserver {
                NotificationCenter.default.removeObserver(exitFullscreenObserver)
            }

            window = newWindow
            appliedVideoSize = nil
            closeObserver = NotificationCenter.default.addObserver(
                forName: NSWindow.willCloseNotification,
                object: newWindow,
                queue: .main
            ) { [weak self] _ in
                self?.onClose()
            }
            exitFullscreenObserver = NotificationCenter.default.addObserver(
                forName: NSWindow.didExitFullScreenNotification,
                object: newWindow,
                queue: .main
            ) { [weak self] _ in
                self?.applyVideoSize()
            }
            applyVideoSize()
        }

        private func applyVideoSize() {
            guard
                let window,
                let videoSize,
                videoSize.width > 0,
                videoSize.height > 0
            else { return }

            window.contentAspectRatio = NSSize(
                width: videoSize.width,
                height: videoSize.height
            )
            guard
                !window.styleMask.contains(.fullScreen),
                appliedVideoSize != videoSize
            else { return }

            appliedVideoSize = videoSize
            let visibleFrame = (window.screen ?? NSScreen.main)?.visibleFrame
                ?? NSRect(x: 0, y: 0, width: 1440, height: 900)
            var height = min(max(visibleFrame.height * 0.4, 320), 640)
            var width = height * videoSize.width / videoSize.height
            if width > visibleFrame.width * 0.8 {
                width = visibleFrame.width * 0.8
                height = width * videoSize.height / videoSize.width
            }

            let contentRect = NSRect(origin: .zero, size: NSSize(width: width, height: height))
            var frame = window.frameRect(forContentRect: contentRect)
            frame.origin.x = window.frame.midX - frame.width / 2
            frame.origin.y = window.frame.maxY - frame.height
            frame.origin.x = min(max(frame.origin.x, visibleFrame.minX), visibleFrame.maxX - frame.width)
            frame.origin.y = min(max(frame.origin.y, visibleFrame.minY), visibleFrame.maxY - frame.height)
            window.setFrame(frame, display: true, animate: window.isVisible)
        }
    }
}

private final class WindowProbeView: NSView {
    var onWindowChanged: ((NSWindow?) -> Void)?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        let currentWindow = window
        DispatchQueue.main.async { [weak self] in
            self?.onWindowChanged?(currentWindow)
        }
    }
}

private struct MirrorVideoSurface: NSViewRepresentable {
    let renderer: VideoRenderer
    let videoSize: CGSize?
    let onTap: (CGFloat, CGFloat) -> Void
    let onSwipe: (CGFloat, CGFloat, CGFloat, CGFloat, Int) -> Void
    let onText: (String, Int) -> Void
    let onInteraction: () -> Void

    func makeNSView(context: Context) -> MirrorDisplayView {
        MirrorDisplayView(
            renderer: renderer,
            onTap: onTap,
            onSwipe: onSwipe,
            onText: onText,
            onInteraction: onInteraction
        )
    }

    func updateNSView(_ nsView: MirrorDisplayView, context: Context) {
        nsView.videoSize = videoSize
        nsView.onTap = onTap
        nsView.onSwipe = onSwipe
        nsView.onText = onText
        nsView.onInteraction = onInteraction
    }

    static func dismantleNSView(_ nsView: MirrorDisplayView, coordinator: ()) {
        nsView.detachRenderer()
    }
}

private final class MirrorDisplayView: NSView {
    private let renderer: VideoRenderer
    private let displayLayer = AVSampleBufferDisplayLayer()
    var videoSize: CGSize?
    var onTap: (CGFloat, CGFloat) -> Void
    var onSwipe: (CGFloat, CGFloat, CGFloat, CGFloat, Int) -> Void
    var onText: (String, Int) -> Void
    var onInteraction: () -> Void

    private var mouseDownState: (location: CGPoint, normalized: CGPoint, time: TimeInterval)?
    private var keyWindowObserver: NSObjectProtocol?
    private var scrollDeltaY: CGFloat = 0
    private var scrollAnchor: CGPoint?
    private var scrollWorkItem: DispatchWorkItem?
    private var textBuffer = ""
    private var textWorkItem: DispatchWorkItem?

    init(
        renderer: VideoRenderer,
        onTap: @escaping (CGFloat, CGFloat) -> Void,
        onSwipe: @escaping (CGFloat, CGFloat, CGFloat, CGFloat, Int) -> Void,
        onText: @escaping (String, Int) -> Void,
        onInteraction: @escaping () -> Void
    ) {
        self.renderer = renderer
        self.onTap = onTap
        self.onSwipe = onSwipe
        self.onText = onText
        self.onInteraction = onInteraction
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        displayLayer.videoGravity = .resizeAspect
        layer?.addSublayer(displayLayer)
        renderer.attach(displayLayer)
    }

    deinit {
        if let keyWindowObserver {
            NotificationCenter.default.removeObserver(keyWindowObserver)
        }
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

    override var acceptsFirstResponder: Bool { true }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if let keyWindowObserver {
            NotificationCenter.default.removeObserver(keyWindowObserver)
        }
        guard let window else {
            keyWindowObserver = nil
            return
        }
        keyWindowObserver = NotificationCenter.default.addObserver(
            forName: NSWindow.didBecomeKeyNotification,
            object: window,
            queue: .main
        ) { [weak self, weak window] _ in
            guard let self, let window else { return }
            window.makeFirstResponder(self)
        }
        if window.isKeyWindow {
            DispatchQueue.main.async { [weak self, weak window] in
                guard let self, let window, self.window === window else { return }
                window.makeFirstResponder(self)
            }
        }
    }

    override func mouseDown(with event: NSEvent) {
        window?.makeFirstResponder(self)
        let location = convert(event.locationInWindow, from: nil)
        guard let normalized = normalizedPoint(location) else {
            mouseDownState = nil
            return
        }
        mouseDownState = (location, normalized, event.timestamp)
    }

    override func mouseUp(with event: NSEvent) {
        guard let start = mouseDownState else { return }
        mouseDownState = nil
        let location = convert(event.locationInWindow, from: nil)
        guard let end = normalizedPoint(location) else { return }

        let elapsed = max(0, event.timestamp - start.time)
        let movement = hypot(location.x - start.location.x, location.y - start.location.y)
        onInteraction()
        if elapsed < 0.15 && movement <= 5 {
            onTap(end.x, end.y)
        } else {
            onSwipe(
                start.normalized.x,
                start.normalized.y,
                end.x,
                end.y,
                max(1, Int((elapsed * 1_000).rounded()))
            )
        }
    }

    override func scrollWheel(with event: NSEvent) {
        let location = convert(event.locationInWindow, from: nil)
        guard let normalized = normalizedPoint(location), event.scrollingDeltaY != 0 else { return }
        if scrollAnchor == nil { scrollAnchor = normalized }
        let scale: CGFloat = event.hasPreciseScrollingDeltas ? 1 : 20
        scrollDeltaY += event.scrollingDeltaY * scale
        guard scrollWorkItem == nil else { return }

        let item = DispatchWorkItem { [weak self] in self?.flushScroll() }
        scrollWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.06, execute: item)
    }

    override func keyDown(with event: NSEvent) {
        if event.keyCode == 53 {
            flushTextBuffer()
            super.keyDown(with: event)
            return
        }
        if event.modifierFlags.intersection([.command, .control]).isEmpty == false {
            flushTextBuffer()
            super.keyDown(with: event)
            return
        }
        if event.keyCode == 51 || event.keyCode == 117 {
            flushTextBuffer()
            onText("", 1)
            onInteraction()
            return
        }
        if event.keyCode == 36 || event.keyCode == 76 {
            flushTextBuffer()
            onText("\n", 0)
            onInteraction()
            return
        }
        if event.modifierFlags.contains(.function) {
            flushTextBuffer()
            super.keyDown(with: event)
            return
        }
        guard
            let characters = event.characters,
            !characters.isEmpty,
            characters.unicodeScalars.allSatisfy({
                !CharacterSet.controlCharacters.contains($0)
            })
        else {
            super.keyDown(with: event)
            return
        }
        textBuffer.append(characters)
        textWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in self?.flushTextBuffer() }
        textWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.08, execute: item)
    }

    private func flushScroll() {
        scrollWorkItem = nil
        guard let anchor = scrollAnchor else { return }
        scrollAnchor = nil
        let delta = scrollDeltaY
        scrollDeltaY = 0
        let rect = videoRect
        guard rect.height > 0 else { return }
        let normalizedDelta = min(max(delta * 3 / rect.height, -0.45), 0.45)
        guard abs(normalizedDelta) >= 0.002 else { return }
        let startY = min(max(anchor.y - normalizedDelta / 2, 0), 1)
        let endY = min(max(anchor.y + normalizedDelta / 2, 0), 1)
        guard startY != endY else { return }
        onSwipe(anchor.x, startY, anchor.x, endY, 100)
        onInteraction()
    }

    private func flushTextBuffer() {
        textWorkItem?.cancel()
        textWorkItem = nil
        guard !textBuffer.isEmpty else { return }
        let text = textBuffer
        textBuffer = ""
        onText(text, 0)
        onInteraction()
    }

    private var videoRect: CGRect {
        guard
            let videoSize,
            videoSize.width > 0,
            videoSize.height > 0,
            bounds.width > 0,
            bounds.height > 0
        else { return .zero }

        let videoAspect = videoSize.width / videoSize.height
        let viewAspect = bounds.width / bounds.height
        if viewAspect > videoAspect {
            let width = bounds.height * videoAspect
            return CGRect(
                x: bounds.midX - width / 2,
                y: bounds.minY,
                width: width,
                height: bounds.height
            )
        }
        let height = bounds.width / videoAspect
        return CGRect(
            x: bounds.minX,
            y: bounds.midY - height / 2,
            width: bounds.width,
            height: height
        )
    }

    private func normalizedPoint(_ point: CGPoint) -> CGPoint? {
        let rect = videoRect
        guard rect.width > 0, rect.height > 0, rect.contains(point) else { return nil }
        let x = min(max((point.x - rect.minX) / rect.width, 0), 1)
        let bottomOriginY = min(max((point.y - rect.minY) / rect.height, 0), 1)
        return CGPoint(x: x, y: 1 - bottomOriginY)
    }

    func detachRenderer() {
        flushTextBuffer()
        scrollWorkItem?.cancel()
        scrollWorkItem = nil
        if let keyWindowObserver {
            NotificationCenter.default.removeObserver(keyWindowObserver)
            self.keyWindowObserver = nil
        }
        renderer.detach(displayLayer)
    }
}
