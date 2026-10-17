import AppKit
import CoreBluetooth
import CoreWLAN
import Foundation
import SwiftUI
import WiFiBridgeCore

enum AppError: Error, LocalizedError {
    case noPairedPhone
    case phoneNotFound
    case identity(String)

    var errorDescription: String? {
        switch self {
        case .noPairedPhone: return "No phone paired yet"
        case .phoneNotFound: return "Phone not found nearby"
        case .identity(let s): return "Identity error: \(s)"
        }
    }
}

/// App state and the automatic-sync loop. Main-actor isolated.
@MainActor
final class AppModel: ObservableObject {
    static let tickInterval: TimeInterval = 20
    static let askBackoff: TimeInterval = 120
    static let bleTimeout: TimeInterval = 15

    @Published private(set) var status = "Starting…"
    @Published private(set) var lastResult: String?
    @Published private(set) var cachedNetworks: [String] = []
    @Published private(set) var phones: [PairedPhone] = []
    @Published private(set) var isBusy = false
    @Published private(set) var locationSummary = ""
    @Published private(set) var locationAuthorized = false
    @Published private(set) var bluetoothSummary = ""
    @Published var autoSync: Bool {
        didSet {
            UserDefaults.standard.set(autoSync, forKey: "autoSync")
            if autoSync { scheduleTick(after: 0.5) }
        }
    }

    let wifi = WiFiController()
    let ble = BLECentral()
    let location = LocationAuthorizer()
    private(set) var identity: MacIdentity?
    lazy var pairing = PairingSession(model: self)

    /// Pairing owns the BLE radio while active; auto-sync stays out of its way.
    var pairingActive = false

    private var timer: Timer?
    private var pendingTick: DispatchWorkItem?
    private var askBackoff = AskBackoff(interval: AppModel.askBackoff)
    private var failedJoins: [String: Date] = [:]

