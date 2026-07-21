#!/usr/bin/env bash
# Packages a built Opentomac.app into a drag-to-install DMG (app + Applications symlink).
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <path-to-Opentomac.app> <output.dmg>" >&2
  exit 1
fi

APP_PATH="$1"
OUT_DMG="$2"

if [ ! -d "$APP_PATH" ] || [[ "$APP_PATH" != *.app ]]; then
  echo "error: '$APP_PATH' is not an .app bundle" >&2
  exit 1
fi

STAGING="$(mktemp -d)"
trap 'rm -rf "$STAGING"' EXIT

echo "==> Staging app bundle"
ditto "$APP_PATH" "$STAGING/$(basename "$APP_PATH")"

echo "==> Adding Applications symlink"
ln -s /Applications "$STAGING/Applications"

echo "==> Building DMG"
hdiutil create -volname "opentomac" -srcfolder "$STAGING" -ov -format UDZO "$OUT_DMG"

echo "==> Done: $OUT_DMG"
