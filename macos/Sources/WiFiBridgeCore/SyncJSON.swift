import Foundation

/// Request JSON: {"n":"<b64url 16 bytes>","ssids":[...],"all":false}
///
/// Encoded by hand rather than with JSONEncoder so the key order and escaping
/// are fixed (compact, keys in spec order, non-ASCII emitted as raw UTF-8,
/// `/` not escaped) and the bytes match docs/test-vectors.json everywhere.
public struct SyncRequest: Equatable, Sendable {
    public let n: String
    public let ssids: [String]
    public let all: Bool

    public init(n: String, ssids: [String], all: Bool) {
        self.n = n
        self.ssids = ssids
        self.all = all
    }

    public var json: Data {
        let list = ssids.map(JSONText.string).joined(separator: ",")
        let s = "{\"n\":\(JSONText.string(n)),\"ssids\":[\(list)],\"all\":\(all ? "true" : "false")}"
        return Data(s.utf8)
    }
}

enum JSONText {
    static func string(_ s: String) -> String {
        var out = "\""
        for scalar in s.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if scalar.value < 0x20 {
                    out += String(format: "\\u%04x", scalar.value)
                } else {
                    out.unicodeScalars.append(scalar)
                }
            }
        }
        return out + "\""
    }
}

public struct WiFiNetwork: Codable, Equatable, Sendable {
    public let ssid: String
    public let security: String   // WPA, WEP, SAE, nopass
    public let password: String
    public let hidden: Bool

    public init(ssid: String, security: String, password: String, hidden: Bool) {
        self.ssid = ssid
        self.security = security
        self.password = password
        self.hidden = hidden
    }
}

public struct SyncResponse: Codable, Equatable, Sendable {
    public enum Status: String, Codable, Sendable {
        case ok, denied, timeout
    }

    public let n: String
    public let status: Status
    public let networks: [WiFiNetwork]

    public init(n: String, status: Status, networks: [WiFiNetwork]) {
        self.n = n
        self.status = status
        self.networks = networks
    }

    enum CodingKeys: String, CodingKey { case n, status, networks }

    /// `networks` may be omitted when status is not `ok`.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        n = try c.decode(String.self, forKey: .n)
        status = try c.decode(Status.self, forKey: .status)
        networks = try c.decodeIfPresent([WiFiNetwork].self, forKey: .networks) ?? []
    }

    public static func decode(_ data: Data) throws -> SyncResponse {
        do {
            return try JSONDecoder().decode(SyncResponse.self, from: data)
        } catch {
            throw ProtocolError.malformed("SYNC_RESPONSE json")
        }
    }
}
