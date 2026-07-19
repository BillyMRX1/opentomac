import SwiftUI
import AppKit

enum DesignTokens {
    enum ColorToken {
        static let accent = Color(red: 0, green: 0.443, blue: 0.89)
        static let accentHover = Color(red: 0, green: 0.467, blue: 0.929)
        static let success = Color(red: 0.086, green: 0.639, blue: 0.29)
        static let warning = Color(red: 0.918, green: 0.702, blue: 0.031)
        static let danger = Color(red: 0.863, green: 0.149, blue: 0.149)
        static let secondaryTint = Color(red: 0.46, green: 0.39, blue: 0.86)
    }

    enum Radius {
        static let small: CGFloat = 8
        static let medium: CGFloat = 12
        static let large: CGFloat = 18
        static let window: CGFloat = 24
        static let pill: CGFloat = 980
    }

    enum Spacing {
        static let xSmall: CGFloat = 4
        static let small: CGFloat = 8
        static let medium: CGFloat = 12
        static let standard: CGFloat = 16
        static let large: CGFloat = 20
        static let xLarge: CGFloat = 24
        static let xxLarge: CGFloat = 32
        static let section: CGFloat = 48
    }

    enum TypeStyle {
        static let display = Font.system(size: 34, weight: .semibold, design: .default)
        static let title = Font.system(size: 28, weight: .semibold, design: .default)
        static let section = Font.system(size: 19, weight: .semibold, design: .default)
        static let heading = Font.system(size: 15, weight: .semibold, design: .default)
        static let body = Font.system(size: 14, weight: .regular, design: .default)
        static let bodyEmphasized = Font.system(size: 14, weight: .semibold, design: .default)
        static let meta = Font.system(size: 12, weight: .regular, design: .default)
        static let metaEmphasized = Font.system(size: 12, weight: .semibold, design: .default)
        static let verification = Font.system(size: 40, weight: .semibold, design: .monospaced)
    }

    enum Motion {
        static let fast = Animation.easeOut(duration: 0.15)
        static let standard = Animation.timingCurve(0.28, 0, 0.22, 1, duration: 0.22)
    }
}

struct LiquidBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            Color(nsColor: .windowBackgroundColor)

            RadialGradient(
                colors: [
                    DesignTokens.ColorToken.accent.opacity(colorScheme == .dark ? 0.24 : 0.13),
                    .clear,
                ],
                center: UnitPoint(x: 0.18, y: 0.08),
                startRadius: 0,
                endRadius: 520
            )

            RadialGradient(
                colors: [
                    DesignTokens.ColorToken.secondaryTint.opacity(colorScheme == .dark ? 0.18 : 0.09),
                    .clear,
                ],
                center: UnitPoint(x: 0.84, y: 0.9),
                startRadius: 0,
                endRadius: 620
            )
        }
        .ignoresSafeArea()
    }
}

private struct GlassCardModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    let radius: CGFloat
    let material: Material
    let shadowed: Bool

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)

        content
            .background(material, in: shape)
            .overlay {
                shape.stroke(Color.primary.opacity(colorScheme == .dark ? 0.12 : 0.09), lineWidth: 1)
            }
            .overlay {
                shape.stroke(
                    LinearGradient(
                        colors: [
                            Color.white.opacity(colorScheme == .dark ? 0.13 : 0.7),
                            Color.white.opacity(0.02),
                            Color.clear,
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    lineWidth: 1
                )
            }
            .shadow(
                color: shadowed ? Color.black.opacity(colorScheme == .dark ? 0.2 : 0.09) : .clear,
                radius: shadowed ? 22 : 0,
                x: 0,
                y: shadowed ? 10 : 0
            )
    }
}

extension View {
    func glassCard(
        radius: CGFloat = DesignTokens.Radius.large,
        material: Material = .ultraThinMaterial,
        shadowed: Bool = true
    ) -> some View {
        modifier(GlassCardModifier(radius: radius, material: material, shadowed: shadowed))
    }

