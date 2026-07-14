import SwiftUI

struct DashboardView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("opentomac")
                .font(.largeTitle.bold())
            Text(model.status)
                .foregroundStyle(.secondary)
            Divider()
            Text("Protocol v\(model.protocolVersion)")
                .font(.footnote)
                .foregroundStyle(.tertiary)
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
