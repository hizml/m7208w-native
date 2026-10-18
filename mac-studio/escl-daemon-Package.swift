// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "M7208WESCL",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "M7208WESCL", path: "Sources")
    ]
)
