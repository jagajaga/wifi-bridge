import Crypto
import Foundation

public enum ProtocolError: Error, Equatable, Sendable {
    case authenticationFailed
    case malformed(String)
    case unexpectedMessage(UInt8)
    case phoneError(UInt8)
    case badProof
    case unknownPhone
    case nonceMismatch
    case messageTooLarge
    case transport(String)
}

/// Key derivation, sealing and the pairing proof (protocol.md "Identities and keys").
public enum ProtocolCrypto {
    public static let hkdfSalt = Data("wifi-bridge/v1".utf8)
    public static let nonceLength = 12
    public static let tagLength = 16

    /// shared = X25519(myPriv, peerPub)
    public static func sharedSecret(myPrivate: Data, peerPublic: Data) throws -> Data {
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: myPrivate)
        let pub = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublic)
        let secret = try priv.sharedSecretFromKeyAgreement(with: pub)
        return secret.withUnsafeBytes { Data($0) }
    }

    /// K = HKDF-SHA256(shared, salt "wifi-bridge/v1", info macPub || phonePub, 32).
    /// `macPub` / `phonePub` are passed by role, so both sides get the same info order.
    public static func sessionKey(myPrivate: Data, peerPublic: Data, macPub: Data, phonePub: Data) throws -> SymmetricKey {
        let shared = try sharedSecret(myPrivate: myPrivate, peerPublic: peerPublic)
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: shared),
            salt: hkdfSalt,
            info: macPub + phonePub,
            outputByteCount: 32
        )
    }

    public static func publicKey(fromPrivate priv: Data) throws -> Data {
        try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: priv).publicKey.rawRepresentation
    }

    public static func randomBytes(_ count: Int) -> Data {
        var rng = SystemRandomNumberGenerator()
        return Data((0..<count).map { _ in UInt8.random(in: .min ... .max, using: &rng) })
    }

    /// nonce(12) || ciphertext || tag(16). `nonce` nil means 12 fresh random bytes.
    public static func seal(key: SymmetricKey, aad: Data, plaintext: Data, nonce: Data? = nil) throws -> Data {
        let n = nonce ?? randomBytes(nonceLength)
        guard n.count == nonceLength else { throw ProtocolError.malformed("nonce length") }
        let box = try ChaChaPoly.seal(plaintext, using: key, nonce: ChaChaPoly.Nonce(data: n), authenticating: aad)
        return box.combined
    }

    public static func open(key: SymmetricKey, aad: Data, box: Data) throws -> Data {
        guard box.count >= nonceLength + tagLength else { throw ProtocolError.authenticationFailed }
        do {
            let sealed = try ChaChaPoly.SealedBox(combined: box)
            return try ChaChaPoly.open(sealed, using: key, authenticating: aad)
        } catch {
            throw ProtocolError.authenticationFailed
        }
    }

    /// proof = HMAC-SHA256(pairSecret, "wb-pair-phone" || macId || phoneId || phonePub)
    public static func pairingProof(pairSecret: Data, macId: Data, phoneId: Data, phonePub: Data) -> Data {
        let msg = Data("wb-pair-phone".utf8) + macId + phoneId + phonePub
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: SymmetricKey(data: pairSecret))
        return Data(mac)
    }

    public static func verifyPairingProof(_ proof: Data, pairSecret: Data, macId: Data, phoneId: Data, phonePub: Data) -> Bool {
        let expected = pairingProof(pairSecret: pairSecret, macId: macId, phoneId: phoneId, phonePub: phonePub)
        return Bytes.constantTimeEqual(proof, expected)
    }

    // AAD constructions (protocol.md "Messages").
    public static func confirmAAD(macId: Data, phoneId: Data) -> Data { Data("wb-pair-confirm".utf8) + macId + phoneId }
    public static func requestAAD(macId: Data, phoneId: Data) -> Data { Data("wb-req".utf8) + macId + phoneId }
    public static func responseAAD(macId: Data, phoneId: Data) -> Data { Data("wb-resp".utf8) + macId + phoneId }
}
