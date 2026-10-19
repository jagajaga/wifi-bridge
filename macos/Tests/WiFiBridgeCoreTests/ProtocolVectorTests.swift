import Crypto
import Foundation
import XCTest
@testable import WiFiBridgeCore

final class ProtocolVectorTests: XCTestCase {
    let v = Vectors.shared

    private func keyData(_ k: SymmetricKey) -> Data { k.withUnsafeBytes { Data($0) } }

    func testPublicKeysFromPrivate() throws {
        XCTAssertEqual(try ProtocolCrypto.publicKey(fromPrivate: v.macPriv), v.macPub)
        XCTAssertEqual(try ProtocolCrypto.publicKey(fromPrivate: v.phonePriv), v.phonePub)
    }

    func testSharedSecretAndSessionKeyFromBothSides() throws {
        let shared = v.hex("keys", "shared")
        XCTAssertEqual(try ProtocolCrypto.sharedSecret(myPrivate: v.macPriv, peerPublic: v.phonePub), shared)
        XCTAssertEqual(try ProtocolCrypto.sharedSecret(myPrivate: v.phonePriv, peerPublic: v.macPub), shared)

        let macSide = try ProtocolCrypto.sessionKey(myPrivate: v.macPriv, peerPublic: v.phonePub,
                                                    macPub: v.macPub, phonePub: v.phonePub)
        let phoneSide = try ProtocolCrypto.sessionKey(myPrivate: v.phonePriv, peerPublic: v.macPub,
                                                      macPub: v.macPub, phonePub: v.phonePub)
        XCTAssertEqual(keyData(macSide), v.K)
        XCTAssertEqual(keyData(phoneSide), v.K)
    }

    func testInfoParse() throws {
        let info = try PhoneInfo(parsing: v.hex("info"))
        XCTAssertEqual(info.phoneId, v.phoneId)
        XCTAssertEqual(info.phonePub, v.phonePub)
        XCTAssertEqual(info.encoded, v.hex("info"))

        XCTAssertThrowsError(try PhoneInfo(parsing: v.hex("info").dropLast()))
        var wrongVersion = v.hex("info"); wrongVersion[0] = 0x02
        XCTAssertThrowsError(try PhoneInfo(parsing: wrongVersion))
    }

    func testPairingQREncodeAndDecode() throws {
        let qr = PairingQR(macId: v.macId, macPub: v.macPub, pairSecret: v.pairSecret, macName: v.macName)
        XCTAssertEqual(qr.payload, v.string("pairing", "qr"))
        XCTAssertEqual(try PairingQR(parsing: v.string("pairing", "qr")), qr)
        XCTAssertEqual(qr.uri, v.string("pairing", "qrUri"))
        XCTAssertEqual(try PairingQR(parsing: v.string("pairing", "qrUri")), qr)
        XCTAssertThrowsError(try PairingQR(parsing: "WBPAIR2;" + v.string("pairing", "qr").dropFirst(8)))
        XCTAssertThrowsError(try PairingQR(parsing: v.string("pairing", "qr") + ";extra"))
    }

    func testProof() {
        let proof = ProtocolCrypto.pairingProof(pairSecret: v.pairSecret, macId: v.macId,
                                                phoneId: v.phoneId, phonePub: v.phonePub)
        XCTAssertEqual(proof, v.hex("pairing", "proof"))
        XCTAssertTrue(ProtocolCrypto.verifyPairingProof(proof, pairSecret: v.pairSecret, macId: v.macId,
                                                        phoneId: v.phoneId, phonePub: v.phonePub))
        var bad = proof; bad[31] ^= 0x01
        XCTAssertFalse(ProtocolCrypto.verifyPairingProof(bad, pairSecret: v.pairSecret, macId: v.macId,
                                                         phoneId: v.phoneId, phonePub: v.phonePub))
        XCTAssertFalse(ProtocolCrypto.verifyPairingProof(proof.dropLast(), pairSecret: v.pairSecret, macId: v.macId,
                                                         phoneId: v.phoneId, phonePub: v.phonePub))
    }

    func testConstantTimeEqual() {
        XCTAssertTrue(Bytes.constantTimeEqual(Data([1, 2, 3]), Data([1, 2, 3])))
        XCTAssertFalse(Bytes.constantTimeEqual(Data([1, 2, 3]), Data([1, 2, 4])))
        XCTAssertFalse(Bytes.constantTimeEqual(Data([1, 2, 3]), Data([1, 2])))
        XCTAssertTrue(Bytes.constantTimeEqual(Data(), Data()))
    }

