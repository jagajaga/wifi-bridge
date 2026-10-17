import Foundation
import XCTest
@testable import WiFiBridgeCore

final class AskBackoffTests: XCTestCase {
    let t0 = Date(timeIntervalSince1970: 1_000_000)

    func testFirstAskAllowed() {
        XCTAssertTrue(AskBackoff(interval: 120).shouldAsk(visible: ["A"], now: t0))
    }

    func testSameSetWithinIntervalBlocked() {
        var b = AskBackoff(interval: 120)
        b.recordAsk(["A", "B"], at: t0)
        XCTAssertFalse(b.shouldAsk(visible: ["A", "B"], now: t0 + 30))
    }

    /// A weak network dropping out of the scan must not count as a new question.
    func testSubsetWithinIntervalBlocked() {
        var b = AskBackoff(interval: 120)
        b.recordAsk(["A", "B", "Weak"], at: t0)
        XCTAssertFalse(b.shouldAsk(visible: ["A", "B"], now: t0 + 20))
    }

    /// Flicker back and forth between two sets already asked about.
    func testFlickerBetweenAskedSetsBlocked() {
        var b = AskBackoff(interval: 120)
        b.recordAsk(["A", "B"], at: t0)
        XCTAssertTrue(b.shouldAsk(visible: ["A", "C"], now: t0 + 20))
        b.recordAsk(["A", "C"], at: t0 + 20)
        XCTAssertFalse(b.shouldAsk(visible: ["A", "B"], now: t0 + 40))
        XCTAssertFalse(b.shouldAsk(visible: ["B", "C"], now: t0 + 60))
    }

    func testNewSSIDAllowed() {
        var b = AskBackoff(interval: 120)
        b.recordAsk(["A"], at: t0)
        XCTAssertTrue(b.shouldAsk(visible: ["A", "New"], now: t0 + 20))
    }

    func testAfterIntervalAllowed() {
        var b = AskBackoff(interval: 120)
        b.recordAsk(["A"], at: t0)
        XCTAssertTrue(b.shouldAsk(visible: ["A"], now: t0 + 120))
    }

    func testResetAllows() {
        var b = AskBackoff(interval: 120)
        b.recordAsk(["A"], at: t0)
        b.reset()
        XCTAssertTrue(b.shouldAsk(visible: ["A"], now: t0 + 1))
    }
}
