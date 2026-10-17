import Foundation

/// protocol.md "Framing": header(1) || chunk, FIRST = 0x01, LAST = 0x02.
public enum Framing {
    public static let first: UInt8 = 0x01
    public static let last: UInt8 = 0x02
    public static let minimumChunk = 19
    public static let maxMessage = 65_536

    /// Chunk size for a negotiated max value length (header byte subtracted), floored at 19.
    public static func chunkSize(maxValueLength: Int) -> Int {
        max(minimumChunk, maxValueLength - 1)
    }

    public static func split(_ message: Data, chunk: Int) -> [Data] {
        precondition(chunk > 0)
        let bytes = [UInt8](message)
        var parts: [[UInt8]] = stride(from: 0, to: bytes.count, by: chunk).map {
            Array(bytes[$0 ..< min($0 + chunk, bytes.count)])
        }
        if parts.isEmpty { parts = [[]] }
        return parts.enumerated().map { i, part in
            var header: UInt8 = 0
            if i == 0 { header |= first }
            if i == parts.count - 1 { header |= last }
            return Data([header] + part)
        }
    }
}

/// Reassembles frames into messages. Not thread-safe; feed from one queue.
public struct Reassembler: Sendable {
    public let cap: Int
    private var buffer: Data?

    public init(cap: Int = Framing.maxMessage) { self.cap = cap }

    /// Feeds one frame. Returns a complete message when a LAST frame closes one.
    /// Frames before any FIRST are ignored; an oversized message is dropped and the state reset.
    public mutating func feed(_ frame: Data) -> Data? {
        guard let header = frame.first else { return nil }
        let chunk = frame.dropFirst()
        if header & Framing.first != 0 { buffer = Data() }
        guard var current = buffer else { return nil }
        current.append(contentsOf: chunk)
        if current.count > cap {
            buffer = nil
            return nil
        }
        if header & Framing.last != 0 {
            buffer = nil
            return current
        }
        buffer = current
        return nil
    }

    public var isIdle: Bool { buffer == nil }
}