    func testPairRequestAndAcceptBytes() throws {
        XCTAssertEqual(Message.pairRequest(macId: v.macId), v.hex("pairing", "pairRequest"))
        XCTAssertEqual(Message.pairAccept(proof: v.hex("pairing", "proof")), v.hex("pairing", "pairAccept"))
        let (type, body) = try Message.parse(v.hex("pairing", "pairAccept"))
        XCTAssertEqual(type, .pairAccept)
        XCTAssertEqual(try Message.proof(fromPairAccept: body), v.hex("pairing", "proof"))
    }

    func testPairConfirmSealMatchesVector() throws {
        let k = SymmetricKey(data: v.K)
        let box = try ProtocolCrypto.seal(key: k, aad: ProtocolCrypto.confirmAAD(macId: v.macId, phoneId: v.phoneId),
                                          plaintext: Data(v.macName.utf8), nonce: v.hex("pairing", "confirmNonce"))
        XCTAssertEqual(Message.pairConfirm(macId: v.macId, box: box), v.hex("pairing", "pairConfirm"))
    }

    func testSyncRequestJSONAndSealMatchVector() throws {
        let request = SyncRequest(n: Bytes.b64url(Data(0..<16)), ssids: ["Casita_Guest", "Café ☕"], all: false)
        XCTAssertEqual(request.json, Data(v.string("sync", "requestJson").utf8))
        let box = try ProtocolCrypto.seal(key: SymmetricKey(data: v.K),
                                          aad: ProtocolCrypto.requestAAD(macId: v.macId, phoneId: v.phoneId),
                                          plaintext: request.json, nonce: v.hex("sync", "requestNonce"))
        XCTAssertEqual(Message.syncRequest(macId: v.macId, box: box), v.hex("sync", "syncRequest"))
    }

    func testSyncRequestJSONEscaping() {
        let r = SyncRequest(n: "x", ssids: ["a\"b\\c/d\n\u{01}"], all: true)
        XCTAssertEqual(String(data: r.json, encoding: .utf8), #"{"n":"x","ssids":["a\"b\\c/d\n\u0001"],"all":true}"#)
        // Escaped output must round-trip through a real JSON parser.
        let parsed = try! JSONSerialization.jsonObject(with: r.json) as! [String: Any]
        XCTAssertEqual(parsed["ssids"] as? [String], ["a\"b\\c/d\n\u{01}"])
    }

    func testOpenSyncResponse() throws {
        let (type, body) = try Message.parse(v.hex("sync", "syncResponse"))
        XCTAssertEqual(type, .syncResponse)
        let json = try ProtocolCrypto.open(key: SymmetricKey(data: v.K),
                                           aad: ProtocolCrypto.responseAAD(macId: v.macId, phoneId: v.phoneId), box: body)
        XCTAssertEqual(json, Data(v.string("sync", "responseJson").utf8))
        XCTAssertEqual(body.prefix(12), v.hex("sync", "responseNonce"))
        let resp = try SyncResponse.decode(json)
        XCTAssertEqual(resp, SyncResponse(n: "AAECAwQFBgcICQoLDA0ODw", status: .ok, networks: [
            WiFiNetwork(ssid: "Casita_Guest", security: "WPA", password: "p@ss;word", hidden: false),
        ]))
    }

    func testTamperedSyncResponseRejected() throws {
        let (_, body) = try Message.parse(v.hex("sync", "tamperedSyncResponse"))
        XCTAssertThrowsError(try ProtocolCrypto.open(key: SymmetricKey(data: v.K),
                                                     aad: ProtocolCrypto.responseAAD(macId: v.macId, phoneId: v.phoneId),
                                                     box: body)) { XCTAssertEqual($0 as? ProtocolError, .authenticationFailed) }
    }

    func testWrongAADRejected() throws {
        let (_, body) = try Message.parse(v.hex("sync", "syncResponse"))
        let k = SymmetricKey(data: v.K)
        for aad in [Data(),
                    ProtocolCrypto.requestAAD(macId: v.macId, phoneId: v.phoneId),
                    ProtocolCrypto.responseAAD(macId: v.phoneId, phoneId: v.macId)] {
            XCTAssertThrowsError(try ProtocolCrypto.open(key: k, aad: aad, box: body))
        }
        // The request box must not open under the response AAD either.
        let req = v.hex("sync", "syncRequest").dropFirst(17)
        XCTAssertThrowsError(try ProtocolCrypto.open(key: k, aad: ProtocolCrypto.responseAAD(macId: v.macId, phoneId: v.phoneId),
                                                     box: Data(req)))
        XCTAssertNoThrow(try ProtocolCrypto.open(key: k, aad: ProtocolCrypto.requestAAD(macId: v.macId, phoneId: v.phoneId),
                                                 box: Data(req)))
    }

    func testAADLiterals() {
        XCTAssertEqual(ProtocolCrypto.confirmAAD(macId: v.macId, phoneId: v.phoneId),
                       Data("wb-pair-confirm".utf8) + v.macId + v.phoneId)
        XCTAssertEqual(ProtocolCrypto.requestAAD(macId: v.macId, phoneId: v.phoneId),
                       Data("wb-req".utf8) + v.macId + v.phoneId)
        XCTAssertEqual(ProtocolCrypto.responseAAD(macId: v.macId, phoneId: v.phoneId),
                       Data("wb-resp".utf8) + v.macId + v.phoneId)
    }

    func testB64url() {
        XCTAssertEqual(Bytes.b64url(Data(0..<16)), "AAECAwQFBgcICQoLDA0ODw")
        XCTAssertEqual(Bytes.fromB64url("AAECAwQFBgcICQoLDA0ODw"), Data(0..<16))
        XCTAssertEqual(Bytes.b64url(Data([0xfb, 0xff])), "-_8")
        XCTAssertEqual(Bytes.fromB64url("-_8"), Data([0xfb, 0xff]))
        XCTAssertNil(Bytes.fromB64url("+/8"))
    }
}

final class FramingTests: XCTestCase {
    let v = Vectors.shared

