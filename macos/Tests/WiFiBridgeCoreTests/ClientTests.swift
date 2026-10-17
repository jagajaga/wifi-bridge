import Foundation
import XCTest
@testable import WiFiBridgeCore

final class PairingClientTests: XCTestCase {
    let v = Vectors.shared

    private func phoneWithPending() throws -> FakePhone {
        let phone = try FakePhone(phonePriv: v.phonePriv, phoneId: v.phoneId)
        phone.pending[v.macId] = (v.pairSecret, v.macPub)
        return phone
    }

    func testAcceptsGoodProofAndSendsVectorBytes() async throws {
        let phone = try phoneWithPending()
        let random = ScriptedRandom([v.hex("pairing", "confirmNonce")])
        let client = PairingClient(identity: try v.identity(), pairSecret: v.pairSecret, random: random.source)
        XCTAssertEqual(client.qr.payload, v.string("pairing", "qr"))

        let paired = try await client.run(phone)

        XCTAssertEqual(paired.phoneId, v.phoneId)
        XCTAssertEqual(paired.phonePub, v.phonePub)
        XCTAssertEqual(phone.received, [v.hex("pairing", "pairRequest"), v.hex("pairing", "pairConfirm")])
        XCTAssertEqual(phone.confirmedMacName, v.macName)
        XCTAssertNotNil(phone.pairedMacs[v.macId])
    }

    func testRejectsBadProofWithoutConfirming() async throws {
        let phone = try phoneWithPending()
        phone.proofSecretOverride = Data(repeating: 0x42, count: 16)
        let client = PairingClient(identity: try v.identity(), pairSecret: v.pairSecret)
        do {
            _ = try await client.run(phone)
            XCTFail("bad proof accepted")
        } catch {
            XCTAssertEqual(error as? ProtocolError, .badProof)
        }
        XCTAssertEqual(phone.received, [v.hex("pairing", "pairRequest")], "PAIR_CONFIRM must not be sent")
    }

    func testRejectsProofComputedForAnotherPhoneKey() async throws {
        // A phone whose INFO pubkey differs from the one the proof covers.
        let phone = try FakePhone(phonePriv: Data(repeating: 7, count: 32), phoneId: v.phoneId)
        phone.pending[v.macId] = (v.pairSecret, v.macPub)
        let client = PairingClient(identity: try v.identity(), pairSecret: Data(repeating: 9, count: 16))
        do { _ = try await client.run(phone); XCTFail("accepted") } catch {
            XCTAssertEqual(error as? ProtocolError, .badProof)
        }
    }

    func testNoPendingPairingSurfacesError3() async throws {
        let phone = try FakePhone(phonePriv: v.phonePriv, phoneId: v.phoneId)
        let client = PairingClient(identity: try v.identity(), pairSecret: v.pairSecret)
        do { _ = try await client.run(phone); XCTFail("accepted") } catch {
            XCTAssertEqual(error as? ProtocolError, .phoneError(3))
        }
    }

    func testLargeMTUStillWorks() async throws {
        let phone = try phoneWithPending()
        phone.chunkSize = 182
        phone.txChunk = 182
        let paired = try await PairingClient(identity: try v.identity(), pairSecret: v.pairSecret).run(phone)
        XCTAssertEqual(paired.phoneId, v.phoneId)
    }
}

final class SyncClientTests: XCTestCase {
    let v = Vectors.shared

    private func pairedPhone() throws -> FakePhone {
        let phone = try FakePhone(phonePriv: v.phonePriv, phoneId: v.phoneId)
        phone.pairedMacs[v.macId] = v.macPub
        return phone
    }

    private var known: [PairedPhone] { [PairedPhone(phoneId: v.phoneId, phonePub: v.phonePub)] }

    func testSendsVectorRequestAndOpensVectorResponse() async throws {
        let phone = try pairedPhone()
        phone.fixedSyncResponse = v.hex("sync", "syncResponse")
        let random = ScriptedRandom([Data(0..<16), v.hex("sync", "requestNonce")])
        let client = SyncClient(identity: try v.identity(), pairedPhones: known, random: random.source)

        let result = try await client.run(phone, ssids: ["Casita_Guest", "Café ☕"], all: false)

        XCTAssertEqual(phone.received, [v.hex("sync", "syncRequest")])
        XCTAssertEqual(result.response.status, .ok)
        XCTAssertEqual(result.response.networks.map(\.password), ["p@ss;word"])
    }

