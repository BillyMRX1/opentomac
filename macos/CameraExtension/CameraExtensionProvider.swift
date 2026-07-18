import CoreMedia
import CoreMediaIO
import CoreVideo
import Foundation
import IOKit.audio

/// Minimal CMIO camera provider. It publishes a deterministic test-pattern
/// stream today so the signed extension can be validated independently.
///
/// TODO(signing milestone): replace `makeTestPatternSampleBuffer` with an
/// App Group shared-memory ring (or authenticated XPC service) written by the
/// main app after VideoToolbox decoding. The extension is a separate process;
/// in-process callbacks or global state cannot bridge camera frames to it.
final class CameraExtensionProviderSource: NSObject, CMIOExtensionProviderSource {
    private(set) var provider: CMIOExtensionProvider!
    private let deviceSource = CameraExtensionDeviceSource()

    init(clientQueue: DispatchQueue?) {
        super.init()
        provider = CMIOExtensionProvider(source: self, clientQueue: clientQueue)
        deviceSource.providerSource = self
        do {
            try provider.addDevice(deviceSource.device)
        } catch {
            fatalError("Could not add opentomac camera device: \(error)")
        }
    }

    var availableProperties: Set<CMIOExtensionProperty> { [.providerManufacturer] }

    func providerProperties(forProperties properties: Set<CMIOExtensionProperty>) throws
        -> CMIOExtensionProviderProperties {
        let result = CMIOExtensionProviderProperties(dictionary: [:])
        if properties.contains(.providerManufacturer) {
            result.manufacturer = "opentomac"
        }
        return result
    }

    func setProviderProperties(_ providerProperties: CMIOExtensionProviderProperties) throws {}

    func connect(to client: CMIOExtensionClient) throws {}

    func disconnect(from client: CMIOExtensionClient) {}
}

final class CameraExtensionDeviceSource: NSObject, CMIOExtensionDeviceSource {
    fileprivate weak var providerSource: CameraExtensionProviderSource?
    private let streamSource: CameraExtensionStreamSource
    fileprivate lazy var device = CMIOExtensionDevice(
        localizedName: "opentomac Phone Camera",
        deviceID: UUID(uuidString: "4D9E37AF-C984-40F7-BFBF-CB7422E335A1")!,
        legacyDeviceID: nil,
        source: self
    )

    override init() {
        streamSource = CameraExtensionStreamSource()
        super.init()
        streamSource.deviceSource = self
        do {
            try device.addStream(streamSource.stream)
        } catch {
            fatalError("Could not add opentomac camera stream: \(error)")
        }
    }

    var availableProperties: Set<CMIOExtensionProperty> { [.deviceTransportType, .deviceModel] }

    func deviceProperties(forProperties properties: Set<CMIOExtensionProperty>) throws
        -> CMIOExtensionDeviceProperties {
        let result = CMIOExtensionDeviceProperties(dictionary: [:])
        if properties.contains(.deviceTransportType) {
            result.transportType = kIOAudioDeviceTransportTypeVirtual
        }
        if properties.contains(.deviceModel) {
            result.model = "Android phone camera"
        }
        return result
    }

    func setDeviceProperties(_ deviceProperties: CMIOExtensionDeviceProperties) throws {}
}

final class CameraExtensionStreamSource: NSObject, CMIOExtensionStreamSource {
    fileprivate weak var deviceSource: CameraExtensionDeviceSource?
    private let queue = DispatchQueue(label: "dev.opentomac.camera-extension.frames")
    private var timer: DispatchSourceTimer?
    private var frameNumber: UInt8 = 0
    private lazy var format: CMIOExtensionStreamFormat = {
        var description: CMFormatDescription?
        CMVideoFormatDescriptionCreate(
            allocator: kCFAllocatorDefault,
            codecType: kCVPixelFormatType_32BGRA,
            width: 1280,
            height: 720,
            extensions: nil,
            formatDescriptionOut: &description
        )
        guard let description else { fatalError("Could not create camera format") }
        return CMIOExtensionStreamFormat(
            formatDescription: description,
            maxFrameDuration: CMTime(value: 1, timescale: 30),
            minFrameDuration: CMTime(value: 1, timescale: 30),
            validFrameDurations: nil
        )
    }()
    fileprivate lazy var stream = CMIOExtensionStream(
            localizedName: "opentomac Phone Camera",
            streamID: UUID(uuidString: "74556D9B-5E3D-4FC2-87AF-610BDAE09945")!,
            direction: .source,
            clockType: .hostTime,
            source: self
        )

