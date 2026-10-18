// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "M7208WStudio",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(name: "M7208WStudio", path: "Sources",
                          resources: [.copy("LNTHR1Z.universal"), .copy("M7208WeSCL")])
    ]
)
