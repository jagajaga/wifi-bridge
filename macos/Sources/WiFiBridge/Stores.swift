import Foundation
import Security
import WiFiBridgeCore

enum KeychainError: Error, LocalizedError {
    case status(OSStatus)
    var errorDescription: String? {
        if case .status(let s) = self { return "Keychain error \(s)" }
        return nil
    }
}

/// Thin wrapper over kSecClassGenericPassword items.
enum Keychain {
    static func set(_ data: Data, service: String, account: String) throws {
        delete(service: service, account: account)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError.status(status) }
    }

    static func get(service: String, account: String) -> Data? {
        try? read(service: service, account: account)
    }

    /// nil only when the item does not exist; any other failure (locked keychain,
    /// access prompt denied) throws, so callers can tell "absent" from "unreadable".
    static func read(service: String, account: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError.status(status) }
        return item as? Data
    }

    @discardableResult
    static func delete(service: String, account: String) -> Bool {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        return SecItemDelete(query as CFDictionary) == errSecSuccess
    }

    static func accounts(service: String) -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let rows = item as? [[String: Any]] else { return [] }
        return rows.compactMap { $0[kSecAttrAccount as String] as? String }.sorted()
    }
}

/// Wi-Fi passwords received from the phone. service "dev.wifibridge.network", account = SSID,
/// value = UTF-8 password ("" for an open network).
enum NetworkCache {
    static let service = "dev.wifibridge.network"

    static func store(_ network: WiFiNetwork) throws {
        try Keychain.set(Data(network.password.utf8), service: service, account: network.ssid)
    }

    static func password(for ssid: String) -> String? {
        Keychain.get(service: service, account: ssid).flatMap { String(data: $0, encoding: .utf8) }
    }

    static func ssids() -> [String] { Keychain.accounts(service: service) }

    static func forget(_ ssid: String) { Keychain.delete(service: service, account: ssid) }
}

/// The Mac's identity: macId and X25519 private key, both in the Keychain.
enum IdentityStore {
    static let service = "dev.wifibridge.identity"

    static func loadOrCreate() throws -> MacIdentity {
        let name = Host.current().localizedName ?? "Mac"
        // Only a missing identity may be replaced. A read that fails for any other
        // reason must not mint a new macId/key: every paired phone would then
        // answer "unknown Mac" and need re-pairing.
        let id = try Keychain.read(service: service, account: "macId")
        let priv = try Keychain.read(service: service, account: "macPriv")
        if let id = id, let priv = priv, let identity = try? MacIdentity(macId: id, privateKey: priv, name: name) {
            return identity
        }
        let fresh = try MacIdentity.generate(name: name)
        try Keychain.set(fresh.privateKey, service: service, account: "macPriv")
        try Keychain.set(fresh.macId, service: service, account: "macId")
        return fresh
    }
}

/// Paired phones (public data only) as JSON in Application Support.
enum PhoneStore {
    static var fileURL: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("WiFiBridge", isDirectory: true).appendingPathComponent("phones.json")
    }

    static func load() -> [PairedPhone] {
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        return (try? JSONDecoder().decode([PairedPhone].self, from: data)) ?? []
    }

    static func save(_ phones: [PairedPhone]) throws {
        let url = fileURL
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(phones).write(to: url, options: .atomic)
    }
}
