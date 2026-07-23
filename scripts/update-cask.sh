#!/usr/bin/env bash
# Regenerates the Homebrew cask for a new release.
# Usage: ./scripts/update-cask.sh v0.3.0
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <tag>" >&2
  exit 1
fi

TAG="$1"
VERSION="${TAG#v}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CASK_PATH="$ROOT/packaging/homebrew/Casks/opentomac.rb"

if [ ! -f "$CASK_PATH" ]; then
  echo "error: cask file not found at '$CASK_PATH'" >&2
  exit 1
fi

DMG_NAME="opentomac-${TAG}-macos.dmg"
DMG_URL="https://github.com/BillyMRX1/opentomac/releases/download/${TAG}/${DMG_NAME}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

echo "==> Downloading $DMG_URL"
if ! curl -fL --retry 3 -o "$WORKDIR/$DMG_NAME" "$DMG_URL"; then
  echo "error: failed to download '$DMG_URL' (release or asset may not exist)" >&2
  exit 1
fi

echo "==> Computing sha256"
SHA256="$(shasum -a 256 "$WORKDIR/$DMG_NAME" | awk '{print $1}')"

if [ -z "$SHA256" ]; then
  echo "error: failed to compute sha256 for '$DMG_NAME'" >&2
  exit 1
fi

echo "==> Updating $CASK_PATH (version=$VERSION, sha256=$SHA256)"
sed -i '' -E "s/^(  version \")[^\"]*(\")$/\1${VERSION}\2/" "$CASK_PATH"
sed -i '' -E "s/^(  sha256 \")[^\"]*(\")$/\1${SHA256}\2/" "$CASK_PATH"

echo "==> Done"
