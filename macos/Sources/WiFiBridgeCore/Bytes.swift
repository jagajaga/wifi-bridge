import Foundation

/// Hex and unpadded base64url helpers (protocol.md "Notation").
public enum Bytes {
    public static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    /// Parses lowercase or uppercase hex; returns nil on odd length or a non-hex digit.
    public static func fromHex(_ string: String) -> Data? {
        let chars = Array(string.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out = Data(capacity: chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = nibble(chars[i]), let lo = nibble(chars[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        return out
    }

    private static func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case 0x30...0x39: return c - 0x30
        case 0x61...0x66: return c - 0x61 + 10
        case 0x41...0x46: return c - 0x41 + 10
        default: return nil
        }
    }

    /// base64url without padding.
    public static func b64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    /// Decodes base64url with or without padding. Rejects standard-alphabet `+` and `/`.
    public static func fromB64url(_ string: String) -> Data? {
        if string.contains("+") || string.contains("/") || string.contains("=") { return nil }
        var s = string.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        switch s.count % 4 {
        case 0: break
        case 2: s += "=="
        case 3: s += "="
        default: return nil
        }
        return Data(base64Encoded: s)
    }

    /// Compares two byte strings in time that depends only on their lengths.
    public static func constantTimeEqual(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        for (x, y) in zip(a, b) { diff |= x ^ y }
        return diff == 0
    }
}
