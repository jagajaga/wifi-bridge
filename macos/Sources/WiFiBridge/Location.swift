import CoreLocation
import Foundation

/// macOS 14+ redacts SSIDs in CoreWLAN results unless the app has Location access.
final class LocationAuthorizer: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    var onChange: (() -> Void)?

    override init() {
        super.init()
        manager.delegate = self
    }

    var status: CLAuthorizationStatus { manager.authorizationStatus }

    var isAuthorized: Bool {
        // 3 = authorizedAlways, 4 = authorizedWhenInUse (compared by raw value so this
        // compiles regardless of which case names the macOS SDK exposes).
        status == .authorizedAlways || status.rawValue == 4
    }

    var summary: String {
        switch status {
        case .notDetermined: return "Location: not yet allowed (needed to see network names)"
        case .denied: return "Location: denied — enable in System Settings › Privacy › Location Services"
        case .restricted: return "Location: restricted"
        default: return isAuthorized ? "Location: allowed" : "Location: unknown status"
        }
    }

    func request() {
        manager.requestWhenInUseAuthorization()
        // Some macOS versions only show the prompt once location updates start.
        if status == .notDetermined { manager.startUpdatingLocation() }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        if status != .notDetermined { manager.stopUpdatingLocation() }
        DispatchQueue.main.async { self.onChange?() }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        manager.stopUpdatingLocation()
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        manager.stopUpdatingLocation()
    }
}
