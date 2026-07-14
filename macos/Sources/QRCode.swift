import SwiftUI
import CoreImage.CIFilterBuiltins

/// Renders a string as a QR code image for display during pairing.
enum QRCode {
    static func image(from string: String) -> Image? {
        let context = CIContext()
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return Image(decorative: cgImage, scale: 1.0)
    }
}
