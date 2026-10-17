import AppKit
import SwiftUI
import WiFiBridgeCore

struct MenuContent: View {
    @ObservedObject var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Text(model.status)
        if let last = model.lastResult { Text(last) }
        if !model.locationAuthorized {
            Button(model.locationSummary) { model.requestLocation() }
        }
        if model.ble.state != .poweredOn { Text(model.bluetoothSummary) }

        Divider()

        Button("Sync all networks from phone") { model.syncAll() }
            .disabled(model.phones.isEmpty || model.isBusy)

        Menu("Cached networks (\(model.cachedNetworks.count))") {
            if model.cachedNetworks.isEmpty {
                Text("None yet")
            }
            ForEach(model.cachedNetworks, id: \.self) { ssid in
                Button("Forget “\(ssid)”") { model.forget(ssid) }
            }
        }

        Divider()

        Button("Pair a phone…") {
            model.pairing.requested = true
            openWindow(id: "pair")
            NSApp.activate(ignoringOtherApps: true)
        }
        Menu("Paired phones (\(model.phones.count))") {
            if model.phones.isEmpty {
                Text("None")
            }
            ForEach(model.phones) { phone in
                Button("Unpair \(phone.label)") { model.unpair(phone) }
            }
        }
        Toggle("Auto-sync", isOn: $model.autoSync)

        Divider()

        Button("Quit WiFi Bridge") { NSApplication.shared.terminate(nil) }
            .keyboardShortcut("q")
    }
}

struct PairView: View {
    @ObservedObject var session: PairingSession
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Text("Pair a phone").font(.title2)
            switch session.phase {
            case .searching:
                if let image = session.qrImage {
                    Image(nsImage: image)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 280, height: 280)
                }
                Text(session.detail).multilineTextAlignment(.center)
                Text("Expires \(session.expiresAt, style: .relative)").font(.caption).foregroundStyle(.secondary)
                Button("Cancel") { session.stop() }
            case .paired(let label):
                Image(systemName: "checkmark.circle.fill").font(.system(size: 48)).foregroundStyle(.green)
                Text("Paired with \(label)")
                Text(session.detail).foregroundStyle(.secondary)
            case .expired:
                Text(session.detail)
                Button("New code") { session.start() }
            case .failed(let why):
                Text(why)
                Button("Try again") { session.start() }
            case .idle:
                Button("Show pairing code") { session.start() }
            }
        }
        .padding(24)
        .frame(minWidth: 360)
        .onAppear {
            // Only start when the user asked from the menu; close a window that
            // SwiftUI restored or opened on its own at launch.
            if session.requested {
                session.requested = false
                session.start()
            } else {
                dismiss()
            }
        }
        .onDisappear { session.stop() }
    }
}
