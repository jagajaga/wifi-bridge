import Foundation

/// When auto-sync may ask the phone again (macos/README "How auto-sync behaves").
///
/// Tracked per SSID, not per visible set: scan results flicker as weak networks
/// come and go, so the exact set rarely repeats and a set-keyed back-off would
/// ask (and, with "Share automatically" off, prompt the user) on every tick.
/// Asking again only helps when some visible SSID has not been asked about
/// within `interval`.
public struct AskBackoff: Sendable {
    public let interval: TimeInterval
    private var askedAt: [String: Date] = [:]

    public init(interval: TimeInterval) { self.interval = interval }

    public func shouldAsk(visible: Set<String>, now: Date) -> Bool {
        visible.contains { ssid in
            guard let at = askedAt[ssid] else { return true }
            return now.timeIntervalSince(at) >= interval
        }
    }

    public mutating func recordAsk(_ ssids: Set<String>, at now: Date) {
        askedAt = askedAt.filter { now.timeIntervalSince($0.value) < interval }
        for ssid in ssids { askedAt[ssid] = now }
    }

    /// Forget every ask (e.g. a phone was just paired).
    public mutating func reset() { askedAt = [:] }
}
