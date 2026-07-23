#!/usr/bin/env bash
# Downloads the latest opentomac macOS release, installs it to /Applications, and
# removes the quarantine flag (opentomac releases are unsigned and un-notarized).
# Usage: curl -fsSL https://raw.githubusercontent.com/BillyMRX1/opentomac/main/scripts/install.sh | bash
set -euo pipefail

REPO="BillyMRX1/opentomac"
APP_NAME="Opentomac.app"
INSTALL_DIR="/Applications"

echo "==> Checking platform"
OS="$(uname -s)"
if [ "$OS" != "Darwin" ]; then
  echo "error: opentomac's macOS installer only runs on Darwin, detected '$OS'" >&2
  exit 1
fi

ARCH="$(uname -m)"
if [ "$ARCH" != "arm64" ]; then
  echo "error: opentomac ships arm64-only macOS builds, detected '$ARCH'" >&2
  exit 1
fi

MACOS_VERSION="$(sw_vers -productVersion)"
MACOS_MAJOR="${MACOS_VERSION%%.*}"
if [ "$MACOS_MAJOR" -lt 14 ]; then
  echo "error: opentomac requires macOS 14 (Sonoma) or later, detected $MACOS_VERSION" >&2
  exit 1
fi

echo "==> Resolving latest release"
LATEST_JSON="$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest")"
TAG="$(printf '%s\n' "$LATEST_JSON" | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/')"

if [ -z "$TAG" ]; then
  echo "error: could not resolve latest release tag from GitHub API response" >&2
  exit 1
fi

echo "==> Latest release: $TAG"

DMG_NAME="opentomac-${TAG}-macos.dmg"
DMG_URL="https://github.com/${REPO}/releases/download/${TAG}/${DMG_NAME}"

WORKDIR="$(mktemp -d)"
MOUNTPOINT="$WORKDIR/mount"
mkdir -p "$MOUNTPOINT"

MOUNTED=0
cleanup() {
  if [ "$MOUNTED" -eq 1 ]; then
    hdiutil detach "$MOUNTPOINT" -quiet 2>/dev/null || true
  fi
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

echo "==> Downloading $DMG_URL"
if ! curl -fL --retry 3 -o "$WORKDIR/$DMG_NAME" "$DMG_URL"; then
  echo "error: failed to download '$DMG_URL'" >&2
  exit 1
fi

echo "==> Mounting DMG"
hdiutil attach "$WORKDIR/$DMG_NAME" -nobrowse -readonly -mountpoint "$MOUNTPOINT"
MOUNTED=1

if [ ! -d "$MOUNTPOINT/$APP_NAME" ]; then
  echo "error: '$APP_NAME' not found inside the downloaded DMG" >&2
  exit 1
fi

if [ ! -w "$INSTALL_DIR" ]; then
  echo "error: '$INSTALL_DIR' is not writable, rerun with sudo: curl -fsSL https://raw.githubusercontent.com/${REPO}/main/scripts/install.sh | sudo bash" >&2
  exit 1
fi

if [ -d "$INSTALL_DIR/$APP_NAME" ]; then
  echo "==> Replacing existing $INSTALL_DIR/$APP_NAME"
  rm -rf "$INSTALL_DIR/$APP_NAME"
fi

echo "==> Installing to $INSTALL_DIR/$APP_NAME"
ditto "$MOUNTPOINT/$APP_NAME" "$INSTALL_DIR/$APP_NAME"

echo "==> Removing quarantine flag"
xattr -dr com.apple.quarantine "$INSTALL_DIR/$APP_NAME"

echo "==> Installed opentomac $TAG to $INSTALL_DIR/$APP_NAME"
echo "    Quarantine was removed because opentomac releases are unsigned and un-notarized."