    init() {
        autoSync = UserDefaults.standard.object(forKey: "autoSync") as? Bool ?? true
        do {
            identity = try IdentityStore.loadOrCreate()
        } catch {
            status = "Keychain unavailable: \(error.localizedDescription)"
        }
        phones = PhoneStore.load()
        cachedNetworks = NetworkCache.ssids()

        wifi.onChange = { [weak self] in self?.scheduleTick(after: 2) }
        location.onChange = { [weak self] in self?.refreshPermissions(); self?.scheduleTick(after: 1) }
        ble.onStateChange = { [weak self] in self?.refreshPermissions() }
        refreshPermissions()
        if location.status == .notDetermined { location.request() }

        timer = Timer.scheduledTimer(withTimeInterval: Self.tickInterval, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.tick() }
        }
        scheduleTick(after: 3)
    }

    /// The identity, retrying the Keychain if it could not be read at launch (e.g. still locked).
    func loadIdentity() -> MacIdentity? {
        if identity == nil { identity = try? IdentityStore.loadOrCreate() }
        return identity
    }

    var menuIcon: String {
        if isBusy { return "arrow.triangle.2.circlepath" }
        return wifi.isAssociated ? "wifi" : "wifi.slash"
    }

    func refreshPermissions() {
        locationSummary = location.summary
        locationAuthorized = location.isAuthorized
        bluetoothSummary = ble.stateSummary
    }

    func requestLocation() { location.request() }

    func scheduleTick(after seconds: TimeInterval) {
        pendingTick?.cancel()
        let item = DispatchWorkItem { [weak self] in
            Task { @MainActor in await self?.tick() }
        }
        pendingTick = item
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    // MARK: Automatic sync

    func tick() async {
        refreshPermissions()
        guard !isBusy, !pairingActive else { return }
        guard wifi.interface != nil else { status = "No Wi-Fi interface"; return }
        guard wifi.isPoweredOn else { status = "Wi-Fi is off"; return }
        if wifi.isAssociated {
            status = wifi.currentSSID.map { "Connected to \($0)" } ?? "Connected"
            return
        }
        guard autoSync else { status = "Offline (auto-sync off)"; return }
        isBusy = true
        defer { isBusy = false }
        await connectIfPossible(mayAskPhone: true)
    }

    /// Scan, try cached passwords strongest first, then (optionally) ask the phone.
    private func connectIfPossible(mayAskPhone: Bool) async {
        status = "Offline — scanning"
        let visible: [CWNetwork]
        do {
            visible = strongestPerSSID(try await wifi.scan())
        } catch {
            status = "Offline — scan failed: \(error.localizedDescription)"
            return
        }
        if visible.isEmpty {
            status = locationAuthorized ? "Offline — no networks visible"
                                        : "Offline — allow Location so network names are visible"
            return
        }

        if await joinFirst(of: visible, using: { NetworkCache.password(for: $0) }) { return }
        guard mayAskPhone else { status = "Offline — no cached network in range"; return }
        guard !phones.isEmpty else { status = "Offline — pair a phone to sync passwords"; return }

        let ssids = visible.compactMap(\.ssid)
        let key = Set(ssids)
        guard askBackoff.shouldAsk(visible: key, now: Date()) else {
            status = "Offline — asked phone recently"
            return
        }
        askBackoff.recordAsk(key, at: Date())

        status = "Syncing with phone…"
        do {
            let result = try await askPhone(ssids: ssids, all: false)
            guard handle(result) else { return }
            let received = Dictionary(result.response.networks.map { ($0.ssid, $0.password) },
                                      uniquingKeysWith: { a, _ in a })
            failedJoins = failedJoins.filter { received[$0.key] == nil }
            if await joinFirst(of: visible, using: { received[$0] }) { return }
            status = result.response.networks.isEmpty ? "Offline — phone knows none of the visible networks"
                                                      : "Offline — could not join"
        } catch {
            status = "Offline"
            lastResult = "Sync failed: \(error.localizedDescription)"
        }
    }

    /// Applies a sync result to the cache. Returns false if the phone did not share.
    private func handle(_ result: SyncClient.Result) -> Bool {
        switch result.response.status {
        case .ok:
            var stored = 0
            for network in result.response.networks {
                do { try NetworkCache.store(network); stored += 1 } catch {
                    lastResult = "Could not cache \(network.ssid): \(error.localizedDescription)"
                }
            }
            cachedNetworks = NetworkCache.ssids()
            lastResult = "Received \(stored) network\(stored == 1 ? "" : "s") at \(Self.time())"
            return true
        case .denied:
            status = "Offline"
            lastResult = "Phone denied the request at \(Self.time())"
            return false
        case .timeout:
            status = "Offline"
            lastResult = "Phone approval timed out at \(Self.time())"
            return false
        }
    }

    private func joinFirst(of visible: [CWNetwork], using password: (String) -> String?) async -> Bool {
        for network in visible {
            guard let ssid = network.ssid, let pw = password(ssid) else { continue }
            if let failed = failedJoins[ssid], Date().timeIntervalSince(failed) < Self.askBackoff { continue }
            status = "Joining \(ssid)…"
            do {
                try await wifi.join(network, password: pw.isEmpty ? nil : pw)
                failedJoins[ssid] = nil
                status = "Connected to \(ssid)"
                lastResult = "Joined \(ssid) at \(Self.time())"
                return true
            } catch {
                failedJoins[ssid] = Date()
                lastResult = "Join \(ssid) failed: \(error.localizedDescription)"
            }
        }
        return false
    }

    /// One CWNetwork per SSID (strongest BSS), strongest first. Redacted (nil) SSIDs dropped.
    private func strongestPerSSID(_ networks: [CWNetwork]) -> [CWNetwork] {
        var best: [String: CWNetwork] = [:]
        for n in networks {
            guard let ssid = n.ssid, !ssid.isEmpty else { continue }
            if let cur = best[ssid], cur.rssiValue >= n.rssiValue { continue }
            best[ssid] = n
        }
        return best.values.sorted { $0.rssiValue > $1.rssiValue }
    }

    // MARK: Phone exchange

    /// Finds a paired phone over BLE and runs one SYNC exchange, within the 15 s budget.
    func askPhone(ssids: [String], all: Bool) async throws -> SyncClient.Result {
        guard let identity = loadIdentity() else { throw AppError.identity("Keychain unavailable") }
        guard !phones.isEmpty else { throw AppError.noPairedPhone }
        let deadline = Date().addingTimeInterval(Self.bleTimeout)
        try await ble.waitUntilReady(timeout: 3)
        let candidates = await ble.discover(maxWait: min(6, deadline.timeIntervalSinceNow))
        guard !candidates.isEmpty else { throw AppError.phoneNotFound }

        let client = SyncClient(identity: identity, pairedPhones: phones)
        var lastError: Error = AppError.phoneNotFound
        for peripheral in candidates {
            let remaining = deadline.timeIntervalSinceNow
            guard remaining > 1 else { lastError = BLEError.timeout; break }
            do {
                let link = try await ble.connect(peripheral, timeout: remaining)
                defer { link.close() }
                return try await client.run(link, ssids: ssids, all: all)
            } catch ProtocolError.unknownPhone {
                continue   // someone else's phone
            } catch {
                lastError = error
            }
        }
        throw lastError
    }

    // MARK: Menu actions

    func syncAll() {
        guard !isBusy, !pairingActive else { return }
        isBusy = true
        status = "Syncing all networks…"
        Task {
            defer { isBusy = false }
            do {
                let result = try await askPhone(ssids: [], all: true)
                if handle(result) {
                    if wifi.isPoweredOn && !wifi.isAssociated {
                        await connectIfPossible(mayAskPhone: false)
                    } else {
                        status = wifi.currentSSID.map { "Connected to \($0)" } ?? "Connected"
                    }
                }
            } catch {
                status = wifi.isAssociated ? "Connected" : "Offline"
                lastResult = "Sync failed: \(error.localizedDescription)"
            }
        }
    }

    func forget(_ ssid: String) {
        NetworkCache.forget(ssid)
        cachedNetworks = NetworkCache.ssids()
    }

    func addPhone(_ phone: PairedPhone) {
        phones.removeAll { $0.phoneId == phone.phoneId }
        phones.append(phone)
        persistPhones()
        askBackoff.reset()
        lastResult = "Paired \(phone.label) at \(Self.time())"
    }

    func unpair(_ phone: PairedPhone) {
        phones.removeAll { $0.phoneId == phone.phoneId }
        persistPhones()
    }

    private func persistPhones() {
        do { try PhoneStore.save(phones) } catch { lastResult = "Could not save phones: \(error.localizedDescription)" }
    }

    static func time() -> String {
        let f = DateFormatter()
        f.timeStyle = .short
        return f.string(from: Date())
    }
}