    func testRoundTripWithFakePhoneVault() async throws {
        let phone = try pairedPhone()
        phone.vault = [WiFiNetwork(ssid: "A", security: "WPA", password: "1", hidden: false),
                       WiFiNetwork(ssid: "B", security: "SAE", password: "2", hidden: true)]
        let client = SyncClient(identity: try v.identity(), pairedPhones: known)
        let some = try await client.run(phone, ssids: ["B", "Z"], all: false)
        XCTAssertEqual(some.response.networks.map(\.ssid), ["B"])
        let all = try await client.run(phone, ssids: [], all: true)
        XCTAssertEqual(all.response.networks.map(\.ssid), ["A", "B"])
    }

    func testRejectsResponseWithDifferentNonce() async throws {
        let phone = try pairedPhone()
        phone.responseNonceOverride = "AAECAwQFBgcICQoLDA0ODw"  // a replayed, validly sealed response
        phone.vault = [WiFiNetwork(ssid: "A", security: "WPA", password: "1", hidden: false)]
        let client = SyncClient(identity: try v.identity(), pairedPhones: known)
        do {
            _ = try await client.run(phone, ssids: ["A"], all: false)
            XCTFail("nonce mismatch accepted")
        } catch {
            XCTAssertEqual(error as? ProtocolError, .nonceMismatch)
        }
    }

    func testReplayedVectorResponseRejectedForFreshNonce() async throws {
        let phone = try pairedPhone()
        phone.fixedSyncResponse = v.hex("sync", "syncResponse")
        let client = SyncClient(identity: try v.identity(), pairedPhones: known)
        do { _ = try await client.run(phone, ssids: ["Casita_Guest"], all: false); XCTFail("replay accepted") } catch {
            XCTAssertEqual(error as? ProtocolError, .nonceMismatch)
        }
    }

    func testTamperedResponseRejected() async throws {
        let phone = try pairedPhone()
        phone.fixedSyncResponse = v.hex("sync", "tamperedSyncResponse")
        let random = ScriptedRandom([Data(0..<16), v.hex("sync", "requestNonce")])
        let client = SyncClient(identity: try v.identity(), pairedPhones: known, random: random.source)
        do { _ = try await client.run(phone, ssids: ["Casita_Guest", "Café ☕"], all: false); XCTFail("accepted") } catch {
            XCTAssertEqual(error as? ProtocolError, .authenticationFailed)
        }
    }

    func testUnknownPhoneRejectedBeforeSending() async throws {
        let phone = try pairedPhone()
        let stranger = PairedPhone(phoneId: Data(repeating: 1, count: 16), phonePub: v.phonePub)
        let client = SyncClient(identity: try v.identity(), pairedPhones: [stranger])
        do { _ = try await client.run(phone, ssids: [], all: true); XCTFail("accepted") } catch {
            XCTAssertEqual(error as? ProtocolError, .unknownPhone)
        }
        XCTAssertEqual(phone.received, [])
        // Same phoneId but a different public key is also not our phone.
        let impostor = PairedPhone(phoneId: v.phoneId, phonePub: v.macPub)
        do { _ = try await SyncClient(identity: try v.identity(), pairedPhones: [impostor]).run(phone, ssids: [], all: true)
            XCTFail("accepted") } catch { XCTAssertEqual(error as? ProtocolError, .unknownPhone) }
    }

    func testPhoneErrorSurfaces() async throws {
        let phone = try FakePhone(phonePriv: v.phonePriv, phoneId: v.phoneId)  // Mac not paired on phone
        let client = SyncClient(identity: try v.identity(), pairedPhones: known)
        do { _ = try await client.run(phone, ssids: [], all: true); XCTFail("accepted") } catch {
            XCTAssertEqual(error as? ProtocolError, .phoneError(1))
        }
    }

    func testDeniedResponseDecodesWithoutNetworks() throws {
        let r = try SyncResponse.decode(Data(#"{"n":"x","status":"denied"}"#.utf8))
        XCTAssertEqual(r.status, .denied)
        XCTAssertEqual(r.networks, [])
    }
}
