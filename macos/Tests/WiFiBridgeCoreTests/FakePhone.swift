import Crypto
import Foundation
import XCTest
@testable import WiFiBridgeCore

/// An in-memory phone peripheral: reassembles the Mac's RX frames, answers like
/// the Android responder would, and frames its replies onto TX.
final class FakePhone: FrameLink {
    let phonePriv: Data
    let phonePub: Data
    let phoneId: Data

    var chunkSize = 19         // Mac -> phone (RX) chunk
    var txChunk = 19           // phone -> Mac (TX) chunk

    /// macId -> (pairSecret, macPub) from a scanned QR.
    var pending: [Data: (secret: Data, macPub: Data)] = [:]
    /// When set, PAIR_ACCEPT is computed with this secret instead (a phone that did not scan our QR).
    var proofSecretOverride: Data?
    /// macId -> macPub for paired Macs.
    var pairedMacs: [Data: Data] = [:]
    /// Networks the vault holds.
    var vault: [WiFiNetwork] = []
    /// When set, the SYNC_RESPONSE echoes this `n` instead of the request's.
    var responseNonceOverride: String?
    /// When set, replies to SYNC_REQUEST with exactly these message bytes.
    var fixedSyncResponse: Data?

    private(set) var received: [Data] = []
    private(set) var confirmedMacName: String?
    private(set) var lastRequestJSON: Data?
    private(set) var closed = false
    private var reassembler = Reassembler()
    private var outbox: [Data] = []

    init(phonePriv: Data, phoneId: Data) throws {
        self.phonePriv = phonePriv
        self.phonePub = try ProtocolCrypto.publicKey(fromPrivate: phonePriv)
        self.phoneId = phoneId
    }

    func readInfo() async throws -> Data { PhoneInfo(phoneId: phoneId, phonePub: phonePub).encoded }

    func writeFrame(_ frame: Data) async throws {
        XCTAssertLessThanOrEqual(frame.count, chunkSize + 1, "frame larger than chunk + header")
        guard let message = reassembler.feed(frame) else { return }
        received.append(message)
        if let reply = try handle(message) {
            outbox += Framing.split(reply, chunk: txChunk)
        }
    }

    func nextFrame() async throws -> Data {
        guard !outbox.isEmpty else { throw ProtocolError.transport("fake phone has nothing to send") }
        return outbox.removeFirst()
    }

    func close() { closed = true }

    /// Each allowReplyWait call: the seconds asked for, and how many whole messages had arrived by then.
    private(set) var replyWaits: [(seconds: TimeInterval, messagesReceived: Int)] = []
    func allowReplyWait(_ seconds: TimeInterval) { replyWaits.append((seconds, received.count)) }

    private func key(macPub: Data) throws -> SymmetricKey {
        try ProtocolCrypto.sessionKey(myPrivate: phonePriv, peerPublic: macPub, macPub: macPub, phonePub: phonePub)
    }

    private func errorMessage(_ code: UInt8) -> Data { Data([MessageType.error.rawValue, code]) }

    private func handle(_ message: Data) throws -> Data? {
        let (type, body) = try Message.parse(message)
        let b = [UInt8](body)
        switch type {
        case .pairRequest:
            let macId = Data(b)
            guard let p = pending[macId] else { return errorMessage(3) }
            let proof = ProtocolCrypto.pairingProof(pairSecret: proofSecretOverride ?? p.secret, macId: macId,
                                                    phoneId: phoneId, phonePub: phonePub)
            return Message.pairAccept(proof: proof)
        case .pairConfirm:
            let macId = Data(b[0..<16])
            guard let p = pending[macId] else { return errorMessage(3) }
            do {
                let name = try ProtocolCrypto.open(key: key(macPub: p.macPub),
                                                   aad: ProtocolCrypto.confirmAAD(macId: macId, phoneId: phoneId),
                                                   box: Data(b[16...]))
                confirmedMacName = String(data: name, encoding: .utf8)
            } catch { return errorMessage(2) }
            pairedMacs[macId] = p.macPub
            pending[macId] = nil
            return Data([MessageType.pairDone.rawValue])
        case .syncRequest:
            let macId = Data(b[0..<16])
            guard let macPub = pairedMacs[macId] else { return errorMessage(1) }
            if let fixed = fixedSyncResponse { return fixed }
            let k = try key(macPub: macPub)
            let json: Data
            do {
                json = try ProtocolCrypto.open(key: k, aad: ProtocolCrypto.requestAAD(macId: macId, phoneId: phoneId),
                                               box: Data(b[16...]))
            } catch { return errorMessage(2) }
            lastRequestJSON = json
            let req = try JSONSerialization.jsonObject(with: json) as! [String: Any]
            let all = req["all"] as! Bool
            let ssids = Set(req["ssids"] as! [String])
            let resp = SyncResponse(n: responseNonceOverride ?? (req["n"] as! String), status: .ok,
                                    networks: vault.filter { all || ssids.contains($0.ssid) })
            let box = try ProtocolCrypto.seal(key: k, aad: ProtocolCrypto.responseAAD(macId: macId, phoneId: phoneId),
                                              plaintext: try JSONEncoder().encode(resp))
            return Message.syncResponse(box: box)
        default:
            return errorMessage(4)
        }
    }
}
