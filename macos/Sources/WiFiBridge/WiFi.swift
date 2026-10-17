import CoreWLAN
import Foundation

enum WiFiError: Error, LocalizedError {
    case noInterface
    var errorDescription: String? { "No Wi-Fi interface" }
}

/// CoreWLAN wrapper. Scans and joins run on a background queue (both block).
final class WiFiController: NSObject, CWEventDelegate {
    private let client = CWWiFiClient.shared()
    /// Called on the main queue after a link or power change.
    var onChange: (() -> Void)?

    override init() {
        super.init()
        client.delegate = self
        do {
            try client.startMonitoringEvent(with: .linkDidChange)
            try client.startMonitoringEvent(with: .powerDidChange)
        } catch {
            NSLog("WiFiBridge: CoreWLAN event monitoring unavailable: \(error)")
        }
    }

    var interface: CWInterface? { client.interface() }

    var isPoweredOn: Bool { interface?.powerOn() ?? false }

    /// SSID of the current network; nil if not associated OR if Location is not authorized.
    var currentSSID: String? { interface?.ssid() }

    /// Associated to some network. `ssid()` is redacted without Location permission,
    /// so the channel (not redacted) is used as well.
    var isAssociated: Bool {
        guard let iface = interface else { return false }
        return iface.ssid() != nil || iface.wlanChannel() != nil
    }

    func scan() async throws -> [CWNetwork] {
        guard let iface = interface else { throw WiFiError.noInterface }
        return try await withCheckedThrowingContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    let found: Set<CWNetwork> = try iface.scanForNetworks(withName: nil)
                    cont.resume(returning: Array(found))
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    func join(_ network: CWNetwork, password: String?) async throws {
        guard let iface = interface else { throw WiFiError.noInterface }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try iface.associate(to: network, password: password)
                    cont.resume()
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
    }

    // MARK: CWEventDelegate (called on a CoreWLAN queue)

    func linkDidChangeForWiFiInterface(withName interfaceName: String) {
        DispatchQueue.main.async { self.onChange?() }
    }

    func powerStateDidChangeForWiFiInterface(withName interfaceName: String) {
        DispatchQueue.main.async { self.onChange?() }
    }
}
