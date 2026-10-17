import CoreBluetooth
import Foundation
import WiFiBridgeCore

enum BLEError: Error, LocalizedError {
    case unavailable(String)
    case timeout
    case disconnected
    case connectFailed(String)
    case missingService
    case missingCharacteristic
    case gatt(String)
    case overflow

    var errorDescription: String? {
        switch self {
        case .unavailable(let s): return "Bluetooth \(s)"
        case .timeout: return "Bluetooth timed out"
        case .disconnected: return "Phone disconnected"
        case .connectFailed(let s): return "Could not connect: \(s)"
        case .missingService: return "Phone does not offer the wifi-bridge service"
        case .missingCharacteristic: return "Phone service is missing a characteristic"
        case .gatt(let s): return "Bluetooth error: \(s)"
        case .overflow: return "Phone sent too much data"
        }
    }
}

/// Resumes a continuation at most once. Touched only on the main queue.
final class OneShot<T> {
    private var continuation: CheckedContinuation<T, Error>?
    init(_ continuation: CheckedContinuation<T, Error>) { self.continuation = continuation }
    func resume(_ result: Result<T, Error>) {
        guard let c = continuation else { return }
        continuation = nil
        c.resume(with: result)
    }
}

enum UUIDs {
    static let service = CBUUID(string: GATT.service)
    static let info = CBUUID(string: GATT.info)
    static let rx = CBUUID(string: GATT.rx)
    static let tx = CBUUID(string: GATT.tx)
}

/// CBCentralManager wrapper. The manager delivers delegate calls on the main
/// queue, and every async entry point hops to the main queue before touching
/// state, so all mutable state here is main-queue confined.
final class BLECentral: NSObject, CBCentralManagerDelegate {
    private var manager: CBCentralManager!
    private var readyWaiters: [OneShot<Void>] = []
    private var discovered: [UUID: (peripheral: CBPeripheral, rssi: Int)] = [:]
    private var connectWaiters: [UUID: OneShot<Void>] = [:]
    private var links: [UUID: BLELink] = [:]

    /// Called on the main queue when the Bluetooth state changes.
    var onStateChange: (() -> Void)?

    override init() {
        super.init()
        manager = CBCentralManager(delegate: self, queue: .main)
    }

    var state: CBManagerState { manager.state }

    var stateSummary: String {
        switch manager.state {
        case .poweredOn: return "Bluetooth: on"
        case .poweredOff: return "Bluetooth: off"
        case .unauthorized: return "Bluetooth: not allowed — enable in System Settings › Privacy › Bluetooth"
        case .unsupported: return "Bluetooth: unsupported"
        case .resetting: return "Bluetooth: resetting"
        case .unknown: return "Bluetooth: starting"
        @unknown default: return "Bluetooth: unknown"
        }
    }