    var formats: [CMIOExtensionStreamFormat] { [format] }
    var activeFormatIndex: Int = 0
    var frameDuration: CMTime = CMTime(value: 1, timescale: 30)

    var availableProperties: Set<CMIOExtensionProperty> {
        [.streamActiveFormatIndex, .streamFrameDuration]
    }

    func streamProperties(forProperties properties: Set<CMIOExtensionProperty>) throws
        -> CMIOExtensionStreamProperties {
        let result = CMIOExtensionStreamProperties(dictionary: [:])
        if properties.contains(.streamActiveFormatIndex) {
            result.activeFormatIndex = activeFormatIndex
        }
        if properties.contains(.streamFrameDuration) {
            result.frameDuration = frameDuration
        }
        return result
    }

    func setStreamProperties(_ streamProperties: CMIOExtensionStreamProperties) throws {
        if let activeFormatIndex = streamProperties.activeFormatIndex {
            guard activeFormatIndex == 0 else { return }
            self.activeFormatIndex = activeFormatIndex
        }
        if let frameDuration = streamProperties.frameDuration {
            self.frameDuration = frameDuration
        }
    }

    func authorizedToStartStream(for client: CMIOExtensionClient) -> Bool { true }

    func startStream() throws {
        guard timer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: 1.0 / 30.0)
        timer.setEventHandler { [weak self] in self?.sendTestPatternFrame() }
        self.timer = timer
        timer.resume()
    }

    func stopStream() throws {
        timer?.cancel()
        timer = nil
    }

    private func sendTestPatternFrame() {
        guard let sampleBuffer = makeTestPatternSampleBuffer() else { return }
        let hostTime = UInt64(ProcessInfo.processInfo.systemUptime * 1_000_000_000)
        stream.send(
            sampleBuffer,
            discontinuity: [],
            hostTimeInNanoseconds: hostTime
        )
    }

    private func makeTestPatternSampleBuffer() -> CMSampleBuffer? {
        var pixelBuffer: CVPixelBuffer?
        let attributes: [CFString: Any] = [
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
            kCVPixelBufferMetalCompatibilityKey: true,
        ]
        guard CVPixelBufferCreate(
            kCFAllocatorDefault,
            1280,
            720,
            kCVPixelFormatType_32BGRA,
            attributes as CFDictionary,
            &pixelBuffer
        ) == kCVReturnSuccess, let pixelBuffer else { return nil }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        if let base = CVPixelBufferGetBaseAddress(pixelBuffer) {
            let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
            let pointer = base.assumingMemoryBound(to: UInt8.self)
            for y in 0..<720 {
                for x in 0..<1280 {
                    let offset = y * bytesPerRow + x * 4
                    pointer[offset] = UInt8((x / 5 + Int(frameNumber)) & 0xff)
                    pointer[offset + 1] = UInt8((y / 3 + Int(frameNumber)) & 0xff)
                    pointer[offset + 2] = frameNumber
                    pointer[offset + 3] = 0xff
                }
            }
        }
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
        frameNumber &+= 1

        var sampleBuffer: CMSampleBuffer?
        var timing = CMSampleTimingInfo(
            duration: frameDuration,
            presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()),
            decodeTimeStamp: .invalid
        )
        guard CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescription: format.formatDescription,
            sampleTiming: &timing,
            sampleBufferOut: &sampleBuffer
        ) == noErr else { return nil }
        return sampleBuffer
    }
}
