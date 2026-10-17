import Foundation

/// The frame-level link a client state machine runs over. The macOS app backs it
/// with a connected CBPeripheral (INFO read, RX write-with-response, TX notify);
/// tests back it with an in-memory fake phone. Implementations own timeouts:
/// every method should fail (throw) once the link's deadline passes.
public protocol FrameLink: AnyObject {
    /// Max chunk bytes per frame, header excluded (>= 19).
    var chunkSize: Int { get }
    /// Reads the INFO characteristic.
    func readInfo() async throws -> Data
    /// Writes one frame to RX and returns once the write response arrived.
    func writeFrame(_ frame: Data) async throws
    /// Returns the next TX notification value (one frame).
    func nextFrame() async throws -> Data
    /// Disconnects. Idempotent.
    func close()
}

/// Messages over a FrameLink: splits outgoing, reassembles incoming.
public final class MessageChannel {
    public let link: any FrameLink
    private var reassembler = Reassembler()

    public init(link: any FrameLink) { self.link = link }

    public func send(_ message: Data) async throws {
        for frame in Framing.split(message, chunk: max(Framing.minimumChunk, link.chunkSize)) {
            try await link.writeFrame(frame)
        }
    }

    public func receive() async throws -> Data {
        while true {
            let frame = try await link.nextFrame()
            if let message = reassembler.feed(frame) { return message }
        }
    }
}

/// The Mac's long-lived identity.
public struct MacIdentity: Sendable, Equatable {
    public let macId: Data
    public let privateKey: Data
    public let publicKey: Data
    public let name: String

    public init(macId: Data, privateKey: Data, name: String) throws {
        guard macId.count == 16 else { throw ProtocolError.malformed("macId length") }
        self.macId = macId
        self.privateKey = privateKey
        self.publicKey = try ProtocolCrypto.publicKey(fromPrivate: privateKey)
        self.name = name
    }

    public static func generate(name: String) throws -> MacIdentity {
        try MacIdentity(macId: ProtocolCrypto.randomBytes(16), privateKey: ProtocolCrypto.randomBytes(32), name: name)
    }
}

public struct PairedPhone: Codable, Equatable, Sendable, Identifiable {
    public let phoneId: Data
    public let phonePub: Data
    public let pairedAt: Date

    public init(phoneId: Data, phonePub: Data, pairedAt: Date = Date()) {
        self.phoneId = phoneId
        self.phonePub = phonePub
        self.pairedAt = pairedAt
    }

    public var id: String { Bytes.hex(phoneId) }
    /// Short label for the menu: first 4 bytes of phoneId.
    public var label: String { "Phone " + String(Bytes.hex(phoneId).prefix(8)) }
}

public typealias RandomSource = @Sendable (Int) -> Data