    /// Waits until the radio is powered on.
    func waitUntilReady(timeout: TimeInterval) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            DispatchQueue.main.async {
                let shot = OneShot(c)
                if let error = self.stateError() { shot.resume(.failure(error)); return }
                if self.manager.state == .poweredOn { shot.resume(.success(())); return }
                self.readyWaiters.append(shot)
                DispatchQueue.main.asyncAfter(deadline: .now() + timeout) {
                    shot.resume(.failure(self.stateError() ?? BLEError.timeout))
                }
            }
        }
    }

    private func stateError() -> Error? {
        switch manager.state {
        case .poweredOff: return BLEError.unavailable("is off")
        case .unauthorized: return BLEError.unavailable("permission denied")
        case .unsupported: return BLEError.unavailable("is unsupported")
        default: return nil
        }
    }

    /// Scans for the wifi-bridge service. Returns once at least one phone has been
    /// seen and `settle` seconds have passed, or after `maxWait`. Strongest first.
    func discover(maxWait: TimeInterval, settle: TimeInterval = 1.5) async -> [CBPeripheral] {
        await withCheckedContinuation { (c: CheckedContinuation<[CBPeripheral], Never>) in
            DispatchQueue.main.async {
                self.discovered = [:]
                self.manager.scanForPeripherals(withServices: [UUIDs.service],
                                                options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
                let start = Date()
                func poll() {
                    let elapsed = Date().timeIntervalSince(start)
                    if elapsed >= maxWait || (!self.discovered.isEmpty && elapsed >= settle) {
                        self.manager.stopScan()
                        let found = self.discovered.values.sorted { $0.rssi > $1.rssi }.map(\.peripheral)
                        c.resume(returning: found)
                    } else {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25, execute: poll)
                    }
                }
                poll()
            }
        }
    }

    /// Connects, discovers the service, subscribes to TX. Every operation on the
    /// returned link fails once `timeout` seconds have passed since this call.
    func connect(_ peripheral: CBPeripheral, timeout: TimeInterval) async throws -> BLELink {
        let link = BLELink(peripheral: peripheral, central: self)
        DispatchQueue.main.async {
            self.links[peripheral.identifier] = link
            link.armDeadline(timeout)
        }
        do {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
                DispatchQueue.main.async {
                    let shot = OneShot(c)
                    self.connectWaiters[peripheral.identifier] = shot
                    link.onFail = { error in shot.resume(.failure(error)) }
                    self.manager.connect(peripheral, options: nil)
                }
            }
            try await link.setUp()
            return link
        } catch {
            link.close()
            throw error
        }
    }

    func cancel(_ peripheral: CBPeripheral) {
        DispatchQueue.main.async {
            self.manager.cancelPeripheralConnection(peripheral)
            self.links[peripheral.identifier] = nil
        }
    }

    // MARK: CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn || stateError() != nil {
            let waiters = readyWaiters
            readyWaiters = []
            let error = stateError()
            for w in waiters { w.resume(error.map { .failure($0) } ?? .success(())) }
        }
        if central.state != .poweredOn {
            for link in links.values { link.fail(BLEError.unavailable("turned off")) }
        }
        onStateChange?()
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        discovered[peripheral.identifier] = (peripheral, RSSI.intValue)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connectWaiters.removeValue(forKey: peripheral.identifier)?.resume(.success(()))
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectWaiters.removeValue(forKey: peripheral.identifier)?
            .resume(.failure(BLEError.connectFailed(error?.localizedDescription ?? "unknown")))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectWaiters.removeValue(forKey: peripheral.identifier)?.resume(.failure(BLEError.disconnected))
        links.removeValue(forKey: peripheral.identifier)?.fail(BLEError.disconnected)
    }
}

/// One connected phone, as a WiFiBridgeCore.FrameLink.
final class BLELink: NSObject, CBPeripheralDelegate, FrameLink {
    let peripheral: CBPeripheral
    private weak var central: BLECentral?

    private var infoChar: CBCharacteristic?
    private var rxChar: CBCharacteristic?
    private var txChar: CBCharacteristic?

    private var setupWaiter: OneShot<Void>?
    private var readWaiter: OneShot<Data>?
    private var writeWaiter: OneShot<Void>?
    private var frameWaiter: OneShot<Data>?
    private var frames: [Data] = []
    private var failure: Error?
    private var deadline: DispatchWorkItem?
    private var closed = false
    fileprivate var onFail: ((Error) -> Void)?

    /// Set once in setUp (main queue) before any frame is written.
    private(set) var chunkSize: Int = Framing.minimumChunk

    init(peripheral: CBPeripheral, central: BLECentral) {
        self.peripheral = peripheral
        self.central = central
        super.init()
        peripheral.delegate = self
    }

    var name: String { peripheral.name ?? peripheral.identifier.uuidString }

