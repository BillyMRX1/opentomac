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
    @Binding var isPresented: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Phone screen").font(.title2.bold())
                Spacer()
                Button("Close") { close() }
                    .keyboardShortcut(.cancelAction)
            }

            Text(statusText)
                .font(.callout)
                .foregroundStyle(.secondary)

            MirrorVideoSurface(renderer: model.videoRenderer)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(.black)
                .clipShape(RoundedRectangle(cornerRadius: 8))
        }
        .padding(20)
        .frame(minWidth: 480, minHeight: 400)
    }

    private var statusText: String {
        if let reason = model.mirrorStoppedReason {
            return "Mirroring ended: \(reason)"
        }
        if !model.mirrorConfigured {
            return "Waiting for the phone… accept the prompt on the phone"
        }
        return "Mirroring live"
    }

    private func close() {
        model.stopMirror()
        isPresented = false
    }
}

private struct MirrorVideoSurface: NSViewRepresentable {
    let renderer: VideoRenderer

    func makeNSView(context: Context) -> MirrorDisplayView {
        MirrorDisplayView(renderer: renderer)
    }

    func updateNSView(_ nsView: MirrorDisplayView, context: Context) {}

    static func dismantleNSView(_ nsView: MirrorDisplayView, coordinator: ()) {
        nsView.detachRenderer()
    }
}

private final class MirrorDisplayView: NSView {
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