    func liquidScreen() -> some View {
        background { LiquidBackground() }
    }
}

struct BrandMark: View {
    var size: CGFloat = 42

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
            .fill(
                LinearGradient(
                    colors: [DesignTokens.ColorToken.accentHover, DesignTokens.ColorToken.accent],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .overlay {
                Image(systemName: "link")
                    .font(.system(size: size * 0.43, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .overlay {
                RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
                    .stroke(Color.white.opacity(0.35), lineWidth: 1)
            }
            .shadow(color: DesignTokens.ColorToken.accent.opacity(0.25), radius: 14, y: 7)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

struct StatusDot: View {
    let active: Bool

    var body: some View {
        Circle()
            .fill(active ? DesignTokens.ColorToken.success : Color.secondary.opacity(0.55))
            .frame(width: 8, height: 8)
            .background {
                if active {
                    Circle()
                        .fill(DesignTokens.ColorToken.success.opacity(0.15))
                        .frame(width: 16, height: 16)
                }
            }
            .accessibilityHidden(true)
    }
}

struct IconBadge: View {
    let systemName: String
    var tint = DesignTokens.ColorToken.accent
    var size: CGFloat = 36

    var body: some View {
        RoundedRectangle(cornerRadius: min(12, size * 0.3), style: .continuous)
            .fill(tint.opacity(0.11))
            .overlay {
                Image(systemName: systemName)
                    .font(.system(size: size * 0.44, weight: .medium))
                    .foregroundStyle(tint)
            }
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

struct EmptyStateView: View {
    let title: String
    let description: String
    let systemImage: String
    var compact = false

    var body: some View {
        VStack(spacing: DesignTokens.Spacing.medium) {
            IconBadge(systemName: systemImage, tint: .secondary, size: compact ? 42 : 52)
            VStack(spacing: DesignTokens.Spacing.xSmall) {
                Text(title)
                    .font(DesignTokens.TypeStyle.heading)
                    .foregroundStyle(.primary)
                Text(description)
                    .font(DesignTokens.TypeStyle.meta)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity, minHeight: compact ? 86 : 150)
        .padding(compact ? DesignTokens.Spacing.medium : DesignTokens.Spacing.large)
    }
}

struct PermissionBanner<Trailing: View>: View {
    let systemImage: String
    let tint: Color
    let title: String
    let detail: String
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(spacing: DesignTokens.Spacing.medium) {
            IconBadge(systemName: systemImage, tint: tint, size: 34)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(DesignTokens.TypeStyle.bodyEmphasized)
                Text(detail)
                    .font(DesignTokens.TypeStyle.meta)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: DesignTokens.Spacing.medium)
            trailing()
        }
        .padding(.horizontal, DesignTokens.Spacing.standard)
        .padding(.vertical, DesignTokens.Spacing.small + 2)
        .glassCard(material: .regularMaterial, shadowed: false)
    }
}

struct SheetHeader<Trailing: View>: View {
    let title: String
    let subtitle: String
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(alignment: .center, spacing: DesignTokens.Spacing.standard) {
            VStack(alignment: .leading, spacing: DesignTokens.Spacing.xSmall) {
                Text(title)
                    .font(DesignTokens.TypeStyle.title)
                Text(subtitle)
                    .font(DesignTokens.TypeStyle.meta)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            trailing()
        }
    }
}

struct ToolTileButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity, minHeight: 92)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: DesignTokens.Radius.large, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: DesignTokens.Radius.large, style: .continuous)
                    .stroke(Color.primary.opacity(0.09), lineWidth: 1)
            }
            .shadow(color: Color.black.opacity(configuration.isPressed ? 0.04 : 0.09), radius: configuration.isPressed ? 6 : 13, y: configuration.isPressed ? 3 : 8)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(DesignTokens.Motion.fast, value: configuration.isPressed)
    }
}
