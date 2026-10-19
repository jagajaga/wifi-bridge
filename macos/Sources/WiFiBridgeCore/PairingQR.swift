import Foundation

/// Pairing code. The QR shows `uri` (`wifibridge://pair?p=<payload>`) so the
/// phone's system camera opens the Android app; `payload` is
/// `WBPAIR1;<b64url macId>;<b64url macPub>;<b64url pairSecret>;<b64url utf8 macName>`.
public struct PairingQR: Equatable, Sendable {
    public static let prefix = "WBPAIR1"
    public static let uriPrefix = "wifibridge://pair?p="
    public let macId: Data
    public let macPub: Data
    public let pairSecret: Data
    public let macName: String

    public init(macId: Data, macPub: Data, pairSecret: Data, macName: String) {
        self.macId = macId
        self.macPub = macPub
        self.pairSecret = pairSecret
        self.macName = macName
    }

    public var payload: String {
        [PairingQR.prefix, Bytes.b64url(macId), Bytes.b64url(macPub), Bytes.b64url(pairSecret),
         Bytes.b64url(Data(macName.utf8))].joined(separator: ";")
    }

    /// What the Mac renders as the QR code.
    public var uri: String { PairingQR.uriPrefix + payload }

    /// Accepts the URI form or the bare payload.
    public init(parsing text: String) throws {
        var payload = text
        if payload.lowercased().hasPrefix(PairingQR.uriPrefix) {
            payload = String(payload.dropFirst(PairingQR.uriPrefix.count))
        }
        let parts = payload.split(separator: ";", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 5, parts[0] == PairingQR.prefix else { throw ProtocolError.malformed("not WBPAIR1") }
        guard let id = Bytes.fromB64url(parts[1]), id.count == 16,
              let pub = Bytes.fromB64url(parts[2]), pub.count == 32,
              let secret = Bytes.fromB64url(parts[3]), secret.count == 16,
              let nameBytes = Bytes.fromB64url(parts[4]),
              let name = String(data: nameBytes, encoding: .utf8)
        else { throw ProtocolError.malformed("WBPAIR1 field") }
        self.init(macId: id, macPub: pub, pairSecret: secret, macName: name)
    }
}