    func testSplitMatchesVectors() {
        for c in v.framing {
            XCTAssertEqual(Framing.split(c.message, chunk: c.chunk), c.frames, "chunk \(c.chunk) len \(c.message.count)")
        }
    }

    func testReassembleVectors() {
        for c in v.framing {
            var r = Reassembler()
            var out: [Data] = []
            for f in c.frames { if let m = r.feed(f) { out.append(m) } }
            XCTAssertEqual(out, [c.message])
            XCTAssertTrue(r.isIdle)
        }
    }

    func testEmptyMessageIsOneFrame() {
        XCTAssertEqual(Framing.split(Data(), chunk: 19), [Data([0x03])])
        var r = Reassembler()
        XCTAssertEqual(r.feed(Data([0x03])), Data())
    }

    func testFramesBeforeFirstAreIgnored() {
        var r = Reassembler()
        XCTAssertNil(r.feed(Data([0x00, 1, 2])))
        XCTAssertNil(r.feed(Data([0x02, 3])))
        XCTAssertEqual(r.feed(Data([0x03, 9])), Data([9]))
    }

    func testFirstDiscardsPartialBuffer() {
        var r = Reassembler()
        XCTAssertNil(r.feed(Data([0x01, 1, 2])))
        XCTAssertNil(r.feed(Data([0x01, 7])))
        XCTAssertEqual(r.feed(Data([0x02, 8])), Data([7, 8]))
    }

    func testOversizedMessageDroppedAndReset() {
        let big = Data(repeating: 0xAB, count: Framing.maxMessage + 1)
        var r = Reassembler()
        var out: [Data] = []
        for f in Framing.split(big, chunk: 500) { if let m = r.feed(f) { out.append(m) } }
        XCTAssertEqual(out, [])
        XCTAssertTrue(r.isIdle)
        // Exactly the cap is accepted, and the reassembler recovered.
        let max = Data(repeating: 0xCD, count: Framing.maxMessage)
        for f in Framing.split(max, chunk: 500) { if let m = r.feed(f) { out.append(m) } }
        XCTAssertEqual(out, [max])
    }

    func testChunkSize() {
        XCTAssertEqual(Framing.chunkSize(maxValueLength: 20), 19)
        XCTAssertEqual(Framing.chunkSize(maxValueLength: 10), 19)
        XCTAssertEqual(Framing.chunkSize(maxValueLength: 183), 182)
    }

    func testRoundTripManySizes() {
        for len in [0, 1, 18, 19, 20, 37, 38, 39, 1000] {
            for chunk in [19, 20, 182, 511] {
                let msg = Data((0..<len).map { UInt8($0 & 0xff) })
                let frames = Framing.split(msg, chunk: chunk)
                XCTAssertTrue(frames.allSatisfy { $0.count <= chunk + 1 })
                XCTAssertEqual(frames.filter { $0[0] & Framing.last != 0 }.count, 1)
                var r = Reassembler()
                XCTAssertEqual(frames.compactMap { r.feed($0) }, [msg])
            }
        }
    }
}
