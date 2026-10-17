import Foundation

/// Mac side of one pairing attempt against one connected candidate phone
/// (protocol.md "Pairing sequence", steps 2-6).
public struct PairingClient: Sendable {
    public let identity: MacIdentity
    public let pairSecret: Data
    public let random: RandomSource

    public init(identity: MacIdentity, pairSecret: Data, random: @escaping RandomSource = ProtocolCrypto.randomBytes) {
        self.identity = identity
        self.pairSecret = pairSecret
        self.random = random
    }

    public var qr: PairingQR {
        PairingQR(macId: identity.macId, macPub: identity.publicKey, pairSecret: pairSecret, macName: identity.name)
    }

    /// Runs the exchange. Throws `.badProof` on a wrong PAIR_ACCEPT and
    /// `.phoneError(3)` when the phone has no pending pairing for us; the caller
    /// should then disconnect and try other candidates. Returns the phone only
    /// after PAIR_DONE, so a half-finished pairing is never stored.
    public func run(_ link: any FrameLink) async throws -> PairedPhone {
        let info = try PhoneInfo(parsing: try await link.readInfo())
        let channel = MessageChannel(link: link)

        try await channel.send(Message.pairRequest(macId: identity.macId))

        let (type, body) = try Message.parse(try await channel.receive())
        switch type {
        case .pairAccept: break
        case .error: throw ProtocolError.phoneError(try Message.errorCode(fromBody: body))
        default: throw ProtocolError.unexpectedMessage(type.rawValue)
        }
        let proof = try Message.proof(fromPairAccept: body)
        guard ProtocolCrypto.verifyPairingProof(proof, pairSecret: pairSecret, macId: identity.macId,
                                                phoneId: info.phoneId, phonePub: info.phonePub)
        else { throw ProtocolError.badProof }

        let key = try ProtocolCrypto.sessionKey(myPrivate: identity.privateKey, peerPublic: info.phonePub,
                                                macPub: identity.publicKey, phonePub: info.phonePub)
        let box = try ProtocolCrypto.seal(
            key: key,
            aad: ProtocolCrypto.confirmAAD(macId: identity.macId, phoneId: info.phoneId),
            plaintext: Data(identity.name.utf8),
            nonce: random(ProtocolCrypto.nonceLength)
        )
        try await channel.send(Message.pairConfirm(macId: identity.macId, box: box))

        let (doneType, doneBody) = try Message.parse(try await channel.receive())
        switch doneType {
        case .pairDone: return PairedPhone(phoneId: info.phoneId, phonePub: info.phonePub)
        case .error: throw ProtocolError.phoneError(try Message.errorCode(fromBody: doneBody))
        default: throw ProtocolError.unexpectedMessage(doneType.rawValue)
        }
    }
}
