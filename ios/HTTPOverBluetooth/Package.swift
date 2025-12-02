// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "HTTPOverBluetooth",
    platforms: [
        .iOS(.v13),
        .macOS(.v10_15)
    ],
    products: [
        .library(
            name: "HTTPOverBluetooth",
            targets: ["HTTPOverBluetooth"]),
    ],
    dependencies: [],
    targets: [
        .target(
            name: "HTTPOverBluetooth",
            dependencies: [],
            path: "Sources/HTTPOverBluetooth"),
        .testTarget(
            name: "HTTPOverBluetoothTests",
            dependencies: ["HTTPOverBluetooth"],
            path: "Tests/HTTPOverBluetoothTests"),
    ]
)