/// Pairing mode: shows the WBPAIR1 QR and tries each nearby phone until one proves
/// it scanned it. Expires after 10 minutes.
@MainActor
final class PairingSession: ObservableObject {
    static let lifetime: TimeInterval = 600

    /// Set by the menu just before it opens the pairing window. SwiftUI may also
    /// open the `Window` scene by itself at launch; without this flag that would
    /// start a pairing session (pausing auto-sync) nobody asked for.
    var requested = false

    enum Phase: Equatable {
        case idle, searching, paired(String), expired, failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    @Published private(set) var qrImage: NSImage?
    @Published private(set) var detail = ""
    @Published private(set) var expiresAt = Date()

    private unowned let model: AppModel
    private var task: Task<Void, Never>?

    init(model: AppModel) { self.model = model }

    func start() {
        stop()
        guard let identity = model.loadIdentity() else { phase = .failed("No Mac identity (Keychain)"); return }
        let client = PairingClient(identity: identity, pairSecret: ProtocolCrypto.randomBytes(16))
        qrImage = QRCode.image(for: client.qr.payload)
        expiresAt = Date().addingTimeInterval(Self.lifetime)
        phase = .searching
        detail = "Scan this code with wifi-bridge on your phone."
        model.pairingActive = true
        task = Task { await self.run(client) }
    }

    func stop() {
        task?.cancel()
        task = nil
        model.pairingActive = false
        if phase == .searching { phase = .idle }
    }

    private func run(_ client: PairingClient) async {
        // No defer resetting pairingActive: a cancelled run must not clear the flag
        // a newer session set. stop() and the two terminal states below clear it.
        // Let an in-flight auto-sync finish with the radio first.
        while model.isBusy && !Task.isCancelled { try? await Task.sleep(nanoseconds: 500_000_000) }
        while !Task.isCancelled {
            guard Date() < expiresAt else {
                phase = .expired
                model.pairingActive = false
                qrImage = nil
                detail = "The code expired. Start again for a new one."
                return
            }
            do {
                try await model.ble.waitUntilReady(timeout: 5)
            } catch {
                detail = error.localizedDescription
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                continue
            }
            let candidates = await model.ble.discover(maxWait: 4)
            for peripheral in candidates where !Task.isCancelled {
                do {
                    let link = try await model.ble.connect(peripheral, timeout: AppModel.bleTimeout)
                    defer { link.close() }
                    let phone = try await client.run(link)
                    model.addPhone(phone)
                    model.pairingActive = false
                    phase = .paired(phone.label)
                    qrImage = nil
                    detail = "Paired. You can close this window."
                    return
                } catch ProtocolError.badProof {
                    detail = "A phone answered with the wrong proof; still looking…"
                } catch ProtocolError.phoneError(3) {
                    detail = "Found a phone that has not scanned this code yet…"
                } catch {
                    detail = "Still looking… (\(error.localizedDescription))"
                }
            }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
        }
    }
}
