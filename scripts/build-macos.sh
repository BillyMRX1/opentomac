#!/usr/bin/env bash
# Builds the macOS app: links the KMP framework, generates the Xcode project, and compiles.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Linking KMP macOS framework"
./gradlew :shared:linkDebugFrameworkMacosArm64

echo "==> Generating Xcode project"
cd macos
xcodegen generate

echo "==> Building Opentomac.app"
xcodebuild \
  -project Opentomac.xcodeproj \
  -scheme Opentomac \
  -configuration Debug \
  -destination 'platform=macOS,arch=arm64' \
  build "$@"

echo "==> Done"
