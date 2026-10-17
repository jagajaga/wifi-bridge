import Foundation

/// Mac side of one SYNC_REQUEST / SYNC_RESPONSE exchange (protocol.md "Sync").
public struct SyncClient: Sendable {
    public let identity: MacIdentity
    public let pairedPhones: [PairedPhone]
    public let random: RandomSource

    /// How long the Mac waits for SYNC_RESPONSE once the request is written: the
    /// phone's approval prompt answers `timeout` after 60 s (protocol.md
    /// "Approval"), plus margin for the response to cross the link.
    public static let replyWait: TimeInterval = 75

    public init(identity: MacIdentity, pairedPhones: [PairedPhone],
                random: @escaping RandomSource = ProtocolCrypto.randomBytes) {
        self.identity = identity
        self.pairedPhones = pairedPhones
        self.random = random
    }

    /// The paired phone this INFO belongs to, if any (phoneId and phonePub must both match).
    public func pairedPhone(for info: PhoneInfo) -> PairedPhone? {
        pairedPhones.first { $0.phoneId == info.phoneId && $0.phonePub == info.phonePub }
    }

    public struct Result: Sendable, Equatable {
        public let phone: PairedPhone
        public let response: SyncResponse
    }

    /// Throws `.unknownPhone` if INFO is not a paired phone (caller tries the next
    /// candidate), `.nonceMismatch` if the response does not echo our `n`.
    public func run(_ link: any FrameLink, ssids: [String], all: Bool) async throws -> Result {
        let info = try PhoneInfo(parsing: try await link.readInfo())
        guard let phone = pairedPhone(for: info) else { throw ProtocolError.unknownPhone }

        let key = try ProtocolCrypto.sessionKey(myPrivate: identity.privateKey, peerPublic: phone.phonePub,
                                                macPub: identity.publicKey, phonePub: phone.phonePub)
        let n = Bytes.b64url(random(16))
        let request = SyncRequest(n: n, ssids: ssids, all: all)
        let box = try ProtocolCrypto.seal(
            key: key,
            aad: ProtocolCrypto.requestAAD(macId: identity.macId, phoneId: phone.phoneId),
            plaintext: request.json,
            nonce: random(ProtocolCrypto.nonceLength)
        )
        let channel = MessageChannel(link: link)
        try await channel.send(Message.syncRequest(macId: identity.macId, box: box))
        link.allowReplyWait(Self.replyWait)

        let (type, body) = try Message.parse(try await channel.receive())
        switch type {
        case .syncResponse: break
        case .error: throw ProtocolError.phoneError(try Message.errorCode(fromBody: body))
        default: throw ProtocolError.unexpectedMessage(type.rawValue)
        }
        let json = try ProtocolCrypto.open(
            key: key, aad: ProtocolCrypto.responseAAD(macId: identity.macId, phoneId: phone.phoneId), box: body)
        let response = try SyncResponse.decode(json)
        guard Bytes.constantTimeEqual(Data(response.n.utf8), Data(n.utf8)) else { throw ProtocolError.nonceMismatch }
        return Result(phone: phone, response: response)
    }
}
