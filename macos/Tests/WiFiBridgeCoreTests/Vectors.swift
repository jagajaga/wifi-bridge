import Foundation
import XCTest
@testable import WiFiBridgeCore

/// docs/test-vectors.json, resolved relative to this file.
struct Vectors: @unchecked Sendable {
    let root: [String: Any]

    static let shared: Vectors = {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // WiFiBridgeCoreTests
            .deletingLastPathComponent()   // Tests
            .deletingLastPathComponent()   // macos
            .deletingLastPathComponent()   // repo root
            .appendingPathComponent("docs/test-vectors.json")
        let data = try! Data(contentsOf: url)
        return Vectors(root: try! JSONSerialization.jsonObject(with: data) as! [String: Any])
    }()

    func section(_ name: String) -> [String: Any] { root[name] as! [String: Any] }
    func string(_ section: String, _ key: String) -> String { self.section(section)[key] as! String }
    func hex(_ section: String, _ key: String) -> Data { Bytes.fromHex(string(section, key))! }
    func hex(_ key: String) -> Data { Bytes.fromHex(root[key] as! String)! }

    var macPriv: Data { hex("keys", "macPriv") }
    var macPub: Data { hex("keys", "macPub") }
    var phonePriv: Data { hex("keys", "phonePriv") }
    var phonePub: Data { hex("keys", "phonePub") }
    var macId: Data { hex("keys", "macId") }
    var phoneId: Data { hex("keys", "phoneId") }
    var K: Data { hex("keys", "K") }
    var macName: String { string("pairing", "macName") }
    var pairSecret: Data { hex("pairing", "pairSecret") }

    var framing: [(message: Data, chunk: Int, frames: [Data])] {
        (root["framing"] as! [[String: Any]]).map {
            (Bytes.fromHex($0["message"] as! String)!, $0["chunk"] as! Int,
             ($0["frames"] as! [String]).map { Bytes.fromHex($0)! })
        }
    }

    func identity() throws -> MacIdentity {
        try MacIdentity(macId: macId, privateKey: macPriv, name: macName)
    }
}

/// Hands out queued byte strings in order (deterministic RandomSource for tests).
final class ScriptedRandom: @unchecked Sendable {
    private var queue: [Data]
    private let lock = NSLock()
    init(_ queue: [Data]) { self.queue = queue }

    var source: RandomSource {
        { [self] count in
            lock.lock(); defer { lock.unlock() }
            precondition(!queue.isEmpty, "ScriptedRandom exhausted")
            let next = queue.removeFirst()
            precondition(next.count == count, "ScriptedRandom: wanted \(count) bytes, next is \(next.count)")
            return next
        }
    }
}
