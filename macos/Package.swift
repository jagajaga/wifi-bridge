// swift-tools-version:6.0
//
// WiFiBridgeCore: all wire-protocol logic, platform independent (builds and
// tests on Linux). WiFiBridge: the macOS menu-bar app (CoreBluetooth, CoreWLAN,
// CoreLocation, Security, SwiftUI) -- declared only when the manifest is
// evaluated on macOS, so `swift build` / `swift test` on Linux still work.
import PackageDescription

var products: [Product] = [
    .library(name: "WiFiBridgeCore", targets: ["WiFiBridgeCore"]),
]

var targets: [Target] = [
    .target(
        name: "WiFiBridgeCore",
        dependencies: [.product(name: "Crypto", package: "swift-crypto")]
    ),
    .testTarget(
        name: "WiFiBridgeCoreTests",
        dependencies: ["WiFiBridgeCore", .product(name: "Crypto", package: "swift-crypto")]
    ),
]

#if os(macOS)
products.append(.executable(name: "WiFiBridge", targets: ["WiFiBridge"]))
targets.append(
    .executableTarget(
        name: "WiFiBridge",
        dependencies: ["WiFiBridgeCore"],
        // Delegate-heavy CoreBluetooth / CoreWLAN / CoreLocation code: Swift 5
        // language mode so strict-concurrency diagnostics on Apple's
        // non-Sendable delegate types do not make the app fragile to build.
        swiftSettings: [.swiftLanguageMode(.v5)],
        linkerSettings: [
            .linkedFramework("CoreBluetooth"),
            .linkedFramework("CoreWLAN"),
            .linkedFramework("CoreLocation"),
            .linkedFramework("Security"),
            .linkedFramework("CoreImage"),
        ]
    )
)
#endif

let package = Package(
    name: "WiFiBridge",
    platforms: [.macOS(.v14)],
    products: products,
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", "3.0.0"..<"5.0.0"),  // 5.x needs Swift tools 6.2; stay buildable with Xcode 16 / Swift 6.1
    ],
    targets: targets,
    swiftLanguageModes: [.v6]
)