    func armDeadline(_ seconds: TimeInterval) {
        let item = DispatchWorkItem { [weak self] in
            self?.fail(BLEError.timeout)
            self?.close()
        }
        deadline = item
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    /// Main queue only.
    func fail(_ error: Error) {
        if failure == nil { failure = error }
        let e = failure!
        onFail?(e)
        onFail = nil
        setupWaiter?.resume(.failure(e)); setupWaiter = nil
        readWaiter?.resume(.failure(e)); readWaiter = nil
        writeWaiter?.resume(.failure(e)); writeWaiter = nil
        frameWaiter?.resume(.failure(e)); frameWaiter = nil
    }

    func close() {
        DispatchQueue.main.async {
            guard !self.closed else { return }
            self.closed = true
            self.deadline?.cancel()
            self.fail(BLEError.disconnected)
            self.central?.cancel(self.peripheral)
        }
    }

    /// Runs `start` on the main queue with a one-shot waiter, failing fast if the link already failed.
    private func perform<T>(_ install: @escaping (OneShot<T>) -> Void) async throws -> T {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<T, Error>) in
            DispatchQueue.main.async {
                let shot = OneShot(c)
                if let f = self.failure { shot.resume(.failure(f)); return }
                install(shot)
            }
        }
    }

    func setUp() async throws {
        try await perform { (shot: OneShot<Void>) in
            self.setupWaiter = shot
            self.peripheral.discoverServices([UUIDs.service])
        }
    }

    // MARK: FrameLink

    func readInfo() async throws -> Data {
        try await perform { (shot: OneShot<Data>) in
            guard let c = self.infoChar else { shot.resume(.failure(BLEError.missingCharacteristic)); return }
            self.readWaiter = shot
            self.peripheral.readValue(for: c)
        }
    }

    func writeFrame(_ frame: Data) async throws {
        try await perform { (shot: OneShot<Void>) in
            guard let c = self.rxChar else { shot.resume(.failure(BLEError.missingCharacteristic)); return }
            self.writeWaiter = shot
            self.peripheral.writeValue(frame, for: c, type: .withResponse)
        }
    }

    func nextFrame() async throws -> Data {
        try await perform { (shot: OneShot<Data>) in
            if !self.frames.isEmpty {
                shot.resume(.success(self.frames.removeFirst()))
            } else {
                self.frameWaiter = shot
            }
        }
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error = error { fail(BLEError.gatt(error.localizedDescription)); return }
        guard let service = peripheral.services?.first(where: { $0.uuid == UUIDs.service }) else {
            fail(BLEError.missingService); return
        }
        peripheral.discoverCharacteristics([UUIDs.info, UUIDs.rx, UUIDs.tx], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error = error { fail(BLEError.gatt(error.localizedDescription)); return }
        for c in service.characteristics ?? [] {
            if c.uuid == UUIDs.info { infoChar = c }
            if c.uuid == UUIDs.rx { rxChar = c }
            if c.uuid == UUIDs.tx { txChar = c }
        }
        guard infoChar != nil, rxChar != nil, let tx = txChar else { fail(BLEError.missingCharacteristic); return }
        peripheral.setNotifyValue(true, for: tx)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == UUIDs.tx else { return }
        if let error = error { fail(BLEError.gatt(error.localizedDescription)); return }
        // Chunk = negotiated value length - 1 header byte. `.withoutResponse` reports
        // ATT_MTU - 3, i.e. what fits in a single ATT write; `.withResponse` would
        // report 512 (long/prepared writes), which the phone would then have to
        // reassemble at the GATT layer. Writes are still sent `.withResponse`.
        chunkSize = Framing.chunkSize(maxValueLength: peripheral.maximumWriteValueLength(for: .withoutResponse))
        setupWaiter?.resume(.success(())); setupWaiter = nil
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == UUIDs.info {
            if let error = error {
                readWaiter?.resume(.failure(BLEError.gatt(error.localizedDescription)))
            } else {
                readWaiter?.resume(.success(characteristic.value ?? Data()))
            }
            readWaiter = nil
        } else if characteristic.uuid == UUIDs.tx, error == nil, let value = characteristic.value {
            if let w = frameWaiter {
                frameWaiter = nil
                w.resume(.success(value))
            } else {
                frames.append(value)
                // 64 KiB message cap / 19-byte minimum chunk, with slack.
                if frames.count > 4096 { frames = []; fail(BLEError.overflow) }
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == UUIDs.rx else { return }
        if let error = error {
            writeWaiter?.resume(.failure(BLEError.gatt(error.localizedDescription)))
        } else {
            writeWaiter?.resume(.success(()))
        }
        writeWaiter = nil
    }
}
