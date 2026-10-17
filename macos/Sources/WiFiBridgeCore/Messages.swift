import Foundation

/// GATT layout (protocol.md). Strings so this module stays free of CoreBluetooth.
public enum GATT {
    public static let service = "C86D1F45-41C6-43D2-A031-789F6B73E9D1"
    public static let info = "1D4FE1B5-E225-4A93-9A96-7997402EEC3D"
    public static let rx = "222462DF-2917-4DDB-97AC-5459A7F90A44"
    public static let tx = "CC6D3B26-2FE0-4ED4-A3E2-FBA6032B3AD6"
}

public enum MessageType: UInt8, Sendable {
    case pairRequest = 0x01
    case pairAccept = 0x02
    case pairConfirm = 0x03
    case pairDone = 0x04
    case syncRequest = 0x10
    case syncResponse = 0x11
    case error = 0x7F
}

/// INFO value: 0x01 || phoneId(16) || phonePub(32).
public struct PhoneInfo: Equatable, Sendable {
    public static let version: UInt8 = 0x01
    public let phoneId: Data
    public let phonePub: Data

    public init(phoneId: Data, phonePub: Data) {
        self.phoneId = phoneId
        self.phonePub = phonePub
    }

    public init(parsing value: Data) throws {
        let b = [UInt8](value)
        guard b.count == 49 else { throw ProtocolError.malformed("INFO length \(b.count)") }
        guard b[0] == PhoneInfo.version else { throw ProtocolError.malformed("INFO version \(b[0])") }
        phoneId = Data(b[1..<17])
        phonePub = Data(b[17..<49])
    }

    public var encoded: Data { Data([PhoneInfo.version]) + phoneId + phonePub }
}

/// Encoders / decoders for the message table.
public enum Message {
    public static func pairRequest(macId: Data) -> Data {
        Data([MessageType.pairRequest.rawValue]) + macId
    }

    public static func pairAccept(proof: Data) -> Data {
        Data([MessageType.pairAccept.rawValue]) + proof
    }

    public static func pairConfirm(macId: Data, box: Data) -> Data {
        Data([MessageType.pairConfirm.rawValue]) + macId + box
    }

    public static func syncRequest(macId: Data, box: Data) -> Data {
        Data([MessageType.syncRequest.rawValue]) + macId + box
    }

    public static func syncResponse(box: Data) -> Data {
        Data([MessageType.syncResponse.rawValue]) + box
    }

    /// Splits a message into type and body.
    public static func parse(_ message: Data) throws -> (MessageType, Data) {
        guard let t = message.first else { throw ProtocolError.malformed("empty message") }
        guard let type = MessageType(rawValue: t) else { throw ProtocolError.unexpectedMessage(t) }
        return (type, Data(message.dropFirst()))
    }

    /// Parses a PAIR_ACCEPT body into its 32-byte proof.
    public static func proof(fromPairAccept body: Data) throws -> Data {
        guard body.count == 32 else { throw ProtocolError.malformed("PAIR_ACCEPT length \(body.count)") }
        return body
    }

    public static func errorCode(fromBody body: Data) throws -> UInt8 {
        guard body.count == 1 else { throw ProtocolError.malformed("ERROR length \(body.count)") }
        return body[body.startIndex]
    }
}
